package com.k4811.common.system;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Reads system properties and changes them through the NWD channel.
 *
 * A regular app can't do "setprop persist.*". Instead, write Settings.System "nwd_system_prop" =
 * "name=value" and send the broadcast com.nwd.action.ACTION_SET_SYSTEM_PROP; an NWD system
 * service applies the property.
 */
public final class SystemProps {
    private SystemProps() {
    }

    private static final String TAG = "K4811Launcher";

    public static final String PROP_DEFAULT_LAUNCHER = "persist.nwd.launcher.default";

    private static final String ACTION_SET_SYSTEM_PROP = "com.nwd.action.ACTION_SET_SYSTEM_PROP";
    private static final String EXTRA_PROP_NAME = "extra_prop_name";
    private static final String EXTRA_PROP_VALUE = "extra_prop_value";
    private static final String SETTING_SYSTEM_PROP = "nwd_system_prop";

    /** android.os.SystemProperties.get(key, def) via reflection (hidden class, allowed for target < 28). */
    public static String get(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            return (String) m.invoke(null, key, def);
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + key + ": " + e);
            return def;
        }
    }

    /** Asks the NWD system to set the property. The result should be checked afterwards with {@link #get}. */
    public static void requestSet(Context context, String key, String value) {
        try {
            Settings.System.putString(context.getContentResolver(), SETTING_SYSTEM_PROP, key + "=" + value);
        } catch (Exception e) {
            Log.w(TAG, "Settings.System " + SETTING_SYSTEM_PROP + " refused: " + e);
        }
        Intent intent = new Intent(ACTION_SET_SYSTEM_PROP);
        intent.putExtra(EXTRA_PROP_NAME, key);
        intent.putExtra(EXTRA_PROP_VALUE, value);
        context.sendBroadcast(intent);
        Log.d(TAG, "→ " + ACTION_SET_SYSTEM_PROP + " " + key + "=" + value);
    }
}
