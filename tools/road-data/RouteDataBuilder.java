import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Gera o grafo de ruas usado pela rota sem internet do mapa integrado (cálculo e recálculo no
 * aparelho), a partir de um recorte do OpenStreetMap (.osm.pbf).
 *
 * Saída em {@code <saída>/v1/}: {@code index.json} e {@code cells/<cx>_<cy>.bin}, os mesmos quadrados
 * de {@value RoadDataBuilder#CELL_DEG}° dos dados das vias. Cada quadrado tem os cruzamentos (nós do
 * grafo) que caem nele e os trechos de rua (arestas) que começam ou terminam neles; um trecho que
 * cruza a borda vai nos dois quadrados, e a ponta de fora é guardada pela coordenada (o app acha o
 * nó no quadrado vizinho pela coordenada, então quadrados de semanas diferentes continuam se ligando).
 * Formato em {@code tools/road-data/README.md}; leitor no app em {@code map/.../route/GraphCell.java}.
 *
 * Uso: {@code java -Xmx8g -cp build RouteDataBuilder <entrada.osm.pbf> <saída> [descrição da fonte]}
 */
public final class RouteDataBuilder {

    static final int CELL_E6 = RoadDataBuilder.CELL_E6;
    static final int FORMAT = 1;

    // Classe da via (iguais às do app); +16 nas ligações (_link).
    static final Map<String, Integer> CLASSES = new HashMap<>();

    static {
        String[] c = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "living_street", "service"};
        for (int i = 0; i < c.length; i++) CLASSES.put(c[i], i + 1);
        for (int i = 0; i < 5; i++) CLASSES.put(c[i] + "_link", (i + 1) | 16);
        CLASSES.put("road", 6);
    }

    // Flags da aresta (iguais às do app).
    static final int FWD_ONLY = 1;
    static final int BWD_ONLY = 2;
    static final int ROUNDABOUT = 4;
    static final int TOLL = 8;
    static final int NAMED = 16;

    /** Tolerância da simplificação da geometria dos trechos (1e-6 grau ≈ 0,11 m): ~0,9 m. */
    static final int TOLERANCE_E6 = 22;

    // ---- passo 1: vias e relações
    final RoadDataBuilder.LongList wayRefs = new RoadDataBuilder.LongList(1 << 24);
    final RoadDataBuilder.IntList wayStart = new RoadDataBuilder.IntList(1 << 20);
    final RoadDataBuilder.ByteList wayClass = new RoadDataBuilder.ByteList(1 << 20);
    final RoadDataBuilder.ByteList wayFlags = new RoadDataBuilder.ByteList(1 << 20);
    final RoadDataBuilder.ByteList waySpeed = new RoadDataBuilder.ByteList(1 << 20);
    final RoadDataBuilder.IntList wayName = new RoadDataBuilder.IntList(1 << 20);
    final RoadDataBuilder.LongList wayIds = new RoadDataBuilder.LongList(1 << 20);
    final Map<String, Integer> nameIds = new HashMap<>();
    final List<String> names = new ArrayList<>();
    /** Proibições de conversão: {tipo (0 proibida, 1 obrigatória), via de origem, nó, via de destino}. */
    final List<long[]> restrictions = new ArrayList<>();

    // ---- passo 2: nós
    long[] ids;
    int[] latE6, lonE6;
    int[] refIdx; // wayRefs como índices em ids

    // ---- grafo
    /** Nó do grafo: índice local no quadrado dele; -1 = não é nó do grafo. */
    int[] local;
    final RoadDataBuilder.IntList edgeA = new RoadDataBuilder.IntList(1 << 20);
    final RoadDataBuilder.IntList edgeB = new RoadDataBuilder.IntList(1 << 20);
    final RoadDataBuilder.IntList edgeWay = new RoadDataBuilder.IntList(1 << 20);
    final RoadDataBuilder.IntList edgeGeo = new RoadDataBuilder.IntList(1 << 20); // início em geo
    final RoadDataBuilder.IntList geo = new RoadDataBuilder.IntList(1 << 22);      // lat, lon intercalados

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: RouteDataBuilder <entrada.osm.pbf> <saída> [descrição da fonte]");
            System.exit(2);
        }
        new RouteDataBuilder().run(args[0], new File(args[1]), args.length > 2 ? args[2] : new File(args[0]).getName());
    }

    void run(String pbf, File out, String source) throws IOException {
        long t0 = System.currentTimeMillis();
        byte[] kinds = Pbf.read(pbf, Pbf.WAYS | Pbf.RELATIONS, null, new Pbf.Handler() {
            @Override
            public void node(long id, double lat, double lon, String[] tags) {
            }

            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
                onWay(id, refs, count, tags);
            }

            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
                onRelation(mids, types, roles, count, tags);
            }
        });
        RoadDataBuilder.log(t0, "passo 1: %d vias de carro, %d nomes, %d proibições de conversão",
            wayStart.size(), names.size(), restrictions.size());

        ids = wayRefs.sortedUniqueCopy();
        latE6 = new int[ids.length];
        lonE6 = new int[ids.length];
        Arrays.fill(latE6, Integer.MIN_VALUE);
        refIdx = new int[wayRefs.size()];
        for (int i = 0; i < refIdx.length; i++) refIdx[i] = Arrays.binarySearch(ids, wayRefs.get(i));
        wayRefs.clear();
        RoadDataBuilder.log(t0, "nós das vias: %d", ids.length);

        byte[] nodeBlobs = new byte[kinds.length];
        for (int i = 0; i < kinds.length; i++) nodeBlobs[i] = (byte) (kinds[i] & Pbf.NODES);
        Pbf.read(pbf, Pbf.NODES, nodeBlobs, new Pbf.Handler() {
            int cursor;

            @Override
            public void node(long id, double lat, double lon, String[] tags) {
                while (cursor < ids.length && ids[cursor] < id) cursor++;
                int i = cursor < ids.length && ids[cursor] == id ? cursor : Arrays.binarySearch(ids, id);
                if (i >= 0) {
                    latE6[i] = (int) Math.round(lat * 1e6);
                    lonE6[i] = (int) Math.round(lon * 1e6);
                }
            }

            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
            }

            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
            }
        });
        RoadDataBuilder.log(t0, "passo 2: coordenadas");

        buildEdges();
        RoadDataBuilder.log(t0, "grafo: %d arestas, %d pontos de geometria", edgeA.size(), geo.size() / 2);
        write(out, source, t0);
    }

    // ------------------------------------------------------------------ vias

    void onWay(long id, long[] refs, int count, String[] tags) {
        String hw = Pbf.tag(tags, "highway");
        if (hw == null || count < 2) return;
        Integer cls = CLASSES.get(hw);
        if (cls == null) return;
        if ("yes".equals(Pbf.tag(tags, "area"))) return;
        if (cls == 9) {
            String service = Pbf.tag(tags, "service");
            // Estacionamentos, acessos a imóveis e drive-thru: muitos e quase nunca caminho.
            if ("parking_aisle".equals(service) || "driveway".equals(service) || "drive-through".equals(service)
                || "emergency_access".equals(service)) return;
        }
        if (!carAllowed(tags)) return;

        int flags = 0;
        String ow = Pbf.tag(tags, "oneway");
        String junction = Pbf.tag(tags, "junction");
        boolean roundabout = "roundabout".equals(junction) || "circular".equals(junction);
        if ("yes".equals(ow) || "1".equals(ow) || "true".equals(ow)) flags |= FWD_ONLY;
        else if ("-1".equals(ow) || "reverse".equals(ow)) flags |= BWD_ONLY;
        else if (!"no".equals(ow) && ("motorway".equals(hw) || roundabout)) flags |= FWD_ONLY;
        if (roundabout) flags |= ROUNDABOUT;
        if ("yes".equals(Pbf.tag(tags, "toll"))) flags |= TOLL;

        String name = Pbf.tag(tags, "name");
        if (name == null || name.trim().isEmpty()) name = Pbf.tag(tags, "ref");
        int nameId = -1;
        if (name != null && !name.trim().isEmpty()) {
            name = name.trim();
            Integer n = nameIds.get(name);
            if (n == null) {
                n = names.size();
                names.add(name);
                nameIds.put(name, n);
            }
            nameId = n;
            flags |= NAMED;
        }
        int fwd = RoadDataBuilder.speed(Pbf.tag(tags, "maxspeed:forward"));
        if (fwd == 0) fwd = RoadDataBuilder.speed(Pbf.tag(tags, "maxspeed"));

        wayStart.add(wayRefs.size());
        wayRefs.addAll(refs, count);
        wayClass.add(cls);
        wayFlags.add(flags);
        waySpeed.add(fwd);
        wayName.add(nameId);
        wayIds.add(id);
    }

    /** Carro pode passar: sem access=no/private (a não ser que liberado para veículo a motor). */
    static boolean carAllowed(String[] tags) {
        String[] specific = {"motorcar", "motor_vehicle", "vehicle"};
        for (String k : specific) {
            String v = Pbf.tag(tags, k);
            if (v == null) continue;
            return !("no".equals(v) || "private".equals(v) || "agricultural".equals(v) || "forestry".equals(v));
        }
        String access = Pbf.tag(tags, "access");
        return access == null || !("no".equals(access) || "private".equals(access) || "agricultural".equals(access)
            || "forestry".equals(access));
    }

    void onRelation(long[] mids, int[] types, String[] roles, int count, String[] tags) {
        if (!"restriction".equals(Pbf.tag(tags, "type"))) return;
        String r = Pbf.tag(tags, "restriction");
        if (r == null) r = Pbf.tag(tags, "restriction:motorcar");
        if (r == null) return;
        int type;
        if (r.startsWith("no_")) type = 0;
        else if (r.startsWith("only_")) type = 1;
        else return;
        String except = Pbf.tag(tags, "except");
        if (except != null && except.contains("motorcar")) return;
        long from = 0, via = 0, to = 0;
        for (int i = 0; i < count; i++) {
            String role = roles[i];
            if ("from".equals(role) && types[i] == 1 && from == 0) from = mids[i];
            else if ("to".equals(role) && types[i] == 1 && to == 0) to = mids[i];
            else if ("via".equals(role)) {
                // Só via nó (as por via são raras e pedem outro modelo).
                if (types[i] != 0 || via != 0) return;
                via = mids[i];
            }
        }
        if (from == 0 || via == 0 || to == 0) return;
        restrictions.add(new long[]{type, from, via, to});
    }

    // ------------------------------------------------------------------ arestas

    void buildEdges() {
        int ways = wayStart.size();
        // Nó do grafo: ponta de via ou usado mais de uma vez (cruzamento).
        byte[] uses = new byte[ids.length];
        for (int w = 0; w < ways; w++) {
            int a = wayStart.get(w), b = end(w);
            for (int i = a; i < b; i++) {
                int j = refIdx[i];
                boolean tip = i == a || i == b - 1;
                int add = tip ? 2 : 1;
                uses[j] = (byte) Math.min(2, uses[j] + add);
            }
        }
        local = new int[ids.length];
        Arrays.fill(local, -1);
        for (int i = 0; i < ids.length; i++) if (uses[i] >= 2 && latE6[i] != Integer.MIN_VALUE) local[i] = 0;
        uses = null;

        int[] la = new int[1024], lo = new int[1024];
        for (int w = 0; w < ways; w++) {
            int a = wayStart.get(w), b = end(w);
            int from = -1;
            int n = 0;
            for (int i = a; i < b; i++) {
                int j = refIdx[i];
                if (latE6[j] == Integer.MIN_VALUE) {
                    // Nó fora do recorte: o trecho acaba aqui.
                    from = -1;
                    n = 0;
                    continue;
                }
                if (n == la.length) {
                    la = Arrays.copyOf(la, n * 2);
                    lo = Arrays.copyOf(lo, n * 2);
                }
                if (from < 0) {
                    if (local[j] < 0) continue; // começa no primeiro nó do grafo
                    from = j;
                    n = 0;
                    la[n] = latE6[j];
                    lo[n] = lonE6[j];
                    n++;
                    continue;
                }
                la[n] = latE6[j];
                lo[n] = lonE6[j];
                n++;
                if (local[j] >= 0) {
                    if (j != from || n > 2) addEdge(from, j, w, la, lo, n);
                    from = j;
                    n = 0;
                    la[n] = latE6[j];
                    lo[n] = lonE6[j];
                    n++;
                }
            }
        }
    }

    int end(int w) {
        return w + 1 < wayStart.size() ? wayStart.get(w + 1) : refIdx.length;
    }

    void addEdge(int a, int b, int way, int[] la, int[] lo, int n) {
        edgeA.add(a);
        edgeB.add(b);
        edgeWay.add(way);
        edgeGeo.add(geo.size());
        // Pontos do meio, simplificados (as pontas são os nós).
        if (n > 2) {
            boolean[] keep = new boolean[n];
            keep[0] = keep[n - 1] = true;
            simplify(la, lo, 0, n - 1, keep);
            for (int i = 1; i < n - 1; i++) {
                if (!keep[i]) continue;
                geo.add(la[i]);
                geo.add(lo[i]);
            }
        }
    }

    /** Douglas-Peucker no plano (lon encolhida pela latitude). */
    static void simplify(int[] la, int[] lo, int s, int e, boolean[] keep) {
        if (e <= s + 1) return;
        double k = Math.cos(Math.toRadians(la[s] / 1e6));
        double ax = lo[s] * k, ay = la[s], bx = lo[e] * k, by = la[e];
        double dx = bx - ax, dy = by - ay;
        double len = Math.hypot(dx, dy);
        double best = -1;
        int bi = -1;
        for (int i = s + 1; i < e; i++) {
            double px = lo[i] * k - ax, py = la[i] - ay;
            double d = len == 0 ? Math.hypot(px, py) : Math.abs(px * dy - py * dx) / len;
            if (d > best) {
                best = d;
                bi = i;
            }
        }
        if (best > TOLERANCE_E6) {
            keep[bi] = true;
            simplify(la, lo, s, bi, keep);
            simplify(la, lo, bi, e, keep);
        }
    }

    // ------------------------------------------------------------------ quadrados

    void write(File out, String source, long t0) throws IOException {
        // Nós por quadrado, ordenados pela coordenada (o app acha o nó de fora por busca binária).
        Map<Long, RoadDataBuilder.IntList> cellNodes = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (local[i] < 0) continue;
            long k = RoadDataBuilder.key(RoadDataBuilder.cellX(lonE6[i]), RoadDataBuilder.cellY(latE6[i]));
            cellNodes.computeIfAbsent(k, x -> new RoadDataBuilder.IntList(256)).add(i);
        }
        for (RoadDataBuilder.IntList l : cellNodes.values()) {
            Integer[] boxed = new Integer[l.size()];
            for (int i = 0; i < boxed.length; i++) boxed[i] = l.get(i);
            Arrays.sort(boxed, (x, y) -> Long.compare(coordKey(x), coordKey(y)));
            for (int i = 0; i < boxed.length; i++) {
                l.set(i, boxed[i]);
                local[boxed[i]] = i;
            }
        }
        // Arestas por quadrado: o de cada ponta.
        Map<Long, RoadDataBuilder.IntList> cellEdges = new HashMap<>();
        for (int e = 0; e < edgeA.size(); e++) {
            long ka = cellOf(edgeA.get(e));
            long kb = cellOf(edgeB.get(e));
            cellEdges.computeIfAbsent(ka, x -> new RoadDataBuilder.IntList(256)).add(e);
            if (kb != ka) cellEdges.computeIfAbsent(kb, x -> new RoadDataBuilder.IntList(256)).add(e);
        }
        // Proibições: no quadrado do nó do meio, com as arestas de cada via que tocam nele.
        Map<Long, Integer> wayById = new HashMap<>();
        Set<Long> restricted = new HashSet<>();
        for (long[] r : restrictions) {
            restricted.add(r[1]);
            restricted.add(r[3]);
        }
        for (int w = 0; w < wayIds.size(); w++) if (restricted.contains(wayIds.get(w))) wayById.put(wayIds.get(w), w);
        Map<Long, List<int[]>> cellRestrictions = new HashMap<>(); // {tipo, nó (ids), via de origem, via de destino}
        int usable = 0;
        for (long[] r : restrictions) {
            Integer from = wayById.get(r[1]), to = wayById.get(r[3]);
            int via = Arrays.binarySearch(ids, r[2]);
            if (from == null || to == null || via < 0 || local[via] < 0) continue;
            cellRestrictions.computeIfAbsent(cellOf(via), x -> new ArrayList<>())
                .add(new int[]{(int) r[0], via, from, to});
            usable++;
        }
        System.out.println("  proibições de conversão usáveis: " + usable);

        File dir = new File(out, "v1");
        File cells = new File(dir, "cells");
        if (!cells.isDirectory() && !cells.mkdirs()) throw new IOException("não criou " + cells);
        long generated = System.currentTimeMillis();
        TreeMap<String, String> index = new TreeMap<>();
        long total = 0;
        int biggest = 0;
        String biggestName = "";
        for (Map.Entry<Long, RoadDataBuilder.IntList> en : cellNodes.entrySet()) {
            long k = en.getKey();
            int cx = (int) (k >> 32), cy = (int) k;
            byte[] data = cell(cx, cy, en.getValue(), cellEdges.get(k), cellRestrictions.get(k));
            String name = cx + "_" + cy;
            try (OutputStream o = new FileOutputStream(new File(cells, name + ".bin"))) {
                o.write(data);
            }
            CRC32 crc = new CRC32();
            crc.update(data);
            index.put(name, Long.toHexString(crc.getValue()) + ":" + data.length);
            total += data.length;
            if (data.length > biggest) {
                biggest = data.length;
                biggestName = name;
            }
        }
        RoadDataBuilder.writeIndex(new File(dir, "index.json"), index, generated, source);
        RoadDataBuilder.log(t0, "gravado: %d quadrados, %.1f MB (maior %s, %.0f KB)", index.size(), total / 1048576.0,
            biggestName, biggest / 1024.0);
    }

    long coordKey(int i) {
        return ((long) latE6[i] << 32) | (lonE6[i] & 0xffffffffL);
    }

    long cellOf(int i) {
        return RoadDataBuilder.key(RoadDataBuilder.cellX(lonE6[i]), RoadDataBuilder.cellY(latE6[i]));
    }

    /**
     * Um quadrado: "K4RT", versão (1 byte) e o resto em deflate. Ver o README (Formato do grafo).
     */
    byte[] cell(int cx, int cy, RoadDataBuilder.IntList nodes, RoadDataBuilder.IntList edges, List<int[]> rs)
        throws IOException {
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;
        long self = RoadDataBuilder.key(cx, cy);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        bytes.write(new byte[]{'K', '4', 'R', 'T', FORMAT});
        Deflater def = new Deflater(9);
        DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bytes, def, 1 << 16));
        o.writeInt(cx);
        o.writeInt(cy);

        int ne = edges == null ? 0 : edges.size();
        // Nomes usados aqui.
        Map<Integer, Integer> localName = new HashMap<>();
        List<String> cellNames = new ArrayList<>();
        for (int i = 0; i < ne; i++) {
            int nm = wayName.get(edgeWay.get(edges.get(i)));
            if (nm >= 0 && !localName.containsKey(nm)) {
                localName.put(nm, cellNames.size());
                cellNames.add(names.get(nm));
            }
        }
        RoadDataBuilder.varint(o, cellNames.size());
        for (String s : cellNames) {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            RoadDataBuilder.varint(o, b.length);
            o.write(b);
        }

        RoadDataBuilder.varint(o, nodes.size());
        int pl = baseLat, po = baseLon;
        for (int i = 0; i < nodes.size(); i++) {
            int n = nodes.get(i);
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(latE6[n] - pl));
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(lonE6[n] - po));
            pl = latE6[n];
            po = lonE6[n];
        }

        RoadDataBuilder.varint(o, ne);
        Map<Integer, Integer> edgeLocal = new HashMap<>();
        for (int i = 0; i < ne; i++) {
            int e = edges.get(i);
            edgeLocal.put(e, i);
            int w = edgeWay.get(e);
            endpoint(o, edgeA.get(e), self, baseLat, baseLon);
            endpoint(o, edgeB.get(e), self, baseLat, baseLon);
            int flags = wayFlags.get(w) & 0xff;
            o.writeByte(wayClass.get(w));
            o.writeByte(flags);
            o.writeByte(waySpeed.get(w));
            if ((flags & NAMED) != 0) RoadDataBuilder.varint(o, localName.get(wayName.get(w)));
            int g0 = edgeGeo.get(e);
            int g1 = e + 1 < edgeGeo.size() ? edgeGeo.get(e + 1) : geo.size();
            RoadDataBuilder.varint(o, (g1 - g0) / 2);
            int ql = latE6[edgeA.get(e)], qo = lonE6[edgeA.get(e)];
            for (int g = g0; g < g1; g += 2) {
                RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(geo.get(g) - ql));
                RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(geo.get(g + 1) - qo));
                ql = geo.get(g);
                qo = geo.get(g + 1);
            }
        }

        // Proibições: nó, aresta de chegada, aresta de saída (índices deste quadrado), tipo.
        List<int[]> out = new ArrayList<>();
        if (rs != null) {
            for (int[] r : rs) {
                int via = r[1];
                int fromEdge = -1, toEdge = -1;
                for (int i = 0; i < ne; i++) {
                    int e = edges.get(i);
                    if (edgeA.get(e) != via && edgeB.get(e) != via) continue;
                    int w = edgeWay.get(e);
                    if (w == r[2] && fromEdge < 0) fromEdge = i;
                    if (w == r[3] && toEdge < 0) toEdge = i;
                }
                if (fromEdge >= 0 && toEdge >= 0) out.add(new int[]{local[via], fromEdge, toEdge, r[0]});
            }
        }
        RoadDataBuilder.varint(o, out.size());
        for (int[] r : out) {
            RoadDataBuilder.varint(o, r[0]);
            RoadDataBuilder.varint(o, r[1]);
            RoadDataBuilder.varint(o, r[2]);
            o.writeByte(r[3]);
        }
        o.close();
        def.end();
        return bytes.toByteArray();
    }

    /** Ponta da aresta: nó deste quadrado (índice × 2) ou de outro (1 e a coordenada, relativa ao canto). */
    void endpoint(DataOutputStream o, int n, long self, int baseLat, int baseLon) throws IOException {
        if (cellOf(n) == self) {
            RoadDataBuilder.varint(o, local[n] << 1);
        } else {
            RoadDataBuilder.varint(o, 1);
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(latE6[n] - baseLat));
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(lonE6[n] - baseLon));
        }
    }
}
