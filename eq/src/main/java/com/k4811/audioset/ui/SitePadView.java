package com.k4811.audioset.ui;

import com.k4811.common.ui.Ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.k4811.audioset.R;
import com.k4811.common.eq.EqModel;

/** Front/rear × left/right pad for fader and balance. */
public class SitePadView extends View {

    public interface Listener {
        void onSite(int fader, int balance);
    }

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final String frontText;
    private final String rearText;

    private int fader = EqModel.SITE_CENTER;
    private int balance = EqModel.SITE_CENTER;
    private Listener listener;

    public SitePadView(Context c) {
        this(c, null);
    }

    public SitePadView(Context c, AttributeSet a) {
        super(c, a);
        frontText = c.getString(R.string.eq_site_pad_front);
        rearText = c.getString(R.string.eq_site_pad_rear);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(3f);
        frame.setColor(Ui.OUTLINE_STRONG);
        grid.setColor(Ui.OUTLINE);
        grid.setStrokeWidth(1.5f);
        label.setColor(Ui.TEXT_MUTED);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(18f);
        dot.setColor(Ui.ACCENT);
        dot.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setSite(int fader, int balance) {
        this.fader = fader;
        this.balance = balance;
        invalidate();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = 28f;
        rect.set(pad, pad + 20f, getWidth() - pad, getHeight() - pad - 20f);
        c.drawRoundRect(rect, 16f, 16f, frame);
        float cx = rect.centerX();
        float cy = rect.centerY();
        c.drawLine(rect.left + 12f, cy, rect.right - 12f, cy, grid);
        c.drawLine(cx, rect.top + 12f, cx, rect.bottom - 12f, grid);
        c.drawText(frontText, cx, rect.top - 6f, label);
        c.drawText(rearText, cx, rect.bottom + 22f, label);

        float x = rect.left + (balance / (float) EqModel.SITE_MAX) * rect.width();
        float y = rect.top + (fader / (float) EqModel.SITE_MAX) * rect.height();
        c.drawCircle(x, y, 14f, dot);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP) {
            float pad = 28f;
            float left = pad;
            float top = pad + 20f;
            float right = getWidth() - pad;
            float bottom = getHeight() - pad - 20f;
            float x = Math.max(left, Math.min(right, event.getX()));
            float y = Math.max(top, Math.min(bottom, event.getY()));
            int b = Math.round((x - left) / Math.max(1f, right - left) * EqModel.SITE_MAX);
            int f = Math.round((y - top) / Math.max(1f, bottom - top) * EqModel.SITE_MAX);
            b = clamp(b);
            f = clamp(f);
            if (f != fader || b != balance) {
                fader = f;
                balance = b;
                invalidate();
                if (listener != null) listener.onSite(fader, balance);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > EqModel.SITE_MAX) return EqModel.SITE_MAX;
        return v;
    }
}
