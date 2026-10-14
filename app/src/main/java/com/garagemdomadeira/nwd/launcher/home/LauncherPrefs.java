package com.garagemdomadeira.nwd.launcher.home;

import android.content.Context;
import android.content.SharedPreferences;

import com.garagemdomadeira.nwd.launcher.ui.DockView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Launcher preferences (Settings screen), read by the home screen in onResume. */
public final class LauncherPrefs {
    private LauncherPrefs() {
    }

    /** Package of the stock launcher (factory value of persist.nwd.launcher.default). */
    public static final String ORIGINAL_LAUNCHER = "com.android.launcher";

    private static final String FILE = "launcher_prefs";
    private static final String KEY_VOLTMETER = "show_voltmeter";
    /** Old "show volume controls" switch; only read to pick the initial home layout. */
    private static final String KEY_VOLUME = "show_volume_controls";
    private static final String KEY_HOME_LAYOUT = "home_layout";
    private static final String KEY_HOME_IMAGE = "home_image";
    private static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    private static final String KEY_MUSIC_FOLDER = "music_folder";
    private static final String KEY_ASK_DEFAULT = "ask_default_launcher";
    private static final String KEY_DOCK_TOGGLES = "dock_toggles";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_FONT = "font";
    private static final String KEY_THEME = "theme";

    /** App languages (language tags), in the order shown in Settings. To add one, append its tag
     *  here, its name to {@code LANGUAGE_NAMES} and create {@code res/values-<tag>/}. */
    public static final String[] LANGUAGES = {"pt", "en", "es", "fr", "ru", "ja", "zh", "hi"};
    /** Each language's name written in itself (with a reference flag), so someone who can't read
     *  the current one finds theirs. "zh" is Mandarin in Simplified Chinese. */
    public static final String[] LANGUAGE_NAMES = {
        "🇧🇷 Português", "🇺🇸 English", "🇪🇸 Español", "🇫🇷 Français",
        "🇷🇺 Русский", "🇯🇵 日本語", "🇨🇳 中文（普通话）", "🇮🇳 हिन्दी",
    };
    public static final String DEFAULT_LANGUAGE = "en";

    /** App fonts: file prefix in assets/fonts ({@code <id>-400/500/600.ttf}), in the order shown in Settings. */
    public static final String[] FONTS = {"Exo2", "Montserrat", "Rajdhani", "Outfit", "Inter", "PublicSans"};
    public static final String[] FONT_NAMES = {"Exo 2", "Montserrat", "Rajdhani", "Outfit", "Inter", "Public Sans"};
    public static final String DEFAULT_FONT = "Exo2";

    /** Maximum optional items in the dock (Home and Apps are fixed and not counted). */
    public static final int MAX_DOCK_TOGGLES = 5;
    public static final DockView.Item[] DEFAULT_DOCK = {
        DockView.Item.RADIO, DockView.Item.MUSIC, DockView.Item.BT_MUSIC, DockView.Item.PHONE, DockView.Item.EQ,
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean showVoltmeter(Context c) {
        return prefs(c).getBoolean(KEY_VOLTMETER, true);
    }

    public static void setShowVoltmeter(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_VOLTMETER, on).apply();
    }

    /**
     * Slot of each home module; modules missing from the map are hidden. With nothing saved, the
     * original home: now playing on the left, clock and volume in the center, fan and Bluetooth
     * on the right (volume only if the old "show volume controls" switch was on).
     */
    public static Map<HomeGrid.Module, HomeGrid.Slot> homeLayout(Context c) {
        Map<HomeGrid.Module, HomeGrid.Slot> out = new EnumMap<>(HomeGrid.Module.class);
        String raw = prefs(c).getString(KEY_HOME_LAYOUT, null);
        if (raw == null) {
            out.put(HomeGrid.Module.NOW_PLAYING, HomeGrid.Slot.LEFT);
            out.put(HomeGrid.Module.CLOCK, HomeGrid.Slot.CENTER_TOP);
            if (prefs(c).getBoolean(KEY_VOLUME, true)) out.put(HomeGrid.Module.VOLUME, HomeGrid.Slot.CENTER_BOTTOM);
            out.put(HomeGrid.Module.FAN, HomeGrid.Slot.RIGHT_TOP);
            out.put(HomeGrid.Module.BLUETOOTH, HomeGrid.Slot.RIGHT_MIDDLE);
            return out;
        }
        for (String pair : raw.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length != 2) continue;
            HomeGrid.Module m = HomeGrid.Module.byId(kv[0].trim());
            HomeGrid.Slot s = HomeGrid.Slot.byId(kv[1].trim());
            if (m != null && s != null && !out.containsValue(s)) out.put(m, s);
        }
        return out;
    }

    /** Folder the MP3 player lists music from (subfolders too); null until the driver picks one. */
    public static String musicFolder(Context c) {
        return prefs(c).getString(KEY_MUSIC_FOLDER, null);
    }

    public static void setMusicFolder(Context c, String path) {
        prefs(c).edit().putString(KEY_MUSIC_FOLDER, path).apply();
    }

    /** Hide the NWD bottom bar while Garagem is in front (see BottomBar). Off by default. */
    public static boolean hideBottomBar(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_BOTTOM_BAR, false);
    }

    public static void setHideBottomBar(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HIDE_BOTTOM_BAR, on).apply();
    }

    /** Path of the picture shown by the Image module (see HomeImage); null if none was chosen. */
    public static String homeImage(Context c) {
        return prefs(c).getString(KEY_HOME_IMAGE, null);
    }

    public static void setHomeImage(Context c, String path) {
        prefs(c).edit().putString(KEY_HOME_IMAGE, path).apply();
    }

    /** Back to the original home, volume included. */
    public static void resetHomeLayout(Context c) {
        prefs(c).edit().putString(KEY_HOME_LAYOUT,
            "now_playing:left,clock:center_top,volume:center_bottom,fan:right_top,bluetooth:right_middle").apply();
    }

    /**
     * Puts {@code module} in {@code slot} (null = hidden). If another module is already there, the
     * two swap places (the other one is hidden if {@code module} had no slot).
     */
    public static void setHomeSlot(Context c, HomeGrid.Module module, HomeGrid.Slot slot) {
        Map<HomeGrid.Module, HomeGrid.Slot> layout = homeLayout(c);
        HomeGrid.Slot old = layout.remove(module);
        if (slot != null) {
            for (HomeGrid.Module other : HomeGrid.Module.values()) {
                if (layout.get(other) != slot) continue;
                if (old != null) layout.put(other, old);
                else layout.remove(other);
            }
            layout.put(module, slot);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<HomeGrid.Module, HomeGrid.Slot> e : layout.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey().id).append(':').append(e.getValue().id);
        }
        prefs(c).edit().putString(KEY_HOME_LAYOUT, sb.toString()).apply();
    }

    /**
     * Ask on startup whether Garagem should become the default launcher. Turned off when the user
     * chooses to go back to the stock launcher (so we don't nag); turned on again when they pick Garagem.
     */
    public static boolean askDefaultLauncher(Context c) {
        return prefs(c).getBoolean(KEY_ASK_DEFAULT, true);
    }

    public static void setAskDefaultLauncher(Context c, boolean ask) {
        prefs(c).edit().putBoolean(KEY_ASK_DEFAULT, ask).apply();
    }

    /** Optional items enabled in the dock, in the enum's fixed order. With nothing saved, the default (5/5). */
    public static Set<DockView.Item> dockToggles(Context c) {
        String raw = prefs(c).getString(KEY_DOCK_TOGGLES, null);
        Set<DockView.Item> out = new LinkedHashSet<>();
        if (raw == null) {
            Collections.addAll(out, DEFAULT_DOCK);
            return out;
        }
        List<String> ids = new ArrayList<>();
        for (String id : raw.split(",")) ids.add(id.trim());
        for (DockView.Item item : DockView.Item.values()) {
            if (item.toggleable && ids.contains(item.id) && out.size() < MAX_DOCK_TOGGLES) out.add(item);
        }
        return out;
    }

    public static void setDockToggles(Context c, Set<DockView.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (DockView.Item item : DockView.Item.values()) {
            if (!item.toggleable || !items.contains(item)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(item.id);
        }
        prefs(c).edit().putString(KEY_DOCK_TOGGLES, sb.toString()).apply();
    }

    /** Language chosen in Settings; English when none was chosen or the saved one no longer exists. */
    public static String language(Context c) {
        String tag = prefs(c).getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
        for (String known : LANGUAGES) {
            if (known.equals(tag)) return tag;
        }
        return DEFAULT_LANGUAGE;
    }

    public static void setLanguage(Context c, String tag) {
        // commit: the app restarts the screens right after and needs to read the new value.
        prefs(c).edit().putString(KEY_LANGUAGE, tag == null ? DEFAULT_LANGUAGE : tag).commit();
    }

    /** Font chosen in Settings; Exo 2 when none was chosen or the saved one no longer exists. */
    public static String font(Context c) {
        String id = prefs(c).getString(KEY_FONT, DEFAULT_FONT);
        for (String known : FONTS) {
            if (known.equals(id)) return id;
        }
        return DEFAULT_FONT;
    }

    public static void setFont(Context c, String id) {
        // commit: the app restarts the screens right after and needs to read the new value.
        prefs(c).edit().putString(KEY_FONT, id == null ? DEFAULT_FONT : id).commit();
    }

    /** Theme color chosen in Settings: 0..Palette.THEMES-1, or Palette.DEFAULT (grayscale). */
    public static int theme(Context c) {
        int t = prefs(c).getInt(KEY_THEME, com.garagemdomadeira.nwd.launcher.ui.Palette.DEFAULT);
        return t >= 0 && t < com.garagemdomadeira.nwd.launcher.ui.Palette.THEMES ? t
            : com.garagemdomadeira.nwd.launcher.ui.Palette.DEFAULT;
    }

    public static void setTheme(Context c, int theme) {
        // commit: the app restarts the screens right after and needs to read the new value.
        prefs(c).edit().putInt(KEY_THEME, theme).commit();
    }

    public static void resetDockToggles(Context c) {
        prefs(c).edit().remove(KEY_DOCK_TOGGLES).apply();
    }
}
