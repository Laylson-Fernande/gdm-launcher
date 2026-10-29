package com.k4811.launcher.status;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.k4811.common.ui.Ui;

/**
 * Barra deslizante do brilho, no estilo do cartão de volume: trilho, parte preenchida na cor do tema
 * e a bolinha. Arrastar ou tocar em qualquer ponto muda o valor.
 */
final class BrightnessBar extends View {

    interface Listener {
        /** {@code done} = o dedo saiu da barra (último valor). */
        void onValue(int value, boolean done);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int min;
    private int max = 10;
    private int value;
    private Listener listener;

    BrightnessBar(Context c) {
        super(c);
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Ui.OUTLINE);
        fill.setColor(Ui.ACCENT);
        knob.setColor(Ui.TEXT);
    }

    void setRange(int min, int max) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        invalidate();
    }

    void setValue(int v) {
        value = Math.max(min, Math.min(max, v));
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    private float radius() {
        return getHeight() * 0.22f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = radius();
        float left = getPaddingLeft() + r;
        float right = getWidth() - getPaddingRight() - r;
        float y = getHeight() / 2f;
        float stroke = Math.max(4f, getHeight() * 0.12f);
        track.setStrokeWidth(stroke);
        fill.setStrokeWidth(stroke);
        float x = left + (right - left) * (value - min) / (float) (max - min);
        c.drawLine(left, y, right, y, track);
        c.drawLine(left, y, x, y, fill);
        c.drawCircle(x, y, r, knob);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
            && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            return true;
        }
        float r = radius();
        float left = getPaddingLeft() + r;
        float right = getWidth() - getPaddingRight() - r;
        float f = Math.max(0f, Math.min(1f, (e.getX() - left) / Math.max(1f, right - left)));
        int v = min + Math.round(f * (max - min));
        boolean done = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (v != value || done) {
            value = v;
            invalidate();
            if (listener != null) listener.onValue(v, done);
        }
        return true;
    }
}
