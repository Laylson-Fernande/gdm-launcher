package com.k4811.launcher.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.k4811.common.ui.Ui;

/**
 * The volume card drawn over other apps: a dark pill with a soft shadow, the speaker (its waves
 * follow the level, an X at zero or muted), − and + buttons around a bar with the theme color
 * gradient and a glowing tip, and the number on the right. The bar glides to each new value.
 *
 * It is also a control: − and + step the volume (held, they repeat), dragging or tapping the bar
 * sets it, and the speaker mutes/unmutes. The service does the actual change ({@link Listener}).
 */
final class VolumeOverlayView extends View {

    interface Listener {
        /** − or + (held: called again every {@link #REPEAT_MS} ms). */
        void onStep(int delta);

        /** Bar touched or dragged to {@code value}. */
        void onSeek(int value);

        void onToggleMute();

        /** A finger went down ({@code true}) or up: the card stays while it is touched. */
        void onTouching(boolean touching);
    }

    /** Card size in design units (1280×720 screen); {@code scale} turns them into pixels. */
    static final float WIDTH = 700;
    static final float HEIGHT = 112;
    /** Room around the card for the shadow. */
    private static final float SHADOW = 16;
    /** Radius of the − and + buttons. */
    private static final float BUTTON = 30;
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_MS = 120;

    private static final int NONE = 0;
    private static final int MINUS = 1;
    private static final int PLUS = 2;
    private static final int BAR = 3;
    private static final int SPEAKER = 4;

    private final float scale;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint button = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sign = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int volume;
    private int max = 40;
    private boolean muted;
    /** Level being drawn (0..1), animated towards volume/max. */
    private float shown;
    private ValueAnimator glide;
    private Listener listener;
    /** Part being touched (NONE, MINUS, PLUS, BAR, SPEAKER). */
    private int touching = NONE;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (touching != MINUS && touching != PLUS) return;
            if (listener != null) listener.onStep(touching == PLUS ? 1 : -1);
            postDelayed(this, REPEAT_MS);
        }
    };

    VolumeOverlayView(Context c, float scale) {
        super(c);
        this.scale = scale;
        // Shadow and glow (setShadowLayer) need a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        border.setStyle(Paint.Style.STROKE);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        sign.setStyle(Paint.Style.STROKE);
        sign.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeCap(Paint.Cap.ROUND);
        number.setTextAlign(Paint.Align.RIGHT);
        number.setTypeface(Ui.semiBold(c));
        applyColors();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Theme colors (they can change in the car-setting while the launcher runs). */
    void applyColors() {
        card.setColor(Ui.SURFACE);
        card.setAlpha(0xFA);
        card.setShadowLayer(px(14), 0, px(4), 0x99000000);
        border.setColor(Ui.OUTLINE);
        border.setStrokeWidth(Math.max(1f, px(1.5f)));
        badge.setColor(Ui.ACCENT_DIM);
        icon.setColor(Ui.ACCENT);
        iconStroke.setColor(Ui.ACCENT);
        button.setColor(Ui.SURFACE_HIGH);
        buttonPressed.setColor(Ui.PRESSED);
        sign.setColor(Ui.TEXT);
        track.setColor(Ui.OUTLINE);
        knob.setColor(Ui.TEXT);
        glow.setColor(Ui.ACCENT);
        number.setColor(Ui.TEXT);
        invalidate();
    }

    /** Full size of the view in pixels, shadow included. */
    int widthPx() {
        return Math.round(px(WIDTH + SHADOW * 2));
    }

    int heightPx() {
        return Math.round(px(HEIGHT + SHADOW * 2));
    }

    void setMuted(boolean on) {
        if (muted == on) return;
        muted = on;
        invalidate();
    }

    /** New value: the number changes at once, the bar glides there ({@code animate} false = jumps). */
    void setVolume(int volume, int max, boolean animate) {
        this.volume = volume;
        this.max = Math.max(1, max);
        float target = Math.max(0f, Math.min(1f, volume / (float) this.max));
        if (glide != null) glide.cancel();
        // Dragging: the bar follows the finger, no glide.
        if (!animate || touching == BAR) {
            shown = target;
            invalidate();
            return;
        }
        glide = ValueAnimator.ofFloat(shown, target);
        glide.setDuration(180);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        glide.start();
    }

    // ------------------------------------------------------------------ layout (pixels)

    private float left() {
        return px(SHADOW);
    }

    private float right() {
        return left() + px(WIDTH);
    }

    private float centerY() {
        return px(SHADOW) + px(HEIGHT) / 2f;
    }

    /** Speaker circle center and radius. */
    private float speakerX() {
        return left() + px(HEIGHT) / 2f;
    }

    private float speakerR() {
        return px(HEIGHT) / 2f - px(14);
    }

    private float textRight() {
        return right() - px(HEIGHT) / 2f + px(8);
    }

    private float minusX() {
        return speakerX() + speakerR() + px(20) + px(BUTTON);
    }

    private float plusX() {
        number.setTextSize(px(40));
        return textRight() - number.measureText("40") - px(20) - px(BUTTON);
    }

    /** Bar ends, between the buttons. */
    private float barStart() {
        return minusX() + px(BUTTON) + px(22);
    }

    private float barEnd() {
        return plusX() - px(BUTTON) - px(22);
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = left();
        float t = px(SHADOW);
        float r = right();
        float b = t + px(HEIGHT);
        float h = b - t;
        float cy = centerY();

        // Card: pill with a soft shadow and a thin border.
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, h / 2f, h / 2f, card);
        c.drawRoundRect(rect, h / 2f, h / 2f, border);

        // Speaker in a tinted circle.
        float bx = speakerX();
        float br = speakerR();
        c.drawCircle(bx, cy, br, touching == SPEAKER ? buttonPressed : badge);
        drawSpeaker(c, bx, cy, br * 1.25f);

        // Number on the right.
        float textRight = textRight();
        number.setTextSize(px(40));
        String text = String.valueOf(volume);
        c.drawText(text, textRight, cy - (number.descent() + number.ascent()) / 2f, number);

        // − and + buttons.
        drawButton(c, minusX(), cy, false, touching == MINUS);
        drawButton(c, plusX(), cy, true, touching == PLUS);

        // Bar between the buttons.
        float x0 = barStart();
        float x1 = barEnd();
        float barH = px(10);
        track.setStrokeWidth(barH);
        c.drawLine(x0, cy, x1, cy, track);
        float xv = x0 + (x1 - x0) * shown;
        if (shown > 0.001f) {
            fill.setStrokeWidth(barH);
            fill.setShader(new LinearGradient(x0, cy, Math.max(x0 + 1, xv), cy,
                Ui.ACCENT_DIM, Ui.ACCENT, Shader.TileMode.CLAMP));
            c.drawLine(x0, cy, xv, cy, fill);
        }
        // Tip: a glowing dot (bigger while dragging).
        float tip = touching == BAR ? 1.4f : 1f;
        glow.setShadowLayer(px(12), 0, 0, Ui.ACCENT);
        c.drawCircle(xv, cy, px(9) * tip, glow);
        c.drawCircle(xv, cy, px(6) * tip, knob);
    }

    private void drawButton(Canvas c, float cx, float cy, boolean plus, boolean pressed) {
        float rr = px(BUTTON);
        c.drawCircle(cx, cy, rr, pressed ? buttonPressed : button);
        float arm = rr * 0.42f;
        sign.setStrokeWidth(Math.max(2f, px(3.5f)));
        c.drawLine(cx - arm, cy, cx + arm, cy, sign);
        if (plus) c.drawLine(cx, cy - arm, cx, cy + arm, sign);
    }

    /** Filled speaker; 0, 1 or 2 waves by level, or an X when muted. {@code size} = icon box side. */
    private void drawSpeaker(Canvas c, float cx, float cy, float size) {
        float u = size / 24f;
        float ox = cx - size / 2f + u * 1.5f;
        float oy = cy - size / 2f;
        path.reset();
        path.moveTo(ox + 3 * u, oy + 9 * u);
        path.lineTo(ox + 7 * u, oy + 9 * u);
        path.lineTo(ox + 12 * u, oy + 4.5f * u);
        path.lineTo(ox + 12 * u, oy + 19.5f * u);
        path.lineTo(ox + 7 * u, oy + 15 * u);
        path.lineTo(ox + 3 * u, oy + 15 * u);
        path.close();
        c.drawPath(path, icon);
        iconStroke.setStrokeWidth(2f * u);
        if (volume <= 0 || muted) {
            c.drawLine(ox + 15.5f * u, oy + 9 * u, ox + 21.5f * u, oy + 15 * u, iconStroke);
            c.drawLine(ox + 21.5f * u, oy + 9 * u, ox + 15.5f * u, oy + 15 * u, iconStroke);
            return;
        }
        rect.set(ox + 9.5f * u, oy + 8 * u, ox + 17.5f * u, oy + 16 * u);
        c.drawArc(rect, -50, 100, false, iconStroke);
        if (volume * 2 >= max) {
            rect.set(ox + 8.5f * u, oy + 4.5f * u, ox + 21.5f * u, oy + 19.5f * u);
            c.drawArc(rect, -50, 100, false, iconStroke);
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = hit(e.getX(), e.getY());
                if (touching == NONE) return false;
                if (listener != null) listener.onTouching(true);
                if (touching == BAR) seek(e.getX());
                if (touching == MINUS || touching == PLUS) {
                    if (listener != null) listener.onStep(touching == PLUS ? 1 : -1);
                    postDelayed(repeat, REPEAT_DELAY_MS);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching == BAR) seek(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
                if (touching == SPEAKER && hit(e.getX(), e.getY()) == SPEAKER && listener != null) {
                    listener.onToggleMute();
                }
                if (touching == BAR) seek(e.getX());
                release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return true;
        }
    }

    private void release() {
        removeCallbacks(repeat);
        boolean was = touching != NONE;
        touching = NONE;
        invalidate();
        if (was && listener != null) listener.onTouching(false);
    }

    private void seek(float x) {
        float x0 = barStart();
        float x1 = barEnd();
        float f = Math.max(0f, Math.min(1f, (x - x0) / Math.max(1f, x1 - x0)));
        int v = Math.round(f * max);
        if (listener != null && v != volume) listener.onSeek(v);
    }

    /** Which part is under the finger (generous areas: it is a car screen). */
    private int hit(float x, float y) {
        float cy = centerY();
        if (Math.abs(y - cy) > px(HEIGHT) / 2f + px(8)) return NONE;
        float reach = px(BUTTON) * 1.35f;
        if (Math.abs(x - minusX()) <= reach) return MINUS;
        if (Math.abs(x - plusX()) <= reach) return PLUS;
        if (Math.abs(x - speakerX()) <= speakerR() + px(6)) return SPEAKER;
        if (x >= barStart() - px(20) && x <= barEnd() + px(20)) return BAR;
        return NONE;
    }

    private float px(float design) {
        return design * scale;
    }
}
