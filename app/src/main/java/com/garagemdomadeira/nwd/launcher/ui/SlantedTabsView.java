package com.garagemdomadeira.nwd.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Tabs with a slanted right side (FM1, FM2, …). The active tab has a light
 * outline and joins the baseline; the others are gray.
 */
public class SlantedTabsView extends View {

    public interface OnTabSelected {
        void onTabSelected(int index);
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private String[] labels = new String[0];
    private int selected;
    private int pressed = -1;
    private OnTabSelected listener;

    public SlantedTabsView(Context c) {
        this(c, null);
    }

    public SlantedTabsView(Context c, AttributeSet a) {
        super(c, a);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 1.5f));
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Ui.medium(c));
        text.setTextSize(Ui.dp(c, 23));
    }

    public void setLabels(String[] labels) {
        this.labels = labels;
        invalidate();
    }

    public void setSelected(int index) {
        if (selected != index) {
            selected = index;
            invalidate();
        }
    }

    public void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    private float slant() {
        return getHeight() * 0.42f;
    }

    private float tabWidth() {
        int n = Math.max(1, labels.length);
        return (getWidth() - slant() - getPaddingLeft()) / n;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (labels.length == 0) return;
        float h = getHeight();
        float top = line.getStrokeWidth();
        float base = h - line.getStrokeWidth();
        float s = slant();
        float tw = tabWidth();
        float x0 = getPaddingLeft();

        // Inactive ones first, the active one on top.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < labels.length; i++) {
                boolean active = i == selected;
                if ((pass == 0) == active) continue;
                float l = x0 + i * tw;
                float r = l + tw;
                // Right-leaning parallelogram; neighboring tabs share the edge.
                path.reset();
                path.moveTo(i == 0 ? l : l + s, base);
                path.lineTo(l, top);
                path.lineTo(r, top);
                path.lineTo(r + s, base);
                if (active) {
                    fill.setColor(Ui.BG_SOFT);
                    c.drawPath(path, fill);
                }
                if (i == pressed && !active) {
                    fill.setColor(Ui.SURFACE_HIGH);
                    c.drawPath(path, fill);
                }
                line.setColor(active ? Ui.TEXT : Ui.OUTLINE_STRONG);
                c.drawPath(path, line);

                text.setColor(active ? Ui.TEXT : Ui.TEXT_MUTED);
                float tx = l + (tw + s) / 2f;
                float ty = h / 2f - (text.descent() + text.ascent()) / 2f;
                c.drawText(labels[i], tx, ty, text);
            }
        }
        // Baseline to the left of the active tab (like an open folder).
        line.setColor(Ui.TEXT);
        c.drawLine(0, base, x0 + selected * tw, base, line);
    }

    private int indexAt(float x) {
        float tw = tabWidth();
        int i = (int) ((x - getPaddingLeft()) / tw);
        return i >= 0 && i < labels.length ? i : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = indexAt(e.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int i = indexAt(e.getX());
                if (i >= 0 && i == pressed && listener != null) listener.onTabSelected(i);
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
