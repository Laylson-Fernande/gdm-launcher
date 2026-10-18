package com.k4811.audioset.ui;

import com.k4811.common.ui.Ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Simple vertical slider for EQ bands and channel volume. */
public class VerticalSlider extends View {

    public interface Listener {
        void onChange(int value);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int min;
    private int max = 6;
    private int value = 3;
    private Integer center;
    private boolean enabled = true;
    private Listener listener;
    private Runnable onDragStart;

    public VerticalSlider(Context c) {
        this(c, null);
    }

    public VerticalSlider(Context c, AttributeSet a) {
        super(c, a);
        track.setColor(Ui.OUTLINE);
        track.setStrokeWidth(4f);
        track.setStrokeCap(Paint.Cap.ROUND);
        active.setColor(Ui.ACCENT);
        active.setStrokeWidth(4f);
        active.setStrokeCap(Paint.Cap.ROUND);
        thumb.setColor(Ui.ACCENT);
        thumb.setStyle(Paint.Style.FILL);
        centerLine.setColor(Ui.TEXT_DIM);
        centerLine.setStrokeWidth(2f);
        setClickable(true);
    }

    public void configure(int min, int max, Integer center) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        this.center = center;
        value = clamp(value);
        invalidate();
    }

    public void setValue(int value) {
        int v = clamp(value);
        if (this.value == v) return;
        this.value = v;
        invalidate();
    }

    public int getValue() {
        return value;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Called when a finger touches the slider, before the first {@link Listener#onChange}. */
    public void setOnDragStart(Runnable onDragStart) {
        this.onDragStart = onDragStart;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setAlpha(enabled ? 1f : 0.35f);
        super.setEnabled(enabled);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float top = 16f;
        float bottom = getHeight() - 16f;
        float span = Math.max(1f, bottom - top);
        c.drawLine(cx, top, cx, bottom, track);
        if (center != null && center >= min && center <= max) {
            float cy = yFor(center, top, span);
            c.drawLine(cx - 14f, cy, cx + 14f, cy, centerLine);
        }
        float vy = yFor(value, top, span);
        float midY = center != null ? yFor(center, top, span) : bottom;
        c.drawLine(cx, midY, cx, vy, active);
        c.drawCircle(cx, vy, 10f, thumb);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && onDragStart != null) onDragStart.run();
        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP) {
            float top = 16f;
            float bottom = getHeight() - 16f;
            float span = Math.max(1f, bottom - top);
            float y = Math.max(top, Math.min(bottom, event.getY()));
            float t = (bottom - y) / span;
            int next = Math.round(min + t * (max - min));
            next = clamp(next);
            if (next != value) {
                value = next;
                invalidate();
                if (listener != null) listener.onChange(value);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private float yFor(int v, float top, float span) {
        float t = (v - min) / (float) (max - min);
        return bottomY(top, span) - t * span;
    }

    private float bottomY(float top, float span) {
        return top + span;
    }

    private int clamp(int v) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
