package com.garagemdomadeira.nwd.launcher.radio;

/** A service memory (preset): 0-based index, band, frequency and RDS name. */
public final class Preset {
    public final int index;
    public final int band;
    public final int frequency;
    public final String psName;

    public Preset(int index, int band, int frequency, String psName) {
        this.index = index;
        this.band = band;
        this.frequency = frequency;
        this.psName = psName;
    }

    public boolean isEmpty() {
        return frequency <= 0;
    }
}
