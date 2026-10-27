package com.k4811.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.k4811.map.route.Phrases;

/** Seta da manobra (virar, manter, retorno, rotatória, chegada), desenhada numa grade de 24×24. */
public class TurnIcon extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private Phrases.Shape shape = Phrases.Shape.STRAIGHT;

    public TurnIcon(Context c) {
        this(c, null);
    }

    public TurnIcon(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setColor(0xFFFFFFFF);
    }

    public void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    public void setShape(Phrases.Shape s) {
        if (s == shape) return;
        shape = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        c.save();
        c.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        c.scale(size / 24f, size / 24f);
        stroke.setStrokeWidth(2.6f);
        boolean mirror = shape == Phrases.Shape.SLIGHT_RIGHT || shape == Phrases.Shape.RIGHT
            || shape == Phrases.Shape.SHARP_RIGHT;
        if (mirror) c.scale(-1, 1, 12, 12);
        path.reset();
        switch (shape) {
            case STRAIGHT:
                c.drawLine(12, 21, 12, 5, stroke);
                head(c, 12, 3, 0);
                break;
            case SLIGHT_LEFT:
            case SLIGHT_RIGHT:
                path.moveTo(14, 21);
                path.lineTo(14, 14);
                path.lineTo(8.2f, 8.2f);
                c.drawPath(path, stroke);
                head(c, 6.8f, 6.8f, -45);
                break;
            case LEFT:
            case RIGHT:
                path.moveTo(15, 21);
                path.lineTo(15, 13);
                path.quadTo(15, 9, 11, 9);
                path.lineTo(7, 9);
                c.drawPath(path, stroke);
                head(c, 5, 9, -90);
                break;
            case SHARP_LEFT:
            case SHARP_RIGHT:
                path.moveTo(16, 4);
                path.lineTo(16, 12);
                path.lineTo(8.5f, 18.5f);
                c.drawPath(path, stroke);
                head(c, 7, 20, -135);
                break;
            case UTURN:
                path.moveTo(16, 21);
                path.lineTo(16, 10);
                path.quadTo(16, 5, 11.5f, 5);
                path.quadTo(7, 5, 7, 10);
                path.lineTo(7, 15);
                c.drawPath(path, stroke);
                head(c, 7, 17, 180);
                break;
            case ROUNDABOUT:
                oval.set(7, 8, 17, 18);
                c.drawArc(oval, 100, 300, false, stroke);
                c.drawLine(12, 18, 12, 22, stroke);
                c.drawLine(15.6f, 9.4f, 18.5f, 6.5f, stroke);
                head(c, 19.8f, 5.2f, 45);
                break;
            case ARRIVE:
                c.drawCircle(12, 9, 6.5f, fill);
                path.moveTo(7.2f, 12.5f);
                path.lineTo(12, 21.5f);
                path.lineTo(16.8f, 12.5f);
                path.close();
                c.drawPath(path, fill);
                break;
        }
        c.restore();
    }

    /** Ponta da seta em (x, y), apontando no ângulo dado (0 = para cima, sentido horário). */
    private void head(Canvas c, float x, float y, float angle) {
        c.save();
        c.rotate(angle, x, y);
        path.reset();
        path.moveTo(x, y - 1);
        path.lineTo(x + 5, y + 5.5f);
        path.lineTo(x - 5, y + 5.5f);
        path.close();
        c.drawPath(path, fill);
        c.restore();
    }
}
