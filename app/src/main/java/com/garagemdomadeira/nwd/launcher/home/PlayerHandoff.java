package com.garagemdomadeira.nwd.launcher.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.garagemdomadeira.nwd.launcher.bt.BtClient;
import com.garagemdomadeira.nwd.launcher.music.MusicPlaybackService;
import com.garagemdomadeira.nwd.launcher.radio.NwdConstants;
import com.garagemdomadeira.nwd.launcher.ui.DockView;
import com.garagemdomadeira.nwd.launcher.ui.Splash;

/**
 * Only one of the three sources (radio, our MP3 player, Bluetooth music) plays at a time: right
 * before one starts, the others are stopped. The radio itself is taken off by the source change
 * that MP3 (source 0) and Bluetooth (app id 15) already request, so only the players need pausing.
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
    private static final int SOURCE_RADIO = 4;

    private static BtClient bt;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Called at app start so Bluetooth is already connected. */
    public static void init(Context context) {
        if (bt != null) return;
        bt = new BtClient(context.getApplicationContext());
        bt.connect();
    }

    /** true if the head unit's current source is already the radio. */
    public static boolean radioIsSource(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
            NwdConstants.SETTING_CURRENT_SOURCE, -1) == SOURCE_RADIO;
    }

    /**
     * Turns the radio on: pauses MP3 and Bluetooth, then runs {@code activate} (which takes the
     * source). If our MP3 was playing, shows the radio Splash (dock stays visible) first: if the
     * source leaves Android before the player has really stopped, the NWD system force-stops our
     * whole package.
     */
    public static void switchToRadio(final Activity activity, final Runnable activate) {
        boolean mp3Playing = MusicPlaybackService.isPlayingNow();
        beforeRadio(activity);
        if (!mp3Playing) {
            activate.run();
            return;
        }
        Splash.show(activity, DockView.Item.RADIO, activate);
    }

    /** Before the radio becomes the source: pauses MP3 and Bluetooth. */
    public static void beforeRadio(Context context) {
        Context app = context.getApplicationContext();
        pauseMp3(app);
        init(app);
        pauseBt(BT_RETRIES);
    }

    /** Before our MP3 player starts: pauses Bluetooth music. */
    public static void beforeMp3(Context context) {
        init(context.getApplicationContext());
        pauseBt(BT_RETRIES);
    }

    /** Before Bluetooth music starts: pauses our MP3 player. */
    public static void beforeBt(Context context) {
        pauseMp3(context.getApplicationContext());
    }

    /**
     * Synchronous: the caller changes the source right after, and the system force-stops our
     * package if the player still holds audio focus when the source leaves Android.
     */
    private static void pauseMp3(Context app) {
        if (MusicPlaybackService.pauseRunning()) return;
        try {
            Intent i = new Intent(app, MusicPlaybackService.class);
            i.setAction(MusicPlaybackService.ACTION_PAUSE);
            app.startService(i);
        } catch (Exception e) {
            Log.d(TAG, "pause MP3: " + e.getMessage());
        }
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
