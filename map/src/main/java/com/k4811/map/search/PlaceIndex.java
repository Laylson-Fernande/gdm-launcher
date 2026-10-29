package com.k4811.map.search;

import android.util.Log;

import com.k4811.map.MapConfig;
import com.k4811.map.route.Places;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Busca sem internet no índice de endereços baixado ({@code /sdcard/K4811/nav/places/cells/}, ver
 * {@link com.k4811.map.road.RoadSync#places}): serve para as sugestões enquanto o motorista digita e
 * para a busca quando não há internet.
 *
 * Cada palavra digitada precisa ser o começo de uma palavra do nome (ou do bairro e da cidade): "fran
 * bran" acha "Rua Francisco Branco"; "parque euclides" acha o parque. Um número no fim ("…branco 1326")
 * é o da casa: o ponto vem dos números conhecidos da rua (CNEFE do IBGE e OpenStreetMap), interpolados
 * entre os vizinhos; sem ele, o ponto da rua sai como aproximado.
 *
 * Lê os quadrados baixados num raio de {@value #RADIUS_KM} km, do mais perto para o mais longe, até
 * {@value #MEMORY_MB} MB em memória. Ler só fora da thread principal.
 */
public final class PlaceIndex {

    private static final String TAG = "K4811Places";
    static final double CELL_DEG = 0.25;
    private static final int RADIUS_KM = 100;
    private static final int MEMORY_MB = 16;
    /** Número fora da faixa conhecida da rua por mais que isso: aproximado. */
    private static final int NUMBER_SLACK = 30;

    private static PlaceIndex instance;

    private final File cellsDir;
    private final LinkedHashMap<String, PlaceCell> memory = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> broken = new HashSet<>();
    private int memoryBytes;
    /** Arquivos no disco (nomes sem .bin); null = ainda não listou. */
    private Set<String> files;

    public static synchronized PlaceIndex get() {
        if (instance == null) instance = new PlaceIndex(new File(dir(), "cells"));
        return instance;
    }

    public static File dir() {
        return new File(MapConfig.dir(), "places");
    }

    PlaceIndex(File cellsDir) {
        this.cellsDir = cellsDir;
    }

    /** Há algum quadrado baixado perto do ponto (a busca sem internet tem com o que trabalhar). */
    public synchronized boolean hasData(double lat, double lon) {
        return !cellsNear(lat, lon, 30).isEmpty();
    }

    /** Lê de antemão os quadrados em volta (a busca abriu: a primeira letra já sai rápida). */
    public synchronized void warm(double lat, double lon) {
        for (String name : cellsNear(lat, lon, RADIUS_KM)) {
            cell(name);
            if (memoryBytes > MEMORY_MB * 1024 * 1024) break;
        }
    }

    /** Um quadrado foi baixado de novo ou apagado. */
    public synchronized void invalidate(String name) {
        PlaceCell c = memory.remove(name);
        if (c != null) memoryBytes -= c.memoryBytes();
        broken.remove(name);
        files = null;
    }

    /** Devolve a memória (tela do mapa fechada). */
    public synchronized void trimMemory() {
        memory.clear();
        memoryBytes = 0;
    }

    // ------------------------------------------------------------------ busca

    /** Até {@code limit} resultados para o texto digitado, os melhores primeiro (perto pesa a favor). */
    public synchronized List<Places.Place> search(String query, double lat, double lon, int limit) {
        Query q = Query.parse(query);
        if (q == null) return Collections.emptyList();
        long t0 = System.currentTimeMillis();
        List<Hit> hits = new ArrayList<>();
        int scanned = 0;
        for (String name : cellsNear(lat, lon, RADIUS_KM)) {
            PlaceCell c = cell(name);
            if (c == null) continue;
            scanned += c.count;
            scan(c, q, lat, lon, hits);
            if (memoryBytes > MEMORY_MB * 1024 * 1024) break; // o resto fica longe demais para caber
        }
        Collections.sort(hits, (a, b) -> Double.compare(b.score, a.score));
        List<Places.Place> out = new ArrayList<>();
        List<Hit> kept = new ArrayList<>();
        for (Hit h : hits) {
            // O mesmo nome na mesma cidade e perto (trechos da mesma rua, um de cada lado da borda de um
            // quadrado): fica o melhor.
            if (duplicate(kept, h)) continue;
            kept.add(h);
            out.add(place(h, q));
            if (out.size() >= limit) break;
        }
        Log.d(TAG, "\"" + query + "\": " + hits.size() + " de " + scanned + " em "
            + (System.currentTimeMillis() - t0) + " ms");
        return out;
    }

    /** Texto digitado, separado: palavras (sem "de", "da"…) e o número da casa. */
    static final class Query {
        String[] words;
        /** Número da casa, ou -1. */
        int number = -1;
        String numberText;

        static Query parse(String text) {
            String n = Text.normalize(text);
            if (n.isEmpty()) return null;
            String[] all = n.split(" ");
            Query q = new Query();
            // Número da casa: o último número que não começa a busca nem faz parte do nome ("Rua 7 de Setembro").
            for (int i = all.length - 1; i >= 1; i--) {
                if (!Text.isNumber(all[i])) continue;
                boolean partOfName = i + 1 < all.length && STOP.contains(all[i + 1]);
                if (!partOfName) {
                    q.number = Integer.parseInt(all[i]);
                    q.numberText = all[i];
                    all[i] = null;
                    // "n 123", "numero 123"
                    if (all[i - 1] != null && (all[i - 1].equals("n") || all[i - 1].equals("numero"))) all[i - 1] = null;
                }
                break;
            }
            List<String> words = new ArrayList<>();
            for (String w : all) if (w != null && !STOP.contains(w)) words.add(w);
            if (words.isEmpty()) for (String w : all) if (w != null) words.add(w);
            if (words.isEmpty()) return null;
            q.words = words.toArray(new String[0]);
            return q;
        }
    }

    static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList("de", "da", "do", "das", "dos", "e"));

    static final class Hit {
        PlaceCell cell;
        int i;
        double score;
    }

    private static void scan(PlaceCell c, Query q, double lat, double lon, List<Hit> out) {
        int latE6 = (int) Math.round(lat * 1e6), lonE6 = (int) Math.round(lon * 1e6);
        double k = Math.cos(Math.toRadians(lat));
        String[] words = q.words;
        int nw = words.length;
        String[] spaced = new String[nw];
        for (int w = 0; w < nw; w++) spaced[w] = " " + words[w];
        // As palavras na ordem digitada ("rua augusta" em "Rua Augusta", não em "Rua Carolina Augusta").
        StringBuilder joined = new StringBuilder();
        for (String w : words) joined.append(' ').append(w);
        String phrase = joined.toString();
        for (int i = 0; i < c.count; i++) {
            String name = c.norm[c.name[i]];
            int inName = 0;
            int whole = 0;
            boolean all = true;
            for (int w = 0; w < nw; w++) {
                int at = name.indexOf(spaced[w]);
                if (at >= 0) {
                    inName++;
                    int end = at + spaced[w].length();
                    if (end == name.length() || name.charAt(end) == ' ') whole++;
                    continue;
                }
                String sub = c.suburb[i] == 0 ? null : c.norm[c.suburb[i] - 1];
                String city = c.city[i] == 0 ? null : c.norm[c.city[i] - 1];
                if ((sub != null && sub.contains(spaced[w])) || (city != null && city.contains(spaced[w]))) continue;
                all = false;
                break;
            }
            if (!all || inName == 0) continue;
            int type = c.type[i];
            double s;
            switch (type) {
                case PlaceCell.CITY:
                    s = 50 - c.rank[i] * 12;
                    break;
                case PlaceCell.SUBURB:
                    s = 28;
                    break;
                case PlaceCell.POI:
                    // Museu, parque, hospital… antes da loja com o mesmo nome (se o nome bate inteiro).
                    s = 22 + (inName == nw ? c.rank[i] * 8 : 0);
                    break;
                default:
                    s = 18;
                    int a = c.numStart[i], b = c.numStart[i + 1];
                    if (q.number >= 0 && b > a) {
                        s += 14;
                        // O número pedido está na faixa conhecida da rua: é quase certamente esta.
                        if (q.number >= c.numbers[a] - NUMBER_SLACK && q.number <= c.numbers[b - 3] + NUMBER_SLACK) s += 20;
                    }
                    break;
            }
            s += inName * 10 + whole * 3;
            // Palavra que só bateu no bairro ou na cidade ajuda a filtrar, mas vale bem menos que no nome.
            s -= (nw - inName) * 8;
            if (name.startsWith(spaced[0])) s += 8;
            if (nw > 1 && name.contains(phrase)) s += 8;
            // Palavras do nome que não foram digitadas (o nome que bate mais certinho vem antes).
            int nameWords = 1;
            for (int p = 1; p < name.length(); p++) if (name.charAt(p) == ' ') nameWords++;
            s -= Math.min(3, Math.max(0, nameWords - inName)) * 1.5;
            double km = Math.hypot((c.lat[i] - latE6) / 1e6, (c.lon[i] - lonE6) / 1e6 * k) * 111.2;
            s -= 9 * Math.log10(1 + km);
            Hit h = new Hit();
            h.cell = c;
            h.i = i;
            h.score = s;
            out.add(h);
        }
    }

    private static boolean duplicate(List<Hit> kept, Hit h) {
        PlaceCell c = h.cell;
        String name = c.norm[c.name[h.i]];
        String city = c.city[h.i] == 0 ? "" : c.norm[c.city[h.i] - 1];
        for (Hit o : kept) {
            PlaceCell d = o.cell;
            if (!d.norm[d.name[o.i]].equals(name)) continue;
            if (!(d.city[o.i] == 0 ? "" : d.norm[d.city[o.i] - 1]).equals(city)) continue;
            if (Math.abs(d.lat[o.i] - c.lat[h.i]) < 20000 && Math.abs(d.lon[o.i] - c.lon[h.i]) < 20000) return true;
        }
        return false;
    }

    /** O resultado para a tela: rua com o número (interpolado) quando foi pedido. */
    private static Places.Place place(Hit h, Query q) {
        PlaceCell c = h.cell;
        int i = h.i;
        String name = c.string(c.name[i]);
        String detail = detail(c, i);
        double lat = c.lat[i] / 1e6, lon = c.lon[i] / 1e6;
        if (c.type[i] != PlaceCell.STREET || q.number < 0) return new Places.Place(name, detail, lat, lon);
        int a = c.numStart[i], b = c.numStart[i + 1];
        if (b <= a) return new Places.Place(name, detail, lat, lon, true, q.numberText);
        int n = q.number;
        int[] v = c.numbers;
        int first = v[a], last = v[b - 3];
        String withNumber = name + ", " + q.numberText;
        if (n <= first || n >= last) {
            int at = n <= first ? a : b - 3;
            boolean approx = Math.abs(n - v[at]) > NUMBER_SLACK;
            return new Places.Place(approx ? name : withNumber, detail, v[at + 1] / 1e6, v[at + 2] / 1e6, approx,
                approx ? q.numberText : null);
        }
        for (int j = a; j + 3 < b; j += 3) {
            if (n < v[j] || n > v[j + 3]) continue;
            double t = v[j + 3] == v[j] ? 0 : (n - v[j]) / (double) (v[j + 3] - v[j]);
            double la = v[j + 1] + (v[j + 4] - v[j + 1]) * t;
            double lo = v[j + 2] + (v[j + 5] - v[j + 2]) * t;
            return new Places.Place(withNumber, detail, la / 1e6, lo / 1e6);
        }
        return new Places.Place(name, detail, lat, lon, true, q.numberText);
    }

    private static String detail(PlaceCell c, int i) {
        String sub = c.suburb[i] == 0 ? null : c.string(c.suburb[i] - 1);
        String city = c.city[i] == 0 ? null : c.string(c.city[i] - 1);
        if (sub == null) return city == null ? "" : city;
        return city == null ? sub : sub + ", " + city;
    }

    // ------------------------------------------------------------------ quadrados

    /** Quadrados baixados a até {@code km} do ponto, do mais perto para o mais longe. */
    private List<String> cellsNear(double lat, double lon, int km) {
        if (files == null) {
            files = new HashSet<>();
            String[] list = cellsDir.list();
            if (list != null) for (String f : list) if (f.endsWith(".bin")) files.add(f.substring(0, f.length() - 4));
        }
        List<String> out = new ArrayList<>();
        final List<Double> dist = new ArrayList<>();
        double dLat = km / 110.574, dLon = km / (111.320 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        int x0 = cell(lon - dLon, 180), x1 = cell(lon + dLon, 180), y0 = cell(lat - dLat, 90), y1 = cell(lat + dLat, 90);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                String name = x + "_" + y;
                if (!files.contains(name)) continue;
                double s = y * CELL_DEG - 90, w = x * CELL_DEG - 180;
                double la = Math.max(s, Math.min(s + CELL_DEG, lat)), lo = Math.max(w, Math.min(w + CELL_DEG, lon));
                double d = Math.hypot(la - lat, (lo - lon) * Math.cos(Math.toRadians(lat))) * 111.2;
                if (d > km) continue;
                int at = 0;
                while (at < dist.size() && dist.get(at) <= d) at++;
                out.add(at, name);
                dist.add(at, d);
            }
        }
        return out;
    }

    private static int cell(double deg, double offset) {
        return (int) Math.floor((deg + offset) / CELL_DEG);
    }

    private PlaceCell cell(String name) {
        PlaceCell c = memory.get(name);
        if (c != null || broken.contains(name)) return c;
        try {
            long t0 = System.currentTimeMillis();
            c = PlaceCell.read(new File(cellsDir, name + ".bin"));
            Log.i(TAG, "quadrado " + name + ": " + c.count + " entradas, " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception e) {
            Log.w(TAG, "quadrado " + name + ": " + e);
            broken.add(name);
            return null;
        }
        memory.put(name, c);
        memoryBytes += c.memoryBytes();
        Iterator<Map.Entry<String, PlaceCell>> it = memory.entrySet().iterator();
        while (memoryBytes > MEMORY_MB * 1024 * 1024 * 5 / 4 && memory.size() > 1 && it.hasNext()) {
            Map.Entry<String, PlaceCell> e = it.next();
            if (e.getKey().equals(name)) continue;
            memoryBytes -= e.getValue().memoryBytes();
            it.remove();
        }
        return c;
    }
}
