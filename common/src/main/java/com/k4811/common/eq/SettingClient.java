package com.k4811.common.eq;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.nwd.setting.service.AudioSettingCallback;
import com.nwd.setting.service.SettingFeature;

/** Connection to the NWD SettingService (MCU path). */
public final class SettingClient {

    public static final String PACKAGE = "com.nwd.setting.service";
    public static final String SERVICE = "com.nwd.setting.service.SettingService";

    public static final int AUDIO_EQ_PRESET = 5;
    public static final int AUDIO_FADER = 12;
    public static final int AUDIO_BALANCE = 13;
    public static final int AUDIO_LOUDNESS = 17;

    public interface Callbacks {
        void log(String msg);

        void onConnection(EqModel.Connection connection);

        void onConnected(SettingFeature feature);

        void onAudioParam(int type, int value);

        void onDspAudio(int type, int value);
    }

    private final Context context;
    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile SettingFeature feature;
    private boolean bound;
    private boolean released;

    private final AudioSettingCallback.Stub audioCallback = new AudioSettingCallback.Stub() {
        @Override
        public void notifyAudioParam(int type, int value) {
            callbacks.onAudioParam(type, value);
        }

        @Override
        public void notifySingleGainParam(int type, int value) {
        }

        @Override
        public void notifyDspAudioParam(int type, int value) {
            callbacks.onDspAudio(type, value);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SettingFeature f = SettingFeature.Stub.asInterface(service);
            feature = f;
            callbacks.log("Connected to SettingService");
            callbacks.onConnection(EqModel.Connection.CONNECTED);
            try {
                f.registAudioCallback(audioCallback);
            } catch (Exception e) {
                callbacks.log("registAudioCallback failed: " + e.getMessage());
            }
            callbacks.onConnected(f);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            feature = null;
            callbacks.log("SettingService disconnected");
            callbacks.onConnection(EqModel.Connection.DISCONNECTED);
            scheduleReconnect();
        }
    };

    public SettingClient(Context context, Callbacks callbacks) {
        this.context = context.getApplicationContext();
        this.callbacks = callbacks;
    }

    public SettingFeature feature() {
        return feature;
    }

    public void connect() {
        if (released || bound) return;
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (Exception e) {
            callbacks.log("Package " + PACKAGE + " not found");
            callbacks.onConnection(EqModel.Connection.NOT_FOUND);
            return;
        }
        callbacks.onConnection(EqModel.Connection.CONNECTING);
        Intent intent = new Intent();
        intent.setClassName(PACKAGE, SERVICE);
        try {
            context.startService(intent);
        } catch (Exception e) {
            callbacks.log("startService failed: " + e.getMessage());
        }
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            callbacks.log("bindService failed: " + e.getMessage());
            bound = false;
        }
        if (!bound) {
            callbacks.log("bindService refused");
            callbacks.onConnection(EqModel.Connection.NOT_FOUND);
            scheduleReconnect();
        }
    }

    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (feature != null) feature.unRegistAudioCallback(audioCallback);
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
    }

    private void scheduleReconnect() {
        if (released) return;
        handler.postDelayed(() -> {
            if (released || feature != null) return;
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (Exception ignored) {
                }
                bound = false;
            }
            connect();
        }, 3000);
    }
}
