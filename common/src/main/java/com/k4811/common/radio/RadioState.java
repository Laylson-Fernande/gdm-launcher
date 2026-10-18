package com.k4811.common.radio;

import java.util.Arrays;

/**
 * Immutable (by convention) snapshot of the radio state, delivered to the screen on every change.
 * RadioClient makes a new copy with {@link #copy()} before changing any field.
 */
public final class RadioState implements Cloneable {
    public enum Connection { CONNECTING, CONNECTED, NOT_FOUND, DISCONNECTED }

    public Connection connection = Connection.CONNECTING;
    public int band = Band.FM1;
    public int frequency = 8750;
    public String psName;
    /** 1-based index of the current preset (0 = none). */
    public int presetIndex;
    public String radioText = "";
    /** 0 closed, 1 playing, 2 seeking, 3 scan, 4 intro. */
    public int radioState = 1;
    public int scanState;
    public boolean stereoSignal;
    public boolean stereoOn = true;
    public boolean locOn;
    public boolean afOn;
    public boolean taOn;
    public boolean tp;
    public boolean taActive;
    public int pty;
    public int radioType = -1;
    public BandRange fmRange = BandRange.FM_DEFAULT;
    public BandRange amRange = BandRange.AM_DEFAULT;
    /** Presets per band (index = band 0..4). */
    public Preset[][] presets = new Preset[5][];

    public boolean isFm() {
        return Band.isFm(band);
    }

    public BandRange range() {
        return isFm() ? fmRange : amRange;
    }

    public boolean isBusy() {
        return (radioState >= 2 && radioState <= 4) || scanState > 0;
    }

    public Preset[] currentPresets() {
        Preset[] p = band >= 0 && band < presets.length ? presets[band] : null;
        return p != null ? p : new Preset[0];
    }

    public RadioState copy() {
        try {
            RadioState c = (RadioState) super.clone();
            c.presets = Arrays.copyOf(presets, presets.length);
            return c;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
