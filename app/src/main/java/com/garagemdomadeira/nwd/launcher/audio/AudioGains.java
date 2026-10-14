package com.garagemdomadeira.nwd.launcher.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.garagemdomadeira.nwd.launcher.R;
import com.garagemdomadeira.nwd.launcher.eq.EqModel;
import com.garagemdomadeira.nwd.launcher.eq.SettingClient;
import com.garagemdomadeira.nwd.launcher.system.SystemProps;
import com.nwd.setting.service.SettingFeature;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-source volume (the head unit's "Gain Setting" in the factory menu), one value per source,
 * independent of each other. Applied through the NWD SettingService (setGainParam), which only
 * touches that gain; the factory menu's "Save" would rewrite every factory setting at once.
 * Values the driver changed are kept here and reapplied when the app starts, in case the MCU
 * doesn't keep them across a restart.
 */
public final class AudioGains {
    private AudioGains() {
    }

    /** A gain source: id = position in key_audio_gain (1 navi … 14 OnStar). */
    public static final class Source {
        public final int id;
        public final int labelRes;

        Source(int id, int labelRes) {
            this.id = id;
            this.labelRes = labelRes;
        }
    }

    /** The sources that matter in a car without DVD/TV/iPod (the rest stays as the factory set it). */
    public static final Source[] SOURCES = {
        new Source(7, R.string.audio_gain_radio),
        new Source(2, R.string.audio_gain_bt),
        new Source(9, R.string.audio_gain_media),
        new Source(1, R.string.audio_gain_navi),
        new Source(6, R.string.audio_gain_aux),
    };

    /** Android music stream ceiling ("Music Gain"): 5–15, default 15. */
    public static final int MUSIC_MIN = 5;
    public static final int MUSIC_MAX = 15;
    private static final String PROP_MUSIC_MAX = "persist.sys.nwd.streammusic.max";
    private static final String SETTING_MUSIC_MAX = "nwd_media_vol";

    private static final String TAG = "GaragemAudio";
    private static final String PREFS = "garagem_audio";
    private static final String KEY_GAIN = "gain_";
    private static final String KEY_MUSIC_MAX = "music_max";
    private static final String SETTING_MAX_GAIN = "mcu_max_gain_volume";
    private static final String DSP_7735 = "/sys/devices/virtual/misc/ak7735-dsp/user_dsp_exist";
    private static final String DSP_7604 = "/sys/devices/virtual/misc/ak7604-dsp/user_dsp_exist";
    private static final int DEFAULT_MAX_GAIN = 25;
    private static final int DSP_MAX_GAIN = 10;

    /** Highest gain, as the factory menu computes it: 10 with the ARM DSP, otherwise the MCU's max (25). */
    public static int maxGain(Context c) {
        if (new File(DSP_7735).exists() || new File(DSP_7604).exists()) return DSP_MAX_GAIN;
        int max = Settings.System.getInt(c.getContentResolver(), SETTING_MAX_GAIN, 0);
        return max > 0 ? max : DEFAULT_MAX_GAIN;
    }

    /** Applies one source's gain right away. Blocking Binder call: worker thread. */
    public static void apply(Context c, SettingFeature f, int source, int value) throws Exception {
        f.setGainParam(source, value);
        prefs(c).edit().putInt(KEY_GAIN + source, value).apply();
        Log.d(TAG, "setGainParam(" + source + ", " + value + ")");
    }

    public static int musicMax() {
        try {
            int v = Integer.parseInt(SystemProps.get(PROP_MUSIC_MAX, String.valueOf(MUSIC_MAX)).trim());
            return Math.max(MUSIC_MIN, Math.min(MUSIC_MAX, v));
        } catch (NumberFormatException e) {
            return MUSIC_MAX;
        }
    }

    public static void setMusicMax(Context c, int value) {
        int v = Math.max(MUSIC_MIN, Math.min(MUSIC_MAX, value));
        prefs(c).edit().putInt(KEY_MUSIC_MAX, v).apply();
        writeMusicMax(c, v);
    }

    private static void writeMusicMax(Context c, int v) {
        SystemProps.requestSet(c, PROP_MUSIC_MAX, String.valueOf(v));
        try {
            Settings.System.putInt(c.getContentResolver(), SETTING_MUSIC_MAX, v);
        } catch (Exception e) {
            Log.d(TAG, SETTING_MUSIC_MAX + ": " + e.getMessage());
        }
    }

    /** At app start: reapplies only what the driver changed here. */
    public static void reapply(Context context) {
        final Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        if (p.contains(KEY_MUSIC_MAX)) writeMusicMax(app, p.getInt(KEY_MUSIC_MAX, MUSIC_MAX));
        boolean any = false;
        for (Source s : SOURCES) any |= p.contains(KEY_GAIN + s.id);
        if (!any) return;

        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final SettingClient[] holder = new SettingClient[1];
        holder[0] = new SettingClient(app, new Callbacks() {
            @Override
            public void onConnected(SettingFeature f) {
                worker.execute(() -> {
                    SharedPreferences prefs = prefs(app);
                    for (Source s : SOURCES) {
                        if (!prefs.contains(KEY_GAIN + s.id)) continue;
                        try {
                            f.setGainParam(s.id, prefs.getInt(KEY_GAIN + s.id, 0));
                        } catch (Exception e) {
                            Log.d(TAG, "reapply " + s.id + ": " + e.getMessage());
                        }
                    }
                    Log.d(TAG, "Gains reapplied");
                    worker.shutdown();
                    holder[0].release();
                });
            }

            @Override
            public void onConnection(EqModel.Connection connection) {
                if (connection == EqModel.Connection.NOT_FOUND) {
                    holder[0].release();
                    worker.shutdown();
                }
            }
        });
        holder[0].connect();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** SettingClient.Callbacks with no-op defaults. */
    static class Callbacks implements SettingClient.Callbacks {
        @Override
        public void log(String msg) {
            Log.d(TAG, msg);
        }

        @Override
        public void onConnection(EqModel.Connection connection) {
        }

        @Override
        public void onConnected(SettingFeature feature) {
        }

        @Override
        public void onAudioParam(int type, int value) {
        }

        @Override
        public void onDspAudio(int type, int value) {
        }
    }
}
