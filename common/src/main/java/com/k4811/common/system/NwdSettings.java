package com.k4811.common.system;

import com.k4811.common.system.ExternalApps;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.nwd.setting.service.SettingFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A single, app-wide client for the NWD SettingService (AIDL {@link SettingFeature}),
 * plus reading of the keys the service keeps in {@code Settings.System}.
 * Commands sent before the connection are queued and go out as soon as it opens.
 * The EQ still uses its own {@code eq/SettingClient} (it needs the audio callback).
 */
public final class NwdSettings {
    private NwdSettings() {
    }

    public static final String PACKAGE = "com.nwd.setting.service";
    private static final String SERVICE = "com.nwd.setting.service.SettingService";
    private static final String TAG = "K4811Settings";

    // ------------------------------------------------------------------ setSystemFeature types (11)

    public static final int FEATURE_HAND_BRAKE = 17;
    public static final int FEATURE_ILL_DIMMER = 19;
    public static final int FEATURE_ANY_KEY_POWER = 22;
    public static final int FEATURE_BEEP = 42;
    /** Inverted: value 0 mutes in reverse, 1 keeps the sound. */
    public static final int FEATURE_REVERSE_KEEP_SOUND = 45;
    public static final int FEATURE_MIRROR_REVERSE = 50;

    // ------------------------------------------------------------------ mcu_System_setting_state bits

    public static final String KEY_SYSTEM_STATE = "mcu_System_setting_state";
    public static final int BIT_ANY_KEY_POWER = 4;
    public static final int BIT_MIRROR_REVERSE = 512;
    /** Set = sound kept in reverse (cleared = muted). */
    public static final int BIT_REVERSE_KEEP_SOUND = 2048;
    public static final int BIT_BEEP = 8192;
    public static final int BIT_HAND_BRAKE = 16384;
    public static final int BIT_ILL_DIMMER = 32768;

    private interface Call {
        void run(SettingFeature f) throws RemoteException;
    }

    private static Context app;
    private static volatile SettingFeature feature;
    private static boolean bound;
    private static final List<Call> queue = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** Called at app startup (K4811App). Without the NWD package, does nothing. */
    public static synchronized void init(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        connect();
    }

    public static boolean isInstalled(Context c) {
        return ExternalApps.installed(c, PACKAGE);
    }

    public static boolean isConnected() {
        return feature != null;
    }

    private static synchronized void connect() {
        if (bound || app == null || !isInstalled(app)) return;
        Intent intent = new Intent();
        intent.setClassName(PACKAGE, SERVICE);
        try {
            app.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "startService failed: " + e);
        }
        try {
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w(TAG, "bindService failed: " + e);
            bound = false;
        }
        if (!bound) main.postDelayed(NwdSettings::connect, 5000);
    }

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            feature = SettingFeature.Stub.asInterface(service);
            Log.d(TAG, "Connected to SettingService");
            List<Call> pending;
            synchronized (queue) {
                pending = new ArrayList<>(queue);
                queue.clear();
            }
            for (Call c : pending) run(c);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            feature = null;
            Log.d(TAG, "SettingService disconnected");
        }
    };

    private static void call(Call c) {
        if (feature == null) {
            synchronized (queue) {
                queue.add(c);
            }
            connect();
            return;
        }
        run(c);
    }

    private static void run(Call c) {
        worker.execute(() -> {
            SettingFeature f = feature;
            if (f == null) {
                synchronized (queue) {
                    queue.add(c);
                }
                return;
            }
            try {
                c.run(f);
            } catch (Exception e) {
                Log.w(TAG, "SettingService call failed: " + e);
            }
        });
    }

    // ------------------------------------------------------------------ commands

    public static void setSystemFeature(int type, int value) {
        call(f -> f.setSystemFeature(type, value));
    }

    public static void setDefaultVolume(int volume) {
        call(f -> f.setDefaultVolume(volume));
    }

    /** 1 = navigation lowers the music, 2 = it doesn't. */
    public static void setGpsPromptVolumeWay(int way) {
        call(f -> f.setGPSPromptVolumeWay(way));
    }

    public static void setRadioRegion(int region) {
        call(f -> f.setRadioRegion(region));
    }

    public static void setMixedRatio(int ratio) {
        call(f -> f.setMixedRatio(ratio));
    }

    public static void setDayBackLight(int value) {
        call(f -> f.setDayBackLight(value));
    }

    public static void setAutoWakeup(boolean on) {
        call(f -> f.setAutoWakeup(on));
    }

    // ------------------------------------------------------------------ Settings.System

    /** MCU inputs; bit 2 = ILL (headlights on). */
    public static final String KEY_NO_SOURCE_DEVICE = "mcu_no_source_device_state";
    private static final int ILL_BIT = 2;

    /** true while the headlights are on (the MCU's ILL signal). */
    public static boolean headlightsOn(Context c) {
        return (getInt(c, KEY_NO_SOURCE_DEVICE, 0) & ILL_BIT) != 0;
    }

    /** Value of the key, or {@code def} if it doesn't exist. */
    public static int getInt(Context c, String key, int def) {
        try {
            return Settings.System.getInt(c.getContentResolver(), key, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean has(Context c, String key) {
        try {
            return Settings.System.getString(c.getContentResolver(), key) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static String getString(Context c, String key) {
        try {
            return Settings.System.getString(c.getContentResolver(), key);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean putInt(Context c, String key, int value) {
        return putString(c, key, String.valueOf(value));
    }

    public static boolean putString(Context c, String key, String value) {
        try {
            ContentResolver cr = c.getContentResolver();
            return Settings.System.putString(cr, key, value);
        } catch (Exception e) {
            Log.w(TAG, "Settings.System " + key + " refused: " + e);
            return false;
        }
    }

    /** Bit of {@code mcu_System_setting_state}. null if the key doesn't exist (head unit without NWD MCU). */
    public static Boolean systemBit(Context c, int bit) {
        if (!has(c, KEY_SYSTEM_STATE)) return null;
        return (getInt(c, KEY_SYSTEM_STATE, 0) & bit) != 0;
    }
}
