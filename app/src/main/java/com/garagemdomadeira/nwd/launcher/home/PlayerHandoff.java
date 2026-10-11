package com.garagemdomadeira.nwd.launcher.home;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.garagemdomadeira.nwd.launcher.bt.BtClient;
import com.garagemdomadeira.nwd.launcher.music.MusicPlaybackService;

/**
 * Before turning the radio on: pauses our MP3 player and Bluetooth music, so neither keeps
 * playing "underneath" when the radio becomes the audio source.
 * Keeps a BtClient connected for the app's lifetime (the BT service drops commands sent
 * before the connection).
 */
public final class PlayerHandoff {
    private PlayerHandoff() {
    }

    private static final String TAG = "GaragemHandoff";
    private static final int BT_PAUSE = 1; // BTFeature.playControl: 0 play, 1 pause, 2 next, 3 prev
    private static final int BT_RETRIES = 6;
    private static final long BT_RETRY_MS = 300;

    private static BtClient bt;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Called at app start so Bluetooth is already connected. */
    public static void init(Context context) {
        if (bt != null) return;
        bt = new BtClient(context.getApplicationContext());
        bt.connect();
    }

    /** Pauses MP3 and Bluetooth. Safe to call even if nothing is playing. */
    public static void pauseOtherPlayers(Context context) {
        Context app = context.getApplicationContext();
        try {
            Intent i = new Intent(app, MusicPlaybackService.class);
            i.setAction(MusicPlaybackService.ACTION_PAUSE);
            app.startService(i);
        } catch (Exception e) {
            Log.d(TAG, "pause MP3: " + e.getMessage());
        }
        init(app);
        pauseBt(BT_RETRIES);
    }

    private static void pauseBt(int triesLeft) {
        if (bt.isConnected()) {
            Log.d(TAG, "→ BT playControl(pause)");
            bt.playControl(BT_PAUSE);
            return;
        }
        if (triesLeft <= 0) {
            Log.d(TAG, "Bluetooth service unavailable; BT music not paused");
            return;
        }
        bt.connect();
        main.postDelayed(() -> pauseBt(triesLeft - 1), BT_RETRY_MS);
    }
}
