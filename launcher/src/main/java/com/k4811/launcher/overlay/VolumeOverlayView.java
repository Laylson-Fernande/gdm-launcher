package com.k4811.launcher.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.k4811.common.ui.Ui;

/**
 * The volume card drawn over other apps: a dark pill with a soft shadow, the speaker (its waves
 * follow the level, an X at zero), a bar with the theme color gradient and a glowing tip, and the
 * number on the right. The bar glides to each new value.
 */
final class VolumeOverlayView extends View {

    /** Card size in design units (1280×720 screen); {@code scale} turns them into pixels. */
    static final float WIDTH = 560;
    static final float HEIGHT = 112;
    /** Room around the card for the shadow. */
    private static final float SHADOW = 16;

    private final float scale;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int volume;
    private int max = 40;
    /** Level being drawn (0..1), animated towards volume/max. */
    private float shown;
    private ValueAnimator glide;

    VolumeOverlayView(Context c, float scale) {
        super(c);
        this.scale = scale;
        // Shadow and glow (setShadowLayer) need a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        border.setStyle(Paint.Style.STROKE);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeCap(Paint.Cap.ROUND);
        number.setTextAlign(Paint.Align.RIGHT);
        number.setTypeface(Ui.semiBold(c));
        applyColors();
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

    /** New value: the number changes at once, the bar glides there ({@code animate} false = jumps). */
    void setVolume(int volume, int max, boolean animate) {
        this.volume = volume;
        this.max = Math.max(1, max);
        float target = Math.max(0f, Math.min(1f, volume / (float) this.max));
        if (glide != null) glide.cancel();
        if (!animate) {
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

    @Override
    protected void onDraw(Canvas c) {
        float l = px(SHADOW);
        float t = px(SHADOW);
        float r = l + px(WIDTH);
        float b = t + px(HEIGHT);
        float h = b - t;
        float cy = (t + b) / 2f;

        // Card: pill with a soft shadow and a thin border.
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, h / 2f, h / 2f, card);
        c.drawRoundRect(rect, h / 2f, h / 2f, border);

        // Speaker in a tinted circle.
        float bx = l + h / 2f;
        float br = h / 2f - px(14);
        c.drawCircle(bx, cy, br, badge);
        drawSpeaker(c, bx, cy, br * 1.25f);

        // Number on the right.
        float textRight = r - h / 2f + px(8);
        number.setTextSize(px(40));
        String text = String.valueOf(volume);
        c.drawText(text, textRight, cy - (number.descent() + number.ascent()) / 2f, number);

        // Bar between the speaker and the number (room for "40").
        float x0 = bx + br + px(26);
        float x1 = textRight - number.measureText("40") - px(28);
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
        // Tip: a glowing dot.
        glow.setShadowLayer(px(12), 0, 0, Ui.ACCENT);
        c.drawCircle(xv, cy, px(9), glow);
        c.drawCircle(xv, cy, px(6), knob);
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
        if (volume <= 0) {
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

    private float px(float design) {
        return design * scale;
    }
}
