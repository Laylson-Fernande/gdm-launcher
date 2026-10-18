package com.k4811.radio.ui;

import com.k4811.common.ui.Ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.k4811.common.radio.BandRange;

/**
 * Draggable tuning dial with a fixed ▼ marker in the center. While dragging it calls
 * {@link Listener#onPreview}; on release, {@link Listener#onTune} with the frequency already
 * snapped to the band step.
 */
public class TunerDialView extends View {

    public interface Listener {
        void onPreview(Integer frequency);

        void onTune(int frequency);
    }

    private static final int TICK_UNIT = 10; // 100 kHz on FM, 10 kHz on AM
    private static final int LABEL_EVERY = 100; // label every 1 MHz (FM) or 100 kHz (AM)

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float unitPx;

    private int frequency = 8750;
    private BandRange range = BandRange.FM_DEFAULT;
    private boolean fm = true;
    private Listener listener;

    private boolean dragging;
    private float dragFreq;
    private float lastX;

    public TunerDialView(Context c) {
        this(c, null);
    }

    public TunerDialView(Context c, AttributeSet a) {
        super(c, a);
        unitPx = Ui.dp(c, 12.5f);
        tick.setStrokeCap(Paint.Cap.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Ui.medium(c));
        label.setTextSize(Ui.dp(c, 22));
        marker.setStyle(Paint.Style.FILL);
        marker.setColor(Ui.TEXT);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setFrequency(int frequency, BandRange range, boolean fm) {
        this.range = range;
        this.fm = fm;
        if (this.frequency != frequency) {
            this.frequency = frequency;
            if (!dragging) invalidate();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float shown = dragging ? dragFreq : frequency;

        float markerH = h * 0.13f;
        float tickTop = markerH + h * 0.06f;
        float tickBase = h * 0.58f;
        float maxTick = tickBase - tickTop;

        float halfUnits = cx / unitPx * TICK_UNIT;
        int first = (int) Math.ceil((shown - halfUnits) / TICK_UNIT) * TICK_UNIT;
        int last = (int) Math.floor((shown + halfUnits) / TICK_UNIT) * TICK_UNIT;

        for (int v = first; v <= last; v += TICK_UNIT) {
            if (v < range.min - TICK_UNIT || v > range.max + TICK_UNIT) continue;
            float x = cx + (v - shown) / TICK_UNIT * unitPx;
            float dist = Math.abs(x - cx) / cx;
            float alpha = Math.max(0.12f, 1f - dist * dist);
            boolean major = v % LABEL_EVERY == 0;
            boolean half = v % (LABEL_EVERY / 2) == 0;
            float th = major ? maxTick : half ? maxTick * 0.62f : maxTick * 0.38f;

            tick.setColor(withAlpha(major ? Ui.TEXT : Ui.TEXT_MUTED, alpha));
            tick.setStrokeWidth(Ui.dp(getContext(), major ? 2.2f : 1.4f));
            c.drawLine(x, tickBase - th, x, tickBase, tick);

            if (major) {
                boolean near = Math.abs(v - shown) < LABEL_EVERY / 2f;
                label.setColor(withAlpha(near ? Ui.TEXT : Ui.TEXT_MUTED, alpha));
                String text = fm ? String.valueOf(v / 100) : String.valueOf(v);
                c.drawText(text, x, h - Ui.dp(getContext(), 6), label);
            }
        }

        // Center needle and ▼ marker.
        tick.setColor(dragging ? Ui.ACCENT : Ui.TEXT);
        tick.setStrokeWidth(Ui.dp(getContext(), 3f));
        c.drawLine(cx, tickTop - h * 0.02f, cx, tickBase + h * 0.04f, tick);
        marker.setColor(dragging ? Ui.ACCENT : Ui.TEXT);
        float mw = markerH * 0.75f;
        path.reset();
        path.moveTo(cx - mw, 0);
        path.lineTo(cx + mw, 0);
        path.lineTo(cx, markerH);
        path.close();
        c.drawPath(path, marker);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                dragFreq = frequency;
                lastX = e.getX();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX;
                lastX = e.getX();
                dragFreq = Math.max(range.min, Math.min(range.max, dragFreq - dx / unitPx * TICK_UNIT));
                if (listener != null) listener.onPreview(range.snap(Math.round(dragFreq)));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int target = range.snap(Math.round(dragFreq));
                dragging = false;
                frequency = target;
                if (listener != null) {
                    listener.onPreview(null);
                    listener.onTune(target);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onPreview(null);
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
