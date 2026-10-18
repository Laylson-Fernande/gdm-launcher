package com.k4811.common.ui;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.EditText;

import com.k4811.common.R;

/**
 * Touch feedback on elements: a short vibration (like an Android key) and a slight "press-in"
 * on press, which springs back with a small bounce. Many head units have no vibration motor;
 * in those cases the visual feedback provides the touch feel.
 */
public final class Touch {
    private Touch() {
    }

    private static final float PRESSED_SCALE = 0.94f;
    /** Large areas (background, whole panels, dials) don't "press in": they only vibrate, or not even that. */
    private static final float MAX_SCALE_W_DP = 480;
    private static final float MAX_SCALE_H_DP = 260;
    private static final float HUGE_W_DP = 900;
    private static final float HUGE_H_DP = 300;

    private static final DecelerateInterpolator PRESS_IN = new DecelerateInterpolator();
    private static final OvershootInterpolator RELEASE = new OvershootInterpolator(3f);

    private static final View.OnTouchListener PRESS = (v, e) -> {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                haptic(v);
                if (scalable(v)) {
                    v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                        .setDuration(80).setInterpolator(PRESS_IN).start();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scalable(v)) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(RELEASE).start();
                }
                break;
            default:
                break;
        }
        return false; // not consumed: the view's click/drag proceeds normally
    };

    private static final View.OnTouchListener HAPTIC_ONLY = (v, e) -> {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) haptic(v);
        return false;
    };

    /** Equips every touchable element in the tree (call after building the screen). */
    public static void install(View root) {
        if (root == null) return;
        if (root instanceof AdapterView || root instanceof EditText) return; // lists: see hapticOnly
        if ((root.isClickable() || root.isLongClickable()) && root.getTag(R.id.touch_installed) == null
            && !huge(root)) {
            root.setTag(R.id.touch_installed, Boolean.TRUE);
            root.setHapticFeedbackEnabled(true);
            root.setOnTouchListener(PRESS);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) install(g.getChildAt(i));
        }
    }

    /**
     * List rows (touch is handled by the ListView): vibration only, no "press-in"
     * (the row doesn't receive the touch end to restore its size).
     */
    public static void hapticOnly(View row) {
        if (row == null || row.getTag(R.id.touch_installed) != null) return;
        row.setTag(R.id.touch_installed, Boolean.TRUE);
        row.setOnTouchListener(HAPTIC_ONLY);
    }

    @SuppressWarnings("deprecation")
    public static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    private static boolean scalable(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        return v.getWidth() / d <= MAX_SCALE_W_DP && v.getHeight() / d <= MAX_SCALE_H_DP;
    }

    /** Screen background and full-cover layers: no feedback (they are taps "on nothing"). */
    private static boolean huge(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return false;
        boolean fullW = lp.width == ViewGroup.LayoutParams.MATCH_PARENT;
        boolean fullH = lp.height == ViewGroup.LayoutParams.MATCH_PARENT;
        float d = v.getResources().getDisplayMetrics().density;
        boolean bigW = fullW || lp.width / d > HUGE_W_DP;
        boolean bigH = fullH || lp.height / d > HUGE_H_DP;
        return bigW && bigH;
    }

    /** Short crossfade when switching a tab's content. */
    public static void fadeIn(View v) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(170).setInterpolator(PRESS_IN).start();
    }
}
