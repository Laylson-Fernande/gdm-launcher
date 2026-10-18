package com.k4811.launcher.overlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.DevMode;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.home.VolumeClient;

/**
 * Shows the volume over any app whenever it changes (steering wheel, knob or screen): listens to
 * the NWD SettingService (same callback as the home screen's volume module) and draws
 * {@link VolumeOverlayView} at the top of the screen for a moment. It doesn't take touches.
 *
 * Started by {@link com.k4811.launcher.LauncherApp}; "Show volume on screen" in the car-setting
 * turns it off. The window is TYPE_SYSTEM_ALERT with SYSTEM_ALERT_WINDOW, granted at install
 * (targetSdk < 23).
 *
 * To see it without the head unit (developer mode only):
 * {@code adb shell am startservice -n <launcher package>/com.k4811.launcher.overlay.VolumeOverlayService
 * -a com.k4811.launcher.VOLUME_PREVIEW --ei volume 25 --ei max 40}
 */
public class VolumeOverlayService extends Service {

    public static final String ACTION_PREVIEW = "com.k4811.launcher.VOLUME_PREVIEW";
    private static final String TAG = "K4811VolOverlay";
    /** How long the card stays after the last change. */
    private static final long SHOW_MS = 2200;
    /** Values that arrive right after connecting are the current volume, not a change. */
    private static final long SETTLE_MS = 2000;
    /** Distance from the top of the screen, in design units. */
    private static final float TOP = 18;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;

    private WindowManager wm;
    private VolumeClient volume;
    private VolumeOverlayView view;
    private boolean attached;
    private float scale = 1f;
    private long startedAt;
    /** Last volume seen (-1 = none yet): only a different value shows the card. */
    private int last = -1;

    public static void start(Context c) {
        try {
            c.startService(new Intent(c, VolumeOverlayService.class));
        } catch (Exception e) {
            Log.w(TAG, "startService: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        scale = Math.min(Math.max(size.x, size.y) / (float) Ui.DESIGN_WIDTH,
            Math.min(size.x, size.y) / (float) Ui.DESIGN_HEIGHT);
        Ui.applyThemeColors(this);
        startedAt = SystemClock.elapsedRealtime();
        volume = new VolumeClient(this);
        volume.setListener(this::onVolume);
        volume.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PREVIEW.equals(intent.getAction()) && DevMode.enabled()) {
            show(intent.getIntExtra("volume", 20), intent.getIntExtra("max", 40));
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (volume != null) volume.release();
        remove();
        super.onDestroy();
    }

    private void onVolume(int value, int max) {
        int previous = last;
        last = value;
        boolean settling = SystemClock.elapsedRealtime() - startedAt < SETTLE_MS;
        if (previous < 0 || settling || value == previous) return;
        if (!LauncherConfig.showVolumeOverlay(this)) return;
        show(value, max);
    }

    private void show(int value, int max) {
        if (view == null) view = new VolumeOverlayView(this, scale);
        if (!attached) {
            Ui.applyThemeColors(this);
            view.applyColors();
            view.setVolume(value, max, false);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                view.widthPx(), view.heightPx(),
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(TOP * scale);
            try {
                wm.addView(view, lp);
                attached = true;
            } catch (Exception e) {
                Log.w(TAG, "overlay refused: " + e.getMessage());
                return;
            }
            // In: slides down a little and settles with a small bounce.
            view.setAlpha(0f);
            view.setTranslationY(-24 * scale);
            view.setScaleX(0.94f);
            view.setScaleY(0.94f);
            view.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                .setDuration(260).setInterpolator(new OvershootInterpolator(1.4f)).start();
        } else {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationY(0);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setVolume(value, max, true);
        }
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_MS);
    }

    /** Out: fades while going back up, then leaves the window. */
    private void hide() {
        if (!attached || view == null) return;
        view.animate().alpha(0f).translationY(-16 * scale).setDuration(220)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(this::remove).start();
    }

    private void remove() {
        if (!attached || view == null) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        attached = false;
    }
}
