package com.k4811.map.road;

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
 * Um quadrado de {@value #CELL_DEG}° dos dados das vias, lido do arquivo gerado por
 * {@code tools/road-data} (formato descrito lá e em RoadDataBuilder.cell): vias com o limite de
 * velocidade e pontos de alerta (radares, lombadas, pedágios, passagens de nível).
 *
 * Coordenadas em 1e-6 grau (int). As vias ficam num índice de 32×32 baldes (~870 m) com uma
 * margem de ~40 m, para achar as vias perto do carro olhando um balde só.
 */
public final class RoadCell {

    public static final double CELL_DEG = 0.25;
    static final int CELL_E6 = 250000;
    private static final int FORMAT = 1;

    // Tipos de ponto (iguais aos do gerador).
    public static final int SPEED_CAMERA = 1;
    public static final int RED_LIGHT_CAMERA = 2;
    public static final int TRAFFIC_CALMING = 3;
    public static final int TOLL_BOOTH = 4;
    public static final int LEVEL_CROSSING = 5;

    private static final int BUCKETS = 32;
    private static final int BUCKET_E6 = CELL_E6 / BUCKETS;
    private static final int MARGIN_E6 = 400;

    public final int cx;
    public final int cy;

    final int ways;
    final byte[] wayOneway;
    final int[] waySpeedFwd;
    final int[] waySpeedBwd;
    /** Primeiro ponto de cada via em {@link #lat}/{@link #lon}; wayStart[ways] = total. */
    final int[] wayStart;
    final int[] lat;
    final int[] lon;

    final int points;
    final int[] pointType;
    final int[] pointSpeed;
    final int[] pointDir;
    final int[] pointLat;
    final int[] pointLon;

    private final int[] bucketStart;
    private final int[] bucketWays;

    public static int cellX(double lon) {
        return (int) Math.floor((lon + 180.0) / CELL_DEG);
    }

    public static int cellY(double lat) {
        return (int) Math.floor((lat + 90.0) / CELL_DEG);
    }

    public static String name(int cx, int cy) {
        return cx + "_" + cy;
    }

    /** Lê o arquivo; lança IOException se estiver corrompido ou num formato desconhecido. */
    static RoadCell read(File f) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(f), 16 * 1024);
        try {
            byte[] magic = new byte[5];
            int n = 0;
            while (n < 5) {
                int r = raw.read(magic, n, 5 - n);
                if (r < 0) throw new EOFException();
                n += r;
            }
            if (magic[0] != 'K' || magic[1] != '4' || magic[2] != 'R' || magic[3] != 'D' || magic[4] != FORMAT) {
                throw new IOException("formato desconhecido");
            }
            DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw), 32 * 1024));
            return new RoadCell(in);
        } finally {
            raw.close();
        }
    }

    private RoadCell(DataInputStream in) throws IOException {
        cx = in.readInt();
        cy = in.readInt();
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;

        ways = varint(in);
        wayOneway = new byte[ways];
        waySpeedFwd = new int[ways];
        waySpeedBwd = new int[ways];
        wayStart = new int[ways + 1];
        int[] la = new int[Math.max(16, ways * 6)];
        int[] lo = new int[la.length];
        int total = 0;
        for (int w = 0; w < ways; w++) {
            in.readUnsignedByte(); // classe da via (ainda não usada)
            wayOneway[w] = in.readByte();
            waySpeedFwd[w] = in.readUnsignedByte();
            waySpeedBwd[w] = in.readUnsignedByte();
            int count = varint(in);
            wayStart[w] = total;
            if (total + count > la.length) {
                int size = Math.max(total + count, la.length + (la.length >> 1));
                la = Arrays.copyOf(la, size);
                lo = Arrays.copyOf(lo, size);
            }
            int pl = baseLat, po = baseLon;
            for (int i = 0; i < count; i++) {
                pl += unzigzag(varint(in));
                po += unzigzag(varint(in));
                la[total] = pl;
                lo[total] = po;
                total++;
            }
        }
        wayStart[ways] = total;
        lat = la;
        lon = lo;

        points = varint(in);
        pointType = new int[points];
        pointSpeed = new int[points];
        pointDir = new int[points];
        pointLat = new int[points];
        pointLon = new int[points];
        for (int p = 0; p < points; p++) {
            pointType[p] = in.readUnsignedByte();
            pointSpeed[p] = in.readUnsignedByte();
            pointDir[p] = in.readShort();
            pointLat[p] = baseLat + unzigzag(varint(in));
            pointLon[p] = baseLon + unzigzag(varint(in));
        }

        // Índice: cada via entra nos baldes que os trechos dela (com a margem) tocam.
        int[] pairs = new int[Math.max(64, ways * 4)];
        int np = 0;
        int[] lastWay = new int[BUCKETS * BUCKETS];
        Arrays.fill(lastWay, -1);
        for (int w = 0; w < ways; w++) {
            for (int i = wayStart[w]; i < wayStart[w + 1]; i++) {
                int j = i + 1 < wayStart[w + 1] ? i + 1 : i;
                int bx0 = bucketOf(Math.min(lon[i], lon[j]) - MARGIN_E6 - baseLon);
                int bx1 = bucketOf(Math.max(lon[i], lon[j]) + MARGIN_E6 - baseLon);
                int by0 = bucketOf(Math.min(lat[i], lat[j]) - MARGIN_E6 - baseLat);
                int by1 = bucketOf(Math.max(lat[i], lat[j]) + MARGIN_E6 - baseLat);
                for (int by = by0; by <= by1; by++) {
                    for (int bx = bx0; bx <= bx1; bx++) {
                        int b = by * BUCKETS + bx;
                        if (lastWay[b] == w) continue;
                        lastWay[b] = w;
                        if (np + 2 > pairs.length) pairs = Arrays.copyOf(pairs, pairs.length * 2);
                        pairs[np++] = b;
                        pairs[np++] = w;
                    }
                }
            }
        }
        bucketStart = new int[BUCKETS * BUCKETS + 1];
        for (int i = 0; i < np; i += 2) bucketStart[pairs[i] + 1]++;
        for (int b = 0; b < BUCKETS * BUCKETS; b++) bucketStart[b + 1] += bucketStart[b];
        bucketWays = new int[np / 2];
        int[] fill = Arrays.copyOf(bucketStart, BUCKETS * BUCKETS);
        for (int i = 0; i < np; i += 2) bucketWays[fill[pairs[i]]++] = pairs[i + 1];
    }

    private static int bucketOf(int offsetE6) {
        int b = offsetE6 / BUCKET_E6;
        if (offsetE6 < 0) b = -1;
        return b < 0 ? 0 : b >= BUCKETS ? BUCKETS - 1 : b;
    }

    /** Vias que podem estar a menos de ~40 m do ponto: índices em {@code out}; devolve quantas. */
    int waysNear(int latE6, int lonE6, int[] out) {
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;
        int b = bucketOf(latE6 - baseLat) * BUCKETS + bucketOf(lonE6 - baseLon);
        int n = Math.min(out.length, bucketStart[b + 1] - bucketStart[b]);
        System.arraycopy(bucketWays, bucketStart[b], out, 0, n);
        return n;
    }

    /** Memória aproximada, em bytes (para o cache). */
    int memoryBytes() {
        return lat.length * 8 + ways * 13 + points * 20 + bucketWays.length * 4;
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
