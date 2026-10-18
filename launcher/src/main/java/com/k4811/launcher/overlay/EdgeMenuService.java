package com.k4811.launcher.overlay;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.McuKeys;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.R;

/**
 * Quick menu over any app: swipe in from an edge (bottom, left or right, chosen in Settings) and a
 * bar comes out with Back | Vol− Vol+ | Home (stacked on the sides). A small touch zone in the
 * middle of that edge catches the swipe; a faint handle drawn on it shows where to swipe (and
 * that the service is running).
 *
 * Back and Vol± are fake MCU keys ({@link McuKeys}, the same path as the steering wheel and the
 * NWD bottom bar); Home opens the home screen. Holding Vol± repeats. The bar goes away after a few
 * seconds without use or with a touch outside it.
 *
 * Started by {@link com.k4811.launcher.LauncherApp}. Settings › Main › "Edge menu" picks the edge or
 * turns it off; the service checks the setting every few seconds, so it applies right away.
 */
public class EdgeMenuService extends Service {

    private static final String TAG = "K4811EdgeMenu";
    /**
     * Touch zone, in design units (1280×720): how deep it goes into the screen and how long it is
     * along the edge. Deep enough for panels that miss the last rows of pixels; only the middle of
     * the edge, so the rest of it stays free for the apps.
     */
    private static final float ZONE_DEPTH = 30;
    private static final float ZONE_LENGTH_BOTTOM = 380;
    private static final float ZONE_LENGTH_SIDE = 300;
    /** How far in the finger must go to open the menu. */
    private static final float SWIPE = 24;
    /** The bar's distance from its edge. */
    private static final float MARGIN = 22;
    private static final long HIDE_MS = 4500;
    private static final long CONFIG_POLL_MS = 3000;
    /** Holding Vol±: first repeat after this, then every REPEAT_MS (like the NWD bottom bar). */
    private static final long REPEAT_DELAY_MS = 800;
    private static final long REPEAT_MS = 100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideMenu;
    private final Runnable pollConfig = new Runnable() {
        @Override
        public void run() {
            applyConfig();
            main.postDelayed(this, CONFIG_POLL_MS);
        }
    };

    private WindowManager wm;
    private float scale = 1f;
    private View strip;
    /** Edge in use (LauncherConfig.EDGE_MENU_*); OFF = no zone. */
    private int side = LauncherConfig.EDGE_MENU_OFF;
    private View menu;
    private boolean menuShown;
    private Runnable repeat;

    public static void start(Context c) {
        try {
            c.startService(new Intent(c, EdgeMenuService.class));
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
        main.post(pollConfig);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        removeMenu();
        removeStrip();
        super.onDestroy();
    }

    /** Zone on the chosen edge; moved when the edge changes, removed when the menu is off. */
    private void applyConfig() {
        int next = LauncherConfig.edgeMenuSide(this);
        if (next == side && (strip != null || side == LauncherConfig.EDGE_MENU_OFF)) return;
        removeMenu();
        removeStrip();
        side = next;
        if (side != LauncherConfig.EDGE_MENU_OFF) addStrip();
    }

    private boolean vertical() {
        return side == LauncherConfig.EDGE_MENU_LEFT || side == LauncherConfig.EDGE_MENU_RIGHT;
    }

    /** How far the finger went into the screen from the edge, in pixels. */
    private float inward(float dx, float dy) {
        if (side == LauncherConfig.EDGE_MENU_LEFT) return dx;
        if (side == LauncherConfig.EDGE_MENU_RIGHT) return -dx;
        return -dy;
    }

    // ------------------------------------------------------------------ strip

    @SuppressLint("ClickableViewAccessibility")
    private void addStrip() {
        Ui.applyThemeColors(this);
        Handle v = new Handle();
        final float[] down = {0, 0};
        final boolean[] fired = {false};
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    fired[0] = false;
                    v.setLit(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!fired[0] && inward(e.getRawX() - down[0], e.getRawY() - down[1]) > px(SWIPE)) {
                        fired[0] = true;
                        showMenu();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setLit(false);
                    return true;
                default:
                    return true;
            }
        });
        int depth = Math.round(px(ZONE_DEPTH));
        int length = Math.round(px(vertical() ? ZONE_LENGTH_SIDE : ZONE_LENGTH_BOTTOM));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            vertical() ? depth : length, vertical() ? length : depth,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = side == LauncherConfig.EDGE_MENU_LEFT ? Gravity.LEFT | Gravity.CENTER_VERTICAL
            : side == LauncherConfig.EDGE_MENU_RIGHT ? Gravity.RIGHT | Gravity.CENTER_VERTICAL
            : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        try {
            wm.addView(v, lp);
            strip = v;
            Log.i(TAG, "zone on edge " + side + " (" + lp.width + "×" + lp.height + " px, scale " + scale + ")");
        } catch (Exception e) {
            Log.w(TAG, "zone refused: " + e.getMessage());
        }
    }

    /** The touch zone: transparent, with a short rounded line against the edge. */
    private final class Handle extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean lit;

        Handle() {
            super(EdgeMenuService.this);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeWidth(px(5));
        }

        void setLit(boolean on) {
            lit = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            line.setColor(lit ? Ui.TEXT : Ui.TEXT_MUTED);
            line.setAlpha(lit ? 230 : 120);
            float w = getWidth();
            float h = getHeight();
            float half = px(56);
            float inset = px(8);
            if (side == LauncherConfig.EDGE_MENU_LEFT) c.drawLine(inset, h / 2 - half, inset, h / 2 + half, line);
            else if (side == LauncherConfig.EDGE_MENU_RIGHT) c.drawLine(w - inset, h / 2 - half, w - inset, h / 2 + half, line);
            else c.drawLine(w / 2 - half, h - inset, w / 2 + half, h - inset, line);
        }
    }

    private void removeStrip() {
        if (strip == null) return;
        try {
            wm.removeView(strip);
        } catch (Exception ignored) {
        }
        strip = null;
    }

    // ------------------------------------------------------------------ menu

    @SuppressLint("ClickableViewAccessibility")
    private void showMenu() {
        if (menuShown) {
            keepOpen();
            return;
        }
        Ui.applyThemeColors(this);
        View bar = buildBar();
        // A touch outside the bar closes it (ACTION_OUTSIDE).
        bar.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideMenu();
            return false;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        int margin = Math.round(px(MARGIN));
        if (side == LauncherConfig.EDGE_MENU_LEFT) {
            lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
            lp.x = margin;
        } else if (side == LauncherConfig.EDGE_MENU_RIGHT) {
            lp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
            lp.x = margin;
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = margin;
        }
        try {
            wm.addView(bar, lp);
        } catch (Exception e) {
            Log.w(TAG, "menu refused: " + e.getMessage());
            return;
        }
        menu = bar;
        menuShown = true;
        // In: comes out of its edge and settles with a small bounce.
        bar.setAlpha(0f);
        setOffset(bar, px(60));
        bar.animate().alpha(1f).translationX(0).translationY(0).setDuration(280)
            .setInterpolator(new OvershootInterpolator(1.2f)).start();
        keepOpen();
    }

    private void keepOpen() {
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_MS);
    }

    private void hideMenu() {
        main.removeCallbacks(hide);
        stopRepeat();
        if (!menuShown || menu == null) return;
        menuShown = false;
        View bar = menu;
        bar.animate().cancel();
        float out = px(40);
        bar.animate().alpha(0f)
            .translationX(side == LauncherConfig.EDGE_MENU_LEFT ? -out : side == LauncherConfig.EDGE_MENU_RIGHT ? out : 0)
            .translationY(vertical() ? 0 : out).setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                try {
                    wm.removeView(bar);
                } catch (Exception ignored) {
                }
                if (menu == bar) menu = null;
            }).start();
    }

    /** Moves the bar {@code by} pixels back towards its edge (start of the entry animation). */
    private void setOffset(View bar, float by) {
        bar.setTranslationX(side == LauncherConfig.EDGE_MENU_LEFT ? -by : side == LauncherConfig.EDGE_MENU_RIGHT ? by : 0);
        bar.setTranslationY(vertical() ? 0 : by);
    }

    private void removeMenu() {
        if (menu == null) return;
        try {
            wm.removeView(menu);
        } catch (Exception ignored) {
        }
        menu = null;
        menuShown = false;
    }

    /**
     * Pill: Back | Vol− Vol+ | Home, thin dividers between the groups. On the side edges it stands
     * up: Back, Vol+, Vol−, Home from top to bottom.
     */
    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(vertical() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(px(10));
        bar.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        bg.setCornerRadius(px(56));
        bg.setStroke(Math.max(1, Math.round(px(1.5f))), Ui.OUTLINE);
        setBackground(bar, bg);

        bar.addView(button(IconView.Icon.CHEVRON_LEFT, getString(R.string.overlay_back), false,
            () -> McuKeys.press(this, McuKeys.BACK)));
        bar.addView(divider());
        View down = button(IconView.Icon.MINUS, "Vol−", true, () -> McuKeys.press(this, McuKeys.VOL_DOWN));
        View up = button(IconView.Icon.PLUS, "Vol+", true, () -> McuKeys.press(this, McuKeys.VOL_UP));
        // Lying down: − then + (left to right). Standing up: + on top.
        bar.addView(vertical() ? up : down);
        bar.addView(vertical() ? down : up);
        bar.addView(divider());
        bar.addView(button(IconView.Icon.HOME, getString(R.string.overlay_home), false, this::goHome));
        return bar;
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (Exception e) {
            McuKeys.press(this, McuKeys.HOME);
        }
    }

    /**
     * Icon over a small label. {@code repeats}: acts on touch and repeats while held (volume);
     * otherwise acts on release and closes the menu.
     */
    @SuppressLint("ClickableViewAccessibility")
    private View button(IconView.Icon icon, String label, boolean repeats, Runnable action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Ui.PRESSED);
        pressed.setCornerRadius(px(44));
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        setBackground(b, states);

        IconView iv = new IconView(this);
        iv.set(icon, IconView.Style.NONE).color(Ui.TEXT);
        iv.setClickable(false);
        iv.setFocusable(false);
        int is = Math.round(px(52));
        b.addView(iv, new LinearLayout.LayoutParams(is, is));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Ui.TEXT_MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        t.setTypeface(Ui.medium(this));
        t.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Math.round(px(2));
        b.addView(t, tlp);

        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    keepOpen();
                    if (repeats) {
                        action.run();
                        startRepeat(action);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    release(v);
                    if (!repeats && inside(v, e)) {
                        action.run();
                        hideMenu();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    release(v);
                    return true;
                default:
                    return true;
            }
        });
        int w = Math.round(px(124));
        int h = Math.round(px(96));
        return wrap(b, w, h);
    }

    private void release(View v) {
        v.setPressed(false);
        v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new OvershootInterpolator(3f)).start();
        stopRepeat();
        keepOpen();
    }

    private static boolean inside(View v, MotionEvent e) {
        return e.getX() >= 0 && e.getY() >= 0 && e.getX() <= v.getWidth() && e.getY() <= v.getHeight();
    }

    private void startRepeat(Runnable action) {
        stopRepeat();
        repeat = new Runnable() {
            @Override
            public void run() {
                action.run();
                keepOpen();
                main.postDelayed(this, REPEAT_MS);
            }
        };
        main.postDelayed(repeat, REPEAT_DELAY_MS);
    }

    private void stopRepeat() {
        if (repeat != null) main.removeCallbacks(repeat);
        repeat = null;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(Ui.HAIRLINE_STRONG);
        int thin = Math.max(1, Math.round(px(1)));
        int len = Math.round(px(48));
        int gap = Math.round(px(10));
        LinearLayout.LayoutParams lp = vertical()
            ? new LinearLayout.LayoutParams(len, thin) : new LinearLayout.LayoutParams(thin, len);
        if (vertical()) {
            lp.topMargin = gap;
            lp.bottomMargin = gap;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
        } else {
            lp.leftMargin = gap;
            lp.rightMargin = gap;
        }
        d.setLayoutParams(lp);
        return d;
    }

    private static View wrap(View v, int w, int h) {
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }

    @SuppressWarnings("deprecation")
    private static void setBackground(View v, android.graphics.drawable.Drawable d) {
        v.setBackgroundDrawable(d);
    }

    private float px(float design) {
        return design * scale;
    }
}
