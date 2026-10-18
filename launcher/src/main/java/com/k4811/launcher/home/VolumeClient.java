package com.k4811.launcher.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.nwd.setting.service.AudioSettingCallback;
import com.nwd.setting.service.SettingFeature;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System volume via SettingFeature (type 14). */
public final class VolumeClient {

    public interface Listener {
        void onVolume(int volume, int max);
    }

    private static final String TAG = "K4811Vol";
    private static final String PACKAGE = "com.nwd.setting.service";
    private static final String SERVICE = "com.nwd.setting.service.SettingService";
    private static final int AUDIO_VOLUME = 14;
    private static final String ACTION_VOLUME_DISPLAY = "com.nwd.ACTION_REQUEST_VLOUME_DISPLAY";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile SettingFeature feature;
    private boolean bound;
    private boolean released;
    private int volume;
    private int max = 40;

    private final AudioSettingCallback.Stub callback = new AudioSettingCallback.Stub() {
        @Override
        public void notifyAudioParam(int type, int value) {
            if (type != AUDIO_VOLUME) return;
            main.post(() -> {
                volume = clamp(value, 0, max);
                if (listener != null) listener.onVolume(volume, max);
            });
        }

        @Override
        public void notifySingleGainParam(int type, int value) {
        }

        @Override
        public void notifyDspAudioParam(int type, int value) {
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            feature = SettingFeature.Stub.asInterface(service);
            try {
                feature.registAudioCallback(callback);
            } catch (Exception e) {
                Log.d(TAG, "registAudioCallback: " + e.getMessage());
            }
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            feature = null;
            bound = false;
        }
    };

    public VolumeClient(Context context) {
        this.context = context.getApplicationContext();
        try {
            int m = Settings.System.getInt(this.context.getContentResolver(), "mcu_max_volume");
            if (m > 0) max = m;
        } catch (Exception ignored) {
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onVolume(volume, max);
    }

    public void connect() {
        if (released || bound) return;
        Intent intent = new Intent();
        intent.setClassName(PACKAGE, SERVICE);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.d(TAG, "startService: " + e.getMessage());
        }
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.d(TAG, "bindService: " + e.getMessage());
            bound = false;
        }
    }

    public void refresh() {
        worker.execute(() -> {
            SettingFeature f = feature;
            if (f == null) return;
            int v = volume;
            try {
                v = f.getAudioParam(AUDIO_VOLUME);
            } catch (Exception e) {
                Log.d(TAG, "getAudioParam: " + e.getMessage());
            }
            final int next = clamp(v, 0, max);
            main.post(() -> {
                volume = next;
                if (listener != null) listener.onVolume(volume, max);
            });
        });
    }

    public void setVolume(int value) {
        final int next = clamp(value, 0, max);
        if (next == volume) return;
        volume = next;
        if (listener != null) listener.onVolume(volume, max);
        worker.execute(() -> {
            SettingFeature f = feature;
            if (f == null) {
                Log.d(TAG, "No SettingService, volume not sent: " + next);
                return;
            }
            try {
                f.setAudioParam(AUDIO_VOLUME, next);
            } catch (Exception e) {
                Log.d(TAG, "setAudioParam: " + e.getMessage());
            }
        });
        try {
            context.sendBroadcast(new Intent(ACTION_VOLUME_DISPLAY));
        } catch (Exception ignored) {
        }
    }

    public void nudge(int delta) {
        setVolume(volume + delta);
    }

    public int volume() {
        return volume;
    }

    public int max() {
        return max;
    }

    public void release() {
        released = true;
        listener = null;
        try {
            if (feature != null) feature.unRegistAudioCallback(callback);
        } catch (Exception ignored) {
        }
        feature = null;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Exception ignored) {
            }
        }
        bound = false;
        worker.shutdown();
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
