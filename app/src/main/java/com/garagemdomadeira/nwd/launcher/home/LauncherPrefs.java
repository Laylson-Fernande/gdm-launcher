package com.garagemdomadeira.nwd.launcher.home;

import android.content.Context;
import android.content.SharedPreferences;

import com.garagemdomadeira.nwd.launcher.ui.DockView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Launcher preferences (Settings screen), read by the home screen in onResume. */
public final class LauncherPrefs {
    private LauncherPrefs() {
    }

    /** Package of the stock launcher (factory value of persist.nwd.launcher.default). */
    public static final String ORIGINAL_LAUNCHER = "com.android.launcher";

    private static final String FILE = "launcher_prefs";
    private static final String KEY_VOLTMETER = "show_voltmeter";
    private static final String KEY_VOLUME = "show_volume_controls";
    private static final String KEY_ASK_DEFAULT = "ask_default_launcher";
    private static final String KEY_DOCK_TOGGLES = "dock_toggles";
    private static final String KEY_LANGUAGE = "language";

    /** App languages (language tags), in the order shown in Settings. To add one, append its tag
     *  here, its name to {@code LANGUAGE_NAMES} and create {@code res/values-<tag>/}. */
    public static final String[] LANGUAGES = {"pt", "en"};
    /** Each language's name written in itself, so someone who can't read the current one finds theirs. */
    public static final String[] LANGUAGE_NAMES = {"Português", "English"};
    public static final String DEFAULT_LANGUAGE = "en";

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

    public static boolean showVolumeControls(Context c) {
        return prefs(c).getBoolean(KEY_VOLUME, true);
    }

    public static void setShowVolumeControls(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_VOLUME, on).apply();
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

    public static void resetDockToggles(Context c) {
        prefs(c).edit().remove(KEY_DOCK_TOGGLES).apply();
    }
}
