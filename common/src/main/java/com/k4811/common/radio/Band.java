package com.k4811.common.radio;

import java.util.Locale;

/** NWD service bands (0..2 = FM1..FM3, 3..4 = AM1..AM2) and frequency formatting. */
public final class Band {
    private Band() {
    }

    public static final int FM1 = 0;
    public static final int FM2 = 1;
    public static final int FM3 = 2;
    public static final int AM1 = 3;
    public static final int AM2 = 4;

    public static final int[] SELECTABLE = {FM1, FM2, FM3, AM1, AM2};
    public static final String[] LABELS = {"FM1", "FM2", "FM3", "AM1", "AM2"};

    public static boolean isFm(int band) {
        return band >= 0 && band <= 2;
    }

    public static String label(int band) {
        return band >= 0 && band < LABELS.length ? LABELS[band] : "B" + band;
    }

    /** FM in 10 kHz units (8750 = 87.50 MHz); AM in kHz. */
    public static String formatValue(int freq, boolean fm) {
        if (!fm) return String.valueOf(freq);
        if (freq % 10 == 0) return String.format(Locale.US, "%.1f", freq / 100f);
        return String.format(Locale.US, "%.2f", freq / 100f);
    }

    public static String unit(boolean fm) {
        return fm ? "MHz" : "kHz";
    }

    /** RDS flags (bitmask) used by setRDSState/getRDSState. */
    public static final int RDS_AF = 1;
    public static final int RDS_TA = 2;
    public static final int RDS_PTY = 4;
    public static final int RDS_TP = 8;
}
