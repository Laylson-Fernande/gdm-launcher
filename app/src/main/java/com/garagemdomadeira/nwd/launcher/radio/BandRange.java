package com.garagemdomadeira.nwd.launcher.radio;

/** Band limits: minimum, maximum and step (same units as the frequency). */
public final class BandRange {
    public final int min;
    public final int max;
    public final int step;

    public BandRange(int min, int max, int step) {
        this.min = min;
        this.max = max;
        this.step = step;
    }

    // Fallback (NWD service "Américas" region) until the service reports the real values.
    public static final BandRange FM_DEFAULT = new BandRange(8750, 10790, 10);
    public static final BandRange AM_DEFAULT = new BandRange(530, 1710, 10);

    public boolean isValid() {
        return min > 0 && max > min && step > 0;
    }

    public int snap(int freq) {
        int clamped = Math.max(min, Math.min(max, freq));
        int steps = Math.round((clamped - min) / (float) step);
        return Math.max(min, Math.min(max, min + steps * step));
    }

    @Override
    public String toString() {
        return min + ".." + max + "/" + step;
    }
}
