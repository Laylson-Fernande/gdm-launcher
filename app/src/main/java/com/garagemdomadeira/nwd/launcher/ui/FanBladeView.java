package com.garagemdomadeira.nwd.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Fan blade: spins while the hardware is on (`mcu_smart_fan_switch`). */
public class FanBladeView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path blade = new Path();
    private float angle;
    private boolean spinning;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!spinning) return;
            angle = (angle + 14f) % 360f;
            invalidate();
            postDelayed(this, 16);
        }
    };

    public FanBladeView(Context c) {
        this(c, null);
    }

    public FanBladeView(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 3));
        stroke.setColor(Ui.OUTLINE_STRONG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.TEXT);
    }

    public void setSpinning(boolean on) {
        if (spinning == on) return;
        spinning = on;
        fill.setColor(on ? Ui.ACCENT : Ui.TEXT);
        stroke.setColor(on ? Ui.ACCENT : Ui.OUTLINE_STRONG);
        if (on) {
            removeCallbacks(tick);
            post(tick);
        } else {
            removeCallbacks(tick);
            invalidate();
        }
    }

    public boolean isSpinning() {
        return spinning;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.82f;
        c.save();
        c.translate(cx, cy);
        c.rotate(angle);
        for (int i = 0; i < 3; i++) {
            c.save();
            c.rotate(i * 120f);
            blade.reset();
            blade.moveTo(0, 0);
            blade.quadTo(r * 0.45f, -r * 0.55f, 0, -r);
            blade.quadTo(-r * 0.35f, -r * 0.45f, 0, 0);
            c.drawPath(blade, fill);
            c.restore();
        }
        c.drawCircle(0, 0, r * 0.14f, fill);
        c.restore();
        c.drawCircle(cx, cy, r, stroke);
    }
}
