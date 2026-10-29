package com.k4811.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;

import com.k4811.common.ui.Ui;

/**
 * Botão de acesso rápido aos alertas (radares, lombadas, pedágios, passagens de nível), no mesmo
 * círculo dos botões de zoom: a câmera de radar na cor do texto quando ligados; apagada e riscada
 * quando desligados.
 */
public class AlertsButton extends MapButton {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path beam = new Path();
    private final RectF body = new RectF(-9, -4, 3, 4);
    private boolean on = true;

    public AlertsButton(Context c) {
        this(c, null);
    }

    public AlertsButton(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        // A câmera do RoadSign, centrada: corpo, lente e o poste.
        beam.moveTo(3, -1.5f);
        beam.lineTo(9, -4.5f);
        beam.lineTo(9, 4.5f);
        beam.lineTo(3, 1.5f);
        beam.close();
    }

    public void setOn(boolean on) {
        if (this.on == on) return;
        this.on = on;
        invalidate();
    }

    @Override
    protected void drawContent(Canvas c) {
        int color = on ? Ui.TEXT_SOFT : Ui.TEXT_MUTED;
        fill.setColor(color);
        stroke.setColor(color);
        stroke.setStrokeWidth(2f);
        c.save();
        c.scale(0.8f, 0.8f);
        c.drawRoundRect(body, 1.5f, 1.5f, fill);
        c.drawPath(beam, fill);
        c.drawLine(-5, 4, -6, 9, stroke);
        c.restore();
        if (!on) {
            // Risco com uma borda na cor do botão, para separar do desenho.
            stroke.setStrokeWidth(4.5f);
            stroke.setColor(Ui.SURFACE_TOP);
            c.drawLine(-8.5f, -8.5f, 8.5f, 8.5f, stroke);
            stroke.setStrokeWidth(2f);
            stroke.setColor(Ui.DANGER);
            c.drawLine(-8.5f, -8.5f, 8.5f, 8.5f, stroke);
        }
    }
}
