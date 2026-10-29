import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Gera o pacote de dados das vias usado pelo mapa integrado (radares, limite de velocidade,
 * lombadas, pedágios, passagens de nível) a partir de um recorte do OpenStreetMap (.osm.pbf).
 *
 * Saída em {@code <saída>/v1/}: {@code index.json} (lista dos quadrados com CRC e tamanho) e
 * {@code cells/<cx>_<cy>.bin}, um arquivo por quadrado de {@value #CELL_DEG}°. Formato em
 * {@code tools/road-data/README.md}; leitor no app em {@code map/.../road/RoadCell.java}.
 *
 * Uso: {@code java -Xmx6g -cp build RoadDataBuilder <entrada.osm.pbf> <saída> [descrição da fonte]}
 */
public final class RoadDataBuilder {

    static final double CELL_DEG = 0.25;
    static final int CELL_E6 = 250000;
    static final int FORMAT = 1;

    // Tipos de ponto (iguais aos do app).
    static final int SPEED_CAMERA = 1;
    static final int RED_LIGHT_CAMERA = 2;
    static final int TRAFFIC_CALMING = 3;
    static final int TOLL_BOOTH = 4;
    static final int LEVEL_CROSSING = 5;

    // Classe da via; +16 nas ligações (_link).
    static final Map<String, Integer> CLASSES = new HashMap<>();

    static {
        String[] c = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street"};
        for (int i = 0; i < c.length; i++) CLASSES.put(c[i], i + 1);
        for (int i = 0; i < 5; i++) CLASSES.put(c[i] + "_link", (i + 1) | 16);
    }

    /** Classe até a qual a via entra mesmo sem limite conhecido (evita casar com a rua errada ao lado). */
    static final int MAJOR_MAX = 5;

    // ---- passo 1: vias e relações
    final LongList nodeIds = new LongList(1 << 22); // nós de vias de carro + membros de relações
    final LongList wayRefs = new LongList(1 << 22); // nós das vias guardadas, em sequência
    final IntList wayStart = new IntList(1 << 18);
    final ByteList wayClass = new ByteList(1 << 18);
    final ByteList wayOneway = new ByteList(1 << 18);
    final ByteList waySpeedFwd = new ByteList(1 << 18);
    final ByteList waySpeedBwd = new ByteList(1 << 18);
    final List<long[]> enforcements = new ArrayList<>(); // {tipo, device, from, to, velocidade}

    // ---- passo 2: nós
    long[] ids;          // ordenado, sem repetição
    int[] latE6, lonE6;  // coordenadas de ids[i]; Integer.MIN_VALUE = não achado
    final LongList pointIds = new LongList(1 << 12);
    final IntList pointLat = new IntList(1 << 12), pointLon = new IntList(1 << 12);
    final ByteList pointType = new ByteList(1 << 12), pointSpeed = new ByteList(1 << 12);
    final IntList pointDir = new IntList(1 << 12);
    /** "direction" relativo à via: 1 forward, 2 backward, 0 nenhum. */
    final ByteList pointRelDir = new ByteList(1 << 12);
    final ByteList pointNeedsRoad = new ByteList(1 << 12);

    int waysSeen, waysKept, waysWithSpeed;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: RoadDataBuilder <entrada.osm.pbf> <saída> [descrição da fonte]");
            System.exit(2);
        }
        new RoadDataBuilder().run(args[0], new File(args[1]), args.length > 2 ? args[2] : new File(args[0]).getName());
    }

    void run(String pbf, File out, String source) throws IOException {
        long t0 = System.currentTimeMillis();
        byte[] kinds = Pbf.read(pbf, Pbf.WAYS | Pbf.RELATIONS, null, new Pbf.Handler() {
            @Override
            public void node(long id, double lat, double lon, String[] tags) {
            }

            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
                onWay(refs, count, tags);
            }

            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
                onRelation(mids, types, roles, count, tags);
            }
        });
        log(t0, "passo 1: %d vias de carro, %d guardadas (%d com limite), %d fiscalizações",
            waysSeen, waysKept, waysWithSpeed, enforcements.size());

        ids = nodeIds.sortedUnique();
        nodeIds.clear();
        latE6 = new int[ids.length];
        lonE6 = new int[ids.length];
        Arrays.fill(latE6, Integer.MIN_VALUE);
        log(t0, "nós necessários: %d", ids.length);

        byte[] nodeBlobs = new byte[kinds.length];
        for (int i = 0; i < kinds.length; i++) nodeBlobs[i] = (byte) (kinds[i] & Pbf.NODES);
        Pbf.read(pbf, Pbf.NODES, nodeBlobs, new Pbf.Handler() {
            int cursor;

            @Override
            public void node(long id, double lat, double lon, String[] tags) {
                // Os .pbf da Geofabrik vêm ordenados por id: um cursor basta; se não, busca binária.
                while (cursor < ids.length && ids[cursor] < id) cursor++;
                int i = cursor < ids.length && ids[cursor] == id ? cursor : Arrays.binarySearch(ids, id);
                if (i >= 0) {
                    latE6[i] = (int) Math.round(lat * 1e6);
                    lonE6[i] = (int) Math.round(lon * 1e6);
                }
                if (tags != null) onTaggedNode(id, lat, lon, tags);
            }

            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
            }

            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
            }
        });
        log(t0, "passo 2: %d pontos com tag", pointIds.size());

        applyEnforcements();
        directionsFromWays();
        write(out, source, t0);
    }

    // ------------------------------------------------------------------ vias

    void onWay(long[] refs, int count, String[] tags) {
        String hw = Pbf.tag(tags, "highway");
        if (hw == null || count < 2) return;
        Integer cls = CLASSES.get(hw);
        if (cls == null) return;
        String area = Pbf.tag(tags, "area");
        if ("yes".equals(area)) return;
        waysSeen++;
        nodeIds.addAll(refs, count);

        int both = speed(Pbf.tag(tags, "maxspeed"));
        int fwd = speed(Pbf.tag(tags, "maxspeed:forward"));
        int bwd = speed(Pbf.tag(tags, "maxspeed:backward"));
        if (fwd == 0) fwd = both;
        if (bwd == 0) bwd = both;
        boolean known = fwd > 0 || bwd > 0;
        if (!known && (cls & 15) > MAJOR_MAX) return;

        int oneway = 0;
        String ow = Pbf.tag(tags, "oneway");
        if ("yes".equals(ow) || "1".equals(ow) || "true".equals(ow)) oneway = 1;
        else if ("-1".equals(ow) || "reverse".equals(ow)) oneway = 2;
        else if (!"no".equals(ow) && ("motorway".equals(hw) || "roundabout".equals(Pbf.tag(tags, "junction")))) oneway = 1;

        wayStart.add(wayRefs.size());
        wayRefs.addAll(refs, count);
        wayClass.add(cls);
        wayOneway.add(oneway);
        waySpeedFwd.add(fwd);
        waySpeedBwd.add(bwd);
        waysKept++;
        if (known) waysWithSpeed++;
    }

    /** Limite em km/h; 0 = desconhecido (sem tag, "none", "signals", zona implícita etc.). */
    static int speed(String v) {
        if (v == null) return 0;
        v = v.trim();
        int semi = v.indexOf(';');
        if (semi > 0) v = v.substring(0, semi).trim();
        boolean mph = v.endsWith("mph");
        if (mph) v = v.substring(0, v.length() - 3).trim();
        else if (v.endsWith("km/h")) v = v.substring(0, v.length() - 4).trim();
        try {
            double n = Double.parseDouble(v);
            if (mph) n *= 1.609344;
            int kmh = (int) Math.round(n);
            return kmh >= 5 && kmh <= 200 ? kmh : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ relações de fiscalização

    void onRelation(long[] mids, int[] types, String[] roles, int count, String[] tags) {
        if (!"enforcement".equals(Pbf.tag(tags, "type"))) return;
        String what = Pbf.tag(tags, "enforcement");
        int type;
        if ("maxspeed".equals(what) || "average_speed".equals(what)) type = SPEED_CAMERA;
        else if ("traffic_signals".equals(what)) type = RED_LIGHT_CAMERA;
        else return;
        long device = 0, from = 0, to = 0;
        for (int i = 0; i < count; i++) {
            if (types[i] != 0) continue;
            if ("device".equals(roles[i]) && device == 0) device = mids[i];
            else if ("from".equals(roles[i]) && from == 0) from = mids[i];
            else if ("to".equals(roles[i]) && to == 0) to = mids[i];
        }
        if (device == 0) return;
        int sp = speed(Pbf.tag(tags, "maxspeed"));
        enforcements.add(new long[]{type, device, from, to, sp});
        nodeIds.add(device);
        if (from != 0) nodeIds.add(from);
        if (to != 0) nodeIds.add(to);
    }

    // ------------------------------------------------------------------ pontos

    void onTaggedNode(long id, double lat, double lon, String[] tags) {
        int type = 0;
        boolean needsRoad = true;
        String hw = Pbf.tag(tags, "highway");
        String calming = Pbf.tag(tags, "traffic_calming");
        if ("speed_camera".equals(hw)) {
            type = SPEED_CAMERA;
            needsRoad = false; // o radar costuma estar ao lado da pista, não em cima dela
        } else if (calming != null && isBump(calming)) {
            type = TRAFFIC_CALMING;
        } else if ("toll_booth".equals(Pbf.tag(tags, "barrier"))) {
            type = TOLL_BOOTH;
        } else if ("level_crossing".equals(Pbf.tag(tags, "railway"))) {
            type = LEVEL_CROSSING;
        }
        if (type == 0) return;
        pointIds.add(id);
        pointLat.add((int) Math.round(lat * 1e6));
        pointLon.add((int) Math.round(lon * 1e6));
        pointType.add(type);
        pointSpeed.add(type == SPEED_CAMERA ? speed(Pbf.tag(tags, "maxspeed")) : 0);
        String dir = Pbf.tag(tags, "direction");
        if (dir == null) dir = Pbf.tag(tags, "camera:direction");
        pointDir.add(direction(dir));
        String rel = dir == null ? "" : dir.trim();
        pointRelDir.add("forward".equals(rel) ? 1 : "backward".equals(rel) ? 2 : 0);
        pointNeedsRoad.add(needsRoad ? 1 : 0);
    }

    static boolean isBump(String v) {
        switch (v) {
            case "bump":
            case "hump":
            case "table":
            case "cushion":
            case "yes":
            case "dip":
                return true;
            default:
                return false;
        }
    }

    /** Rumo em graus (0 = norte) de "direction"; -1 se ausente ou relativo à via (forward/backward). */
    static int direction(String v) {
        if (v == null) return -1;
        v = v.trim().toUpperCase(Locale.ROOT);
        String[] card = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        for (int i = 0; i < card.length; i++) if (card[i].equals(v)) return (int) Math.round(i * 22.5) % 360;
        try {
            int d = (int) Math.round(Double.parseDouble(v));
            return ((d % 360) + 360) % 360;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Junta as relações de fiscalização: velocidade e sentido no radar, ou um radar novo. */
    void applyEnforcements() {
        Map<Long, Integer> byId = new HashMap<>();
        for (int i = 0; i < pointIds.size(); i++) byId.put(pointIds.get(i), i);
        int added = 0;
        for (long[] e : enforcements) {
            int type = (int) e[0];
            int dev = Arrays.binarySearch(ids, e[1]);
            if (dev < 0 || latE6[dev] == Integer.MIN_VALUE) continue;
            int dir = -1;
            int a = e[2] != 0 ? Arrays.binarySearch(ids, e[2]) : -1;
            int b = e[3] != 0 ? Arrays.binarySearch(ids, e[3]) : dev;
            if (a >= 0 && b >= 0 && latE6[a] != Integer.MIN_VALUE && latE6[b] != Integer.MIN_VALUE && a != b) {
                dir = (int) Math.round(bearing(latE6[a] / 1e6, lonE6[a] / 1e6, latE6[b] / 1e6, lonE6[b] / 1e6)) % 360;
            }
            Integer p = byId.get(e[1]);
            if (p != null) {
                if (type == RED_LIGHT_CAMERA) pointType.set(p, RED_LIGHT_CAMERA);
                if (pointSpeed.get(p) == 0) pointSpeed.set(p, (int) e[4]);
                if (pointDir.get(p) < 0) pointDir.set(p, dir);
            } else {
                pointIds.add(e[1]);
                pointLat.add(latE6[dev]);
                pointLon.add(lonE6[dev]);
                pointType.add(type);
                pointSpeed.add((int) e[4]);
                pointDir.add(dir);
                pointRelDir.add(0);
                pointNeedsRoad.add(0);
                byId.put(e[1], pointIds.size() - 1);
                added++;
            }
        }
        System.out.println("  fiscalizações: " + added + " radares só na relação");
    }

    /**
     * Radar sem sentido que é um nó de uma via guardada: numa via de mão única, o sentido é o da via
     * (pistas separadas, como numa avenida com canteiro); com direction=forward/backward, o sentido
     * da via no ponto (ou o contrário). Evita avisar o radar da pista do outro lado.
     */
    void directionsFromWays() {
        Map<Long, Integer> cams = new HashMap<>();
        for (int p = 0; p < pointIds.size(); p++) {
            int t = pointType.get(p);
            if ((t == SPEED_CAMERA || t == RED_LIGHT_CAMERA) && pointDir.get(p) < 0) cams.put(pointIds.get(p), p);
        }
        int fixed = 0;
        for (int w = 0; w < wayStart.size() && !cams.isEmpty(); w++) {
            int a = wayStart.get(w);
            int b = w + 1 < wayStart.size() ? wayStart.get(w + 1) : wayRefs.size();
            for (int i = a; i < b; i++) {
                Integer p = cams.get(wayRefs.get(i));
                if (p == null) continue;
                int rel = pointRelDir.get(p);
                int oneway = wayOneway.get(w);
                boolean reverse;
                if (rel == 1 || rel == 2) reverse = rel == 2;
                else if (oneway == 1 || oneway == 2) reverse = oneway == 2;
                else continue;
                // Rumo do trecho que passa pelo nó (o seguinte; no último nó, o anterior).
                int i0 = i + 1 < b ? i : i - 1;
                if (i0 < a) continue;
                int n0 = Arrays.binarySearch(ids, wayRefs.get(i0));
                int n1 = Arrays.binarySearch(ids, wayRefs.get(i0 + 1));
                if (n0 < 0 || n1 < 0 || latE6[n0] == Integer.MIN_VALUE || latE6[n1] == Integer.MIN_VALUE) continue;
                double brg = bearing(latE6[n0] / 1e6, lonE6[n0] / 1e6, latE6[n1] / 1e6, lonE6[n1] / 1e6);
                if (reverse) brg += 180;
                pointDir.set(p, (int) Math.round(brg) % 360);
                cams.remove(wayRefs.get(i));
                fixed++;
            }
        }
        System.out.println("  sentido pela via: " + fixed + " radares");
    }

    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    // ------------------------------------------------------------------ quadrados

    static int cellX(int lonE6) {
        return Math.floorDiv(lonE6 + 180000000, CELL_E6);
    }

    static int cellY(int latE6) {
        return Math.floorDiv(latE6 + 90000000, CELL_E6);
    }

    static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xffffffffL);
    }

    void write(File out, String source, long t0) throws IOException {
        // Quadrados de cada via: os dos vértices e, em trechos longos, os do meio.
        Map<Long, IntList> cellWays = new HashMap<>();
        int[] lat = new int[256], lon = new int[256];
        int dropped = 0;
        for (int w = 0; w < wayStart.size(); w++) {
            int n = coords(w, lat, lon);
            if (n < 2) { dropped++; continue; }
            if (lat.length < n) { lat = new int[n * 2]; lon = new int[n * 2]; n = coords(w, lat, lon); }
            long last = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                int steps = 1;
                if (i > 0) {
                    int d = Math.max(Math.abs(lat[i] - lat[i - 1]), Math.abs(lon[i] - lon[i - 1]));
                    steps = Math.max(1, d / (CELL_E6 / 4));
                }
                for (int s = 1; s <= steps; s++) {
                    int la = i == 0 ? lat[0] : lat[i - 1] + (int) ((long) (lat[i] - lat[i - 1]) * s / steps);
                    int lo = i == 0 ? lon[0] : lon[i - 1] + (int) ((long) (lon[i] - lon[i - 1]) * s / steps);
                    long k = key(cellX(lo), cellY(la));
                    if (k == last) continue;
                    last = k;
                    IntList l = cellWays.computeIfAbsent(k, x -> new IntList(64));
                    if (l.size() == 0 || l.get(l.size() - 1) != w) l.add(w);
                }
            }
        }
        // Pontos: os que precisam estar numa via de carro (lombada, pedágio, ferrovia) são conferidos.
        Map<Long, IntList> cellPoints = new HashMap<>();
        int[] count = new int[6];
        for (int p = 0; p < pointIds.size(); p++) {
            if (pointNeedsRoad.get(p) != 0 && Arrays.binarySearch(ids, pointIds.get(p)) < 0) continue;
            cellPoints.computeIfAbsent(key(cellX(pointLon.get(p)), cellY(pointLat.get(p))), x -> new IntList(16)).add(p);
            count[pointType.get(p)]++;
        }
        System.out.printf("  pontos: %d radares, %d radares de semáforo, %d lombadas, %d pedágios, %d passagens de nível%n",
            count[1], count[2], count[3], count[4], count[5]);
        if (dropped > 0) System.out.println("  vias sem coordenadas (fora do recorte): " + dropped);

        File dir = new File(out, "v1");
        File cells = new File(dir, "cells");
        if (!cells.isDirectory() && !cells.mkdirs()) throw new IOException("não criou " + cells);
        long generated = System.currentTimeMillis();
        TreeMap<String, String> index = new TreeMap<>();
        java.util.Set<Long> keys = new java.util.HashSet<>(cellWays.keySet());
        keys.addAll(cellPoints.keySet());
        long total = 0;
        int biggest = 0;
        for (long k : keys) {
            int cx = (int) (k >> 32), cy = (int) k;
            byte[] data = cell(cx, cy, cellWays.get(k), cellPoints.get(k), lat, lon);
            String name = cx + "_" + cy;
            try (OutputStream o = new FileOutputStream(new File(cells, name + ".bin"))) {
                o.write(data);
            }
            CRC32 crc = new CRC32();
            crc.update(data);
            index.put(name, Long.toHexString(crc.getValue()) + ":" + data.length);
            total += data.length;
            biggest = Math.max(biggest, data.length);
        }
        writeIndex(new File(dir, "index.json"), index, generated, source);
        log(t0, "gravado: %d quadrados, %.1f MB (maior %.0f KB)", keys.size(), total / 1048576.0, biggest / 1024.0);
    }

    /** Coordenadas da via w (só os nós achados); devolve quantos. */
    int coords(int w, int[] lat, int[] lon) {
        int a = wayStart.get(w);
        int b = w + 1 < wayStart.size() ? wayStart.get(w + 1) : wayRefs.size();
        int n = 0;
        for (int i = a; i < b; i++) {
            int j = Arrays.binarySearch(ids, wayRefs.get(i));
            if (j < 0 || latE6[j] == Integer.MIN_VALUE) continue;
            if (n < lat.length) {
                lat[n] = latE6[j];
                lon[n] = lonE6[j];
            }
            n++;
        }
        return n;
    }

    /**
     * Um quadrado: "K4RD", versão (1 byte) e o resto em deflate:
     * cx, cy (int), nVias (varint), vias, nPontos (varint), pontos. Sem data: o mesmo dado gera o
     * mesmo arquivo (mesmo CRC), e o app só baixa de novo o quadrado que mudou.
     * Via: classe, mão única (0 dupla, 1 no sentido, 2 contra), limite ida, limite volta (bytes),
     * nPontos (varint) e as coordenadas em 1e-6 grau, zigzag: a primeira relativa ao canto
     * sudoeste do quadrado, as outras relativas à anterior. Ponto: tipo, limite (bytes),
     * sentido (short, -1 = qualquer), lat, lon (zigzag, relativas ao canto).
     */
    byte[] cell(int cx, int cy, IntList ways, IntList points, int[] lat, int[] lon) throws IOException {
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        bytes.write(new byte[]{'K', '4', 'R', 'D', FORMAT});
        Deflater def = new Deflater(9);
        DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bytes, def, 1 << 16));
        o.writeInt(cx);
        o.writeInt(cy);
        int nw = ways == null ? 0 : ways.size();
        varint(o, nw);
        for (int i = 0; i < nw; i++) {
            int w = ways.get(i);
            int n = coords(w, lat, lon);
            if (lat.length < n) throw new IllegalStateException("buffer");
            o.writeByte(wayClass.get(w));
            o.writeByte(wayOneway.get(w));
            o.writeByte(waySpeedFwd.get(w));
            o.writeByte(waySpeedBwd.get(w));
            varint(o, n);
            int pl = baseLat, po = baseLon;
            for (int j = 0; j < n; j++) {
                varint(o, zigzag(lat[j] - pl));
                varint(o, zigzag(lon[j] - po));
                pl = lat[j];
                po = lon[j];
            }
        }
        int np = points == null ? 0 : points.size();
        varint(o, np);
        for (int i = 0; i < np; i++) {
            int p = points.get(i);
            o.writeByte(pointType.get(p));
            o.writeByte(pointSpeed.get(p));
            o.writeShort(pointDir.get(p));
            varint(o, zigzag(pointLat.get(p) - baseLat));
            varint(o, zigzag(pointLon.get(p) - baseLon));
        }
        o.close();
        def.end();
        return bytes.toByteArray();
    }

    static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    static void varint(DataOutputStream o, int v) throws IOException {
        while ((v & ~0x7f) != 0) {
            o.writeByte((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        o.writeByte(v);
    }

    static void writeIndex(File f, TreeMap<String, String> cells, long generated, String source) throws IOException {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(cells.size() * 28 + 256);
        sb.append("{\"format\":").append(FORMAT)
            .append(",\"generated\":").append(generated)
            .append(",\"generated_iso\":\"").append(iso.format(new Date(generated))).append('"')
            .append(",\"source\":\"").append(source.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            .append(",\"license\":\"ODbL 1.0, (c) OpenStreetMap contributors\"")
            .append(",\"cell_deg\":").append(CELL_DEG)
            .append(",\"cells\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : cells.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        sb.append("}}\n");
        try (Writer w = new java.io.OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    static void log(long t0, String fmt, Object... args) {
        Runtime rt = Runtime.getRuntime();
        System.out.printf("[%5.1fs %5d MB] %s%n", (System.currentTimeMillis() - t0) / 1000.0,
            (rt.totalMemory() - rt.freeMemory()) >> 20, String.format(fmt, args));
    }

    // ------------------------------------------------------------------ listas de primitivos

    static final class LongList {
        long[] a;
        int n;

        LongList(int cap) {
            a = new long[cap];
        }

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n + (n >> 1) + 16);
            a[n++] = v;
        }

        void addAll(long[] v, int count) {
            if (n + count > a.length) a = Arrays.copyOf(a, Math.max(n + count, n + (n >> 1) + 16));
            System.arraycopy(v, 0, a, n, count);
            n += count;
        }

        long get(int i) {
            return a[i];
        }

        int size() {
            return n;
        }

        void clear() {
            a = new long[0];
            n = 0;
        }

        /** Como {@link #sortedUnique()}, sem mexer na ordem desta lista. */
        long[] sortedUniqueCopy() {
            long[] c = Arrays.copyOf(a, n);
            Arrays.sort(c);
            int m = 0;
            for (int i = 0; i < c.length; i++) if (m == 0 || c[i] != c[m - 1]) c[m++] = c[i];
            if (m == c.length) return c;
            long[] out = Arrays.copyOf(c, m);
            c = null;
            return out;
        }

        long[] sortedUnique() {
            Arrays.sort(a, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) if (m == 0 || a[i] != a[m - 1]) a[m++] = a[i];
            return Arrays.copyOf(a, m);
        }
    }

    static final class IntList {
        int[] a;
        int n;

        IntList(int cap) {
            a = new int[cap];
        }

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n + (n >> 1) + 16);
            a[n++] = v;
        }

        int get(int i) {
            return a[i];
        }

        void set(int i, int v) {
            a[i] = v;
        }

        int size() {
            return n;
        }
    }

    static final class ByteList {
        byte[] a;
        int n;

        ByteList(int cap) {
            a = new byte[cap];
        }

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n + (n >> 1) + 16);
            a[n++] = (byte) v;
        }

        int get(int i) {
            return a[i];
        }

        void set(int i, int v) {
            a[i] = (byte) v;
        }

        int size() {
            return n;
        }
    }
}
