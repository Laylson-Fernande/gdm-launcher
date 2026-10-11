package com.garagemdomadeira.nwd.launcher.eq;

/** Binary frames for the ARM DSP. */
public final class DspFrames {

    private static final byte TYPE_AUDIO = 0x0D;
    private static final int DATA_OFFSET = 5;

    public static final int LOUDNESS = 0x01;
    public static final int ONE_EQ = 0x07;
    public static final int PHASE = 0x09;
    public static final int LEVEL = 0x0A;
    public static final int DELAY = 0x0B;
    public static final int ALL_EQ = 0xF1;
    public static final int SITE = 0xF9;

    private DspFrames() {
    }

    private static byte[] frame(int dataType, byte[] payload) {
        int len = payload.length + 3;
        byte[] out = new byte[len + 3];
        out[0] = (byte) 0xF0;
        out[1] = (byte) len;
        out[2] = TYPE_AUDIO;
        out[3] = (byte) dataType;
        System.arraycopy(payload, 0, out, DATA_OFFSET, payload.length);
        int sum = 0;
        for (int i = 1; i < out.length - 1; i++) sum += out[i];
        out[out.length - 1] = (byte) sum;
        return out;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    public static byte[] loudness(boolean on) {
        return frame(LOUDNESS, bytes(17, on ? 1 : 0));
    }

    public static byte[] site(int fader, int balance) {
        return frame(SITE, bytes(
            clamp(fader, 0, EqModel.SITE_MAX),
            clamp(balance, 0, EqModel.SITE_MAX)));
    }

    public static byte[] oneEq(int band, int freq, int q, int gainWire) {
        int qq = q == 0 ? 10 : q;
        return frame(ONE_EQ, bytes(
            band, freq & 0xFF, (freq >> 8) & 0xFF, qq & 0xFF, (qq >> 8) & 0xFF, gainWire));
    }

    public static byte[] allEq(int[] freqs, int[] qs, int[] gainsWire) {
        int n = freqs.length;
        byte[] payload = new byte[1 + n * 5];
        payload[0] = (byte) n;
        for (int i = 0; i < n; i++) {
            int q = qs[i] == 0 ? EqModel.EQ32_DEFAULT_Q : qs[i];
            int base = 1 + i * 5;
            payload[base] = (byte) (freqs[i] & 0xFF);
            payload[base + 1] = (byte) ((freqs[i] >> 8) & 0xFF);
            payload[base + 2] = (byte) (q & 0xFF);
            payload[base + 3] = (byte) ((q >> 8) & 0xFF);
            payload[base + 4] = (byte) gainsWire[i];
        }
        return frame(ALL_EQ, payload);
    }

    public static byte[] level(int channel, int attenuation) {
        return frame(LEVEL, bytes(channel, clamp(attenuation, 0, 15)));
    }

    public static byte[] phase(int channel, boolean inverted) {
        return frame(PHASE, bytes(channel, inverted ? 1 : 0));
    }

    public static byte[] delay(int channel, int tenthsMs) {
        int d = clamp(tenthsMs, 0, 200);
        return frame(DELAY, bytes(channel, d & 0xFF, (d >> 8) & 0xFF));
    }

    public static int gainToWire(int db, int eqMax) {
        int g = clamp(db, EqModel.EQ32_MIN_DB, EqModel.EQ32_MAX_DB);
        return clamp(Math.round((g + 12) * eqMax / 24f), 0, eqMax);
    }

    public static int wireToGain(int wire, int eqMax) {
        return clamp((int) Math.ceil(wire * 24.0 / eqMax) - 12, EqModel.EQ32_MIN_DB, EqModel.EQ32_MAX_DB);
    }

    public static String toHex(byte[] frame) {
        StringBuilder sb = new StringBuilder(frame.length * 2);
        for (byte b : frame) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
