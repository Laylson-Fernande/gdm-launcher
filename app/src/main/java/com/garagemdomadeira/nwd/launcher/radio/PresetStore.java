package com.garagemdomadeira.nwd.launcher.radio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.garagemdomadeira.nwd.launcher.system.SystemProps;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mirror of the radio memories in props {@code persist.com.garagemdomadeira.<banda>.radio<n>}.
 * The NWD service forgets the memories when the head unit powers off; the props are the source
 * of truth.
 *
 * Value = frequency in app units (FM in 10 kHz, AM in kHz); empty or "0" = free slot.
 * Writes go through the NWD channel ({@link SystemProps#requestSet}), which is not immediate: the
 * requested value is kept in an in-memory cache so subsequent reads already see it.
 */
public final class PresetStore {
    private PresetStore() {
    }

    public static final int SLOTS = 12;
    private static final String PREFIX = "persist.com.garagemdomadeira.";
    /** Interval between consecutive writes: the NWD channel uses a single key in Settings.System. */
    private static final long WRITE_GAP_MS = 120;

    private static final Map<String, Integer> pending = new HashMap<>();
    private static final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean draining;

    /** @param slot 0-based (M1 = 0). */
    public static String key(int band, int slot) {
        return PREFIX + Band.label(band).toLowerCase(Locale.US) + ".radio" + (slot + 1);
    }

    /** The band's 12 frequencies; 0 = empty slot. */
    public static int[] read(int band) {
        int[] out = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) out[i] = read(band, i);
        return out;
    }

    public static int read(int band, int slot) {
        String k = key(band, slot);
        synchronized (pending) {
            Integer v = pending.get(k);
            if (v != null) return v;
        }
        return parse(SystemProps.get(k, ""));
    }

    public static boolean hasAny(int[] freqs) {
        for (int f : freqs) {
            if (f > 0) return true;
        }
        return false;
    }

    /** Writes the slot (0 = clear). Does nothing if it already has that value. */
    public static void write(Context context, int band, int slot, int frequency) {
        int value = Math.max(0, frequency);
        if (read(band, slot) == value) return;
        String k = key(band, slot);
        synchronized (pending) {
            pending.put(k, value);
        }
        Context app = context.getApplicationContext();
        main.post(() -> {
            queue.add(new String[]{k, String.valueOf(value)});
            drain(app);
        });
    }

    /** Writes the band's 12 slots (frequencies missing from the array become 0). */
    public static void writeBand(Context context, int band, int[] freqs) {
        for (int i = 0; i < SLOTS; i++) write(context, band, i, i < freqs.length ? freqs[i] : 0);
    }

    private static void drain(Context app) {
        if (draining) return;
        String[] next = queue.poll();
        if (next == null) return;
        draining = true;
        SystemProps.requestSet(app, next[0], next[1]);
        main.postDelayed(() -> {
            draining = false;
            drain(app);
        }, WRITE_GAP_MS);
    }

    private static int parse(String s) {
        if (s == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
