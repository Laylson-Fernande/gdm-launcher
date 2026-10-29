package com.k4811.map.route;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

/**
 * Um quadrado do grafo de ruas da rota sem internet (gerador {@code tools/road-data/RouteDataBuilder},
 * formato no README de lá): os cruzamentos que caem nele (nós, ordenados pela coordenada) e os trechos
 * de rua que começam ou terminam neles (arestas, com a geometria). A ponta de uma aresta que fica em
 * outro quadrado vem pela coordenada ({@link #extLat}/{@link #extLon}), achada lá com {@link #find}.
 * Coordenadas em 1e-6 grau.
 */
final class GraphCell {

    // Flags da aresta (iguais às do gerador).
    static final int FWD_ONLY = 1;
    static final int BWD_ONLY = 2;
    static final int ROUNDABOUT = 4;
    static final int TOLL = 8;
    private static final int NAMED = 16;

    private static final int FORMAT = 1;
    private static final int CELL_E6 = 250000;

    final int cx;
    final int cy;
    final String[] names;
    final int nodes;
    final int[] nodeLat;
    final int[] nodeLon;
    final int edges;
    /** Ponta da aresta: índice do nó deste quadrado, ou -1 - k para a ponta de fora k. */
    final int[] edgeA;
    final int[] edgeB;
    final int[] extLat;
    final int[] extLon;
    /** Classe: 1 motorway … 9 service, +16 nas ligações (_link). */
    final byte[] cls;
    final byte[] flags;
    /** Limite (km/h) no sentido do traçado; 0 = desconhecido. */
    final byte[] speed;
    /** Índice em {@link #names}, ou -1. */
    final int[] name;
    /** Pontos do meio de cada aresta em geoLat/geoLon, de geoStart[e] a geoStart[e+1]. */
    final int[] geoStart;
    final int[] geoLat;
    final int[] geoLon;
    /** Comprimento de cada aresta, em metros. */
    final float[] length;
    /** Arestas de cada nó: adj[adjStart[n] … adjStart[n+1]). */
    final int[] adjStart;
    final int[] adj;
    /** Proibições de conversão, ordenadas pelo nó: nó, aresta de chegada, de saída, tipo (0 proibida, 1 obrigatória). */
    final int[] resVia;
    final int[] resFrom;
    final int[] resTo;
    final byte[] resType;

    static GraphCell read(File f) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(f), 16 * 1024);
        try {
            byte[] magic = new byte[5];
            int n = 0;
            while (n < 5) {
                int r = raw.read(magic, n, 5 - n);
                if (r < 0) throw new EOFException();
                n += r;
            }
            if (magic[0] != 'K' || magic[1] != '4' || magic[2] != 'R' || magic[3] != 'T' || magic[4] != FORMAT) {
                throw new IOException("formato desconhecido");
            }
            return new GraphCell(new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw), 32 * 1024)));
        } finally {
            raw.close();
        }
    }

    private GraphCell(DataInputStream in) throws IOException {
        cx = in.readInt();
        cy = in.readInt();
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;
        int nn = varint(in);
        names = new String[nn];
        byte[] buf = new byte[256];
        for (int i = 0; i < nn; i++) {
            int len = varint(in);
            if (buf.length < len) buf = new byte[len * 2];
            in.readFully(buf, 0, len);
            names[i] = new String(buf, 0, len, "UTF-8");
        }
        nodes = varint(in);
        nodeLat = new int[nodes];
        nodeLon = new int[nodes];
        int pl = baseLat, po = baseLon;
        for (int i = 0; i < nodes; i++) {
            pl += unzigzag(varint(in));
            po += unzigzag(varint(in));
            nodeLat[i] = pl;
            nodeLon[i] = po;
        }
        edges = varint(in);
        edgeA = new int[edges];
        edgeB = new int[edges];
        cls = new byte[edges];
        flags = new byte[edges];
        speed = new byte[edges];
        name = new int[edges];
        geoStart = new int[edges + 1];
        length = new float[edges];
        int[] xl = new int[64], xo = new int[64];
        int nx = 0;
        int[] gl = new int[Math.max(16, edges * 2)], go = new int[gl.length];
        int ng = 0;
        for (int e = 0; e < edges; e++) {
            for (int end = 0; end < 2; end++) {
                int v = varint(in);
                int ref;
                if ((v & 1) == 0) {
                    ref = v >> 1;
                } else {
                    if (nx == xl.length) {
                        xl = Arrays.copyOf(xl, nx * 2);
                        xo = Arrays.copyOf(xo, nx * 2);
                    }
                    xl[nx] = baseLat + unzigzag(varint(in));
                    xo[nx] = baseLon + unzigzag(varint(in));
                    ref = -1 - nx;
                    nx++;
                }
                if (end == 0) edgeA[e] = ref;
                else edgeB[e] = ref;
            }
            cls[e] = in.readByte();
            int f = in.readUnsignedByte();
            flags[e] = (byte) f;
            speed[e] = in.readByte();
            name[e] = (f & NAMED) != 0 ? varint(in) : -1;
            int k = varint(in);
            geoStart[e] = ng;
            if (ng + k > gl.length) {
                gl = Arrays.copyOf(gl, Math.max(ng + k, gl.length * 2));
                go = Arrays.copyOf(go, gl.length);
            }
            int ql = edgeA[e] >= 0 ? nodeLat[edgeA[e]] : xl[-1 - edgeA[e]];
            int qo = edgeA[e] >= 0 ? nodeLon[edgeA[e]] : xo[-1 - edgeA[e]];
            for (int j = 0; j < k; j++) {
                ql += unzigzag(varint(in));
                qo += unzigzag(varint(in));
                gl[ng] = ql;
                go[ng] = qo;
                ng++;
            }
        }
        geoStart[edges] = ng;
        geoLat = Arrays.copyOf(gl, ng);
        geoLon = Arrays.copyOf(go, ng);
        extLat = Arrays.copyOf(xl, nx);
        extLon = Arrays.copyOf(xo, nx);

        int nr = varint(in);
        resVia = new int[nr];
        resFrom = new int[nr];
        resTo = new int[nr];
        resType = new byte[nr];
        for (int i = 0; i < nr; i++) {
            resVia[i] = varint(in);
            resFrom[i] = varint(in);
            resTo[i] = varint(in);
            resType[i] = in.readByte();
        }
        sortRestrictions();

        // Comprimentos e lista de arestas de cada nó.
        adjStart = new int[nodes + 1];
        for (int e = 0; e < edges; e++) {
            length[e] = (float) measure(e);
            if (edgeA[e] >= 0) adjStart[edgeA[e] + 1]++;
            if (edgeB[e] >= 0 && edgeB[e] != edgeA[e]) adjStart[edgeB[e] + 1]++;
        }
        for (int i = 0; i < nodes; i++) adjStart[i + 1] += adjStart[i];
        adj = new int[adjStart[nodes]];
        int[] fill = Arrays.copyOf(adjStart, nodes);
        for (int e = 0; e < edges; e++) {
            if (edgeA[e] >= 0) adj[fill[edgeA[e]]++] = e;
            if (edgeB[e] >= 0 && edgeB[e] != edgeA[e]) adj[fill[edgeB[e]]++] = e;
        }
    }

    private void sortRestrictions() {
        int n = resVia.length;
        for (int i = 1; i < n; i++) {
            for (int j = i; j > 0 && resVia[j - 1] > resVia[j]; j--) {
                swap(resVia, j);
                swap(resFrom, j);
                swap(resTo, j);
                byte t = resType[j];
                resType[j] = resType[j - 1];
                resType[j - 1] = t;
            }
        }
    }

    private static void swap(int[] a, int j) {
        int t = a[j];
        a[j] = a[j - 1];
        a[j - 1] = t;
    }

    /** Primeira proibição do nó (ou -1). */
    int firstRestriction(int node) {
        int lo = 0, hi = resVia.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (resVia[mid] < node) lo = mid + 1;
            else {
                if (resVia[mid] == node) found = mid;
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Nó com essa coordenada exata, ou -1. */
    int find(int lat, int lon) {
        int lo = 0, hi = nodes - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = nodeLat[mid] != lat ? Integer.compare(nodeLat[mid], lat) : Integer.compare(nodeLon[mid], lon);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    int latA(int e) {
        return edgeA[e] >= 0 ? nodeLat[edgeA[e]] : extLat[-1 - edgeA[e]];
    }

    int lonA(int e) {
        return edgeA[e] >= 0 ? nodeLon[edgeA[e]] : extLon[-1 - edgeA[e]];
    }

    int latB(int e) {
        return edgeB[e] >= 0 ? nodeLat[edgeB[e]] : extLat[-1 - edgeB[e]];
    }

    int lonB(int e) {
        return edgeB[e] >= 0 ? nodeLon[edgeB[e]] : extLon[-1 - edgeB[e]];
    }

    /** Número de pontos da aresta (pontas incluídas). */
    int points(int e) {
        return geoStart[e + 1] - geoStart[e] + 2;
    }

    /** Ponto {@code i} da aresta, de A (0) a B (points-1). */
    int pointLat(int e, int i) {
        if (i == 0) return latA(e);
        int k = geoStart[e] + i - 1;
        return k < geoStart[e + 1] ? geoLat[k] : latB(e);
    }

    int pointLon(int e, int i) {
        if (i == 0) return lonA(e);
        int k = geoStart[e] + i - 1;
        return k < geoStart[e + 1] ? geoLon[k] : lonB(e);
    }

    private double measure(int e) {
        double d = 0;
        int n = points(e);
        double k = Math.cos(Math.toRadians(latA(e) / 1e6)) * 0.111195;
        for (int i = 1; i < n; i++) {
            double dy = (pointLat(e, i) - pointLat(e, i - 1)) * 0.111195;
            double dx = (pointLon(e, i) - pointLon(e, i - 1)) * k;
            d += Math.sqrt(dx * dx + dy * dy);
        }
        return d;
    }

    /**
     * Identidade de um trecho de rua que não muda quando os dados são gerados de novo (os índices mudam):
     * as coordenadas das duas pontas, sem sentido, e o número de pontos. Serve ao perfil de rotas
     * preferidas ({@link RouteHistory}).
     */
    long key(int e) {
        long a = ((long) latA(e) << 32) | (lonA(e) & 0xffffffffL);
        long b = ((long) latB(e) << 32) | (lonB(e) & 0xffffffffL);
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return mix(lo) * 31 + mix(hi) + points(e);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    // Índice espacial (feito na primeira consulta): 32×32 baldes com as arestas que passam por perto.
    private static final int BUCKETS = 32;
    private static final int BUCKET_E6 = CELL_E6 / BUCKETS;
    private static final int MARGIN_E6 = 400; // ~45 m
    private int[] bucketStart;
    private int[] bucketEdges;

    /** Arestas que podem estar a menos de ~45 m do ponto; devolve quantas (até out.length). */
    synchronized int edgesNear(int lat, int lon, int[] out) {
        if (bucketStart == null) buildIndex();
        int b = bucketOf(lat - (cy * CELL_E6 - 90000000)) * BUCKETS + bucketOf(lon - (cx * CELL_E6 - 180000000));
        int n = Math.min(out.length, bucketStart[b + 1] - bucketStart[b]);
        System.arraycopy(bucketEdges, bucketStart[b], out, 0, n);
        return n;
    }

    private static int bucketOf(int offset) {
        int b = offset < 0 ? -1 : offset / BUCKET_E6;
        return b < 0 ? 0 : b >= BUCKETS ? BUCKETS - 1 : b;
    }

    private void buildIndex() {
        int baseLat = cy * CELL_E6 - 90000000, baseLon = cx * CELL_E6 - 180000000;
        int[] pairs = new int[Math.max(64, edges * 4)];
        int np = 0;
        int[] last = new int[BUCKETS * BUCKETS];
        Arrays.fill(last, -1);
        for (int e = 0; e < edges; e++) {
            int n = points(e);
            for (int i = 0; i + 1 < n; i++) {
                int la0 = pointLat(e, i), lo0 = pointLon(e, i), la1 = pointLat(e, i + 1), lo1 = pointLon(e, i + 1);
                int by0 = bucketOf(Math.min(la0, la1) - MARGIN_E6 - baseLat), by1 = bucketOf(Math.max(la0, la1) + MARGIN_E6 - baseLat);
                int bx0 = bucketOf(Math.min(lo0, lo1) - MARGIN_E6 - baseLon), bx1 = bucketOf(Math.max(lo0, lo1) + MARGIN_E6 - baseLon);
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        int b = by * BUCKETS + bx;
                        if (last[b] == e) continue;
                        last[b] = e;
                        if (np + 2 > pairs.length) pairs = Arrays.copyOf(pairs, pairs.length * 2);
                        pairs[np++] = b;
                        pairs[np++] = e;
                    }
                }
            }
        }
        int[] start = new int[BUCKETS * BUCKETS + 1];
        for (int i = 0; i < np; i += 2) start[pairs[i] + 1]++;
        for (int b = 0; b < BUCKETS * BUCKETS; b++) start[b + 1] += start[b];
        int[] list = new int[np / 2];
        int[] fill = Arrays.copyOf(start, BUCKETS * BUCKETS);
        for (int i = 0; i < np; i += 2) list[fill[pairs[i]]++] = pairs[i + 1];
        bucketEdges = list;
        bucketStart = start;
    }

    /**
     * Ponto da aresta mais perto de (lat, lon): {distância em m, rumo do trecho no sentido A→B}.
     * {@code k} = cos(latitude).
     */
    double[] nearest(int e, int lat, int lon, double k) {
        double best = Double.MAX_VALUE, brg = 0;
        int n = points(e);
        for (int i = 0; i + 1 < n; i++) {
            double ax = (pointLon(e, i) - lon) * 0.111195 * k, ay = (pointLat(e, i) - lat) * 0.111195;
            double bx = (pointLon(e, i + 1) - lon) * 0.111195 * k, by = (pointLat(e, i + 1) - lat) * 0.111195;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double d = Math.hypot(ax + dx * t, ay + dy * t);
            if (d < best) {
                best = d;
                brg = Math.toDegrees(Math.atan2(dx, dy));
            }
        }
        return new double[]{best, (brg + 360) % 360};
    }

    /** Memória aproximada, em bytes (para o cache). */
    int memoryBytes() {
        return nodes * 8 + edges * 39 + geoLat.length * 8 + adj.length * 4 + extLat.length * 8 + names.length * 60;
    }

    private static int varint(DataInputStream in) throws IOException {
        int r = 0;
        int s = 0;
        while (true) {
            int b = in.readUnsignedByte();
            r |= (b & 0x7f) << s;
            if ((b & 0x80) == 0) return r;
            s += 7;
        }
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }
}
