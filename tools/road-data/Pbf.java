import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Leitor mínimo de arquivos .osm.pbf (formato do OpenStreetMap), sem bibliotecas: lê os blobs,
 * descompacta (zlib) e decodifica o protobuf à mão. Só o que o gerador usa: nós (densos ou não),
 * vias e relações com as tags.
 *
 * Formato: https://wiki.openstreetmap.org/wiki/PBF_Format
 */
final class Pbf {

    interface Handler {
        /** Nó; {@code tags} é null quando não tem tag (a maioria). */
        void node(long id, double lat, double lon, String[] tags);

        void way(long id, long[] refs, int count, String[] tags);

        /** Membros: ids, tipos (0 nó, 1 via, 2 relação) e papéis. */
        void relation(long id, long[] ids, int[] types, String[] roles, int count, String[] tags);
    }

    static final int NODES = 1;
    static final int WAYS = 2;
    static final int RELATIONS = 4;

    private Pbf() {
    }

    /**
     * Lê o arquivo e entrega só os tipos pedidos em {@code want}. Devolve, por blob, os tipos
     * que ele contém; {@code onlyBlobs} (se não for null) diz quais blobs abrir (os outros são
     * pulados sem descompactar).
     */
    static byte[] read(String path, int want, byte[] onlyBlobs, Handler h) throws IOException {
        byte[] kinds = new byte[1024];
        int blob = 0;
        Inflater inflater = new Inflater();
        byte[] raw = new byte[1 << 16];
        byte[] data = new byte[1 << 20];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
            while (true) {
                int headerLen;
                try {
                    headerLen = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                byte[] header = new byte[headerLen];
                in.readFully(header);
                String type = null;
                int dataSize = 0;
                PB hb = new PB(header, 0, headerLen);
                while (hb.more()) {
                    int t = hb.tag();
                    if (t == (1 << 3 | 2)) {
                        int l = hb.len();
                        type = new String(header, hb.p, l, StandardCharsets.UTF_8);
                        hb.p += l;
                    } else if (t == (3 << 3)) {
                        dataSize = (int) hb.varint();
                    } else {
                        hb.skip(t & 7);
                    }
                }
                if (blob >= kinds.length) kinds = java.util.Arrays.copyOf(kinds, kinds.length * 2);
                boolean open = "OSMData".equals(type) && (onlyBlobs == null || (blob < onlyBlobs.length && onlyBlobs[blob] != 0));
                if (!open) {
                    in.skipNBytes(dataSize);
                    if (onlyBlobs != null && blob < onlyBlobs.length) kinds[blob] = onlyBlobs[blob];
                    blob++;
                    continue;
                }
                if (raw.length < dataSize) raw = new byte[dataSize];
                in.readFully(raw, 0, dataSize);
                int size = unpack(raw, dataSize, inflater, data);
                if (size > data.length) {
                    data = new byte[size];
                    size = unpack(raw, dataSize, inflater, data);
                }
                kinds[blob] = (byte) block(data, size, want, h);
                blob++;
            }
        } finally {
            inflater.end();
        }
        return java.util.Arrays.copyOf(kinds, blob);
    }

    /** Conteúdo do Blob; devolve o tamanho (se maior que {@code out}, chame de novo com um maior). */
    private static int unpack(byte[] raw, int len, Inflater inflater, byte[] out) throws IOException {
        PB pb = new PB(raw, 0, len);
        int rawSize = -1;
        int zStart = -1, zLen = 0;
        while (pb.more()) {
            int t = pb.tag();
            int f = t >>> 3;
            if (f == 1 && (t & 7) == 2) {
                int l = pb.len();
                if (l <= out.length) System.arraycopy(raw, pb.p, out, 0, l);
                return l;
            } else if (f == 2 && (t & 7) == 0) {
                rawSize = (int) pb.varint();
            } else if (f == 3 && (t & 7) == 2) {
                zLen = pb.len();
                zStart = pb.p;
                pb.p += zLen;
            } else {
                pb.skip(t & 7);
            }
        }
        if (zStart < 0) throw new IOException("blob sem zlib (lzma/zstd não suportados)");
        if (rawSize > out.length) return rawSize;
        inflater.reset();
        inflater.setInput(raw, zStart, zLen);
        try {
            int n = 0;
            while (n < rawSize) {
                int r = inflater.inflate(out, n, rawSize - n);
                if (r == 0 && (inflater.finished() || inflater.needsInput())) break;
                n += r;
            }
            return n;
        } catch (DataFormatException e) {
            throw new IOException(e);
        }
    }

    /** PrimitiveBlock; devolve os tipos encontrados. */
    private static int block(byte[] d, int len, int want, Handler h) {
        PB pb = new PB(d, 0, len);
        String[] strings = null;
        long granularity = 100, latOff = 0, lonOff = 0;
        int[] groups = new int[32];
        int ng = 0;
        while (pb.more()) {
            int t = pb.tag();
            int f = t >>> 3;
            if (f == 1 && (t & 7) == 2) {
                int l = pb.len();
                strings = strings(d, pb.p, l);
                pb.p += l;
            } else if (f == 2 && (t & 7) == 2) {
                int l = pb.len();
                if (ng + 2 > groups.length) groups = java.util.Arrays.copyOf(groups, groups.length * 2);
                groups[ng++] = pb.p;
                groups[ng++] = l;
                pb.p += l;
            } else if (f == 17 && (t & 7) == 0) {
                granularity = pb.varint();
            } else if (f == 19 && (t & 7) == 0) {
                latOff = pb.varint();
            } else if (f == 20 && (t & 7) == 0) {
                lonOff = pb.varint();
            } else {
                pb.skip(t & 7);
            }
        }
        int kinds = 0;
        for (int g = 0; g < ng; g += 2) {
            PB gp = new PB(d, groups[g], groups[g + 1]);
            while (gp.more()) {
                int t = gp.tag();
                int f = t >>> 3;
                int l = gp.len();
                if (f == 1) {
                    kinds |= NODES;
                    if ((want & NODES) != 0) node(d, gp.p, l, strings, granularity, latOff, lonOff, h);
                } else if (f == 2) {
                    kinds |= NODES;
                    if ((want & NODES) != 0) dense(d, gp.p, l, strings, granularity, latOff, lonOff, h);
                } else if (f == 3) {
                    kinds |= WAYS;
                    if ((want & WAYS) != 0) way(d, gp.p, l, strings, h);
                } else if (f == 4) {
                    kinds |= RELATIONS;
                    if ((want & RELATIONS) != 0) relation(d, gp.p, l, strings, h);
                }
                gp.p += l;
            }
        }
        return kinds;
    }

    private static String[] strings(byte[] d, int off, int len) {
        PB pb = new PB(d, off, len);
        String[] out = new String[256];
        int n = 0;
        while (pb.more()) {
            int t = pb.tag();
            if (t == (1 << 3 | 2)) {
                int l = pb.len();
                if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                out[n++] = new String(d, pb.p, l, StandardCharsets.UTF_8);
                pb.p += l;
            } else {
                pb.skip(t & 7);
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static void node(byte[] d, int off, int len, String[] s, long gran, long latOff, long lonOff, Handler h) {
        PB pb = new PB(d, off, len);
        long id = 0, lat = 0, lon = 0;
        int ks = -1, kl = 0, vs = -1, vl = 0;
        while (pb.more()) {
            int t = pb.tag();
            int f = t >>> 3;
            if (f == 1) id = pb.sint();
            else if (f == 8) lat = pb.sint();
            else if (f == 9) lon = pb.sint();
            else if (f == 2 && (t & 7) == 2) { kl = pb.len(); ks = pb.p; pb.p += kl; }
            else if (f == 3 && (t & 7) == 2) { vl = pb.len(); vs = pb.p; pb.p += vl; }
            else pb.skip(t & 7);
        }
        h.node(id, 1e-9 * (latOff + gran * lat), 1e-9 * (lonOff + gran * lon), keyVals(d, ks, kl, vs, vl, s));
    }

    private static void dense(byte[] d, int off, int len, String[] s, long gran, long latOff, long lonOff, Handler h) {
        PB pb = new PB(d, off, len);
        int is = 0, il = 0, las = 0, lal = 0, los = 0, lol = 0, kvs = -1, kvl = 0;
        while (pb.more()) {
            int t = pb.tag();
            int f = t >>> 3;
            if ((t & 7) != 2) { pb.skip(t & 7); continue; }
            int l = pb.len();
            if (f == 1) { is = pb.p; il = l; }
            else if (f == 8) { las = pb.p; lal = l; }
            else if (f == 9) { los = pb.p; lol = l; }
            else if (f == 10) { kvs = pb.p; kvl = l; }
            pb.p += l;
        }
        PB ids = new PB(d, is, il), lats = new PB(d, las, lal), lons = new PB(d, los, lol);
        PB kv = kvs < 0 ? null : new PB(d, kvs, kvl);
        long id = 0, lat = 0, lon = 0;
        String[] buf = new String[16];
        while (ids.more()) {
            id += ids.sint();
            lat += lats.sint();
            lon += lons.sint();
            String[] tags = null;
            if (kv != null && kv.more()) {
                int n = 0;
                while (kv.more()) {
                    int k = (int) kv.varint();
                    if (k == 0) break;
                    int v = (int) kv.varint();
                    if (n + 2 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[n++] = s[k];
                    buf[n++] = s[v];
                }
                if (n > 0) tags = java.util.Arrays.copyOf(buf, n);
            }
            h.node(id, 1e-9 * (latOff + gran * lat), 1e-9 * (lonOff + gran * lon), tags);
        }
    }

    private static long[] refsBuf = new long[2048];

    private static void way(byte[] d, int off, int len, String[] s, Handler h) {
        PB pb = new PB(d, off, len);
        long id = 0;
        int ks = -1, kl = 0, vs = -1, vl = 0;
        int n = 0;
        while (pb.more()) {
            int t = pb.tag();
            int f = t >>> 3;
            if (f == 1 && (t & 7) == 0) id = pb.varint();
            else if (f == 2 && (t & 7) == 2) { kl = pb.len(); ks = pb.p; pb.p += kl; }
            else if (f == 3 && (t & 7) == 2) { vl = pb.len(); vs = pb.p; pb.p += vl; }
            else if (f == 8 && (t & 7) == 2) {
                int l = pb.len();
                PB r = new PB(d, pb.p, l);
                long ref = 0;
                while (r.more()) {
                    ref += r.sint();
                    if (n == refsBuf.length) refsBuf = java.util.Arrays.copyOf(refsBuf, n * 2);
                    refsBuf[n++] = ref;
                }
                pb.p += l;
            } else pb.skip(t & 7);
        }
        h.way(id, refsBuf, n, keyVals(d, ks, kl, vs, vl, s));
    }

    private static void relation(byte[] d, int off, int len, String[] s, Handler h) {
        PB pb = new PB(d, off, len);
        long id = 0;
        int ks = -1, kl = 0, vs = -1, vl = 0;
        int rs = -1, rl = 0, ms = -1, ml = 0, ts = -1, tl = 0;
        while (pb.more()) {
            int t = pb.tag();
            int f = t >>> 3;
            if (f == 1 && (t & 7) == 0) id = pb.varint();
            else if ((t & 7) == 2 && (f == 2 || f == 3 || f == 8 || f == 9 || f == 10)) {
                int l = pb.len();
                if (f == 2) { ks = pb.p; kl = l; }
                else if (f == 3) { vs = pb.p; vl = l; }
                else if (f == 8) { rs = pb.p; rl = l; }
                else if (f == 9) { ms = pb.p; ml = l; }
                else { ts = pb.p; tl = l; }
                pb.p += l;
            } else pb.skip(t & 7);
        }
        String[] tags = keyVals(d, ks, kl, vs, vl, s);
        if (tags == null || ms < 0) return;
        long[] ids = new long[64];
        int[] types = new int[64];
        String[] roles = new String[64];
        int n = 0;
        PB m = new PB(d, ms, ml), r = new PB(d, rs, rl), ty = new PB(d, ts, tl);
        long mid = 0;
        while (m.more()) {
            mid += m.sint();
            if (n == ids.length) {
                ids = java.util.Arrays.copyOf(ids, n * 2);
                types = java.util.Arrays.copyOf(types, n * 2);
                roles = java.util.Arrays.copyOf(roles, n * 2);
            }
            ids[n] = mid;
            roles[n] = rs < 0 ? "" : s[(int) r.varint()];
            types[n] = ts < 0 ? 0 : (int) ty.varint();
            n++;
        }
        h.relation(id, ids, types, roles, n, tags);
    }

    private static String[] keyVals(byte[] d, int ks, int kl, int vs, int vl, String[] s) {
        if (ks < 0 || kl == 0) return null;
        PB k = new PB(d, ks, kl), v = new PB(d, vs, vl);
        String[] out = new String[16];
        int n = 0;
        while (k.more()) {
            if (n + 2 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            out[n++] = s[(int) k.varint()];
            out[n++] = s[(int) v.varint()];
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Valor da tag {@code key} em {@code tags} (pares chave, valor), ou null. */
    static String tag(String[] tags, String key) {
        if (tags == null) return null;
        for (int i = 0; i < tags.length; i += 2) if (key.equals(tags[i])) return tags[i + 1];
        return null;
    }

    /** Leitor de protobuf sobre um trecho de um array. */
    static final class PB {
        final byte[] b;
        int p;
        final int end;

        PB(byte[] b, int off, int len) {
            this.b = b;
            p = off;
            end = off + len;
        }

        boolean more() {
            return p < end;
        }

        long varint() {
            long r = 0;
            int s = 0;
            while (true) {
                byte x = b[p++];
                r |= (long) (x & 0x7f) << s;
                if (x >= 0) return r;
                s += 7;
            }
        }

        int tag() {
            return (int) varint();
        }

        int len() {
            return (int) varint();
        }

        long sint() {
            long v = varint();
            return (v >>> 1) ^ -(v & 1);
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: {
                    int l = len(); // p += len() leria p antes de len() avançar
                    p += l;
                    break;
                }
                case 5: p += 4; break;
                default: throw new IllegalStateException("wire type " + wireType);
            }
        }
    }
}
