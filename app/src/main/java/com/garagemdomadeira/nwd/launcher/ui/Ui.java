package com.garagemdomadeira.nwd.launcher.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

/** Layout colors, fonts and scale. The design is done at 1280×720 "dp". */
public final class Ui {
    private Ui() {
    }

    public static final int DESIGN_WIDTH = 1280;
    public static final int DESIGN_HEIGHT = 720;

    // Palette: near-black graphite, grays and a single cyan accent.
    public static final int BG = 0xFF0B0D10;
    public static final int SURFACE = 0xFF15181C;
    public static final int SURFACE_HIGH = 0xFF22262B;
    public static final int SURFACE_TOP = 0xFF2B3036;
    public static final int OUTLINE = 0xFF353A41;
    public static final int OUTLINE_STRONG = 0xFF555B63;
    public static final int TEXT = 0xFFE9EBEE;
    public static final int TEXT_MUTED = 0xFF8D949D;
    public static final int TEXT_DIM = 0xFF5D636B;
    public static final int ACCENT = 0xFFFFFFFF;
    public static final int ACCENT_DIM = 0x26FFFFFF;
    public static final int DANGER = 0xFFFF6B6B;

    private static Typeface regular;
    private static Typeface medium;
    private static Typeface semiBold;

    public static Typeface regular(Context c) {
        if (regular == null) regular = load(c, "fonts/Exo2-400.ttf");
        return regular;
    }

    public static Typeface medium(Context c) {
        if (medium == null) medium = load(c, "fonts/Exo2-500.ttf");
        return medium;
    }

    public static Typeface semiBold(Context c) {
        if (semiBold == null) semiBold = load(c, "fonts/Exo2-600.ttf");
        return semiBold;
    }

    private static Typeface load(Context c, String path) {
        try {
            return Typeface.createFromAsset(c.getApplicationContext().getAssets(), path);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    /** Applies the medium font to every TextView in the tree (specific ones are adjusted later). */
    public static void applyFont(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setTypeface(medium(v.getContext()));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyFont(g.getChildAt(i));
        }
    }

    /**
     * Applies the language chosen in Settings to the context and app resources. English lives in
     * values/ (the default); every other language in values-<tag>/.
     */
    public static void applyLanguage(Context c) {
        String tag = com.garagemdomadeira.nwd.launcher.home.LauncherPrefs.language(c);
        Locale locale = "pt".equals(tag) ? new Locale("pt", "BR") : new Locale(tag);
        Locale.setDefault(locale);
        setLocale(c.getResources(), locale);
        setLocale(c.getApplicationContext().getResources(), locale);
    }

    @SuppressWarnings("deprecation")
    private static void setLocale(Resources res, Locale locale) {
        Configuration config = new Configuration(res.getConfiguration());
        if (locale.equals(config.locale)) return;
        config.locale = locale;
        if (android.os.Build.VERSION.SDK_INT >= 17) config.setLayoutDirection(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    /**
     * Makes 1 dp = 1 pixel of a 1280×720 layout, whatever the head unit's density.
     * Also ignores the system font scale.
     */
    public static void applyDesignDensity(Activity a) {
        // Every screen calls this before setContentView: the right place to apply the language too.
        applyLanguage(a);
        Display d = a.getWindowManager().getDefaultDisplay();
        Point size = new Point();
        d.getRealSize(size);
        int w = Math.max(size.x, size.y);
        int h = Math.min(size.x, size.y);
        float density = Math.min(w / (float) DESIGN_WIDTH, h / (float) DESIGN_HEIGHT);
        set(a.getResources().getDisplayMetrics(), density);
        set(a.getApplicationContext().getResources().getDisplayMetrics(), density);
    }

    private static void set(DisplayMetrics m, float density) {
        m.density = density;
        m.scaledDensity = density;
        m.densityDpi = Math.round(density * DisplayMetrics.DENSITY_DEFAULT);
    }

    /** Fullscreen: hides the status and navigation bars and draws beneath them. */
    @SuppressWarnings("deprecation")
    public static void hideSystemBars(android.view.Window window) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }
}
