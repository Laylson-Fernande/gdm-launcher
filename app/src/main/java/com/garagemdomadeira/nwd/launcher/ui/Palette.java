package com.garagemdomadeira.nwd.launcher.ui;

/**
 * Color tokens of the current theme. Grayscale comes in two variants: the default (the original
 * monochrome look, black background) and {@link #GRAY_TINTED} (graphite background). The other 12
 * are derived from a hue: 6 accents on a near-black background ("black" variant) and the same 6
 * accents on a background tinted with that hue ("tinted" variant).
 *
 * Derivation happens in OKLCH (perceptual lightness/chroma/hue), so every theme keeps the same
 * lightness per token: backgrounds stay dark and text stays readable whatever the hue. Semantic
 * colors (danger, warning, success) never change.
 */
public final class Palette {

    /** Theme index for the original monochrome look (grayscale, black background). */
    public static final int DEFAULT = -1;
    /** Number of hues; themes 0..HUES-1 are "black", HUES..2*HUES-1 are "tinted". */
    public static final int HUES = 6;
    /** Grayscale with a graphite (lighter) background. */
    public static final int GRAY_TINTED = HUES * 2;
    /** Valid themes: DEFAULT and 0..THEMES-1. */
    public static final int THEMES = GRAY_TINTED + 1;

    /** Accent per hue (OKLCH lightness, chroma, hue): blue, violet, red, orange, yellow, green. */
    private static final double[][] ACCENTS = {
        {0.72, 0.15, 255},
        {0.72, 0.16, 300},
        {0.68, 0.19, 25},
        {0.76, 0.16, 55},
        {0.88, 0.16, 95},
        {0.78, 0.17, 150},
    };

    // Semantic colors: identical in every theme.
    public static final int DANGER = 0xFFFF6B6B;
    public static final int WARNING = 0xFFFFC857;
    public static final int SUCCESS = 0xFF5BD18B;

    public final int bg;
    public final int bgSoft;
    public final int surface;
    public final int surfaceHigh;
    public final int surfaceTop;
    public final int pressed;
    public final int hairline;
    public final int hairlineStrong;
    public final int outline;
    public final int outlineStrong;
    public final int textDim;
    public final int textMuted;
    public final int textSoft;
    public final int text;
    public final int accent;
    /** Accent at low opacity: fills of active chips/pills. */
    public final int accentFill;
    /** Accent at very low opacity: subtle highlights. */
    public final int accentDim;

    /** Default (monochrome) values. Also the lightness reference for derived themes. */
    private static final int[] BASE = {
        0xFF050608, // bg
        0xFF0B0D10, // bgSoft
        0xFF15181C, // surface
        0xFF22262B, // surfaceHigh
        0xFF2B3036, // surfaceTop
        0xFF14171B, // pressed
        0xFF1C1F24, // hairline
        0xFF2C3036, // hairlineStrong
        0xFF353A41, // outline
        0xFF555B63, // outlineStrong
        0xFF5D636B, // textDim
        0xFF8D949D, // textMuted
        0xFFB4B7BC, // textSoft
        0xFFE9EBEE, // text
    };
    /** Chroma of each neutral token in the "tinted" variant (same order as BASE). */
    private static final double[] TINT = {
        0.022, 0.024, 0.030, 0.032, 0.032, 0.030, 0.030, 0.032, 0.032, 0.030, 0.028, 0.022, 0.014, 0.008,
    };
    /** Lightness lift of the backgrounds in the "tinted" variant, so the hue is visible. */
    private static final double[] LIFT = {
        0.035, 0.035, 0.03, 0.025, 0.02, 0.025, 0.025, 0.02, 0.015, 0.0, 0.0, 0.0, 0.0, 0.0,
    };

    private Palette(int[] n, int accent) {
        bg = n[0];
        bgSoft = n[1];
        surface = n[2];
        surfaceHigh = n[3];
        surfaceTop = n[4];
        pressed = n[5];
        hairline = n[6];
        hairlineStrong = n[7];
        outline = n[8];
        outlineStrong = n[9];
        textDim = n[10];
        textMuted = n[11];
        textSoft = n[12];
        text = n[13];
        this.accent = accent;
        accentFill = (accent & 0x00FFFFFF) | 0x1F000000;
        accentDim = (accent & 0x00FFFFFF) | 0x26000000;
    }

    public static Palette of(int theme) {
        if (theme == GRAY_TINTED) {
            int[] n = new int[BASE.length];
            for (int i = 0; i < BASE.length; i++) {
                n[i] = Oklch.toColor(Math.min(1, Oklch.lightness(BASE[i]) + LIFT[i] * 1.6), 0, 0);
            }
            return new Palette(n, 0xFFFFFFFF);
        }
        if (theme < 0 || theme >= THEMES) return new Palette(BASE.clone(), 0xFFFFFFFF);
        boolean tinted = theme >= HUES;
        double[] a = ACCENTS[theme % HUES];
        int[] n = new int[BASE.length];
        for (int i = 0; i < BASE.length; i++) {
            double l = Oklch.lightness(BASE[i]);
            n[i] = tinted
                ? Oklch.toColor(Math.min(1, l + LIFT[i]), TINT[i], a[2])
                // "Black": neutral grays with just a trace of the hue.
                : Oklch.toColor(l, Math.min(0.006, TINT[i]), a[2]);
        }
        return new Palette(n, Oklch.toColor(a[0], a[1], a[2]));
    }

    /** Swatch shown in Settings: the accent for "black" themes, a deep shade for "tinted" ones. */
    public static int swatch(int theme) {
        if (theme == DEFAULT) return 0xFFD5D8DC;
        if (theme == GRAY_TINTED) return Oklch.toColor(0.40, 0, 0);
        double[] a = ACCENTS[theme % HUES];
        return theme < HUES ? Oklch.toColor(a[0], a[1], a[2]) : Oklch.toColor(a[0] * 0.55, a[1] * 0.75, a[2]);
    }

    /**
     * Minimal OKLCH ↔ sRGB conversion (Björn Ottosson's Oklab). Colors outside the sRGB gamut keep
     * their lightness and hue and lose chroma until they fit.
     */
    static final class Oklch {
        private Oklch() {
        }

        static double lightness(int color) {
            double r = toLinear((color >> 16) & 0xFF);
            double g = toLinear((color >> 8) & 0xFF);
            double b = toLinear(color & 0xFF);
            double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
            double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
            double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
            return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        }

        static int toColor(double lightness, double chroma, double hueDeg) {
            double h = Math.toRadians(hueDeg);
            double c = chroma;
            double r = 0, g = 0, b = 0;
            for (int i = 0; i < 60; i++) {
                double A = c * Math.cos(h);
                double B = c * Math.sin(h);
                double l = cube(lightness + 0.3963377774 * A + 0.2158037573 * B);
                double m = cube(lightness - 0.1055613458 * A - 0.0638541728 * B);
                double s = cube(lightness - 0.0894841775 * A - 1.2914855480 * B);
                r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
                g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
                b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
                if (inGamut(r) && inGamut(g) && inGamut(b)) break;
                c *= 0.94;
            }
            return 0xFF000000 | (toSrgb(r) << 16) | (toSrgb(g) << 8) | toSrgb(b);
        }

        private static boolean inGamut(double v) {
            return v >= -0.0005 && v <= 1.0005;
        }

        private static double cube(double v) {
            return v * v * v;
        }

        private static double toLinear(int c) {
            double v = c / 255.0;
            return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }

        private static int toSrgb(double v) {
            v = Math.max(0, Math.min(1, v));
            double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            return (int) Math.round(s * 255);
        }
    }
}
