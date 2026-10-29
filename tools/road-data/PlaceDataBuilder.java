import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Gera o índice de endereços e lugares usado pela busca sem internet do mapa integrado (sugestões
 * enquanto digita, rua com número da casa, parques, escolas, bairros, cidades), a partir de um recorte
 * do OpenStreetMap (.osm.pbf) e, se houver, do CNEFE 2022 do IBGE (endereços com número e coordenada).
 *
 * Saída em {@code <saída>/v1/}: {@code index.json} e {@code cells/<cx>_<cy>.bin}, os mesmos quadrados de
 * {@value RoadDataBuilder#CELL_DEG}° dos dados das vias. Formato em {@code tools/road-data/README.md};
 * leitor no app em {@code map/.../search/PlaceCell.java}.
 *
 * Uso: {@code java -Xmx10g -cp build PlaceDataBuilder <entrada.osm.pbf> <saída> <descrição> [pasta do CNEFE]}
 * (a pasta tem os .zip por UF do IBGE, como vêm do site; sem ela, só os números de casa do OSM).
 */
public final class PlaceDataBuilder {

    static final int CELL_E6 = RoadDataBuilder.CELL_E6;
    static final int FORMAT = 1;

    // Tipos de entrada (iguais aos do app).
    static final int STREET = 1;
    static final int POI = 2;
    static final int CITY = 3;
    static final int SUBURB = 4;

    /** Erro máximo da interpolação dos números de casa (os pontos que sobram cobrem o resto): ~25 m. */
    static final double NUMBER_ERROR_M = 25;
    /** Trechos da mesma rua a até isso um do outro viram uma entrada só. */
    static final int STREET_JOIN_E6 = 3000; // ~330 m

    /** Chaves de POI do OSM (com nome). */
    static final String[] POI_KEYS = {"amenity", "shop", "leisure", "tourism", "office", "healthcare", "historic",
        "craft", "aeroway", "public_transport", "railway", "natural", "landuse", "man_made", "sport", "club",
        "building"};
    static final Set<String> POI_SKIP = new HashSet<>(Arrays.asList(
        // valores que não são lugar de destino (ou são demais)
        "bench", "waste_basket", "bicycle_parking", "vending_machine", "parking_space", "parking_entrance",
        "grave_yard", "tree", "tree_row", "stone", "rock", "wetland", "water", "coastline", "cliff", "ridge",
        "valley", "scrub", "wood", "grassland", "heath", "residential", "farmland", "meadow", "forest",
        "grass", "orchard", "allotments", "brownfield", "greenfield", "construction", "railway",
        "level_crossing", "crossing", "switch", "buffer_stop", "signal", "platform", "stop_position",
        "pipeline", "tower", "mast", "pole", "survey_point", "street_cabinet", "storage_tank", "yes",
        "house", "detached", "apartments", "roof", "shed", "garages", "garage", "hut", "terrace",
        "semidetached_house", "bungalow", "farm_auxiliary", "ruins", "service", "abandoned", "disused"));

    // ---- OSM
    final RoadDataBuilder.LongList wayRefs = new RoadDataBuilder.LongList(1 << 22);
    final RoadDataBuilder.IntList wayStart = new RoadDataBuilder.IntList(1 << 18);
    final List<String> wayName = new ArrayList<>();
    /** STREET ou POI. */
    final RoadDataBuilder.ByteList wayKind = new RoadDataBuilder.ByteList(1 << 18);
    /** POI de via: importância (ver {@link #importance}). */
    final RoadDataBuilder.ByteList wayRank = new RoadDataBuilder.ByteList(1 << 18);
    final List<String[]> wayAddr = new ArrayList<>(); // {rua, número} de prédios com endereço, ou null
    long[] ids;
    int[] latE6, lonE6;

    /** Entradas pontuais: lugares, POI de nó e de via (centro), endereços do OSM. */
    final List<Entry> points = new ArrayList<>();
    /** Endereços (rua, número) do OSM: {lat, lon, número} por rua normalizada. */
    final List<Object[]> osmAddresses = new ArrayList<>();

    /** Trechos de rua com nome: entram nas ruas (agrupadas por quadrado e nome). */
    final List<Seg> segments = new ArrayList<>();

    static final class Seg {
        String name;
        int minLat, maxLat, minLon, maxLon;
        int midLat, midLon;
        int length; // em 1e-6 grau, para escolher o trecho mais longo
    }

    static final class Entry {
        int type;
        String name;
        int lat, lon;
        String suburb; // contexto: bairro (ou null)
        String city;
        int rank;      // lugares: 0 cidade, 1 town, 2 village/hamlet; POI: importância 0–3
        int[] numbers; // ruas: número, lat, lon, …
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: PlaceDataBuilder <entrada.osm.pbf> <saída> [descrição] [pasta do CNEFE]");
            System.exit(2);
        }
        new PlaceDataBuilder().run(args[0], new File(args[1]), args.length > 2 ? args[2] : new File(args[0]).getName(),
            args.length > 3 ? new File(args[3]) : null);
    }

    void run(String pbf, File out, String source, File cnefe) throws IOException {
        long t0 = System.currentTimeMillis();
        byte[] kinds = Pbf.read(pbf, Pbf.WAYS, null, new Pbf.Handler() {
            @Override
            public void node(long id, double lat, double lon, String[] tags) {
            }

            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
                onWay(refs, count, tags);
            }

            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
            }
        });
        RoadDataBuilder.log(t0, "passo 1: %d vias com nome ou endereço", wayStart.size());
        ids = wayRefs.sortedUniqueCopy();
        latE6 = new int[ids.length];
        lonE6 = new int[ids.length];
        Arrays.fill(latE6, Integer.MIN_VALUE);
        Pbf.read(pbf, Pbf.NODES, null, new Pbf.Handler() {
            int cursor;

            @Override
            public void node(long id, double lat, double lon, String[] tags) {
                while (cursor < ids.length && ids[cursor] < id) cursor++;
                int i = cursor < ids.length && ids[cursor] == id ? cursor : Arrays.binarySearch(ids, id);
                if (i >= 0) {
                    latE6[i] = (int) Math.round(lat * 1e6);
                    lonE6[i] = (int) Math.round(lon * 1e6);
                }
                if (tags != null) onNode(lat, lon, tags);
            }

            @Override
            public void way(long id, long[] refs, int count, String[] tags) {
            }

            @Override
            public void relation(long id, long[] mids, int[] types, String[] roles, int count, String[] tags) {
            }
        });
        RoadDataBuilder.log(t0, "passo 2: %d lugares e POIs de nó, %d endereços", points.size(), osmAddresses.size());
        waysToEntries();
        wayRefs.clear();
        ids = null;
        latE6 = lonE6 = null;
        RoadDataBuilder.log(t0, "vias: %d trechos de rua, %d entradas pontuais", segments.size(), points.size());

        Map<Long, List<Entry>> cells = new HashMap<>();
        buildStreets(cells);
        RoadDataBuilder.log(t0, "ruas agrupadas");
        if (cnefe != null) readCnefe(cnefe, cells, t0);
        addOsmAddresses(cells);
        for (Entry e : points) cellList(cells, e.lat, e.lon).add(e);
        context(cells);
        RoadDataBuilder.log(t0, "contexto (bairro, cidade)");
        write(cells, out, source, t0);
    }

    static List<Entry> cellList(Map<Long, List<Entry>> cells, int lat, int lon) {
        return cells.computeIfAbsent(RoadDataBuilder.key(RoadDataBuilder.cellX(lon), RoadDataBuilder.cellY(lat)),
            k -> new ArrayList<>());
    }

    // ------------------------------------------------------------------ OSM

    void onWay(long[] refs, int count, String[] tags) {
        if (count < 2) return;
        String name = Pbf.tag(tags, "name");
        String hw = Pbf.tag(tags, "highway");
        int kind = 0;
        if (hw != null && name != null && !"bus_stop".equals(hw) && !"platform".equals(hw)) {
            kind = STREET;
        } else if (name != null && poi(tags)) {
            kind = POI;
            name = poiName(tags, name);
        }
        String street = Pbf.tag(tags, "addr:street");
        String number = Pbf.tag(tags, "addr:housenumber");
        String[] addr = street != null && number != null ? new String[]{street, number} : null;
        if (kind == 0 && addr == null) return;
        wayStart.add(wayRefs.size());
        wayRefs.addAll(refs, count);
        wayName.add(name == null ? null : name.trim());
        wayKind.add(kind);
        wayRank.add(kind == POI ? Math.max(2, importance(tags)) : 0);
        wayAddr.add(addr);
    }

    /**
     * Importância de um POI, para a busca ordenar ("Masp" acha o museu antes da banca "Trianon-Masp"):
     * 3 para os destinos que se procuram pelo nome (museu, parque, hospital, shopping, estação,
     * aeroporto, universidade, estádio), 1 para o resto. Nó vale um a menos que via; CNEFE vale 0.
     */
    static int importance(String[] tags) {
        String t = Pbf.tag(tags, "tourism"), l = Pbf.tag(tags, "leisure"), a = Pbf.tag(tags, "amenity");
        if ("museum".equals(t) || "attraction".equals(t) || "theme_park".equals(t) || "zoo".equals(t)
            || "aquarium".equals(t) || "park".equals(l) || "stadium".equals(l) || "hospital".equals(a)
            || "university".equals(a) || "college".equals(a) || "mall".equals(Pbf.tag(tags, "shop"))
            || "aerodrome".equals(Pbf.tag(tags, "aeroway")) || "station".equals(Pbf.tag(tags, "railway"))
            || "station".equals(Pbf.tag(tags, "public_transport")) || "bus_station".equals(a)) return 3;
        return 1;
    }

    /** Nome com a sigla ou o nome curto, se houver: "Museu de Arte de São Paulo (MASP)". */
    static String poiName(String[] tags, String name) {
        String n = name.trim();
        String alt = Pbf.tag(tags, "short_name");
        if (alt == null) alt = Pbf.tag(tags, "alt_name");
        if (alt == null) return n;
        alt = alt.trim();
        int semi = alt.indexOf(';');
        if (semi > 0) alt = alt.substring(0, semi).trim();
        if (alt.isEmpty() || alt.length() > 40 || normalize(n).contains(normalize(alt))) return n;
        return n + " (" + alt + ")";
    }

    static boolean poi(String[] tags) {
        for (String k : POI_KEYS) {
            String v = Pbf.tag(tags, k);
            if (v == null || POI_SKIP.contains(v)) continue;
            if ("building".equals(k) || "landuse".equals(k)) {
                // Prédio ou área com nome só conta se for algo que se procura (shopping, escola…).
                if (!Arrays.asList("commercial", "retail", "school", "university", "hospital", "church",
                    "cathedral", "stadium", "train_station", "public", "civic", "government", "hotel",
                    "industrial", "cemetery", "religious", "college", "kindergarten").contains(v)) continue;
            }
            return true;
        }
        return false;
    }

    void onNode(double lat, double lon, String[] tags) {
        String name = Pbf.tag(tags, "name");
        int la = (int) Math.round(lat * 1e6), lo = (int) Math.round(lon * 1e6);
        String place = Pbf.tag(tags, "place");
        if (name != null && place != null) {
            Entry e = new Entry();
            e.name = name.trim();
            e.lat = la;
            e.lon = lo;
            switch (place) {
                case "city":
                    e.type = CITY;
                    e.rank = 0;
                    break;
                case "town":
                    e.type = CITY;
                    e.rank = 1;
                    break;
                case "village":
                case "hamlet":
                case "isolated_dwelling":
                    e.type = CITY;
                    e.rank = 2;
                    break;
                case "suburb":
                case "neighbourhood":
                case "quarter":
                case "borough":
                    e.type = SUBURB;
                    break;
                default:
                    e.type = 0;
            }
            if (e.type != 0) {
                points.add(e);
                return;
            }
        }
        if (name != null && poi(tags)) {
            Entry e = new Entry();
            e.type = POI;
            e.name = poiName(tags, name.trim());
            e.lat = la;
            e.lon = lo;
            e.rank = Math.max(1, importance(tags) - 1);
            points.add(e);
        }
        String street = Pbf.tag(tags, "addr:street");
        String number = Pbf.tag(tags, "addr:housenumber");
        if (street != null && number != null) {
            int n = number(number);
            if (n > 0) osmAddresses.add(new Object[]{street, n, la, lo});
        }
    }

    void waysToEntries() {
        int[] la = new int[64], lo = new int[64];
        for (int w = 0; w < wayStart.size(); w++) {
            int a = wayStart.get(w);
            int b = w + 1 < wayStart.size() ? wayStart.get(w + 1) : wayRefs.size();
            if (la.length < b - a) {
                la = new int[(b - a) * 2];
                lo = new int[(b - a) * 2];
            }
            int n = 0;
            for (int i = a; i < b; i++) {
                int j = Arrays.binarySearch(ids, wayRefs.get(i));
                if (j < 0 || latE6[j] == Integer.MIN_VALUE) continue;
                la[n] = latE6[j];
                lo[n] = lonE6[j];
                n++;
            }
            if (n == 0) continue;
            int minLat = Integer.MAX_VALUE, maxLat = Integer.MIN_VALUE, minLon = Integer.MAX_VALUE, maxLon = Integer.MIN_VALUE;
            long length = 0;
            for (int i = 0; i < n; i++) {
                minLat = Math.min(minLat, la[i]);
                maxLat = Math.max(maxLat, la[i]);
                minLon = Math.min(minLon, lo[i]);
                maxLon = Math.max(maxLon, lo[i]);
                if (i > 0) length += Math.abs(la[i] - la[i - 1]) + Math.abs(lo[i] - lo[i - 1]);
            }
            int kind = wayKind.get(w);
            String name = wayName.get(w);
            if (kind == STREET) {
                Seg s = new Seg();
                s.name = name;
                s.minLat = minLat;
                s.maxLat = maxLat;
                s.minLon = minLon;
                s.maxLon = maxLon;
                s.midLat = la[n / 2];
                s.midLon = lo[n / 2];
                s.length = (int) Math.min(Integer.MAX_VALUE, length);
                segments.add(s);
            } else if (kind == POI) {
                Entry e = new Entry();
                e.type = POI;
                e.name = name;
                e.lat = (int) (((long) minLat + maxLat) / 2);
                e.lon = (int) (((long) minLon + maxLon) / 2);
                // Área grande (~300 m de lado) também é importante: parque, campus, aeroporto.
                e.rank = maxLat - minLat > 2700 && maxLon - minLon > 2700 ? 3 : wayRank.get(w);
                points.add(e);
            }
            String[] addr = wayAddr.get(w);
            if (addr != null) {
                int num = number(addr[1]);
                if (num > 0) {
                    osmAddresses.add(new Object[]{addr[0], num, (int) (((long) minLat + maxLat) / 2),
                        (int) (((long) minLon + maxLon) / 2)});
                }
            }
        }
    }

    /** "123", "123A", "123-125" → 123; 0 se não for número. */
    static int number(String s) {
        int n = 0, i = 0;
        s = s.trim();
        while (i < s.length() && Character.isDigit(s.charAt(i)) && i < 6) n = n * 10 + (s.charAt(i++) - '0');
        return i == 0 ? 0 : n;
    }

    // ------------------------------------------------------------------ ruas

    /** Trechos com o mesmo nome perto um do outro, no mesmo quadrado, viram uma rua. */
    void buildStreets(Map<Long, List<Entry>> cells) {
        Map<String, List<Seg>> byKey = new HashMap<>();
        for (Seg s : segments) {
            long cell = RoadDataBuilder.key(RoadDataBuilder.cellX(s.midLon), RoadDataBuilder.cellY(s.midLat));
            byKey.computeIfAbsent(cell + "|" + normalize(s.name), k -> new ArrayList<>()).add(s);
        }
        segments.clear();
        streetIndex = new HashMap<>();
        for (Map.Entry<String, List<Seg>> en : byKey.entrySet()) {
            List<Seg> list = en.getValue();
            // União por proximidade (caixas com folga se tocando).
            int n = list.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    Seg a = list.get(i), b = list.get(j);
                    if (a.minLat - STREET_JOIN_E6 > b.maxLat || b.minLat - STREET_JOIN_E6 > a.maxLat) continue;
                    if (a.minLon - STREET_JOIN_E6 > b.maxLon || b.minLon - STREET_JOIN_E6 > a.maxLon) continue;
                    union(parent, i, j);
                }
                if (n > 400 && i > 400) break; // nome muito comum (ex.: sem nome real): não junta tudo
            }
            Map<Integer, List<Seg>> groups = new HashMap<>();
            for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(list.get(i));
            for (List<Seg> g : groups.values()) {
                Seg longest = g.get(0);
                Street st = new Street();
                st.minLat = st.minLon = Integer.MAX_VALUE;
                st.maxLat = st.maxLon = Integer.MIN_VALUE;
                for (Seg s : g) {
                    if (s.length > longest.length) longest = s;
                    st.minLat = Math.min(st.minLat, s.minLat);
                    st.maxLat = Math.max(st.maxLat, s.maxLat);
                    st.minLon = Math.min(st.minLon, s.minLon);
                    st.maxLon = Math.max(st.maxLon, s.maxLon);
                }
                Entry e = new Entry();
                e.type = STREET;
                e.name = longest.name;
                e.lat = longest.midLat;
                e.lon = longest.midLon;
                st.entry = e;
                cellList(cells, e.lat, e.lon).add(e);
                streetIndex.computeIfAbsent(normalize(e.name), k -> new ArrayList<>()).add(st);
            }
        }
    }

    static final class Street {
        Entry entry;
        int minLat, maxLat, minLon, maxLon;
        /** Números juntados: número, lat, lon. */
        RoadDataBuilder.IntList numbers;
        String suburb;
    }

    Map<String, List<Street>> streetIndex;

    static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }

    /** A rua com esse nome que contém o ponto (com folga de ~500 m), ou null. */
    Street street(String normalized, int lat, int lon) {
        List<Street> list = streetIndex.get(normalized);
        if (list == null) return null;
        Street best = null;
        long bestD = Long.MAX_VALUE;
        for (Street s : list) {
            long dLat = Math.max(0, Math.max(s.minLat - lat, lat - s.maxLat));
            long dLon = Math.max(0, Math.max(s.minLon - lon, lon - s.maxLon));
            long d = dLat + dLon;
            if (d < 4500 && d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ CNEFE

    /**
     * Endereços do CNEFE 2022 (IBGE): uma linha por endereço, com o número e a coordenada. Lê cada
     * .zip (uma UF) e junta os números por rua (município + nome); estabelecimentos de ensino, saúde e
     * religiosos com nome viram POI.
     */
    void readCnefe(File dir, Map<Long, List<Entry>> cells, long t0) throws IOException {
        File[] zips = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            System.out.println("  CNEFE: nenhum .zip em " + dir);
            return;
        }
        Arrays.sort(zips);
        Charset cs = Charset.forName("ISO-8859-1"); // o CNEFE vem em ASCII (sem acentos)
        int matched = 0, created = 0, pois = 0;
        long rows = 0, kept = 0;
        for (File zip : zips) {
            Map<String, RoadDataBuilder.IntList> streets = new HashMap<>(); // mun|tipo título nome → número, lat, lon
            Map<String, String> display = new HashMap<>();
            Map<String, Map<String, Integer>> localities = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
                ZipEntry ze;
                while ((ze = z.getNextEntry()) != null) {
                    if (!ze.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) continue;
                    BufferedReader r = new BufferedReader(new InputStreamReader(z, cs), 1 << 20);
                    String header = r.readLine();
                    if (header == null) continue;
                    Map<String, Integer> col = new HashMap<>();
                    String[] h = header.split(";", -1);
                    for (int i = 0; i < h.length; i++) col.put(h[i].trim(), i);
                    int cMun = col.get("COD_MUNICIPIO"), cLoc = col.get("DSC_LOCALIDADE"), cTipo = col.get("NOM_TIPO_SEGLOGR"),
                        cTit = col.get("NOM_TITULO_SEGLOGR"), cNome = col.get("NOM_SEGLOGR"), cNum = col.get("NUM_ENDERECO"),
                        cLat = col.get("LATITUDE"), cLon = col.get("LONGITUDE"), cNv = col.get("NV_GEO_COORD"),
                        cEsp = col.get("COD_ESPECIE"), cEst = col.get("DSC_ESTABELECIMENTO");
                    String line;
                    while ((line = r.readLine()) != null) {
                        rows++;
                        String[] f = line.split(";", -1);
                        if (f.length <= cEst) continue;
                        int nv = parseInt(f[cNv]);
                        if (nv < 1 || nv > 2) continue; // só endereço exato ou a face da quadra
                        int la, lo;
                        try {
                            la = (int) Math.round(Double.parseDouble(f[cLat]) * 1e6);
                            lo = (int) Math.round(Double.parseDouble(f[cLon]) * 1e6);
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        String esp = f[cEsp];
                        String est = f[cEst].trim();
                        if (!est.isEmpty() && ("4".equals(esp) || "5".equals(esp) || "8".equals(esp))) {
                            Entry e = new Entry();
                            e.type = POI;
                            e.name = title(est);
                            e.lat = la;
                            e.lon = lo;
                            points.add(e);
                            pois++;
                        }
                        int num = parseInt(f[cNum]);
                        if (num <= 0) continue;
                        String full = (f[cTipo] + " " + f[cTit] + " " + f[cNome]).trim();
                        if (f[cNome].trim().isEmpty()) continue;
                        String key = f[cMun] + "|" + normalize(full);
                        RoadDataBuilder.IntList l = streets.get(key);
                        if (l == null) {
                            l = new RoadDataBuilder.IntList(16);
                            streets.put(key, l);
                            display.put(key, title(full));
                        }
                        l.add(num);
                        l.add(la);
                        l.add(lo);
                        String loc = f[cLoc].trim();
                        if (!loc.isEmpty()) localities.computeIfAbsent(key, k -> new HashMap<>()).merge(loc, 1, Integer::sum);
                    }
                }
            }
            // Cada rua do CNEFE: os números (um ponto por número, o do meio) simplificados.
            for (Map.Entry<String, RoadDataBuilder.IntList> en : streets.entrySet()) {
                String key = en.getKey();
                String norm = key.substring(key.indexOf('|') + 1);
                for (int[] part : splitByDistance(en.getValue())) {
                    int[] simple = simplifyNumbers(part);
                    kept += simple.length / 3;
                    int mid = (simple.length / 3) / 2 * 3;
                    int la = simple[mid + 1], lo = simple[mid + 2];
                    Street st = street(norm, la, lo);
                    String suburb = mostCommon(localities.get(key));
                    if (st != null) {
                        matched++;
                        if (st.numbers == null) st.numbers = new RoadDataBuilder.IntList(simple.length);
                        for (int v : simple) st.numbers.add(v);
                        if (st.suburb == null) st.suburb = suburb;
                    } else {
                        created++;
                        Entry e = new Entry();
                        e.type = STREET;
                        e.name = display.get(key);
                        e.lat = la;
                        e.lon = lo;
                        st = new Street();
                        st.entry = e;
                        st.minLat = st.maxLat = la;
                        st.minLon = st.maxLon = lo;
                        st.numbers = new RoadDataBuilder.IntList(simple.length);
                        for (int v : simple) st.numbers.add(v);
                        st.suburb = suburb;
                        cellList(cells, la, lo).add(e);
                        streetIndex.computeIfAbsent(norm, k -> new ArrayList<>()).add(st);
                    }
                }
            }
            RoadDataBuilder.log(t0, "CNEFE %s: %d linhas até agora, %d ruas", zip.getName(), rows, streets.size());
        }
        // Números das ruas: ordenados e simplificados de novo (podem ter vindo de mais de uma parte).
        for (List<Street> list : streetIndex.values()) {
            for (Street st : list) {
                if (st.numbers == null) continue;
                st.entry.numbers = simplifyNumbers(Arrays.copyOf(st.numbers.a, st.numbers.n));
                if (st.suburb != null) st.entry.suburb = title(st.suburb);
            }
        }
        System.out.printf("  CNEFE: %d linhas, %d números guardados, %d ruas do OSM com número, %d ruas só do CNEFE, %d POIs%n",
            rows, kept, matched, created, pois);
    }

    void addOsmAddresses(Map<Long, List<Entry>> cells) {
        int added = 0;
        Map<Street, RoadDataBuilder.IntList> extra = new HashMap<>();
        for (Object[] a : osmAddresses) {
            int la = (Integer) a[2], lo = (Integer) a[3];
            Street st = street(normalize((String) a[0]), la, lo);
            if (st == null) continue;
            RoadDataBuilder.IntList l = extra.computeIfAbsent(st, k -> new RoadDataBuilder.IntList(16));
            l.add((Integer) a[1]);
            l.add(la);
            l.add(lo);
            added++;
        }
        for (Map.Entry<Street, RoadDataBuilder.IntList> en : extra.entrySet()) {
            Entry e = en.getKey().entry;
            RoadDataBuilder.IntList l = en.getValue();
            if (e.numbers != null) for (int v : e.numbers) l.add(v);
            e.numbers = simplifyNumbers(Arrays.copyOf(l.a, l.n));
        }
        System.out.println("  endereços do OSM nas ruas: " + added);
    }

    /** Separa os pontos de uma "rua" que na verdade são ruas diferentes com o mesmo nome (longe). */
    static List<int[]> splitByDistance(RoadDataBuilder.IntList l) {
        int n = l.size() / 3;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        // Ordem por latitude e união dos vizinhos a menos de ~1 km (o bastante para não juntar bairros diferentes).
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Integer.compare(l.get(x * 3 + 1), l.get(y * 3 + 1)));
        for (int i = 0; i < n; i++) {
            int a = order[i];
            for (int j = i + 1; j < n; j++) {
                int b = order[j];
                if (l.get(b * 3 + 1) - l.get(a * 3 + 1) > 9000) break;
                if (Math.abs(l.get(b * 3 + 2) - l.get(a * 3 + 2)) > 9000) continue;
                if (find(parent, a) != find(parent, b)) union(parent, a, b);
                break; // o primeiro vizinho basta (os pontos ficam encadeados)
            }
        }
        Map<Integer, RoadDataBuilder.IntList> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            RoadDataBuilder.IntList g = groups.computeIfAbsent(find(parent, i), k -> new RoadDataBuilder.IntList(16));
            g.add(l.get(i * 3));
            g.add(l.get(i * 3 + 1));
            g.add(l.get(i * 3 + 2));
        }
        List<int[]> out = new ArrayList<>();
        for (RoadDataBuilder.IntList g : groups.values()) out.add(Arrays.copyOf(g.a, g.n));
        return out;
    }

    /**
     * Números de uma rua (número, lat, lon, …): ordena, junta os repetidos (o ponto do meio) e deixa só
     * os pontos necessários para a interpolação pelo número errar menos de {@value #NUMBER_ERROR_M} m.
     */
    static int[] simplifyNumbers(int[] in) {
        int n = in.length / 3;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Integer.compare(in[x * 3], in[y * 3]));
        // Repetidos (apartamentos, lojas no mesmo número): a mediana das coordenadas.
        RoadDataBuilder.IntList u = new RoadDataBuilder.IntList(n * 3);
        for (int i = 0; i < n; ) {
            int j = i;
            int num = in[order[i] * 3];
            while (j < n && in[order[j] * 3] == num) j++;
            int[] la = new int[j - i], lo = new int[j - i];
            for (int k = i; k < j; k++) {
                la[k - i] = in[order[k] * 3 + 1];
                lo[k - i] = in[order[k] * 3 + 2];
            }
            Arrays.sort(la);
            Arrays.sort(lo);
            u.add(num);
            u.add(la[la.length / 2]);
            u.add(lo[lo.length / 2]);
            i = j;
        }
        int m = u.size() / 3;
        if (m <= 2) return Arrays.copyOf(u.a, u.n);
        boolean[] keep = new boolean[m];
        keep[0] = keep[m - 1] = true;
        keepNumbers(u, 0, m - 1, keep);
        RoadDataBuilder.IntList out = new RoadDataBuilder.IntList(m);
        for (int i = 0; i < m; i++) {
            if (!keep[i]) continue;
            out.add(u.get(i * 3));
            out.add(u.get(i * 3 + 1));
            out.add(u.get(i * 3 + 2));
        }
        return Arrays.copyOf(out.a, out.n);
    }

    static void keepNumbers(RoadDataBuilder.IntList u, int s, int e, boolean[] keep) {
        if (e <= s + 1) return;
        int ns = u.get(s * 3), ne = u.get(e * 3);
        double k = Math.cos(Math.toRadians(u.get(s * 3 + 1) / 1e6)) * 0.111; // m por 1e-6 grau de longitude
        double worst = -1;
        int wi = -1;
        for (int i = s + 1; i < e; i++) {
            double t = ne == ns ? 0.5 : (u.get(i * 3) - ns) / (double) (ne - ns);
            double la = u.get(s * 3 + 1) + (u.get(e * 3 + 1) - u.get(s * 3 + 1)) * t;
            double lo = u.get(s * 3 + 2) + (u.get(e * 3 + 2) - u.get(s * 3 + 2)) * t;
            double d = Math.hypot((u.get(i * 3 + 1) - la) * 0.111, (u.get(i * 3 + 2) - lo) * k);
            if (d > worst) {
                worst = d;
                wi = i;
            }
        }
        if (worst > NUMBER_ERROR_M) {
            keep[wi] = true;
            keepNumbers(u, s, wi, keep);
            keepNumbers(u, wi, e, keep);
        }
    }

    static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String mostCommon(Map<String, Integer> m) {
        if (m == null) return null;
        String best = null;
        int bc = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() > bc) {
                bc = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    static final Set<String> LOWER = new HashSet<>(Arrays.asList("de", "da", "do", "das", "dos", "e", "a", "o"));

    /** "RUA DOUTOR JOSE DA SILVA" → "Rua Doutor Jose da Silva". */
    static String title(String s) {
        String[] w = s.trim().toLowerCase(new Locale("pt", "BR")).split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            if (w[i].isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (i > 0 && LOWER.contains(w[i])) b.append(w[i]);
            else b.append(Character.toUpperCase(w[i].charAt(0))).append(w[i].substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ contexto

    /**
     * Bairro e cidade de cada entrada: o lugar ({@code place=*}) mais perto. Cidade: city/town até
     * ~25 km, village até ~6 km; bairro: até ~2 km (as ruas do CNEFE já trazem o bairro).
     */
    void context(Map<Long, List<Entry>> cells) {
        List<Entry> cities = new ArrayList<>(), suburbs = new ArrayList<>();
        for (List<Entry> l : cells.values()) {
            for (Entry e : l) {
                if (e.type == CITY) cities.add(e);
                else if (e.type == SUBURB) suburbs.add(e);
            }
        }
        Grid cityGrid = new Grid(cities, 25000), suburbGrid = new Grid(suburbs, 2000);
        for (List<Entry> l : cells.values()) {
            for (Entry e : l) {
                if (e.type == CITY) continue;
                Entry c = cityGrid.nearest(e.lat, e.lon, x -> x.rank < 2 ? 225000 : 54000);
                if (c != null) e.city = c.name;
                if (e.type == SUBURB) continue;
                if (e.suburb == null) {
                    Entry s = suburbGrid.nearest(e.lat, e.lon, x -> 18000);
                    if (s != null) e.suburb = s.name;
                }
                if (e.suburb != null && e.suburb.equalsIgnoreCase(e.city == null ? "" : e.city)) e.suburb = null;
            }
        }
        for (Entry e : cities) {
            // Vila e cidade pequena: o município (a cidade grande mais perto) como contexto.
            if (e.rank == 0) continue;
            Entry c = cityGrid.nearest(e.lat, e.lon, x -> x.rank < e.rank ? 225000 : 0);
            if (c != null && c != e) e.city = c.name;
        }
    }

    interface Radius {
        int of(Entry e); // raio em 1e-6 grau
    }

    /** Grade simples para o mais perto. */
    static final class Grid {
        final Map<Long, List<Entry>> cells = new HashMap<>();
        final int size;

        Grid(List<Entry> list, int sizeE6) {
            size = Math.max(10000, sizeE6);
            for (Entry e : list) cells.computeIfAbsent(k(e.lat / size, e.lon / size), x -> new ArrayList<>()).add(e);
        }

        static long k(int a, int b) {
            return ((long) a << 32) | (b & 0xffffffffL);
        }

        Entry nearest(int lat, int lon, Radius r) {
            Entry best = null;
            double bd = Double.MAX_VALUE;
            int cy = lat / size, cx = lon / size;
            double k = Math.cos(Math.toRadians(lat / 1e6));
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    List<Entry> l = cells.get(k(cy + dy, cx + dx));
                    if (l == null) continue;
                    for (Entry e : l) {
                        double d = Math.hypot(e.lat - lat, (e.lon - lon) * k);
                        if (d > r.of(e)) continue;
                        // Cidade maior "puxa" mais (o centro de uma town fica longe da periferia).
                        double w = d * (e.type == CITY ? 1 + e.rank * 0.8 : 1);
                        if (w < bd) {
                            bd = w;
                            best = e;
                        }
                    }
                }
            }
            return best;
        }
    }

    // ------------------------------------------------------------------ normalização (igual à do app)

    static final Pattern MARKS = Pattern.compile("\\p{M}+");
    static final Map<String, String> ABBR = new HashMap<>();

    static {
        String[] a = {"r", "rua", "av", "avenida", "avda", "avenida", "pc", "praca", "pca", "praca", "tv", "travessa",
            "trav", "travessa", "al", "alameda", "rod", "rodovia", "est", "estrada", "estr", "estrada", "lgo", "largo",
            "lad", "ladeira", "vl", "vila", "jd", "jardim", "jrd", "jardim", "pq", "parque", "cj", "conjunto",
            "conj", "conjunto", "res", "residencial", "dr", "doutor", "dra", "doutora", "prof", "professor",
            "profa", "professora", "pe", "padre", "sto", "santo", "sta", "santa", "gov", "governador",
            "pres", "presidente", "cel", "coronel", "cap", "capitao", "gal", "general", "gen", "general",
            "ten", "tenente", "mal", "marechal", "eng", "engenheiro", "dep", "deputado", "sen", "senador",
            "ver", "vereador", "des", "desembargador", "min", "ministro", "maj", "major", "sgt", "sargento",
            "mons", "monsenhor", "com", "comendador", "cmte", "comandante", "bel", "bacharel", "arq", "arquiteto",
            "pref", "prefeito", "vig", "vigario", "fr", "frei", "ir", "irma", "nsa", "nossa", "sra", "senhora",
            "sr", "senhor", "bl", "bloco"};
        for (int i = 0; i < a.length; i += 2) ABBR.put(a[i], a[i + 1]);
    }

    /** Minúsculas, sem acento, sem pontuação, abreviações por extenso: "R. Dr. José" → "rua doutor jose". */
    static String normalize(String s) {
        if (s == null) return "";
        String t = MARKS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        StringBuilder b = new StringBuilder(t.length());
        StringBuilder w = new StringBuilder();
        for (int i = 0; i <= t.length(); i++) {
            char c = i < t.length() ? t.charAt(i) : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                w.append(c);
                continue;
            }
            if (w.length() == 0) continue;
            String word = w.toString();
            String full = ABBR.get(word);
            if (b.length() > 0) b.append(' ');
            b.append(full == null ? word : full);
            w.setLength(0);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ saída

    void write(Map<Long, List<Entry>> cells, File out, String source, long t0) throws IOException {
        File dir = new File(out, "v1");
        File cdir = new File(dir, "cells");
        if (!cdir.isDirectory() && !cdir.mkdirs()) throw new IOException("não criou " + cdir);
        TreeMap<String, String> index = new TreeMap<>();
        long total = 0;
        int biggest = 0;
        String biggestName = "";
        int[] count = new int[5];
        for (Map.Entry<Long, List<Entry>> en : cells.entrySet()) {
            long k = en.getKey();
            int cx = (int) (k >> 32), cy = (int) k;
            List<Entry> list = dedupe(en.getValue());
            for (Entry e : list) count[e.type]++;
            byte[] data = cell(cx, cy, list);
            String name = cx + "_" + cy;
            try (OutputStream o = new FileOutputStream(new File(cdir, name + ".bin"))) {
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
        RoadDataBuilder.writeIndex(new File(dir, "index.json"), index, System.currentTimeMillis(), source);
        System.out.printf("  entradas: %d ruas, %d POIs, %d cidades, %d bairros%n", count[STREET], count[POI],
            count[CITY], count[SUBURB]);
        RoadDataBuilder.log(t0, "gravado: %d quadrados, %.1f MB (maior %s, %.0f KB)", index.size(),
            total / 1048576.0, biggestName, biggest / 1024.0);
    }

    /** POIs repetidos (mesmo nome a menos de ~200 m, como o do OSM e o do CNEFE): fica o primeiro. */
    static List<Entry> dedupe(List<Entry> list) {
        Map<String, List<Entry>> byName = new HashMap<>();
        List<Entry> out = new ArrayList<>(list.size());
        for (Entry e : list) {
            if (e.type != POI) {
                out.add(e);
                continue;
            }
            String n = normalize(e.name);
            if (n.isEmpty()) continue;
            List<Entry> same = byName.computeIfAbsent(n, k -> new ArrayList<>());
            boolean dup = false;
            for (Entry o : same) {
                if (Math.abs(o.lat - e.lat) < 1800 && Math.abs(o.lon - e.lon) < 1800) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            same.add(e);
            out.add(e);
        }
        return out;
    }

    /**
     * Um quadrado: "K4PL", versão (1 byte) e o resto em deflate. Ver o README (Formato do índice de
     * endereços).
     */
    static byte[] cell(int cx, int cy, List<Entry> list) throws IOException {
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;
        Map<String, Integer> strings = new HashMap<>();
        List<String> table = new ArrayList<>();
        for (Entry e : list) {
            for (String s : new String[]{e.name, e.suburb, e.city}) {
                if (s != null && !strings.containsKey(s)) {
                    strings.put(s, table.size());
                    table.add(s);
                }
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        bytes.write(new byte[]{'K', '4', 'P', 'L', FORMAT});
        Deflater def = new Deflater(9);
        DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bytes, def, 1 << 16));
        o.writeInt(cx);
        o.writeInt(cy);
        RoadDataBuilder.varint(o, table.size());
        for (String s : table) {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            RoadDataBuilder.varint(o, b.length);
            o.write(b);
        }
        RoadDataBuilder.varint(o, list.size());
        for (Entry e : list) {
            o.writeByte(e.type | (e.type == CITY || e.type == POI ? e.rank << 4 : 0));
            RoadDataBuilder.varint(o, strings.get(e.name));
            RoadDataBuilder.varint(o, e.suburb == null ? 0 : strings.get(e.suburb) + 1);
            RoadDataBuilder.varint(o, e.city == null ? 0 : strings.get(e.city) + 1);
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(e.lat - baseLat));
            RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(e.lon - baseLon));
            if (e.type == STREET) {
                int n = e.numbers == null ? 0 : e.numbers.length / 3;
                RoadDataBuilder.varint(o, n);
                int pn = 0, pl = e.lat, po = e.lon;
                for (int i = 0; i < n; i++) {
                    RoadDataBuilder.varint(o, e.numbers[i * 3] - pn);
                    RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(e.numbers[i * 3 + 1] - pl));
                    RoadDataBuilder.varint(o, RoadDataBuilder.zigzag(e.numbers[i * 3 + 2] - po));
                    pn = e.numbers[i * 3];
                    pl = e.numbers[i * 3 + 1];
                    po = e.numbers[i * 3 + 2];
                }
            }
        }
        o.close();
        def.end();
        return bytes.toByteArray();
    }
}
