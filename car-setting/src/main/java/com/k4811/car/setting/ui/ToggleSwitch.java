package com.k4811.car.setting.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.k4811.common.ui.Ui;

/**
 * Interruptor liga/desliga desenhado (trilho + bolinha). Sem PNG nem AndroidX.
 */
public final class ToggleSwitch extends View {

    private static final long ANIM_MS = 160;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean on;
    /** 0 = desligado, 1 = ligado (posição da bolinha). */
    private float progress;
    private ValueAnimator animator;

    public ToggleSwitch(Context c) {
        this(c, null);
    }

    public ToggleSwitch(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setFocusable(true);
        track.setStyle(Paint.Style.FILL);
        thumb.setStyle(Paint.Style.FILL);
    }

    public void setOn(boolean on, boolean animate) {
        if (this.on == on && (animator == null || !animator.isRunning())) {
            progress = on ? 1f : 0f;
            invalidate();
            return;
        }
        this.on = on;
        float target = on ? 1f : 0f;
        if (!animate) {
            if (animator != null) animator.cancel();
            progress = target;
            invalidate();
            return;
        }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public boolean isOn() {
        return on;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(Ui.dp(getContext(), 72));
        int h = Math.round(Ui.dp(getContext(), 40));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float pad = Ui.dp(getContext(), 2);
        float radius = h / 2f;

        // Trilho.
        rect.set(0, 0, w, h);
        track.setColor(on || progress > 0.5f ? Ui.ACCENT : Ui.OUTLINE);
        if (!isEnabled()) track.setAlpha(100);
        else track.setAlpha(255);
        c.drawRoundRect(rect, radius, radius, track);

        // Bolinha.
        float thumbR = radius - pad;
        float cx = pad + thumbR + progress * (w - 2f * (pad + thumbR));
        float cy = h / 2f;
        thumb.setColor(Ui.TEXT);
        if (!isEnabled()) thumb.setAlpha(140);
        else thumb.setAlpha(255);
        c.drawCircle(cx, cy, thumbR, thumb);

        if (isPressed() && isEnabled()) {
            track.setColor(Ui.PRESSED);
            track.setAlpha(60);
            c.drawRoundRect(rect, radius, radius, track);
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
