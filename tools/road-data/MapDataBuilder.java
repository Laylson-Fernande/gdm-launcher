import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Gera o mapa offline do mapa integrado (ruas com nome, água, mar, áreas verdes, areia, prédios,
 * ferrovias, limites de municípios e estados, nomes de lugares) a partir de um recorte do
 * OpenStreetMap (.osm.pbf). O app desenha os tiles a partir disso (map/.../vector/).
 *
 * Mesmos quadrados de 0,25° dos dados das vias, em três níveis:
 * <ul>
 * <li>visão geral: quadrados de 2° ({@code o<x>_<y>}), zooms 7 a 9 (estradas principais, mar,
 * grandes lagos, limites de estados, cidades);</li>
 * <li>seção simplificada de cada quadrado: zooms 10 a 12;</li>
 * <li>8×8 blocos de cada quadrado (~3,5 km), com todo o detalhe: zoom 13 em diante. Cada bloco tem
 * duas seções: a base e o detalhe (prédios, vias de serviço, trilhas e caminhos, só do zoom 15 em
 * diante), para os zooms 13–14 não abrirem os prédios.</li>
 * </ul>
 * Coordenadas em Web Mercator inteiro (mundo = 2^26 unidades, ~0,6 m). Formato em
 * {@code tools/road-data/README.md}; leitor em {@code map/.../vector/MapCell.java}.
 *
 * Uso: {@code java -Xmx12g -cp build MapDataBuilder <entrada.osm.pbf> <saída> [descrição da fonte]}
 */
public final class MapDataBuilder {

    static final int FORMAT = 1;
    static final int WORLD_BITS = 26;
    static final double WORLD = 1 << WORLD_BITS;
    static final int BLOCKS = 8;
    /** Bits descartados nas coordenadas de cada nível (0 = ~0,6 m; 4 = ~10 m; 7 = ~75 m). */
    static final int SHIFT_HIGH = 0, SHIFT_LOW = 4, SHIFT_OVERVIEW = 7;
    /** Tolerância da simplificação (Douglas-Peucker), em unidades do mundo. */
    static final int TOL_HIGH = 1, TOL_LOW = 20, TOL_OVERVIEW = 160;

    // ---- tipos (iguais aos do app, MapCell)
    static final int MOTORWAY = 1, TRUNK = 2, PRIMARY = 3, SECONDARY = 4, TERTIARY = 5, MINOR = 6, LIVING = 7,
        SERVICE = 8, PEDESTRIAN = 9, TRACK = 10, PATH = 11;
    static final int RAIL = 20, LIGHT_RAIL = 21, TRAM = 22;
    static final int RIVER = 30, STREAM = 31;
    static final int BOUNDARY_STATE = 40, BOUNDARY_CITY = 41;
    static final int WATER = 50, OCEAN = 51, PARK = 52, FOREST = 53, SAND = 54, BUILDING = 55;
    static final int CITY = 60, TOWN = 61, VILLAGE = 62, SUBURB = 63, NEIGHBOURHOOD = 64;
    /** Só no gerador: litoral (vira o polígono do mar). */
    static final int COAST = 99;

    // ---- flags
    static final int F_NAME = 1, F_TUNNEL = 2, F_BRIDGE = 4, F_LINK = 8, F_AREA = 16;

    static final Map<String, Integer> HIGHWAYS = new HashMap<>();

    static {
        String[][] h = {
            {"motorway", "1"}, {"trunk", "2"}, {"primary", "3"}, {"secondary", "4"}, {"tertiary", "5"},
            {"unclassified", "6"}, {"residential", "6"}, {"road", "6"}, {"living_street", "7"}, {"service", "8"},
            {"pedestrian", "9"}, {"track", "10"}, {"footway", "11"}, {"path", "11"}, {"cycleway", "11"},
            {"steps", "11"}, {"bridleway", "11"}};
        for (String[] e : h) HIGHWAYS.put(e[0], Integer.parseInt(e[1]));
    }

    // ---- passo 1: relações
    final RoadDataBuilder.LongList relMembers = new RoadDataBuilder.LongList(1 << 20);
    final RoadDataBuilder.IntList relStart = new RoadDataBuilder.IntList(1 << 14);
    final RoadDataBuilder.ByteList relType = new RoadDataBuilder.ByteList(1 << 14);
    /** Vias que são limite administrativo (id) e o nível (4 estado, 8 município). */
    final HashMap<Long, Integer> boundaryWays = new HashMap<>();
    long[] memberIds;      // ids de vias membros de relações (ordenado)
    int[] memberWay;       // índice da via guardada de memberIds[i], -1 se não achada

    // ---- passo 2: vias
    final RoadDataBuilder.LongList wayRefs = new RoadDataBuilder.LongList(1 << 24);
    final RoadDataBuilder.IntList wayStart = new RoadDataBuilder.IntList(1 << 20);
    final RoadDataBuilder.ByteList wayType = new RoadDataBuilder.ByteList(1 << 20);
    final RoadDataBuilder.ByteList wayFlags = new RoadDataBuilder.ByteList(1 << 20);
    final RoadDataBuilder.IntList wayName = new RoadDataBuilder.IntList(1 << 20);
    final List<String> names = new ArrayList<>();
    final HashMap<String, Integer> nameIds = new HashMap<>();

    // ---- passo 3: nós
    long[] nodeIds;
    int nodeCount;
    int[] nodeX, nodeY;
    int[] refIdx; // wayRefs como índice em nodeIds (-1 = nó fora do recorte)
    final RoadDataBuilder.IntList placeX = new RoadDataBuilder.IntList(1 << 14), placeY = new RoadDataBuilder.IntList(1 << 14);
    final RoadDataBuilder.ByteList placeType = new RoadDataBuilder.ByteList(1 << 14);
    final RoadDataBuilder.IntList placeName = new RoadDataBuilder.IntList(1 << 14);

    // ---- relações montadas: anéis (coordenadas)
    final List<int[][]> relRingsX = new ArrayList<>(), relRingsY = new ArrayList<>();
    final RoadDataBuilder.ByteList relPolyType = new RoadDataBuilder.ByteList(1 << 14);

    // ---- litoral
    /** Litoral em pedaços de até {@value #COAST_PIECE} trechos (a cadeia inteira tem milhões de pontos). */
    final List<int[]> coastX = new ArrayList<>(), coastY = new ArrayList<>();
    static final int COAST_PIECE = 256;
    final Grid cellGrid = new Grid(0.25), blockGrid = new Grid(0.25 / BLOCKS), ovGrid = new Grid(2.0);
    /** Segmentos do litoral por bloco (para saber se um bloco sem litoral é mar ou terra). */
    final HashMap<Long, RoadDataBuilder.IntList> coastSegs = new HashMap<>();
    /** Quadrados e quadrados da visão geral com litoral: índices das cadeias que passam por eles. */
    final HashMap<Long, RoadDataBuilder.IntList> coastByCell = new HashMap<>(), coastByOv = new HashMap<>();

    /**
     * Camadas que o app não desenha (o padrão do {@code map_hide} do app: prédios, vias pequenas,
     * verde, limites, trilhos e córregos) e por isso nem entram nos arquivos: menos download e
     * menos espaço no GitHub Pages. Para desenhar alguma de novo, tire daqui e gere de novo.
     */
    static boolean omitted(int type) {
        switch (type) {
            case BUILDING:
            case SERVICE:
            case PEDESTRIAN:
            case TRACK:
            case PATH:
            case PARK:
            case FOREST:
            case SAND:
            case RAIL:
            case LIGHT_RAIL:
            case TRAM:
            case STREAM:
            case BOUNDARY_STATE:
            case BOUNDARY_CITY:
                return true;
            default:
                return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: MapDataBuilder <entrada.osm.pbf> <saída> [descrição da fonte]");
            System.exit(2);
        }
        new MapDataBuilder().run(args[0], new File(args[1]), args.length > 2 ? args[2] : new File(args[0]).getName());
    }

    void run(String pbf, File out, String source) throws IOException {
        long t0 = System.currentTimeMillis();
        byte[] kinds = Pbf.read(pbf, Pbf.RELATIONS, null, new Handler() {
            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
                onRelation(mids, types, count, tags);
            }
        });
        memberIds = relMembers.sortedUniqueCopy();
        memberWay = new int[memberIds.length];
        Arrays.fill(memberWay, -1);
        RoadDataBuilder.log(t0, "passo 1: %d relações, %d vias membros, %d vias de limite",
            relType.size(), memberIds.length, boundaryWays.size());

        byte[] wayBlobs = new byte[kinds.length];
        for (int i = 0; i < kinds.length; i++) wayBlobs[i] = (byte) (kinds[i] & Pbf.WAYS);
        Pbf.read(pbf, Pbf.WAYS, wayBlobs, new Handler() {
            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
                onWay(id, refs, count, tags);
            }
        });
        RoadDataBuilder.log(t0, "passo 2: %d vias guardadas, %d nós nelas, %d nomes", wayStart.size(), wayRefs.size(), names.size());

        // Ordenado e sem repetição, no próprio array da cópia (sem uma terceira cópia de ~3 GB).
        nodeIds = Arrays.copyOf(wayRefs.a, wayRefs.size());
        Arrays.sort(nodeIds);
        int m = 0;
        for (int i = 0; i < nodeIds.length; i++) if (m == 0 || nodeIds[i] != nodeIds[m - 1]) nodeIds[m++] = nodeIds[i];
        nodeCount = m;
        nodeX = new int[nodeCount];
        nodeY = new int[nodeCount];
        Arrays.fill(nodeX, Integer.MIN_VALUE);
        RoadDataBuilder.log(t0, "nós necessários: %d", nodeCount);
        byte[] nodeBlobs = new byte[kinds.length];
        for (int i = 0; i < kinds.length; i++) nodeBlobs[i] = (byte) (kinds[i] & Pbf.NODES);
        Pbf.read(pbf, Pbf.NODES, nodeBlobs, new Handler() {
            int cursor;

            @Override
            public void node(long id, double lat, double lon, String[] tags) {
                while (cursor < nodeCount && nodeIds[cursor] < id) cursor++;
                int i = cursor < nodeCount && nodeIds[cursor] == id ? cursor : Arrays.binarySearch(nodeIds, 0, nodeCount, id);
                int x = 0, y = 0;
                if (i >= 0 || tags != null) {
                    x = mercX(lon);
                    y = mercY(lat);
                }
                if (i >= 0) {
                    nodeX[i] = x;
                    nodeY[i] = y;
                }
                if (tags != null) onNode(x, y, tags);
            }
        });
        RoadDataBuilder.log(t0, "passo 3: %d lugares com nome", placeX.size());

        // Referências viram índices (libera os ids de 64 bits).
        refIdx = new int[wayRefs.size()];
        for (int i = 0; i < refIdx.length; i++) {
            int j = Arrays.binarySearch(nodeIds, 0, nodeCount, wayRefs.get(i));
            refIdx[i] = j >= 0 && nodeX[j] != Integer.MIN_VALUE ? j : -1;
        }
        wayRefs.clear();
        nodeIds = null;
        RoadDataBuilder.log(t0, "referências resolvidas");

        buildRelations();
        RoadDataBuilder.log(t0, "polígonos de relações: %d", relPolyType.size());
        buildCoast();
        RoadDataBuilder.log(t0, "litoral: %d cadeias", coastX.size());
        write(out, source, t0);
    }

    /** Handler com métodos vazios. */
    abstract static class Handler implements Pbf.Handler {
        @Override
        public void node(long id, double lat, double lon, String[] tags) {
        }

        @Override
        public void way(long id, long[] refs, int count, String[] tags) {
        }

        @Override
        public void relation(long id, long[] ids, int[] types, String[] roles, int count, String[] tags) {
        }
    }

    // ------------------------------------------------------------------ coordenadas

    static int mercX(double lon) {
        return (int) Math.floor((lon + 180) / 360 * WORLD + 0.5);
    }

    static int mercY(double lat) {
        lat = Math.max(-85.05, Math.min(85.05, lat));
        double s = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
        return (int) Math.floor((0.5 - s / (2 * Math.PI)) * WORLD + 0.5);
    }

    /**
     * Grade em graus (quadrados, blocos ou visão geral): coluna i vai do oeste para o leste, linha j
     * do sul para o norte (como os quadrados das vias); bordas em coordenadas do mundo.
     */
    static final class Grid {
        final double deg;
        final int cols, rows;
        final int[] yEdge; // yEdge[j] = borda sul da linha j (y cresce para o sul)

        Grid(double deg) {
            this.deg = deg;
            cols = (int) Math.round(360 / deg);
            rows = (int) Math.round(180 / deg);
            yEdge = new int[rows + 1];
            for (int j = 0; j <= rows; j++) yEdge[j] = mercY(j * deg - 90);
        }

        int x0(int i) {
            return (int) Math.floor(i * WORLD / cols + 0.5);
        }

        int col(int x) {
            return (int) Math.floor((double) x * cols / WORLD);
        }

        /** Linha que contém y (y do mundo cresce para o sul; a linha j cresce para o norte). */
        int row(int y) {
            int lo = 0, hi = rows - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (yEdge[mid] >= y) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        /** Retângulo da célula (i, j): {xMin, yMin, xMax, yMax} no mundo. */
        int[] rect(int i, int j) {
            return new int[]{x0(i), yEdge[j + 1], x0(i + 1), yEdge[j]};
        }
    }

    static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    // ------------------------------------------------------------------ passo 1: relações

    void onRelation(long[] mids, int[] types, int count, String[] tags) {
        String type = Pbf.tag(tags, "type");
        boolean mp = "multipolygon".equals(type);
        if ("boundary".equals(type) || (mp && "administrative".equals(Pbf.tag(tags, "boundary")))) {
            if (!"administrative".equals(Pbf.tag(tags, "boundary"))) return;
            if (omitted(BOUNDARY_STATE) && omitted(BOUNDARY_CITY)) return;
            String lvl = Pbf.tag(tags, "admin_level");
            int level = "4".equals(lvl) ? 4 : "8".equals(lvl) ? 8 : 0;
            if (level == 0) return;
            for (int i = 0; i < count; i++) {
                if (types[i] != 1) continue;
                Integer old = boundaryWays.get(mids[i]);
                if (old == null || old > level) boundaryWays.put(mids[i], level);
                relMembers.add(mids[i]);
            }
            return;
        }
        if (!mp) return;
        int area = areaType(tags);
        if (area == 0 || omitted(area)) return;
        relStart.add(relMembers.size());
        relType.add(area);
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (types[i] != 1) continue;
            relMembers.add(mids[i]);
            n++;
        }
        relMembers.add(-1); // fim da relação (ids de via são positivos)
        if (n == 0) relType.set(relType.size() - 1, 0);
    }

    static int areaType(String[] tags) {
        String b = Pbf.tag(tags, "building");
        if (b != null && !"no".equals(b)) return BUILDING;
        String natural = Pbf.tag(tags, "natural");
        String landuse = Pbf.tag(tags, "landuse");
        String leisure = Pbf.tag(tags, "leisure");
        String waterway = Pbf.tag(tags, "waterway");
        if ("water".equals(natural) || "bay".equals(natural) || "reservoir".equals(landuse) || "basin".equals(landuse)
            || "riverbank".equals(waterway) || "dock".equals(waterway)) return WATER;
        if ("wood".equals(natural) || "scrub".equals(natural) || "forest".equals(landuse) || "orchard".equals(landuse)) {
            return FOREST;
        }
        if ("park".equals(leisure) || "garden".equals(leisure) || "pitch".equals(leisure) || "golf_course".equals(leisure)
            || "grass".equals(landuse) || "meadow".equals(landuse) || "recreation_ground".equals(landuse)
            || "village_green".equals(landuse) || "cemetery".equals(landuse) || "grassland".equals(natural)
            || "heath".equals(natural)) return PARK;
        if ("beach".equals(natural) || "sand".equals(natural)) return SAND;
        return 0;
    }

    // ------------------------------------------------------------------ passo 2: vias

    void onWay(long id, long[] refs, int count, String[] tags) {
        if (count < 2) return;
        int member = Arrays.binarySearch(memberIds, id);
        int type = 0, flags = 0;
        String name = null;
        boolean closed = refs[0] == refs[count - 1];
        if (tags != null) {
            String hw = Pbf.tag(tags, "highway");
            Integer road = hw == null ? null : HIGHWAYS.get(hw.endsWith("_link") ? hw.substring(0, hw.length() - 5) : hw);
            String rail = Pbf.tag(tags, "railway");
            String waterway = Pbf.tag(tags, "waterway");
            String boundary = Pbf.tag(tags, "boundary");
            if (road != null && !"yes".equals(Pbf.tag(tags, "area"))) {
                type = road;
                if (hw.endsWith("_link")) flags |= F_LINK;
                name = Pbf.tag(tags, "name");
                if (name == null && type <= TERTIARY) name = Pbf.tag(tags, "ref");
            } else if ("rail".equals(rail) || "narrow_gauge".equals(rail) || "preserved".equals(rail)) {
                type = RAIL;
            } else if ("light_rail".equals(rail) || "subway".equals(rail) || "monorail".equals(rail)) {
                type = LIGHT_RAIL;
            } else if ("tram".equals(rail)) {
                type = TRAM;
            } else if ("river".equals(waterway) || "canal".equals(waterway)) {
                type = RIVER;
                name = Pbf.tag(tags, "name");
            } else if ("stream".equals(waterway) && Pbf.tag(tags, "name") != null) {
                // Córrego só com nome: os sem nome (zona rural) dobrariam o tamanho da água.
                type = STREAM;
                name = Pbf.tag(tags, "name");
            } else if ("coastline".equals(Pbf.tag(tags, "natural"))) {
                type = COAST;
            } else if (closed && count >= 4) {
                type = areaType(tags);
                if (type != 0) flags |= F_AREA;
            }
            if (type == 0 && "administrative".equals(boundary)) {
                String lvl = Pbf.tag(tags, "admin_level");
                if ("4".equals(lvl)) type = BOUNDARY_STATE;
                else if ("8".equals(lvl)) type = BOUNDARY_CITY;
            }
            if (type != 0 && type < COAST && (flags & F_AREA) == 0) {
                String tunnel = Pbf.tag(tags, "tunnel");
                if (tunnel != null && !"no".equals(tunnel)) flags |= F_TUNNEL;
                String bridge = Pbf.tag(tags, "bridge");
                if (bridge != null && !"no".equals(bridge)) flags |= F_BRIDGE;
                if ((type == RIVER || type == STREAM) && (flags & F_TUNNEL) != 0) type = 0; // bueiro
            }
            if ("construction".equals(hw) || "proposed".equals(hw)) type = 0;
        }
        if (type == 0) {
            Integer b = boundaryWays.get(id);
            if (b != null) type = b == 4 ? BOUNDARY_STATE : BOUNDARY_CITY;
        }
        if (omitted(type)) type = 0;
        if (type == 0 && member < 0) return;
        if (member >= 0) memberWay[member] = wayStart.size();
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) name = null;
        }
        wayStart.add(wayRefs.size());
        wayRefs.addAll(refs, count);
        wayType.add(type);
        wayFlags.add(flags | (name != null ? F_NAME : 0));
        wayName.add(name == null ? -1 : nameId(name));
    }

    int nameId(String s) {
        Integer i = nameIds.get(s);
        if (i != null) return i;
        names.add(s);
        nameIds.put(s, names.size() - 1);
        return names.size() - 1;
    }

    // ------------------------------------------------------------------ passo 3: lugares

    void onNode(int x, int y, String[] tags) {
        String place = Pbf.tag(tags, "place");
        if (place == null) return;
        int type;
        switch (place) {
            case "city": type = CITY; break;
            case "town": type = TOWN; break;
            case "village": type = VILLAGE; break;
            case "suburb":
            case "borough": type = SUBURB; break;
            case "neighbourhood":
            case "quarter": type = NEIGHBOURHOOD; break;
            default: return;
        }
        String name = Pbf.tag(tags, "name");
        if (name == null || name.trim().isEmpty()) return;
        placeX.add(x);
        placeY.add(y);
        placeType.add(type);
        placeName.add(nameId(name.trim()));
    }

    // ------------------------------------------------------------------ geometria das vias

    int wayLen(int w) {
        int b = w + 1 < wayStart.size() ? wayStart.get(w + 1) : refIdx.length;
        return b - wayStart.get(w);
    }

    /** Coordenadas da via (só os nós achados) em xs/ys; devolve quantos. */
    int wayCoords(int w, int[] xs, int[] ys) {
        int a = wayStart.get(w), n = 0;
        int b = w + 1 < wayStart.size() ? wayStart.get(w + 1) : refIdx.length;
        for (int i = a; i < b; i++) {
            int j = refIdx[i];
            if (j < 0) continue;
            xs[n] = nodeX[j];
            ys[n] = nodeY[j];
            n++;
        }
        return n;
    }

    /** Monta os anéis dos multipolígonos juntando as vias pelas pontas. */
    void buildRelations() {
        int dropped = 0;
        for (int r = 0; r < relType.size(); r++) {
            int type = relType.get(r);
            if (type == 0) continue;
            List<int[]> parts = new ArrayList<>();
            for (int i = relStart.get(r); ; i++) {
                long id = relMembers.get(i);
                if (id < 0) break;
                int m = Arrays.binarySearch(memberIds, id);
                int w = m >= 0 ? memberWay[m] : -1;
                if (w < 0) continue;
                int a = wayStart.get(w), len = wayLen(w);
                int[] p = Arrays.copyOfRange(refIdx, a, a + len);
                boolean ok = true;
                for (int v : p) if (v < 0) { ok = false; break; }
                if (ok) parts.add(p);
            }
            List<int[]> rings = joinRings(parts);
            if (rings.isEmpty()) { dropped++; continue; }
            int[][] rx = new int[rings.size()][], ry = new int[rings.size()][];
            for (int k = 0; k < rings.size(); k++) {
                int[] ring = rings.get(k);
                rx[k] = new int[ring.length];
                ry[k] = new int[ring.length];
                for (int i = 0; i < ring.length; i++) {
                    rx[k][i] = nodeX[ring[i]];
                    ry[k][i] = nodeY[ring[i]];
                }
            }
            relRingsX.add(rx);
            relRingsY.add(ry);
            relPolyType.add(type);
        }
        relMembers.clear();
        if (dropped > 0) System.out.println("  multipolígonos sem anel fechado: " + dropped);
    }

    /** Junta trechos (sequências de nós) em anéis fechados; o que não fecha fica de fora. */
    static List<int[]> joinRings(List<int[]> parts) {
        List<int[]> rings = new ArrayList<>();
        HashMap<Integer, List<Integer>> ends = new HashMap<>();
        boolean[] used = new boolean[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            int[] p = parts.get(i);
            if (p.length < 2) { used[i] = true; continue; }
            if (p[0] == p[p.length - 1]) {
                used[i] = true;
                if (p.length >= 4) rings.add(p);
                continue;
            }
            ends.computeIfAbsent(p[0], k -> new ArrayList<>()).add(i);
            ends.computeIfAbsent(p[p.length - 1], k -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < parts.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            RoadDataBuilder.IntList ring = new RoadDataBuilder.IntList(256);
            for (int v : parts.get(i)) ring.add(v);
            int first = ring.get(0);
            boolean closed = false;
            while (true) {
                int end = ring.get(ring.size() - 1);
                if (end == first) { closed = true; break; }
                List<Integer> cand = ends.get(end);
                int next = -1;
                if (cand != null) for (int c : cand) if (!used[c]) { next = c; break; }
                if (next < 0) break;
                used[next] = true;
                int[] p = parts.get(next);
                if (p[0] == end) for (int k = 1; k < p.length; k++) ring.add(p[k]);
                else for (int k = p.length - 2; k >= 0; k--) ring.add(p[k]);
            }
            if (closed && ring.size() >= 4) rings.add(Arrays.copyOf(ring.a, ring.size()));
        }
        return rings;
    }

    // ------------------------------------------------------------------ litoral

    /** Junta as vias do litoral em cadeias e indexa por quadrado, quadrado da visão geral e bloco. */
    void buildCoast() {
        List<int[]> parts = new ArrayList<>();
        for (int w = 0; w < wayStart.size(); w++) {
            if (wayType.get(w) != COAST) continue;
            int a = wayStart.get(w), len = wayLen(w);
            int[] p = Arrays.copyOfRange(refIdx, a, a + len);
            int n = 0;
            for (int v : p) if (v >= 0) p[n++] = v;
            if (n >= 2) parts.add(Arrays.copyOf(p, n));
        }
        // Encadeia: o fim de uma via é o começo da seguinte (o litoral tem sentido: terra à esquerda).
        HashMap<Integer, Integer> byStart = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) byStart.put(parts.get(i)[0], i);
        boolean[] used = new boolean[parts.size()];
        boolean[] hasPrev = new boolean[parts.size()];
        for (int[] p : parts) {
            Integer nx = byStart.get(p[p.length - 1]);
            if (nx != null && parts.get(nx) != p) hasPrev[nx] = true;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < parts.size(); i++) {
                // Primeiro as cadeias abertas (a partir de quem não tem anterior); depois os anéis.
                if (used[i] || (pass == 0 && hasPrev[i])) continue;
                RoadDataBuilder.IntList chain = new RoadDataBuilder.IntList(1024);
                int cur = i;
                int startNode = parts.get(i)[0];
                while (true) {
                    used[cur] = true;
                    int[] p = parts.get(cur);
                    for (int k = chain.size() == 0 ? 0 : 1; k < p.length; k++) chain.add(p[k]);
                    int end = p[p.length - 1];
                    if (end == startNode) break;
                    Integer nx = byStart.get(end);
                    if (nx == null || used[nx]) break;
                    cur = nx;
                }
                int n = chain.size();
                // Pedaços que se encostam pela ponta (juntados de novo depois do recorte).
                for (int a = 0; a < n - 1; a += COAST_PIECE) {
                    int b = Math.min(n - 1, a + COAST_PIECE);
                    int[] xs = new int[b - a + 1], ys = new int[b - a + 1];
                    for (int k = a; k <= b; k++) {
                        xs[k - a] = nodeX[chain.get(k)];
                        ys[k - a] = nodeY[chain.get(k)];
                    }
                    coastX.add(xs);
                    coastY.add(ys);
                }
            }
        }
        for (int c = 0; c < coastX.size(); c++) {
            int[] xs = coastX.get(c), ys = coastY.get(c);
            for (int i = 0; i + 1 < xs.length; i++) {
                int bx0 = blockGrid.col(Math.min(xs[i], xs[i + 1])), bx1 = blockGrid.col(Math.max(xs[i], xs[i + 1]));
                int by0 = blockGrid.row(Math.max(ys[i], ys[i + 1])), by1 = blockGrid.row(Math.min(ys[i], ys[i + 1]));
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        coastSegs.computeIfAbsent(key(bx, by), k -> new RoadDataBuilder.IntList(64)).add(c);
                        coastSegs.get(key(bx, by)).add(i);
                        addOnce(coastByCell, key(bx / BLOCKS, by / BLOCKS), c);
                        addOnce(coastByOv, key(bx / (BLOCKS * 8), by / (BLOCKS * 8)), c);
                    }
                }
            }
        }
    }

    static void addOnce(HashMap<Long, RoadDataBuilder.IntList> map, long k, int v) {
        RoadDataBuilder.IntList l = map.computeIfAbsent(k, x -> new RoadDataBuilder.IntList(8));
        if (l.size() == 0 || l.get(l.size() - 1) != v) {
            for (int i = 0; i < l.size(); i++) if (l.get(i) == v) return;
            l.add(v);
        }
    }

    /**
     * Mar dentro do retângulo r ({xMin, yMin, xMax, yMax}): anéis (com EVEN_ODD) ou null se não há
     * mar. O litoral tem a terra à esquerda; o mar fica à direita, então o polígono do mar é
     * percorrido no sentido horário (na tela, norte para cima) e segue a borda do retângulo nesse
     * sentido entre um trecho de litoral e o próximo. Ilhas inteiras dentro ficam como furos.
     */
    List<int[][]> ocean(int[] r, RoadDataBuilder.IntList chains) {
        List<int[]> pieceX = new ArrayList<>(), pieceY = new ArrayList<>();
        List<int[]> ringX = new ArrayList<>(), ringY = new ArrayList<>();
        if (chains != null) {
            List<int[]> fx = new ArrayList<>(), fy = new ArrayList<>();
            for (int ci = 0; ci < chains.size(); ci++) {
                int c = chains.get(ci);
                for (int[][] f : clipLine(coastX.get(c), coastY.get(c), coastX.get(c).length, r)) {
                    fx.add(f[0]);
                    fy.add(f[1]);
                }
            }
            assemble(fx, fy, r, pieceX, pieceY, ringX, ringY);
        }
        List<int[][]> out = new ArrayList<>();
        if (pieceX.isEmpty()) {
            boolean sea = isSea((r[0] + r[2]) / 2, (r[1] + r[3]) / 2);
            if (sea) {
                out.add(new int[][]{{r[0], r[2], r[2], r[0]}, {r[1], r[1], r[3], r[3]}});
                for (int i = 0; i < ringX.size(); i++) if (area2(ringX.get(i), ringY.get(i)) < 0) out.add(new int[][]{ringX.get(i), ringY.get(i)});
            } else {
                // Terra com "lagoas" de litoral (anel horário = mar dentro).
                for (int i = 0; i < ringX.size(); i++) if (area2(ringX.get(i), ringY.get(i)) > 0) out.add(new int[][]{ringX.get(i), ringY.get(i)});
            }
            return out.isEmpty() ? null : out;
        }
        long w = r[2] - r[0], h = r[3] - r[1], per = 2 * (w + h);
        int n = pieceX.size();
        long[] tin = new long[n], tout = new long[n];
        for (int i = 0; i < n; i++) {
            int[] px = pieceX.get(i), py = pieceY.get(i);
            tin[i] = perim(r, px[0], py[0]);
            tout[i] = perim(r, px[px.length - 1], py[py.length - 1]);
        }
        boolean[] used = new boolean[n];
        for (int s = 0; s < n; s++) {
            if (used[s]) continue;
            RoadDataBuilder.IntList rx = new RoadDataBuilder.IntList(256), ry = new RoadDataBuilder.IntList(256);
            int cur = s;
            int guard = 0;
            while (guard++ <= n) {
                used[cur] = true;
                int[] px = pieceX.get(cur), py = pieceY.get(cur);
                for (int k = 0; k < px.length; k++) { rx.add(px[k]); ry.add(py[k]); }
                long t = tout[cur];
                // Próxima entrada no sentido horário a partir da saída (pode ser a do próprio começo).
                int best = -1;
                long bestD = Long.MAX_VALUE;
                for (int j = 0; j < n; j++) {
                    if (used[j] && j != s) continue;
                    long d = Math.floorMod(tin[j] - t, per);
                    if (d < bestD) { bestD = d; best = j; }
                }
                if (best < 0) break;
                // Cantos entre t e a entrada seguinte.
                long[] corners = {0, w, w + h, 2 * w + h};
                int[][] cxy = {{r[0], r[1]}, {r[2], r[1]}, {r[2], r[3]}, {r[0], r[3]}};
                long travelled = 0;
                while (true) {
                    long nextCorner = Long.MAX_VALUE;
                    int ci = -1;
                    for (int k = 0; k < 4; k++) {
                        long d = Math.floorMod(corners[k] - t, per);
                        if (d == 0) d = per;
                        if (d < nextCorner) { nextCorner = d; ci = k; }
                    }
                    if (travelled + nextCorner >= bestD) break;
                    travelled += nextCorner;
                    t = corners[ci];
                    rx.add(cxy[ci][0]);
                    ry.add(cxy[ci][1]);
                }
                if (best == s) break;
                cur = best;
            }
            if (rx.size() >= 3) out.add(new int[][]{Arrays.copyOf(rx.a, rx.size()), Arrays.copyOf(ry.a, ry.size())});
        }
        for (int i = 0; i < ringX.size(); i++) if (area2(ringX.get(i), ringY.get(i)) < 0) out.add(new int[][]{ringX.get(i), ringY.get(i)});
        return out.isEmpty() ? null : out;
    }

    /** Posição de um ponto da borda, no sentido horário a partir do canto noroeste. */
    static long perim(int[] r, int x, int y) {
        long w = r[2] - r[0], h = r[3] - r[1];
        if (y <= r[1]) return x - r[0];
        if (x >= r[2]) return w + (y - r[1]);
        if (y >= r[3]) return w + h + (r[2] - x);
        return 2 * w + h + (r[3] - y);
    }

    /** Área com sinal ×2 (na tela, y para baixo): positiva = sentido horário. */
    static double area2(int[] xs, int[] ys) {
        double a = 0;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) a += (double) xs[j] * ys[i] - (double) xs[i] * ys[j];
        return a;
    }

    /**
     * Junta os trechos de litoral recortados num retângulo pelas pontas que ficaram dentro dele (os
     * pedaços da cadeia se encostam): o que fecha vira anel (ilha); o resto entra e sai pela borda.
     * Ponta solta dentro (o recorte da Geofabrik corta o litoral) é levada até a borda mais perto.
     */
    static void assemble(List<int[]> fx, List<int[]> fy, int[] r, List<int[]> pieceX, List<int[]> pieceY,
                         List<int[]> ringX, List<int[]> ringY) {
        int n = fx.size();
        HashMap<Long, Integer> byStart = new HashMap<>();
        boolean[] hasPrev = new boolean[n];
        for (int i = 0; i < n; i++) {
            int[] x = fx.get(i), y = fy.get(i);
            if (!onEdge(r, x[0], y[0])) byStart.put(key(x[0], y[0]), i);
        }
        for (int i = 0; i < n; i++) {
            int[] x = fx.get(i), y = fy.get(i);
            int m = x.length - 1;
            if (onEdge(r, x[m], y[m])) continue;
            Integer nx = byStart.get(key(x[m], y[m]));
            if (nx != null && nx != i) hasPrev[nx] = true;
        }
        boolean[] used = new boolean[n];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if (used[i] || (pass == 0 && hasPrev[i])) continue;
                RoadDataBuilder.IntList cx = new RoadDataBuilder.IntList(256), cy = new RoadDataBuilder.IntList(256);
                int cur = i;
                boolean ring = false;
                while (true) {
                    used[cur] = true;
                    int[] x = fx.get(cur), y = fy.get(cur);
                    for (int k = cx.size() == 0 ? 0 : 1; k < x.length; k++) { cx.add(x[k]); cy.add(y[k]); }
                    int ex = x[x.length - 1], ey = y[y.length - 1];
                    if (onEdge(r, ex, ey)) break;
                    if (ex == cx.get(0) && ey == cy.get(0) && cx.size() > 2) { ring = true; break; }
                    Integer nx = byStart.get(key(ex, ey));
                    if (nx == null || used[nx]) break;
                    cur = nx;
                }
                int m = cx.size();
                if (m < 2) continue;
                int[] px = Arrays.copyOf(cx.a, m), py = Arrays.copyOf(cy.a, m);
                if (ring) {
                    ringX.add(Arrays.copyOf(px, m - 1));
                    ringY.add(Arrays.copyOf(py, m - 1));
                    continue;
                }
                if (!onEdge(r, px[0], py[0])) {
                    int[] e = nearestEdge(r, px[0], py[0]);
                    px = prepend(px, e[0]);
                    py = prepend(py, e[1]);
                }
                if (!onEdge(r, px[px.length - 1], py[py.length - 1])) {
                    int[] e = nearestEdge(r, px[px.length - 1], py[py.length - 1]);
                    px = Arrays.copyOf(px, px.length + 1);
                    py = Arrays.copyOf(py, py.length + 1);
                    px[px.length - 1] = e[0];
                    py[py.length - 1] = e[1];
                }
                pieceX.add(px);
                pieceY.add(py);
            }
        }
    }

    static int[] prepend(int[] a, int v) {
        int[] b = new int[a.length + 1];
        b[0] = v;
        System.arraycopy(a, 0, b, 1, a.length);
        return b;
    }

    static boolean inside(int[] r, int x, int y) {
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }

    static boolean onEdge(int[] r, int x, int y) {
        return x <= r[0] || x >= r[2] || y <= r[1] || y >= r[3];
    }

    static int[] nearestEdge(int[] r, int x, int y) {
        int dl = x - r[0], dr = r[2] - x, dt = y - r[1], db = r[3] - y;
        int m = Math.min(Math.min(dl, dr), Math.min(dt, db));
        if (m == dl) return new int[]{r[0], y};
        if (m == dr) return new int[]{r[2], y};
        if (m == dt) return new int[]{x, r[1]};
        return new int[]{x, r[3]};
    }

    static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (double) (b - a) * t);
    }

    /** Liang-Barsky: {t0, t1} do trecho dentro do retângulo, ou null. */
    static double[] clipSeg(int[] r, int x0, int y0, int x1, int y1) {
        double t0 = 0, t1 = 1;
        double dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - r[0], r[2] - x0, y0 - r[1], r[3] - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) { if (t > t1) return null; if (t > t0) t0 = t; }
                else { if (t < t0) return null; if (t < t1) t1 = t; }
            }
        }
        return new double[]{t0, t1};
    }

    /** Bloco sem litoral: mar se o ponto está à direita do trecho de litoral mais perto. */
    boolean isSea(int x, int y) {
        int bx = blockGrid.col(x), by = blockGrid.row(y);
        double best = Double.MAX_VALUE;
        boolean sea = false;
        // Até ~3° (quadrados de 2° da visão geral no mar aberto ficam longe da ilha mais perto).
        for (int rad = 0; rad <= 96; rad++) {
            for (int j = by - rad; j <= by + rad; j++) {
                for (int i = bx - rad; i <= bx + rad; i++) {
                    if (Math.max(Math.abs(i - bx), Math.abs(j - by)) != rad) continue;
                    RoadDataBuilder.IntList segs = coastSegs.get(key(i, j));
                    if (segs == null) continue;
                    for (int k = 0; k < segs.size(); k += 2) {
                        int[] xs = coastX.get(segs.get(k)), ys = coastY.get(segs.get(k));
                        int s = segs.get(k + 1);
                        double ax = xs[s], ay = ys[s], ex = xs[s + 1] - ax, ey = ys[s + 1] - ay;
                        double len = ex * ex + ey * ey;
                        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * ex + (y - ay) * ey) / len));
                        double qx = ax + ex * t - x, qy = ay + ey * t - y;
                        double d = qx * qx + qy * qy;
                        if (d < best) {
                            best = d;
                            sea = ex * (y - ay) - ey * (x - ax) > 0;
                        }
                    }
                }
            }
            // Achou algo: o anel seguinte ainda pode ter algo mais perto; mais um e para.
            if (best < Double.MAX_VALUE && rad >= 1) {
                double ringDist = (double) (rad) * (blockGrid.x0(1) - blockGrid.x0(0));
                if (best <= ringDist * ringDist) break;
            }
        }
        return sea;
    }

    // ------------------------------------------------------------------ simplificação e recorte

    /** Douglas-Peucker: devolve quantos pontos sobraram (compactados no começo de xs/ys). */
    static int simplify(int[] xs, int[] ys, int n, double tol) {
        if (n <= 2 || tol <= 0) return dedupe(xs, ys, n);
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        double tol2 = tol * tol;
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            double ax = xs[a], ay = ys[a], ex = xs[b] - ax, ey = ys[b] - ay;
            double len = ex * ex + ey * ey;
            double worst = -1;
            int wi = -1;
            for (int i = a + 1; i < b; i++) {
                double px = xs[i] - ax, py = ys[i] - ay, d;
                if (len == 0) d = px * px + py * py;
                else {
                    double cr = px * ey - py * ex;
                    d = cr * cr / len;
                }
                if (d > worst) { worst = d; wi = i; }
            }
            if (worst > tol2) {
                keep[wi] = true;
                if (sp + 4 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                stack[sp++] = a;
                stack[sp++] = wi;
                stack[sp++] = wi;
                stack[sp++] = b;
            }
        }
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            xs[m] = xs[i];
            ys[m] = ys[i];
            m++;
        }
        return dedupe(xs, ys, m);
    }

    static int dedupe(int[] xs, int[] ys, int n) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && xs[i] == xs[m - 1] && ys[i] == ys[m - 1]) continue;
            xs[m] = xs[i];
            ys[m] = ys[i];
            m++;
        }
        return m;
    }

    /** Recorta uma linha no retângulo: trechos dentro (cada um {xs, ys}). */
    static List<int[][]> clipLine(int[] xs, int[] ys, int n, int[] r) {
        List<int[][]> out = new ArrayList<>();
        RoadDataBuilder.IntList cx = null, cy = null;
        for (int i = 0; i + 1 < n; i++) {
            double[] t = clipSeg(r, xs[i], ys[i], xs[i + 1], ys[i + 1]);
            if (t == null) {
                if (cx != null) { out.add(new int[][]{Arrays.copyOf(cx.a, cx.size()), Arrays.copyOf(cy.a, cy.size())}); cx = null; }
                continue;
            }
            if (cx == null) {
                cx = new RoadDataBuilder.IntList(32);
                cy = new RoadDataBuilder.IntList(32);
                cx.add(lerp(xs[i], xs[i + 1], t[0]));
                cy.add(lerp(ys[i], ys[i + 1], t[0]));
            }
            cx.add(lerp(xs[i], xs[i + 1], t[1]));
            cy.add(lerp(ys[i], ys[i + 1], t[1]));
            if (t[1] < 1) { out.add(new int[][]{Arrays.copyOf(cx.a, cx.size()), Arrays.copyOf(cy.a, cy.size())}); cx = null; }
        }
        if (cx != null) out.add(new int[][]{Arrays.copyOf(cx.a, cx.size()), Arrays.copyOf(cy.a, cy.size())});
        return out;
    }

    /** Sutherland-Hodgman: um anel recortado no retângulo (pode ficar vazio). */
    static int[][] clipRing(int[] xs, int[] ys, int n, int[] r) {
        boolean allIn = true;
        for (int i = 0; i < n; i++) if (!inside(r, xs[i], ys[i])) { allIn = false; break; }
        if (allIn) return new int[][]{Arrays.copyOf(xs, n), Arrays.copyOf(ys, n)};
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) { px[i] = xs[i]; py[i] = ys[i]; }
        int m = n;
        for (int edge = 0; edge < 4 && m > 0; edge++) {
            double[] qx = new double[m * 2 + 2], qy = new double[m * 2 + 2];
            int k = 0;
            for (int i = 0; i < m; i++) {
                double ax = px[(i + m - 1) % m], ay = py[(i + m - 1) % m], bx = px[i], by = py[i];
                boolean ain = in(edge, r, ax, ay), bin = in(edge, r, bx, by);
                if (bin) {
                    if (!ain) { double[] c = cross(edge, r, ax, ay, bx, by); qx[k] = c[0]; qy[k] = c[1]; k++; }
                    qx[k] = bx; qy[k] = by; k++;
                } else if (ain) {
                    double[] c = cross(edge, r, ax, ay, bx, by); qx[k] = c[0]; qy[k] = c[1]; k++;
                }
            }
            px = qx;
            py = qy;
            m = k;
        }
        if (m < 3) return null;
        int[] ox = new int[m], oy = new int[m];
        for (int i = 0; i < m; i++) { ox[i] = (int) Math.round(px[i]); oy[i] = (int) Math.round(py[i]); }
        return new int[][]{ox, oy};
    }

    static boolean in(int edge, int[] r, double x, double y) {
        switch (edge) {
            case 0: return x >= r[0];
            case 1: return x <= r[2];
            case 2: return y >= r[1];
            default: return y <= r[3];
        }
    }

    static double[] cross(int edge, int[] r, double ax, double ay, double bx, double by) {
        double t;
        switch (edge) {
            case 0: t = (r[0] - ax) / (bx - ax); return new double[]{r[0], ay + (by - ay) * t};
            case 1: t = (r[2] - ax) / (bx - ax); return new double[]{r[2], ay + (by - ay) * t};
            case 2: t = (r[1] - ay) / (by - ay); return new double[]{ax + (bx - ax) * t, r[1]};
            default: t = (r[3] - ay) / (by - ay); return new double[]{ax + (bx - ax) * t, r[3]};
        }
    }

    /**
     * No nível de detalhe, só ruas, trilhos e prédios usam a tolerância mínima: mata, parque e areia
     * (contornos traçados com muitos pontos) ficam com ~2,4 m, água e córregos com ~1,2 m.
     */
    static int tolerance(int type, int tol) {
        if (tol != TOL_HIGH) return tol;
        if (type == FOREST) return 6;
        if (type == PARK || type == SAND) return 4;
        if (type == WATER || type == STREAM || type == RIVER || type == BOUNDARY_CITY || type == BOUNDARY_STATE) return 2;
        return tol;
    }

    // ------------------------------------------------------------------ distribuição

    /** Feições: vias [0, W) e polígonos de relações [W, W+R). */
    int featureCount() {
        return wayStart.size() + relPolyType.size();
    }

    int ftype(int f) {
        int W = wayStart.size();
        return f < W ? wayType.get(f) : relPolyType.get(f - W);
    }

    boolean isArea(int f) {
        int W = wayStart.size();
        return f >= W || (wayFlags.get(f) & F_AREA) != 0;
    }

    /** Anéis (polígono) ou a linha (1 parte) da feição, em coordenadas do mundo. */
    int[][][] geometry(int f, int[] bufX, int[] bufY) {
        int W = wayStart.size();
        if (f >= W) return new int[][][]{relRingsX.get(f - W), relRingsY.get(f - W)};
        int n = wayCoords(f, bufX, bufY);
        return new int[][][]{{Arrays.copyOf(bufX, n)}, {Arrays.copyOf(bufY, n)}};
    }

    /** Entra na visão geral (zooms 7–9). */
    static boolean inOverview(int type, int[][] xs, int[][] ys) {
        if (type == MOTORWAY || type == TRUNK || type == PRIMARY || type == BOUNDARY_STATE) return true;
        if (type == WATER || type == FOREST) return bboxSize(xs, ys) > 3000;
        return false;
    }

    /** Entra na seção simplificada (zooms 10–12). */
    static boolean inLow(int type, int[][] xs, int[][] ys) {
        if (type >= MOTORWAY && type <= MINOR) return true;
        if (type == RAIL || type == RIVER || type == BOUNDARY_STATE || type == BOUNDARY_CITY) return true;
        if (type == WATER || type == PARK || type == FOREST || type == SAND) return bboxSize(xs, ys) > 160;
        return false;
    }

    static int bboxSize(int[][] xs, int[][] ys) {
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (int k = 0; k < xs.length; k++) {
            for (int i = 0; i < xs[k].length; i++) {
                x0 = Math.min(x0, xs[k][i]); x1 = Math.max(x1, xs[k][i]);
                y0 = Math.min(y0, ys[k][i]); y1 = Math.max(y1, ys[k][i]);
            }
        }
        return Math.max(x1 - x0, y1 - y0);
    }

    /**
     * Células da grade tocadas pela geometria: as das arestas e, num polígono, as de dentro
     * (linha do centro de cada fileira cruzando os anéis, regra par/ímpar).
     */
    static java.util.HashSet<Long> cellsOf(Grid g, int[][] xs, int[][] ys, boolean area) {
        java.util.HashSet<Long> out = new java.util.HashSet<>();
        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE, minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;
        for (int k = 0; k < xs.length; k++) {
            int[] x = xs[k], y = ys[k];
            int n = x.length;
            if (n == 1) out.add(key(g.col(x[0]), g.row(y[0])));
            for (int i = 0; i < n; i++) {
                int j = i + 1 < n ? i + 1 : (area ? 0 : -1);
                if (j < 0) break;
                int c0 = g.col(Math.min(x[i], x[j])), c1 = g.col(Math.max(x[i], x[j]));
                int r0 = g.row(Math.max(y[i], y[j])), r1 = g.row(Math.min(y[i], y[j]));
                for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) out.add(key(c, r));
                minRow = Math.min(minRow, r0); maxRow = Math.max(maxRow, r1);
                minCol = Math.min(minCol, c0); maxCol = Math.max(maxCol, c1);
            }
        }
        if (!area || maxRow - minRow < 2 || maxCol - minCol < 2) return out;
        double[] xsCross = new double[64];
        for (int r = minRow + 1; r < maxRow; r++) {
            double yc = (g.yEdge[r] + (double) g.yEdge[r + 1]) / 2;
            int nc = 0;
            for (int k = 0; k < xs.length; k++) {
                int[] x = xs[k], y = ys[k];
                for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
                    if ((y[i] > yc) != (y[j] > yc)) {
                        if (nc == xsCross.length) xsCross = Arrays.copyOf(xsCross, nc * 2);
                        xsCross[nc++] = x[j] + (double) (x[i] - x[j]) * (yc - y[j]) / (y[i] - y[j]);
                    }
                }
            }
            Arrays.sort(xsCross, 0, nc);
            for (int i = 0; i + 1 < nc; i += 2) {
                int c0 = g.col((int) Math.ceil(xsCross[i])), c1 = g.col((int) Math.floor(xsCross[i + 1]));
                for (int c = c0; c <= c1; c++) out.add(key(c, r));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ gravação

    void write(File out, String source, long t0) throws IOException {
        File dir = new File(out, "v1");
        File cells = new File(dir, "cells");
        if (!cells.isDirectory() && !cells.mkdirs()) throw new IOException("não criou " + cells);
        int W = wayStart.size(), F = featureCount();
        int[] bufX = new int[1 << 16], bufY = new int[1 << 16];

        // Pares (quadrado, feição) e (visão geral, feição), ordenados: um quadrado por vez.
        RoadDataBuilder.LongList pairs = new RoadDataBuilder.LongList(1 << 22);
        RoadDataBuilder.LongList ovPairs = new RoadDataBuilder.LongList(1 << 18);
        for (int f = 0; f < F; f++) {
            int type = ftype(f);
            if (type == 0 || type == COAST) continue;
            if (f < W && wayLen(f) > bufX.length) {
                bufX = new int[wayLen(f) * 2];
                bufY = new int[wayLen(f) * 2];
            }
            int[][][] g = geometry(f, bufX, bufY);
            if (g[0].length == 0 || g[0][0].length == 0) continue;
            boolean area = isArea(f);
            if (type == BUILDING) {
                pairs.add(cellIndex(cellGrid.col(g[0][0][0]), cellGrid.row(g[1][0][0])) << 32 | f);
            } else {
                for (long k : cellsOf(cellGrid, g[0], g[1], area)) {
                    pairs.add(cellIndex((int) (k >> 32), (int) k) << 32 | f);
                }
            }
            if (inOverview(type, g[0], g[1])) {
                for (long k : cellsOf(ovGrid, g[0], g[1], area)) ovPairs.add(cellIndex((int) (k >> 32), (int) k) << 32 | f);
            }
        }
        // Lugares: id = F + p.
        for (int p = 0; p < placeX.size(); p++) {
            int x = placeX.get(p), y = placeY.get(p);
            pairs.add(cellIndex(cellGrid.col(x), cellGrid.row(y)) << 32 | (F + p));
            if (placeType.get(p) <= TOWN) ovPairs.add(cellIndex(ovGrid.col(x), ovGrid.row(y)) << 32 | (F + p));
        }
        // Quadrados de mar perto do litoral (sem feição nenhuma, só o mar).
        java.util.HashSet<Long> coastNear = new java.util.HashSet<>();
        for (long k : coastByCell.keySet()) {
            int cx = (int) (k >> 32), cy = (int) k;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) coastNear.add(key(cx + dx, cy + dy));
        }
        for (long k : coastNear) pairs.add(cellIndex((int) (k >> 32), (int) k) << 32 | 0x7fffffffL);
        java.util.HashSet<Long> ovNear = new java.util.HashSet<>();
        for (long k : coastByOv.keySet()) ovNear.add(k);
        for (long k : ovNear) ovPairs.add(cellIndex((int) (k >> 32), (int) k) << 32 | 0x7fffffffL);
        RoadDataBuilder.log(t0, "distribuição: %d pares em quadrados, %d na visão geral", pairs.size(), ovPairs.size());

        TreeMap<String, String> index = new TreeMap<>();
        long[] totals = new long[2];
        int[] biggest = new int[1];
        long[] sorted = pairs.sortedUniqueCopy();
        pairs.clear();
        for (int i = 0; i < sorted.length; ) {
            int ci = (int) (sorted[i] >>> 32);
            int j = i;
            while (j < sorted.length && (int) (sorted[j] >>> 32) == ci) j++;
            int cx = ci % 100000, cy = ci / 100000;
            byte[] data = cellFile(cx, cy, sorted, i, j, F);
            if (data != null) put(cells, "" + cx + "_" + cy, data, index, totals, biggest);
            i = j;
            if (index.size() % 500 == 0 && data != null) RoadDataBuilder.log(t0, "  %d quadrados", index.size());
        }
        sorted = ovPairs.sortedUniqueCopy();
        for (int i = 0; i < sorted.length; ) {
            int ci = (int) (sorted[i] >>> 32);
            int j = i;
            while (j < sorted.length && (int) (sorted[j] >>> 32) == ci) j++;
            int ox = ci % 100000, oy = ci / 100000;
            byte[] data = overviewFile(ox, oy, sorted, i, j, F);
            if (data != null) put(cells, "o" + ox + "_" + oy, data, index, totals, biggest);
            i = j;
        }
        writeIndex(new File(dir, "index.json"), index, System.currentTimeMillis(), source);
        RoadDataBuilder.log(t0, "gravado: %d arquivos, %.1f MB (maior %.0f KB); quadrados %.1f MB",
            index.size(), (totals[0] + totals[1]) / 1048576.0, biggest[0] / 1024.0, totals[0] / 1048576.0);
        StringBuilder tb = new StringBuilder();
        for (int t = 0; t < typeBytes.length; t++) if (typeBytes[t] > 0) tb.append(' ').append(t).append('=').append(typeBytes[t] >> 20).append("MB");
        System.out.println("  bytes por tipo (antes do deflate):" + tb);
    }

    static void writeIndex(File f, TreeMap<String, String> cells, long generated, String source) throws IOException {
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(cells.size() * 30 + 256);
        sb.append("{\"format\":").append(FORMAT)
            .append(",\"generated\":").append(generated)
            .append(",\"generated_iso\":\"").append(iso.format(new java.util.Date(generated))).append('"')
            .append(",\"source\":\"").append(source.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            .append(",\"license\":\"ODbL 1.0, (c) OpenStreetMap contributors\"")
            .append(",\"cell_deg\":0.25,\"overview_deg\":2,\"cells\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : cells.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        sb.append("}}\n");
        try (java.io.Writer w = new java.io.OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    static long cellIndex(int col, int row) {
        return (long) row * 100000 + col;
    }

    void put(File cells, String name, byte[] data, TreeMap<String, String> index, long[] totals, int[] biggest) throws IOException {
        try (OutputStream o = new FileOutputStream(new File(cells, name + ".bin"))) {
            o.write(data);
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        index.put(name, Long.toHexString(crc.getValue()) + ":" + data.length);
        totals[name.startsWith("o") ? 1 : 0] += data.length;
        biggest[0] = Math.max(biggest[0], data.length);
    }

    final long[] typeBytes = new long[100];

    /** Um quadrado: seção simplificada + 8×8 blocos. */
    byte[] cellFile(int cx, int cy, long[] pairs, int from, int to, int F) throws IOException {
        int[] cellRect = cellGrid.rect(cx, cy);
        Section low = new Section(cellRect, SHIFT_LOW);
        Section[] blocks = new Section[BLOCKS * BLOCKS];
        Section[] details = new Section[BLOCKS * BLOCKS];
        int[][] blockRects = new int[BLOCKS * BLOCKS][];
        for (int by = 0; by < BLOCKS; by++) {
            for (int bx = 0; bx < BLOCKS; bx++) {
                // by = 0 é a fileira de cima (norte), como o y do mundo.
                int[] r = blockGrid.rect(cx * BLOCKS + bx, cy * BLOCKS + (BLOCKS - 1 - by));
                blockRects[by * BLOCKS + bx] = r;
                blocks[by * BLOCKS + bx] = new Section(r, SHIFT_HIGH);
                details[by * BLOCKS + bx] = new Section(r, SHIFT_HIGH);
            }
        }
        int[] bufX = new int[1 << 16], bufY = new int[1 << 16];
        int W = wayStart.size();
        boolean any = false;
        for (int i = from; i < to; i++) {
            int f = (int) pairs[i];
            if (f == 0x7fffffff) continue;
            if (f >= F) {
                int p = f - F;
                int type = placeType.get(p), x = placeX.get(p), y = placeY.get(p);
                int b = blockAt(cx, cy, x, y);
                if (b >= 0) blocks[b].point(type, placeName.get(p), x, y);
                if (type <= SUBURB) low.point(type, placeName.get(p), x, y);
                any = true;
                continue;
            }
            if (f < W && wayLen(f) > bufX.length) {
                bufX = new int[wayLen(f) * 2];
                bufY = new int[wayLen(f) * 2];
            }
            int type = ftype(f);
            int flags = f < W ? wayFlags.get(f) : F_AREA;
            int name = f < W ? wayName.get(f) : -1;
            int[][][] g = geometry(f, bufX, bufY);
            boolean area = isArea(f);
            any = true;
            if (type == BUILDING) {
                int b = blockAt(cx, cy, g[0][0][0], g[1][0][0]);
                if (b >= 0) details[b].polygon(type, flags, -1, g[0], g[1], null, TOL_HIGH);
                continue;
            }
            // Primeiro no quadrado (um polígono da Amazônia tem milhões de pontos), depois nos blocos.
            int[][] cx2, cy2;
            if (area) {
                List<int[]> rx = new ArrayList<>(), ry = new ArrayList<>();
                for (int k = 0; k < g[0].length; k++) {
                    int[][] c = clipRing(g[0][k], g[1][k], g[0][k].length, cellRect);
                    if (c != null) { rx.add(c[0]); ry.add(c[1]); }
                }
                if (rx.isEmpty()) continue;
                cx2 = rx.toArray(new int[0][]);
                cy2 = ry.toArray(new int[0][]);
            } else {
                List<int[][]> pieces = clipLine(g[0][0], g[1][0], g[0][0].length, cellRect);
                if (pieces.isEmpty()) continue;
                cx2 = new int[pieces.size()][];
                cy2 = new int[pieces.size()][];
                for (int k = 0; k < cx2.length; k++) { cx2[k] = pieces.get(k)[0]; cy2[k] = pieces.get(k)[1]; }
            }
            if (inLow(type, g[0], g[1])) {
                if (area) low.polygon(type, flags, -1, cx2, cy2, null, TOL_LOW);
                else for (int k = 0; k < cx2.length; k++) low.line(type, flags, name, cx2[k], cy2[k], cellRect, TOL_LOW);
            }
            for (long k : cellsOf(blockGrid, cx2, cy2, area)) {
                int gbx = (int) (k >> 32), gby = (int) k;
                int bx = gbx - cx * BLOCKS, byS = gby - cy * BLOCKS;
                if (bx < 0 || bx >= BLOCKS || byS < 0 || byS >= BLOCKS) continue;
                int b = (BLOCKS - 1 - byS) * BLOCKS + bx;
                Section target = isDetail(type) ? details[b] : blocks[b];
                if (area) target.polygon(type, flags, -1, cx2, cy2, blockRects[b], TOL_HIGH);
                else for (int p2 = 0; p2 < cx2.length; p2++) target.line(type, flags, name, cx2[p2], cy2[p2], blockRects[b], TOL_HIGH);
            }
        }
        // Mar.
        RoadDataBuilder.IntList chains = coastByCell.get(key(cx, cy));
        boolean nearCoast = false;
        for (int dy = -1; dy <= 1 && !nearCoast; dy++) for (int dx = -1; dx <= 1; dx++) if (coastByCell.containsKey(key(cx + dx, cy + dy))) { nearCoast = true; break; }
        if (nearCoast) {
            List<int[][]> sea = ocean(cellRect, chains);
            if (sea != null) { low.rings(OCEAN, sea, cellRect, TOL_LOW); any = true; }
            for (int b = 0; b < blocks.length; b++) {
                List<int[][]> s = ocean(blockRects[b], chains);
                if (s != null) blocks[b].rings(OCEAN, s, blockRects[b], TOL_HIGH);
            }
        }
        if (!any) return null;
        List<byte[]> sections = new ArrayList<>();
        sections.add(low.bytes());
        for (Section s : blocks) sections.add(s.bytes());
        // Seções de detalhe só se alguma tem algo (com prédios e vias pequenas fora, ficam vazias e
        // o arquivo termina nos blocos; o app trata a seção que falta como vazia).
        boolean anyDetail = false;
        for (Section s : details) anyDetail |= s.count > 0;
        if (anyDetail) for (Section s : details) sections.add(s.bytes());
        return file(cx, cy, 0, sections);
    }

    /** Vai para a seção de detalhe do bloco (desenhado do zoom 15 em diante). */
    static boolean isDetail(int type) {
        return type == BUILDING || type == SERVICE || type == PEDESTRIAN || type == TRACK || type == PATH;
    }

    /** Bloco (by de cima para baixo) do ponto, se estiver neste quadrado. */
    int blockAt(int cx, int cy, int x, int y) {
        int gbx = blockGrid.col(x), gby = blockGrid.row(y);
        int bx = gbx - cx * BLOCKS, byS = gby - cy * BLOCKS;
        if (bx < 0 || bx >= BLOCKS || byS < 0 || byS >= BLOCKS) return -1;
        return (BLOCKS - 1 - byS) * BLOCKS + bx;
    }

    byte[] overviewFile(int ox, int oy, long[] pairs, int from, int to, int F) throws IOException {
        int[] rect = ovGrid.rect(ox, oy);
        Section s = new Section(rect, SHIFT_OVERVIEW);
        int[] bufX = new int[1 << 16], bufY = new int[1 << 16];
        int W = wayStart.size();
        boolean any = false;
        for (int i = from; i < to; i++) {
            int f = (int) pairs[i];
            if (f == 0x7fffffff) continue;
            any = true;
            if (f >= F) {
                int p = f - F;
                s.point(placeType.get(p), placeName.get(p), placeX.get(p), placeY.get(p));
                continue;
            }
            if (f < W && wayLen(f) > bufX.length) {
                bufX = new int[wayLen(f) * 2];
                bufY = new int[wayLen(f) * 2];
            }
            int type = ftype(f);
            int[][][] g = geometry(f, bufX, bufY);
            if (isArea(f)) s.polygon(type, F_AREA, -1, g[0], g[1], rect, TOL_OVERVIEW);
            else s.line(type, f < W ? wayFlags.get(f) & ~F_NAME : 0, -1, g[0][0], g[1][0], rect, TOL_OVERVIEW);
        }
        List<int[][]> sea = ocean(rect, coastByOv.get(key(ox, oy)));
        if (sea != null) { s.rings(OCEAN, sea, rect, TOL_OVERVIEW); any = true; }
        if (!any) return null;
        List<byte[]> sections = new ArrayList<>();
        sections.add(s.bytes());
        return file(ox, oy, 1, sections);
    }

    /**
     * Arquivo: "K4MP", versão, tipo (0 quadrado, 1 visão geral), col, linha (int), nSeções (byte),
     * tamanho de cada seção (int) e as seções, cada uma em deflate (zlib) separado.
     */
    static byte[] file(int col, int row, int kind, List<byte[]> sections) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        DataOutputStream o = new DataOutputStream(bytes);
        o.write(new byte[]{'K', '4', 'M', 'P', FORMAT, (byte) kind});
        o.writeInt(col);
        o.writeInt(row);
        o.writeByte(sections.size());
        List<byte[]> packed = new ArrayList<>();
        Deflater def = new Deflater(9);
        for (byte[] s : sections) {
            def.reset();
            def.setInput(s);
            def.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream(s.length / 2 + 64);
            byte[] buf = new byte[1 << 16];
            while (!def.finished()) {
                int n = def.deflate(buf);
                z.write(buf, 0, n);
            }
            packed.add(z.toByteArray());
        }
        def.end();
        for (byte[] p : packed) o.writeInt(p.length);
        for (byte[] p : packed) o.write(p);
        o.close();
        return bytes.toByteArray();
    }

    /**
     * Uma seção: shift (byte), origem x, y (int, canto noroeste), nNomes (varint) e os nomes (UTF-8,
     * tamanho varint), nFeições (varint) e as feições: tipo, flags (bytes), nome (varint, se F_NAME),
     * nPartes (varint), e por parte nPontos (varint) e as coordenadas >> shift, zigzag, a primeira
     * relativa à origem e as outras à anterior.
     */
    final class Section {
        final int[] rect;
        final int shift;
        final ByteArrayOutputStream body = new ByteArrayOutputStream(4096);
        final DataOutputStream o = new DataOutputStream(body);
        final HashMap<Integer, Integer> localNames = new HashMap<>();
        final List<String> nameList = new ArrayList<>();
        int count;

        Section(int[] rect, int shift) {
            this.rect = rect;
            this.shift = shift;
        }

        int local(int name) {
            Integer i = localNames.get(name);
            if (i != null) return i;
            nameList.add(names.get(name));
            localNames.put(name, nameList.size() - 1);
            return nameList.size() - 1;
        }

        void point(int type, int name, int x, int y) throws IOException {
            o.writeByte(type);
            o.writeByte(F_NAME);
            RoadDataBuilder.varint(o, local(name));
            RoadDataBuilder.varint(o, 1);
            RoadDataBuilder.varint(o, 1);
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag((x >> shift) - (rect[0] >> shift)));
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag((y >> shift) - (rect[1] >> shift)));
            count++;
        }

        void line(int type, int flags, int name, int[] xs, int[] ys, int[] clip, int tol) throws IOException {
            tol = tolerance(type, tol);
            List<int[][]> pieces = clipLine(xs, ys, xs.length, clip);
            for (int[][] p : pieces) {
                int n = simplify(p[0], p[1], p[0].length, tol);
                n = quantize(p[0], p[1], n);
                if (n < 2) continue;
                write(type, flags & ~F_AREA, name, new int[][]{p[0]}, new int[][]{p[1]}, new int[]{n});
            }
        }

        void polygon(int type, int flags, int name, int[][] xs, int[][] ys, int[] clip, int tol) throws IOException {
            tol = tolerance(type, tol);
            List<int[]> rx = new ArrayList<>(), ry = new ArrayList<>();
            List<Integer> lens = new ArrayList<>();
            double minArea = (double) (1 << shift) * (1 << shift) * 2;
            // Mata: pedaços com menos de ~0,3 ha não fazem diferença na tela e pesam muito no total.
            if (type == FOREST) minArea = Math.max(minArea, 20000);
            for (int k = 0; k < xs.length; k++) {
                int[][] c = clip == null ? new int[][]{xs[k].clone(), ys[k].clone()} : clipRing(xs[k], ys[k], xs[k].length, clip);
                if (c == null) continue;
                int n = c[0].length;
                if (n > 1 && c[0][0] == c[0][n - 1] && c[1][0] == c[1][n - 1]) n--; // sem repetir o primeiro
                n = simplify(c[0], c[1], n, tol);
                n = quantize(c[0], c[1], n);
                if (n < 3) continue;
                if (Math.abs(area2(Arrays.copyOf(c[0], n), Arrays.copyOf(c[1], n))) < minArea) continue;
                rx.add(c[0]);
                ry.add(c[1]);
                lens.add(n);
            }
            if (rx.isEmpty()) return;
            int[] ln = new int[lens.size()];
            for (int i = 0; i < ln.length; i++) ln[i] = lens.get(i);
            write(type, flags | F_AREA, name, rx.toArray(new int[0][]), ry.toArray(new int[0][]), ln);
        }

        void rings(int type, List<int[][]> rings, int[] clip, int tol) throws IOException {
            int[][] xs = new int[rings.size()][], ys = new int[rings.size()][];
            for (int i = 0; i < xs.length; i++) { xs[i] = rings.get(i)[0]; ys[i] = rings.get(i)[1]; }
            polygon(type, F_AREA, -1, xs, ys, clip, tol);
        }

        /** Arredonda para a grade do shift e tira repetidos. */
        int quantize(int[] xs, int[] ys, int n) {
            if (shift == 0) return dedupe(xs, ys, n);
            int half = 1 << (shift - 1);
            for (int i = 0; i < n; i++) {
                xs[i] = ((xs[i] + half) >> shift) << shift;
                ys[i] = ((ys[i] + half) >> shift) << shift;
            }
            return dedupe(xs, ys, n);
        }

        void write(int type, int flags, int name, int[][] xs, int[][] ys, int[] lens) throws IOException {
            if (name < 0) flags &= ~F_NAME;
            int before = body.size();
            o.writeByte(type);
            o.writeByte(flags);
            if ((flags & F_NAME) != 0) RoadDataBuilder.varint(o, local(name));
            RoadDataBuilder.varint(o, xs.length);
            int px = rect[0] >> shift, py = rect[1] >> shift;
            for (int k = 0; k < xs.length; k++) {
                RoadDataBuilder.varint(o, lens[k]);
                for (int i = 0; i < lens[k]; i++) {
                    int x = xs[k][i] >> shift, y = ys[k][i] >> shift;
                    RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(x - px));
                    RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(y - py));
                    px = x;
                    py = y;
                }
            }
            count++;
            typeBytes[type] += body.size() - before;
        }

        byte[] bytes() throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream(body.size() + 1024);
            DataOutputStream h = new DataOutputStream(all);
            h.writeByte(shift);
            h.writeInt(rect[0]);
            h.writeInt(rect[1]);
            RoadDataBuilder.varint(h, nameList.size());
            for (String s : nameList) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                RoadDataBuilder.varint(h, b.length);
                h.write(b);
            }
            RoadDataBuilder.varint(h, count);
            o.flush();
            body.writeTo(h);
            h.flush();
            return all.toByteArray();
        }
    }
}
