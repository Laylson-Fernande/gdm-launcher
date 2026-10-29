package com.k4811.map.search;

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
 * Um quadrado do índice de endereços (gerador {@code tools/road-data/PlaceDataBuilder}, formato no README
 * de lá): ruas (com os números de casa conhecidos), POIs (parques, escolas, lojas…), cidades e bairros,
 * cada um com o bairro e a cidade em volta. Coordenadas em 1e-6 grau.
 */
final class PlaceCell {

    static final int STREET = 1;
    static final int POI = 2;
    static final int CITY = 3;
    static final int SUBURB = 4;
    private static final int FORMAT = 1;
    private static final int CELL_E6 = 250000;

    final int cx;
    final int cy;
    /** Os textos como vieram (UTF-8, um atrás do outro; ver {@link #string}): só os mostrados viram String. */
    private final byte[] text;
    private final int[] textStart;
    /** Cada texto normalizado ({@link Text#normalize}) com um espaço na frente: " rua doutor jose". */
    final String[] norm;
    final int count;
    final byte[] type;
    /** Lugares: 0 cidade, 1 cidade pequena, 2 vila/povoado. POIs: importância, 0 a 3. */
    final byte[] rank;
    final int[] name;
    /** Índice em {@link #strings} + 1 (0 = nenhum). */
    final int[] suburb;
    final int[] city;
    final int[] lat;
    final int[] lon;
    /** Ruas: os números em {@link #numbers} (número, lat, lon) de numStart[i] a numStart[i+1]. */
    final int[] numStart;
    final int[] numbers;

    static PlaceCell read(File f) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(f), 16 * 1024);
        try {
            byte[] magic = new byte[5];
            int n = 0;
            while (n < 5) {
                int r = raw.read(magic, n, 5 - n);
                if (r < 0) throw new EOFException();
                n += r;
            }
            if (magic[0] != 'K' || magic[1] != '4' || magic[2] != 'P' || magic[3] != 'L' || magic[4] != FORMAT) {
                throw new IOException("formato desconhecido");
            }
            return new PlaceCell(new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw), 32 * 1024)));
        } finally {
            raw.close();
        }
    }

    private PlaceCell(DataInputStream in) throws IOException {
        cx = in.readInt();
        cy = in.readInt();
        int baseLat = cy * CELL_E6 - 90000000;
        int baseLon = cx * CELL_E6 - 180000000;
        int ns = varint(in);
        norm = new String[ns];
        textStart = new int[ns + 1];
        byte[] all = new byte[Math.max(64, ns * 24)];
        int used = 0;
        for (int i = 0; i < ns; i++) {
            int len = varint(in);
            if (used + len > all.length) all = Arrays.copyOf(all, Math.max(used + len, all.length * 2));
            in.readFully(all, used, len);
            textStart[i] = used;
            norm[i] = " " + Text.normalize(new String(all, used, len, "UTF-8"));
            used += len;
        }
        textStart[ns] = used;
        text = Arrays.copyOf(all, used);
        count = varint(in);
        type = new byte[count];
        rank = new byte[count];
        name = new int[count];
        suburb = new int[count];
        city = new int[count];
        lat = new int[count];
        lon = new int[count];
        numStart = new int[count + 1];
        int[] nums = new int[Math.max(16, count)];
        int nn = 0;
        for (int i = 0; i < count; i++) {
            int t = in.readUnsignedByte();
            type[i] = (byte) (t & 15);
            rank[i] = (byte) (t >> 4);
            name[i] = varint(in);
            suburb[i] = varint(in);
            city[i] = varint(in);
            lat[i] = baseLat + unzigzag(varint(in));
            lon[i] = baseLon + unzigzag(varint(in));
            numStart[i] = nn;
            if (type[i] == STREET) {
                int k = varint(in);
                if (nn + k * 3 > nums.length) nums = Arrays.copyOf(nums, Math.max(nn + k * 3, nums.length * 2));
                int pn = 0, pl = lat[i], po = lon[i];
                for (int j = 0; j < k; j++) {
                    pn += varint(in);
                    pl += unzigzag(varint(in));
                    po += unzigzag(varint(in));
                    nums[nn++] = pn;
                    nums[nn++] = pl;
                    nums[nn++] = po;
                }
            }
        }
        numStart[count] = nn;
        numbers = Arrays.copyOf(nums, nn);
    }

    /** O texto {@code i} como veio (com acentos). */
    String string(int i) {
        try {
            return new String(text, textStart[i], textStart[i + 1] - textStart[i], "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Memória aproximada, em bytes (para o cache). */
    int memoryBytes() {
        int s = text.length + textStart.length * 4;
        for (String t : norm) s += 40 + t.length() * 2;
        return s + count * 30 + numbers.length * 4;
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
