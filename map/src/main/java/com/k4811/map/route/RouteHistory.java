package com.k4811.map.route;

import android.util.Log;

import com.k4811.map.MapConfig;
import com.k4811.map.geo.Geo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Perfil de rotas preferidas: os trechos de rua por onde o carro passou com a tela de navegação aberta
 * (navegando ou não), contados uma vez por viagem, em {@code /sdcard/K4811/nav/history.bin}. Nada sai do
 * aparelho; apagar o arquivo zera o perfil.
 *
 * <ul>
 * <li><b>Registro:</b> cada posição do GPS (andando) é casada com o trecho do grafo baixado mais perto no
 * mesmo sentido ({@link #record}); o trecho entra na viagem depois de duas posições seguidas nele (ou de
 * uma só, a até 8 m da rua e no sentido dela). Sem o
 * grafo baixado ali, nada é registrado.</li>
 * <li><b>Uso:</b> a rota calculada no aparelho fica até 25% mais "barata" nos trechos usados
 * ({@link #discount}), e as rotas da internet são comparadas pela parte delas que passa por eles
 * ({@link #share}).</li>
 * </ul>
 * Trechos não usados há mais de um ano não contam. Chamar fora da thread principal.
 */
public final class RouteHistory {

    private static final String TAG = "K4811Route";
    private static final int FORMAT = 1;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_AGE_DAYS = 365;
    /** Distância máxima (m) da posição ao trecho, além da precisão. */
    private static final double MATCH_M = 20;
    /** Até essa distância (m), uma posição só já põe o trecho na viagem. */
    private static final double SURE_M = 8;
    /** Viagem acaba depois disso sem posição. */
    private static final long TRIP_GAP_MS = 10 * 60 * 1000;
    /** Numa viagem longa, grava o que já foi a cada tanto (o processo pode morrer no meio). */
    private static final long FLUSH_MS = 10 * 60 * 1000;

    private static RouteHistory instance;

    private final File file;
    /** Trecho → {vezes, último dia}. */
    private Map<Long, int[]> edges;
    private boolean dirty;

    // Viagem em andamento.
    private final Set<Long> trip = new HashSet<>();
    private long lastKey;
    private int lastCount;
    private long lastFix;
    private long tripSaved;
    private double prevLat = Double.NaN;
    private double prevLon;

    public static synchronized RouteHistory get() {
        if (instance == null) instance = new RouteHistory(new File(MapConfig.dir(), "history.bin"));
        return instance;
    }

    RouteHistory(File file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ consulta

    /** Vezes que o trecho foi usado (0 se nunca, ou há mais de um ano). */
    public synchronized int count(long key) {
        load();
        int[] v = edges.get(key);
        if (v == null) return 0;
        return today() - v[1] > MAX_AGE_DAYS ? 0 : v[0];
    }

    /** Há algum trecho no perfil. */
    public synchronized boolean isEmpty() {
        load();
        return edges.isEmpty();
    }

    /** Desconto no custo do trecho (0 a 0,25): uma vez = 8%, cresce devagar com as repetições. */
    synchronized double discount(long key) {
        int n = count(key);
        if (n <= 0) return 0;
        return Math.min(0.25, 0.08 + 0.05 * (Math.log(n) / Math.log(2)));
    }

    /**
     * Fração da rota (0 a 1, pela distância) que passa por trechos do perfil, em amostras a cada ~30 m.
     * 0 sem grafo baixado ou perfil vazio.
     */
    public double share(Route r) {
        if (isEmpty() || r == null || r.size() < 2) return 0;
        double total = r.along[r.size() - 1];
        if (total <= 0) return 0;
        int samples = 0, used = 0;
        int i = 1;
        for (double d = 15; d < total; d += 30) {
            while (i < r.size() - 1 && r.along[i] < d) i++;
            double t = (d - r.along[i - 1]) / Math.max(1e-6, r.along[i] - r.along[i - 1]);
            double lat = r.lat[i - 1] + (r.lat[i] - r.lat[i - 1]) * t;
            double lon = r.lon[i - 1] + (r.lon[i] - r.lon[i - 1]) * t;
            double brg = Geo.bearing(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i]);
            samples++;
            long key = match(lat, lon, brg, 12);
            if (key != 0 && count(key) > 0) used++;
        }
        return samples == 0 ? 0 : used / (double) samples;
    }

    // ------------------------------------------------------------------ registro

    /**
     * Uma posição do GPS andando: casa com o trecho e o põe na viagem. O sentido vem do deslocamento
     * desde a posição anterior (o rumo do GPS some ou falha devagar).
     * @param accuracy precisão (m), 0 se não souber
     */
    public synchronized void record(double lat, double lon, double accuracy) {
        long now = System.currentTimeMillis();
        if (lastFix > 0 && now - lastFix > TRIP_GAP_MS) endTrip();
        lastFix = now;
        double bearing = Double.NaN;
        if (!Double.isNaN(prevLat)) {
            if (Geo.distance(prevLat, prevLon, lat, lon) < 5) return; // parado: espera andar
            bearing = Geo.bearing(prevLat, prevLon, lat, lon);
        }
        prevLat = lat;
        prevLon = lon;
        long key = match(lat, lon, bearing, MATCH_M + Math.min(30, accuracy));
        // Bem em cima da rua, no sentido dela: basta uma posição (trecho curto, carro rápido).
        if (key != 0 && !Double.isNaN(bearing) && match(lat, lon, bearing, SURE_M) == key) trip.add(key);
        if (key == 0) {
            lastKey = 0;
            lastCount = 0;
            return;
        }
        if (key == lastKey) lastCount++;
        else {
            lastKey = key;
            lastCount = 1;
        }
        if (lastCount >= 2) trip.add(key);
        if (tripSaved == 0) tripSaved = now;
        if (now - tripSaved > FLUSH_MS) {
            tripSaved = now;
            commit();
        }
    }

    /** Fim da viagem (navegação encerrada, tela fechada): soma os trechos ao perfil e grava. */
    public synchronized void endTrip() {
        prevLat = Double.NaN;
        lastKey = 0;
        lastCount = 0;
        tripSaved = 0;
        commit();
    }

    private void commit() {
        if (trip.isEmpty()) return;
        load();
        int day = today();
        for (long k : trip) {
            int[] v = edges.get(k);
            if (v == null) edges.put(k, new int[]{1, day});
            else {
                v[0] = Math.min(10000, v[0] + 1);
                v[1] = day;
            }
        }
        Log.i(TAG, "perfil de rotas: +" + trip.size() + " trechos (" + edges.size() + " no total)");
        trip.clear();
        dirty = true;
        save();
    }

    /**
     * O trecho do grafo mais perto, até {@code maxM} metros, no sentido do rumo (se houver); 0 se nenhum.
     */
    static long match(double lat, double lon, double bearing, double maxM) {
        int la = (int) Math.round(lat * 1e6), lo = (int) Math.round(lon * 1e6);
        GraphCell c = GraphStore.get().cell(GraphStore.cellX(lo), GraphStore.cellY(la));
        if (c == null) return 0;
        int[] near = new int[256];
        int n = c.edgesNear(la, lo, near);
        double k = Math.cos(Math.toRadians(lat));
        long best = 0;
        double bestScore = Double.MAX_VALUE;
        for (int j = 0; j < n; j++) {
            int e = near[j];
            double[] dn = c.nearest(e, la, lo, k);
            if (dn[0] > maxM) continue;
            double score = dn[0];
            if (!Double.isNaN(bearing)) {
                double diff = Math.abs(Geo.angleDiff(bearing, dn[1]));
                boolean fwd = (c.flags[e] & GraphCell.BWD_ONLY) == 0 && diff < 50;
                boolean bwd = (c.flags[e] & GraphCell.FWD_ONLY) == 0 && diff > 130;
                if (!fwd && !bwd) continue; // rua cruzando ou na contramão
            }
            if (score < bestScore) {
                bestScore = score;
                best = c.key(e);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ arquivo

    private static int today() {
        return (int) (System.currentTimeMillis() / DAY_MS);
    }

    private void load() {
        if (edges != null) return;
        edges = new HashMap<>();
        if (!file.isFile()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != 0x4b345248 || in.readByte() != FORMAT) return; // "K4RH"
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                int c = in.readInt();
                int d = in.readInt();
                edges.put(k, new int[]{c, d});
            }
        } catch (IOException e) {
            Log.w(TAG, "perfil de rotas: " + e);
        }
    }

    private void save() {
        if (!dirty) return;
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(file.getPath() + ".tmp");
        int day = today();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            int n = 0;
            for (int[] v : edges.values()) if (day - v[1] <= MAX_AGE_DAYS) n++;
            out.writeInt(0x4b345248);
            out.writeByte(FORMAT);
            out.writeInt(n);
            for (Map.Entry<Long, int[]> e : edges.entrySet()) {
                if (day - e.getValue()[1] > MAX_AGE_DAYS) continue; // os velhos saem do arquivo
                out.writeLong(e.getKey());
                out.writeInt(e.getValue()[0]);
                out.writeInt(e.getValue()[1]);
            }
        } catch (IOException e) {
            Log.w(TAG, "perfil de rotas: " + e);
            return;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!tmp.renameTo(file)) return;
        }
        dirty = false;
    }
}
