package com.k4811.map.vector;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Uma seção de um arquivo do mapa offline, já lida: as feições com tipo, flags, nome, partes e
 * coordenadas absolutas do mundo (Web Mercator, mundo = 2^26 unidades) e o retângulo de cada uma.
 *
 * Arquivo ({@code K4811/nav/maps/cells/<nome>.bin}, gerado por tools/road-data/MapDataBuilder):
 * "K4MP", versão, tipo (0 quadrado, 1 visão geral), coluna, linha (int), nSeções (byte) e o tamanho
 * de cada seção (int), seguidos das seções, cada uma em deflate separado. Num quadrado: seção 0 =
 * simplificada (zooms 10–12), 1..64 = base dos blocos 8×8, 65..128 = detalhe dos blocos (prédios,
 * caminhos). Na visão geral: só a seção 0. Formato da seção em tools/road-data/README.md.
 */
public final class MapSection {

    // Tipos (iguais aos do gerador).
    public static final int MOTORWAY = 1, TRUNK = 2, PRIMARY = 3, SECONDARY = 4, TERTIARY = 5, MINOR = 6,
        LIVING = 7, SERVICE = 8, PEDESTRIAN = 9, TRACK = 10, PATH = 11;
    public static final int RAIL = 20, LIGHT_RAIL = 21, TRAM = 22;
    public static final int RIVER = 30, STREAM = 31;
    public static final int BOUNDARY_STATE = 40, BOUNDARY_CITY = 41;
    public static final int WATER = 50, OCEAN = 51, PARK = 52, FOREST = 53, SAND = 54, BUILDING = 55;
    public static final int CITY = 60, TOWN = 61, VILLAGE = 62, SUBURB = 63, NEIGHBOURHOOD = 64;

    public static final int F_NAME = 1, F_TUNNEL = 2, F_BRIDGE = 4, F_LINK = 8, F_AREA = 16;

    static final int FORMAT = 1;
    public static final int BLOCKS = 8;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public final int count;
    public final byte[] type;
    public final byte[] flags;
    /** Nome (índice em {@link #names}) ou -1. */
    public final int[] name;
    public final String[] names;
    /** Partes da feição i: de partStart[i] a partStart[i+1]. */
    public final int[] partStart;
    /** Pontos da parte k: de ptStart[k] a ptStart[k+1] (índices de ponto em xy, 2 ints cada). */
    public final int[] ptStart;
    public final int[] xy;
    /** Retângulo de cada feição: minX, minY, maxX, maxY. */
    public final int[] box;

    private MapSection(int count, byte[] type, byte[] flags, int[] name, String[] names, int[] partStart,
                       int[] ptStart, int[] xy, int[] box) {
        this.count = count;
        this.type = type;
        this.flags = flags;
        this.name = name;
        this.names = names;
        this.partStart = partStart;
        this.ptStart = ptStart;
        this.xy = xy;
        this.box = box;
    }

    /** Memória aproximada (para o limite do cache). */
    public int memoryBytes() {
        return count * 30 + ptStart.length * 4 + xy.length * 4 + names.length * 40 + 64;
    }

    /** Lê a seção {@code index} do arquivo; null se o arquivo não tem essa seção. */
    static MapSection read(File f, int index) throws IOException {
        byte[] packed;
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[15];
            in.readFully(head);
            if (head[0] != 'K' || head[1] != '4' || head[2] != 'M' || head[3] != 'P') throw new IOException("não é K4MP");
            if (head[4] != FORMAT) throw new IOException("versão " + head[4]);
            int n = head[14] & 0xff;
            if (index >= n) return null;
            long offset = 15 + 4L * n;
            int len = 0;
            for (int i = 0; i <= index; i++) {
                int l = in.readInt();
                if (i < index) offset += l;
                else len = l;
            }
            if (len == 0) return null;
            packed = new byte[len];
            in.seek(offset);
            in.readFully(packed);
        }
        return parse(inflate(packed));
    }

    private static byte[] inflate(byte[] packed) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(packed);
            byte[] out = new byte[Math.max(1024, packed.length * 3)];
            int n = 0;
            while (!inf.finished()) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                int r = inf.inflate(out, n, out.length - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
            return n == out.length ? out : java.util.Arrays.copyOf(out, n);
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
    }

    private static MapSection parse(byte[] b) throws IOException {
        Reader r = new Reader(b);
        int shift = b[r.p++] & 0xff;
        int ox = r.int32();
        int oy = r.int32();
        int nNames = r.varint();
        String[] names = new String[nNames];
        for (int i = 0; i < nNames; i++) {
            int l = r.varint();
            names[i] = new String(b, r.p, l, UTF8);
            r.p += l;
        }
        int count = r.varint();
        byte[] type = new byte[count];
        byte[] flags = new byte[count];
        int[] name = new int[count];
        int[] partStart = new int[count + 1];
        int[] box = new int[count * 4];
        IntBuf pts = new IntBuf(count * 2 + 16);
        IntBuf xy = new IntBuf(Math.max(64, b.length));
        int parts = 0;
        for (int i = 0; i < count; i++) {
            type[i] = b[r.p++];
            int fl = b[r.p++] & 0xff;
            flags[i] = (byte) fl;
            name[i] = (fl & F_NAME) != 0 ? r.varint() : -1;
            partStart[i] = parts;
            int np = r.varint();
            int px = ox >> shift, py = oy >> shift;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int k = 0; k < np; k++) {
                pts.add(xy.n / 2);
                parts++;
                int n = r.varint();
                for (int j = 0; j < n; j++) {
                    px += r.zigzag();
                    py += r.zigzag();
                    int x = px << shift, y = py << shift;
                    xy.add(x);
                    xy.add(y);
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            box[i * 4] = minX;
            box[i * 4 + 1] = minY;
            box[i * 4 + 2] = maxX;
            box[i * 4 + 3] = maxY;
        }
        partStart[count] = parts;
        pts.add(xy.n / 2);
        return new MapSection(count, type, flags, name, names, partStart, pts.toArray(), xy.toArray(), box);
    }

    /** Leitor de varints sobre o array. */
    private static final class Reader {
        final byte[] b;
        int p;

        Reader(byte[] b) {
            this.b = b;
        }

        int varint() throws IOException {
            int r = 0, s = 0;
            while (true) {
                if (p >= b.length) throw new IOException("fim da seção");
                byte x = b[p++];
                r |= (x & 0x7f) << s;
                if (x >= 0) return r;
                s += 7;
            }
        }

        int zigzag() throws IOException {
            int v = varint();
            return (v >>> 1) ^ -(v & 1);
        }

        int int32() {
            int v = ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
            p += 4;
            return v;
        }
    }

    private static final class IntBuf {
        int[] a;
        int n;

        IntBuf(int cap) {
            a = new int[cap];
        }

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n + (n >> 1) + 16);
            a[n++] = v;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
