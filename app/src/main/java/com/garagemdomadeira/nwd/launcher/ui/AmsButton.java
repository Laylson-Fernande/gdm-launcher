package com.garagemdomadeira.nwd.launcher.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Central "◎ AMS" button with a ring; during the scan a cyan arc spins around it. */
public class AmsButton extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ValueAnimator spin = ValueAnimator.ofFloat(0f, 360f);

    private boolean busy;
    private float angle;

    public AmsButton(Context c) {
        this(c, null);
    }

    public AmsButton(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 4));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(Ui.dp(c, 4));
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Ui.ACCENT);
        text.setTypeface(Ui.medium(c));
        text.setTextSize(Ui.dp(c, 22));
        text.setColor(Ui.TEXT);
        icon.setColor(Ui.TEXT);
        spin.setDuration(1400);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(an -> {
            angle = (float) an.getAnimatedValue();
            invalidate();
        });
    }

    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        if (busy) spin.start();
        else spin.cancel();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        spin.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outer = Math.min(cx, cy) - ring.getStrokeWidth();
        float inner = outer - Ui.dp(getContext(), 9);

        // Outer ring.
        ring.setColor(isPressed() ? Ui.ACCENT : Ui.OUTLINE_STRONG);
        c.drawCircle(cx, cy, outer, ring);
        if (busy) {
            rect.set(cx - outer, cy - outer, cx + outer, cy + outer);
            c.drawArc(rect, angle, 90, false, arc);
        }

        // Inner disc with a subtle gradient.
        int top = isPressed() ? Ui.OUTLINE : Ui.HAIRLINE_STRONG;
        bg.setShader(new RadialGradient(cx, cy - inner * 0.4f, inner * 1.3f, top, Ui.PRESSED, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, inner, bg);

        // "◎ AMS": target icon + text, centered together.
        String label = "AMS";
        float textW = text.measureText(label);
        float iconR = Ui.dp(getContext(), 8);
        float gap = Ui.dp(getContext(), 6);
        float total = iconR * 2 + gap + textW;
        float startX = cx - total / 2f;
        float iconCx = startX + iconR;
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(Ui.dp(getContext(), 2.2f));
        icon.setColor(busy ? Ui.ACCENT : Ui.TEXT);
        c.drawCircle(iconCx, cy, iconR, icon);
        icon.setStyle(Paint.Style.FILL);
        c.drawCircle(iconCx, cy, iconR * 0.45f, icon);
        float ty = cy - (text.descent() + text.ascent()) / 2f;
        c.drawText(label, startX + iconR * 2 + gap, ty, text);
    }
}
