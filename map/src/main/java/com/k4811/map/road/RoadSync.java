package com.k4811.map.road;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Process;
import android.util.Log;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.config.NwdConfigs;
import com.k4811.map.MapConfig;
import com.k4811.map.geo.Geo;
import com.k4811.map.route.Route;
import com.k4811.map.tiles.Tiles;
import com.k4811.map.vector.MapStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

/**
 * Mantém atualizados, quando há internet, os dois pacotes baixados do servidor: os dados das vias
 * (radares, limites de velocidade…, {@link #get}) e o mapa offline ({@link #maps}: ruas, água,
 * prédios…, desenhado pelo app). As mesmas regras para os dois:
 * <ul>
 * <li><b>Raio:</b> os quadrados num raio de {@value #AREA_KM} km do carro, conferidos a cada
 * período escolhido nas Configurações (1, 7 ou 30 dias) ou quando o carro se afasta
 * {@value #AREA_MOVE_KM} km do centro da última conferência.</li>
 * <li><b>Rota:</b> ao calcular uma rota, os quadrados por onde ela passa (inclusive fora do raio).</li>
 * </ul>
 * Conferir = baixar o {@code index.json} do servidor (CRC e tamanho de cada quadrado) e baixar só
 * os quadrados que mudaram. Sem internet, nada acontece e o mapa usa o que já está no disco (os
 * dados velhos nunca são apagados por idade; só os mais longe saem quando passa do espaço máximo).
 *
 * O mapa offline também baixa os quadrados de 2° da visão geral ({@code o<x>_<y>}) do raio e da rota.
 *
 * Estado em {@code /sdcard/K4811/nav/roads/state.json} e {@code …/maps/state.json} (lidos pelo car-setting):
 * {@code cells} (quadrado → "crc:tamanho" do que está no disco), {@code bytes}, {@code updated}
 * (última conferência completa), {@code data_generated} (quando o servidor gerou os dados),
 * {@code area_lat/area_lon/area_time} e {@code index_etag}.
 */
public final class RoadSync {

    private static final String TAG_ROADS = "K4811Road";
    private static final String TAG_MAPS = "K4811MapData";
    static final int AREA_KM = 100;
    static final int AREA_MOVE_KM = 30;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Depois de uma falha (rede), espera isso antes de tentar de novo. */
    private static final long RETRY_MS = 10L * 60 * 1000;
    /** Na rota: quadrados a menos disso da linha também entram (curvas perto da borda). */
    private static final double ROUTE_MARGIN_DEG = 0.02;
    /** Quadrado da visão geral do mapa offline (graus). */
    static final double OVERVIEW_DEG = 2.0;
    /** Baixando o mapa, os tiles na tela são refeitos no máximo a cada tanto. */
    private static final long REDRAW_MS = 5000;

    private static RoadSync roads;
    private static RoadSync maps;

    private final Context app;
    /** Este é o mapa offline (senão, os dados das vias). */
    private final boolean isMap;
    private final String TAG;
    private long lastRedraw;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "K4811Sync");
        t.setDaemon(true);
        return t;
    });

    // Estado (só no worker, menos os campos volatile lidos na thread principal).
    private JSONObject state;
    private JSONObject index;
    private long indexChecked;
    private volatile double areaLat = Double.NaN;
    private volatile double areaLon = Double.NaN;
    private volatile long areaTime;
    private volatile long lastFailure;
    private volatile boolean areaQueued;
    /** O state.json já foi lido (antes disso, área e prazos ainda não são conhecidos). */
    private volatile boolean loaded;
    private volatile Route routeQueued;
    /** Quadrados da última rota: não saem na limpeza de espaço. */
    private final Set<String> routeCells = Collections.synchronizedSet(new HashSet<String>());

    /** Dados das vias (radares, limites, lombadas…). */
    public static synchronized RoadSync get(Context c) {
        if (roads == null) roads = new RoadSync(c.getApplicationContext(), false);
        return roads;
    }

    /** Mapa offline (ruas, água, prédios…). */
    public static synchronized RoadSync maps(Context c) {
        if (maps == null) maps = new RoadSync(c.getApplicationContext(), true);
        return maps;
    }

    /** Posição atual para os dois pacotes (ver {@link #area}). */
    public static void areaAll(Context c, double lat, double lon) {
        get(c).area(lat, lon);
        maps(c).area(lat, lon);
    }

    /** Rota calculada, para os dois pacotes (ver {@link #route}). */
    public static void routeAll(Context c, Route r) {
        get(c).route(r);
        maps(c).route(r);
    }

    private RoadSync(Context app, boolean isMap) {
        this.app = app;
        this.isMap = isMap;
        TAG = isMap ? TAG_MAPS : TAG_ROADS;
        worker.execute(this::loadState);
    }

    private File dir() {
        return isMap ? MapStore.dir() : RoadStore.dir();
    }

    private String baseUrl(MapConfig cfg) {
        return isMap ? cfg.mapData : cfg.roadData;
    }

    // ------------------------------------------------------------------ gatilhos

    /**
     * Posição atual (chamar à vontade, por exemplo a cada posição do GPS): confere o raio se o
     * período venceu ou se o carro se afastou. A conta é barata; o trabalho vai para o worker.
     */
    public void area(double lat, double lon) {
        if (Double.isNaN(lat) || areaQueued || !loaded) return;
        long now = System.currentTimeMillis();
        if (now - lastFailure < RETRY_MS) return;
        boolean due = areaTime == 0 || now - areaTime >= periodMs() || now < areaTime
            || Double.isNaN(areaLat) || Geo.distance(lat, lon, areaLat, areaLon) > AREA_MOVE_KM * 1000;
        if (!due || !enabled() || !MapConfig.online(app)) return;
        areaQueued = true;
        worker.execute(() -> {
            try {
                syncArea(lat, lon);
            } finally {
                areaQueued = false;
            }
        });
    }

    /** Sem GPS à mão (internet acabou de conectar): usa a última posição conhecida. */
    public void areaFromLastKnown() {
        double[] p = lastKnown();
        if (p != null) area(p[0], p[1]);
    }

    /** Rota calculada: baixa o que falta dos quadrados por onde ela passa. */
    public void route(Route r) {
        if (r == null || r.size() < 2 || !enabled() || !MapConfig.online(app)) return;
        boolean queued = routeQueued != null;
        routeQueued = r;
        if (queued) return;
        worker.execute(() -> {
            Route latest = routeQueued;
            routeQueued = null;
            if (latest != null) syncRoute(latest);
        });
    }

    private boolean enabled() {
        if (isMap) return LauncherConfig.navOfflineMap(app);
        return LauncherConfig.navCameras(app) || LauncherConfig.navSpeedLimit(app) || LauncherConfig.navRoadAlerts(app);
    }

    private long periodMs() {
        return LauncherConfig.navDataDays(app) * DAY_MS;
    }

    // ------------------------------------------------------------------ trabalho (worker)

    private void syncArea(double lat, double lon) {
        List<String> wanted = cellsAround(lat, lon, AREA_KM);
        if (isMap) wanted.addAll(0, overviewAround(lat, lon, AREA_KM));
        Log.i(TAG, "raio: " + wanted.size() + " quadrados em volta de " + lat + "," + lon);
        if (sync(wanted)) {
            areaLat = lat;
            areaLon = lon;
            areaTime = System.currentTimeMillis();
            try {
                state.put("area_lat", lat);
                state.put("area_lon", lon);
                state.put("area_time", areaTime);
                state.put("updated", areaTime);
            } catch (JSONException ignored) {
            }
            prune();
            saveState();
        }
    }

    private void syncRoute(Route r) {
        List<String> wanted = cellsAlong(r);
        if (isMap) wanted.addAll(0, overviewAlong(r));
        routeCells.clear();
        routeCells.addAll(wanted);
        Log.i(TAG, "rota: " + wanted.size() + " quadrados");
        if (sync(wanted)) {
            prune();
            saveState();
        }
    }

    /** Confere o índice (se venceu) e baixa os quadrados que mudaram; false se a rede falhou. */
    private boolean sync(List<String> wanted) {
        MapConfig cfg = MapConfig.get(app);
        if (baseUrl(cfg).isEmpty()) {
            // Build sem endereço: não insiste a cada posição.
            lastFailure = System.currentTimeMillis();
            return false;
        }
        try {
            long now = System.currentTimeMillis();
            boolean stale = now - indexChecked >= Math.min(periodMs(), DAY_MS) || now < indexChecked;
            // Índice ainda válido de antes (o processo reiniciou): usa a cópia do disco.
            if (index == null && !stale) index = NwdConfigs.read(new File(dir(), "index.json"));
            if (index == null || stale) fetchIndex(cfg);
            JSONObject remote = index.optJSONObject("cells");
            JSONObject local = state.optJSONObject("cells");
            int downloaded = 0;
            for (String name : wanted) {
                String want = remote == null ? null : remote.optString(name, null);
                String have = local.optString(name, null);
                if (want == null) {
                    // O servidor não tem mais esse quadrado (sem dados): some daqui também.
                    if (have != null) delete(name);
                    continue;
                }
                if (want.equals(have) && file(name).isFile()) continue;
                try {
                    download(cfg, name, want);
                } catch (StaleIndex e) {
                    // Dados publicados de novo depois do índice que temos: índice novo e mais uma vez.
                    fetchIndex(cfg);
                    remote = index.optJSONObject("cells");
                    want = remote == null ? null : remote.optString(name, null);
                    if (want == null || want.equals(have)) continue;
                    download(cfg, name, want);
                }
                downloaded++;
                redraw(false);
            }
            saveState();
            if (downloaded > 0) redraw(true);
            Log.i(TAG, "conferido: " + wanted.size() + " quadrados, " + downloaded + " baixados");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sincronizar: " + e, e);
            lastFailure = System.currentTimeMillis();
            saveState();
            return false;
        }
    }

    private void fetchIndex(MapConfig cfg) throws IOException, JSONException {
        String etag = state.optString("index_etag", null);
        File cached = new File(dir(), "index.json");
        HttpURLConnection c = open(cfg, baseUrl(cfg) + "index.json");
        try {
            if (etag != null && cached.isFile()) c.setRequestProperty("If-None-Match", etag);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && index == null) {
                index = NwdConfigs.read(cached);
            } else if (code == HttpURLConnection.HTTP_OK) {
                byte[] body = readAll(c.getInputStream());
                JSONObject j = new JSONObject(new String(body, "UTF-8"));
                index = j;
                writeAtomic(cached, body);
                if (isMap) MapStore.get().indexChanged();
                String newEtag = c.getHeaderField("ETag");
                if (newEtag != null) state.put("index_etag", newEtag);
                else state.remove("index_etag");
            } else if (code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                throw new IOException("index HTTP " + code);
            }
        } finally {
            c.disconnect();
        }
        if (index == null) throw new IOException("index ilegível");
        indexChecked = System.currentTimeMillis();
        state.put("index_checked", indexChecked);
        state.put("data_generated", index.optLong("generated", 0));
    }

    private void download(MapConfig cfg, String name, String want) throws IOException, JSONException {
        String[] crcSize = want.split(":");
        HttpURLConnection c = open(cfg, baseUrl(cfg) + "cells/" + name + ".bin");
        byte[] body;
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException(name + " HTTP " + code);
            body = readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
        CRC32 crc = new CRC32();
        crc.update(body);
        if (!Long.toHexString(crc.getValue()).equals(crcSize[0])) {
            throw new StaleIndex(name);
        }
        writeAtomic(file(name), body);
        state.getJSONObject("cells").put(name, want);
        invalidate(name);
    }

    private void invalidate(String name) {
        if (isMap) {
            MapStore.get().invalidate(name);
        } else {
            int[] xy = xy(name);
            RoadStore.get().invalidate(xy[0], xy[1]);
        }
    }

    /** Mapa offline: os tiles na tela são refeitos com o que chegou (no máximo a cada {@link #REDRAW_MS}). */
    private void redraw(boolean now) {
        if (!isMap) return;
        long t = System.currentTimeMillis();
        if (!now && t - lastRedraw < REDRAW_MS) return;
        lastRedraw = t;
        Tiles.get(app).dataChanged();
    }

    private void delete(String name) {
        //noinspection ResultOfMethodCallIgnored
        file(name).delete();
        state.optJSONObject("cells").remove(name);
        invalidate(name);
    }

    /** Passou do espaço máximo: apaga os quadrados mais longe do centro do raio (fora da rota). */
    private void prune() {
        long limit = isMap ? MapConfig.get(app).mapDataBytes : MapConfig.get(app).roadDataBytes;
        JSONObject local = state.optJSONObject("cells");
        long total = 0;
        List<String> names = new ArrayList<>();
        Iterator<String> it = local.keys();
        while (it.hasNext()) {
            String name = it.next();
            names.add(name);
            total += size(local.optString(name));
        }
        if (total > limit && !Double.isNaN(areaLat)) {
            final double lat = areaLat, lon = areaLon;
            Collections.sort(names, (a, b) -> Double.compare(cellDistance(b, lat, lon), cellDistance(a, lat, lon)));
            for (String name : names) {
                if (total <= limit * 9 / 10) break;
                if (routeCells.contains(name) || cellDistance(name, lat, lon) <= AREA_KM * 1000) continue;
                total -= size(local.optString(name));
                delete(name);
            }
        }
        try {
            state.put("bytes", total);
        } catch (JSONException ignored) {
        }
    }

    // ------------------------------------------------------------------ quadrados

    /** Quadrados a até {@code km} de um ponto, do mais perto para o mais longe. */
    static List<String> cellsAround(double lat, double lon, int km) {
        double dLat = km / 110.574;
        double dLon = km / (111.320 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        int x0 = RoadCell.cellX(lon - dLon), x1 = RoadCell.cellX(lon + dLon);
        int y0 = RoadCell.cellY(lat - dLat), y1 = RoadCell.cellY(lat + dLat);
        List<String> out = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                String name = RoadCell.name(x, y);
                if (cellDistance(name, lat, lon) <= km * 1000) out.add(name);
            }
        }
        Collections.sort(out, (a, b) -> Double.compare(cellDistance(a, lat, lon), cellDistance(b, lat, lon)));
        return out;
    }

    /** Quadrados da visão geral (2°) a até {@code km} de um ponto. */
    static List<String> overviewAround(double lat, double lon, int km) {
        double dLat = km / 110.574;
        double dLon = km / (111.320 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        List<String> out = new ArrayList<>();
        for (int y = ovCell(lat - dLat, 90); y <= ovCell(lat + dLat, 90); y++) {
            for (int x = ovCell(lon - dLon, 180); x <= ovCell(lon + dLon, 180); x++) {
                String name = "o" + x + "_" + y;
                if (cellDistance(name, lat, lon) <= km * 1000) out.add(name);
            }
        }
        return out;
    }

    /** Quadrados da visão geral por onde a rota passa. */
    static List<String> overviewAlong(Route r) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < r.size(); i++) out.add("o" + ovCell(r.lon[i], 180) + "_" + ovCell(r.lat[i], 90));
        return new ArrayList<>(out);
    }

    private static int ovCell(double deg, double offset) {
        return (int) Math.floor((deg + offset) / OVERVIEW_DEG);
    }

    /** Quadrados por onde a rota passa (com uma margem), na ordem do caminho. */
    static List<String> cellsAlong(Route r) {
        return cellsAlong(r, 0, Double.MAX_VALUE);
    }

    /** Idem, só no trecho entre {@code from} e {@code to} metros do começo da rota. */
    static List<String> cellsAlong(Route r, double from, double to) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        double m = ROUTE_MARGIN_DEG;
        for (int i = 0; i < r.size(); i++) {
            if (r.along[i] < from && (i + 1 >= r.size() || r.along[i + 1] < from)) continue;
            if (i > 0 && r.along[i - 1] > to) break;
            // Entre dois pontos longe um do outro, passos de ~1 km.
            int steps = 1;
            if (i > 0) steps = Math.max(1, (int) ((r.along[i] - r.along[i - 1]) / 1000));
            for (int s = 1; s <= steps; s++) {
                double lat = i == 0 ? r.lat[0] : r.lat[i - 1] + (r.lat[i] - r.lat[i - 1]) * s / steps;
                double lon = i == 0 ? r.lon[0] : r.lon[i - 1] + (r.lon[i] - r.lon[i - 1]) * s / steps;
                out.add(RoadCell.name(RoadCell.cellX(lon), RoadCell.cellY(lat)));
                out.add(RoadCell.name(RoadCell.cellX(lon - m), RoadCell.cellY(lat - m)));
                out.add(RoadCell.name(RoadCell.cellX(lon + m), RoadCell.cellY(lat - m)));
                out.add(RoadCell.name(RoadCell.cellX(lon - m), RoadCell.cellY(lat + m)));
                out.add(RoadCell.name(RoadCell.cellX(lon + m), RoadCell.cellY(lat + m)));
            }
        }
        return new ArrayList<>(out);
    }

    /** Distância em metros do ponto até o retângulo do quadrado (0 se estiver dentro). */
    static double cellDistance(String name, double lat, double lon) {
        int[] xy = xy(name);
        double d = name.startsWith("o") ? OVERVIEW_DEG : RoadCell.CELL_DEG;
        double s = xy[1] * d - 90, w = xy[0] * d - 180;
        double la = Math.max(s, Math.min(s + d, lat));
        double lo = Math.max(w, Math.min(w + d, lon));
        return Geo.distance(lat, lon, la, lo);
    }

    static int[] xy(String name) {
        int u = name.indexOf('_');
        int start = name.startsWith("o") ? 1 : 0;
        return new int[]{Integer.parseInt(name.substring(start, u)), Integer.parseInt(name.substring(u + 1))};
    }

    private File file(String name) {
        return new File(new File(dir(), "cells"), name + ".bin");
    }

    private static long size(String crcSize) {
        int c = crcSize.indexOf(':');
        try {
            return c < 0 ? 0 : Long.parseLong(crcSize.substring(c + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ estado e rede

    private void loadState() {
        JSONObject j = NwdConfigs.read(new File(dir(), "state.json"));
        state = j == null ? new JSONObject() : j;
        try {
            if (state.optJSONObject("cells") == null) state.put("cells", new JSONObject());
        } catch (JSONException ignored) {
        }
        indexChecked = state.optLong("index_checked", 0);
        areaTime = state.optLong("area_time", 0);
        if (state.has("area_lat")) {
            areaLat = state.optDouble("area_lat", Double.NaN);
            areaLon = state.optDouble("area_lon", Double.NaN);
        }
        loaded = true;
    }

    private void saveState() {
        File dir = dir();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        NwdConfigs.write(new File(dir, "state.json"), state);
    }

    private HttpURLConnection open(MapConfig cfg, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", cfg.userAgent);
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename " + f);
        }
    }

    /** O arquivo baixado não bate com o índice: o servidor publicou dados novos no meio. */
    private static final class StaleIndex extends IOException {
        StaleIndex(String name) {
            super(name + ": CRC diferente do índice");
        }
    }

    /** Última posição: a do GPS do Android ou, sem ela, onde o mapa estava da última vez. */
    private double[] lastKnown() {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            Location l = lm == null ? null : lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null && lm != null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) return new double[]{l.getLatitude(), l.getLongitude()};
        } catch (Exception ignored) {
        }
        SharedPreferences p = app.getSharedPreferences("k4811_map", Context.MODE_PRIVATE);
        if (p.contains("lat")) return new double[]{p.getFloat("lat", 0), p.getFloat("lon", 0)};
        return null;
    }
}
