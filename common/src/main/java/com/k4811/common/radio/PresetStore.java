package com.k4811.common.radio;

import android.content.Context;
import android.util.Log;

import com.k4811.common.system.SystemProps;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Radio memories and the last station listened to, kept in {@code K4811/radio/radio.config.json}
 * next to the station art ({@link StationArt}). The NWD service forgets both when the head unit
 * powers off; this file is the source of truth. It's meant to be readable and editable by hand:
 * <pre>
 * {
 *   "version": 1,
 *   "last": { "band": "FM1", "frequency": 99.1 },
 *   "bands": {
 *     "FM1": { "M1": 104.5, "M2": 99.1, "M7": 87.55 },
 *     "AM1": { "M1": 1040 }
 *   }
 * }
 * </pre>
 * Bands FM1–FM3 and AM1–AM2; memories M1–M12 (a missing one is empty); FM in MHz and AM in kHz,
 * the same text as the screen and the art file names. Internally frequencies stay in app units
 * (FM in 10 kHz, AM in kHz).
 *
 * Reads come from memory and reload the file when its date changes (edited by hand). Writes update
 * memory at once and save in the background (temporary file, then rename). A file that doesn't
 * parse is never overwritten: until it's fixed, changes stay in memory only. The first time, with
 * no file yet, the memories are imported from the old props {@code persist.k4811.*}.
 */
public final class PresetStore {
    private PresetStore() {
    }

    public static final int SLOTS = 12;
    private static final String TAG = "K4811Radio";
    private static final String FILE_NAME = "radio.config.json";
    private static final int VERSION = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Old storage, only read to import into the file. */
    private static final String OLD_PREFIX = "persist.k4811.";

    private static final Object LOCK = new Object();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    private static final int[][] freqs = new int[Band.LABELS.length][SLOTS];
    private static int[] last;
    /** File the memories came from (or go to); null until located. */
    private static File file;
    /** lastModified of the file when it was read or written; -1 = not loaded yet. */
    private static long stamp = -1;
    /** Saves queued but not on disk yet: the file is older than memory, don't reload it. */
    private static int pendingSaves;
    private static boolean broken;

    /** The band's 12 frequencies; 0 = empty slot. */
    public static int[] read(int band) {
        synchronized (LOCK) {
            load();
            return valid(band) ? freqs[band].clone() : new int[SLOTS];
        }
    }

    public static int read(int band, int slot) {
        synchronized (LOCK) {
            load();
            return valid(band) && slot >= 0 && slot < SLOTS ? freqs[band][slot] : 0;
        }
    }

    public static boolean hasAny(int[] freqs) {
        for (int f : freqs) {
            if (f > 0) return true;
        }
        return false;
    }

    /** Writes the slot (0 = clear). Does nothing if it already has that value. */
    public static void write(Context context, int band, int slot, int frequency) {
        synchronized (LOCK) {
            load();
            int value = Math.max(0, frequency);
            if (!valid(band) || slot < 0 || slot >= SLOTS || freqs[band][slot] == value) return;
            freqs[band][slot] = value;
            save();
        }
    }

    /** Writes the band's 12 slots (frequencies missing from the array become 0). */
    public static void writeBand(Context context, int band, int[] values) {
        synchronized (LOCK) {
            load();
            if (!valid(band)) return;
            boolean changed = false;
            for (int i = 0; i < SLOTS; i++) {
                int v = i < values.length ? Math.max(0, values[i]) : 0;
                if (freqs[band][i] != v) {
                    freqs[band][i] = v;
                    changed = true;
                }
            }
            if (changed) save();
        }
    }

    /** Last station listened to: {band, frequency}, or null if none was saved. */
    public static int[] readLast() {
        synchronized (LOCK) {
            load();
            return last == null ? null : last.clone();
        }
    }

    /** Saves the last station listened to. Does nothing if it is already saved. */
    public static void writeLast(Context context, int band, int frequency) {
        if (!valid(band) || frequency <= 0) return;
        synchronized (LOCK) {
            load();
            if (last != null && last[0] == band && last[1] == frequency) return;
            last = new int[]{band, frequency};
            save();
        }
    }

    /** true while the file can't be parsed (edited by hand with a mistake): changes aren't saved. */
    public static boolean isBroken() {
        synchronized (LOCK) {
            load();
            return broken;
        }
    }

    /** Where the memories are (or will be) saved, for messages. */
    public static File file() {
        synchronized (LOCK) {
            load();
            return file;
        }
    }

    // ------------------------------------------------------------------ file

    /** Reads the file the first time and whenever it changed on disk. Caller holds LOCK. */
    private static void load() {
        if (pendingSaves > 0) return;
        File found = locate();
        if (found == null) {
            if (stamp < 0) {
                stamp = 0;
                importOldProps();
            }
            return;
        }
        long modified = found.lastModified();
        if (found.equals(file) && modified == stamp) return;
        file = found;
        stamp = modified;
        try {
            parse(readText(found));
            broken = false;
        } catch (Exception e) {
            broken = true;
            Log.w(TAG, "Invalid " + found + ": " + e.getMessage());
        }
    }

    /** First existing radio.config.json, in the same folder order as the station art. */
    private static File locate() {
        for (File dir : StationArt.dirs()) {
            File f = new File(dir, FILE_NAME);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static void parse(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        int[][] next = new int[Band.LABELS.length][SLOTS];
        JSONObject bands = root.optJSONObject("bands");
        if (bands != null) {
            Iterator<String> names = bands.keys();
            while (names.hasNext()) {
                String name = names.next();
                int band = band(name);
                JSONObject slots = bands.optJSONObject(name);
                if (band < 0 || slots == null) continue;
                for (int i = 0; i < SLOTS; i++) {
                    next[band][i] = toUnits(band, slots.optDouble("M" + (i + 1), 0));
                }
            }
        }
        int[] nextLast = null;
        JSONObject l = root.optJSONObject("last");
        if (l != null) {
            int band = band(l.optString("band", ""));
            int freq = band < 0 ? 0 : toUnits(band, l.optDouble("frequency", 0));
            if (freq > 0) nextLast = new int[]{band, freq};
        }
        for (int b = 0; b < next.length; b++) freqs[b] = next[b];
        last = nextLast;
    }

    /** Queues the current memory to the file (unless it's broken). Caller holds LOCK. */
    private static void save() {
        if (broken) return;
        final String text = toJson();
        pendingSaves++;
        writer.execute(() -> {
            File written = writeFile(text);
            synchronized (LOCK) {
                pendingSaves--;
                if (written != null) {
                    file = written;
                    stamp = written.lastModified();
                }
            }
        });
    }

    /** Writes next to the current file, or in the first folder that accepts it. Returns the file or null. */
    private static File writeFile(String text) {
        List<File> targets = new ArrayList<>();
        File current;
        synchronized (LOCK) {
            current = file;
        }
        if (current != null) targets.add(current);
        for (File dir : StationArt.dirs()) {
            File f = new File(dir, FILE_NAME);
            if (!targets.contains(f)) targets.add(f);
        }
        for (File target : targets) {
            File dir = target.getParentFile();
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) continue;
            File tmp = new File(dir, FILE_NAME + ".tmp");
            OutputStream out = null;
            try {
                out = new FileOutputStream(tmp);
                out.write(text.getBytes(UTF8));
                out.close();
                out = null;
                if (tmp.renameTo(target)) return target;
            } catch (Exception e) {
                Log.w(TAG, "Can't write " + target + ": " + e.getMessage());
            } finally {
                close(out);
            }
            tmp.delete();
        }
        return null;
    }

    /** Readable and stable output: fixed key order, one band per line. Caller holds LOCK. */
    private static String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": ").append(VERSION).append(",\n");
        if (last != null) {
            sb.append("  \"last\": { \"band\": \"").append(Band.label(last[0]))
                .append("\", \"frequency\": ").append(format(last[0], last[1])).append(" },\n");
        }
        sb.append("  \"bands\": {\n");
        for (int b = 0; b < Band.LABELS.length; b++) {
            sb.append("    \"").append(Band.label(b)).append("\": {");
            boolean first = true;
            for (int i = 0; i < SLOTS; i++) {
                if (freqs[b][i] <= 0) continue;
                sb.append(first ? " " : ", ").append("\"M").append(i + 1).append("\": ")
                    .append(format(b, freqs[b][i]));
                first = false;
            }
            sb.append(first ? "}" : " }").append(b < Band.LABELS.length - 1 ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    /** First run without a file: brings the memories and last station from the old props. */
    private static void importOldProps() {
        boolean any = false;
        for (int b = 0; b < Band.LABELS.length; b++) {
            for (int i = 0; i < SLOTS; i++) {
                int v = parseInt(SystemProps.get(OLD_PREFIX + Band.label(b).toLowerCase(Locale.US) + ".radio" + (i + 1), ""));
                freqs[b][i] = v;
                any |= v > 0;
            }
        }
        String v = SystemProps.get(OLD_PREFIX + "radio.last", "").trim();
        int sep = v.indexOf(':');
        if (sep > 0) {
            int band = band(v.substring(0, sep));
            int freq = parseInt(v.substring(sep + 1));
            if (band >= 0 && freq > 0) {
                last = new int[]{band, freq};
                any = true;
            }
        }
        if (any) {
            Log.i(TAG, "Importing radio memories from the old props into " + FILE_NAME);
            save();
        }
    }

    // ------------------------------------------------------------------ util

    private static boolean valid(int band) {
        return band >= 0 && band < Band.LABELS.length;
    }

    private static int band(String label) {
        String l = label.trim().toUpperCase(Locale.US);
        for (int b = 0; b < Band.LABELS.length; b++) {
            if (Band.LABELS[b].equals(l)) return b;
        }
        return -1;
    }

    /** File value (FM in MHz, AM in kHz) to app units (FM in 10 kHz, AM in kHz). */
    private static int toUnits(int band, double value) {
        if (value <= 0 || Double.isNaN(value)) return 0;
        return (int) Math.round(Band.isFm(band) ? value * 100 : value);
    }

    private static String format(int band, int frequency) {
        return Band.formatValue(frequency, Band.isFm(band));
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, UTF8);
        } finally {
            close(in);
        }
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
