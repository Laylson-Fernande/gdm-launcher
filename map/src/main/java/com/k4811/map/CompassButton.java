package com.k4811.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;

import com.k4811.common.ui.Ui;

/**
 * Botão da bússola, no mesmo círculo dos botões de zoom.
 *
 * Direção do carro para cima: uma agulha (ponta vermelha = norte) que gira com o mapa, mostrando
 * onde fica o norte. Norte para cima: a agulha parada e um "N" em cima. Toque troca o modo.
 */
public class CompassButton extends MapButton {

    /** Vermelho do norte da agulha (fixo, como numa bússola). */
    private static final int NORTH_RED = 0xFFE53935;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path north = new Path();
    private final Path south = new Path();
    private boolean headingUp = true;
    private float bearing;

    public CompassButton(Context c) {
        this(c, null);
    }

    public CompassButton(Context c, AttributeSet a) {
        super(c, a);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Ui.semiBold(c), Typeface.BOLD));
        // Agulha: metade de cima vermelha, de baixo na cor do texto.
        north.moveTo(0, -9);
        north.lineTo(3.2f, 0);
        north.lineTo(-3.2f, 0);
        north.close();
        south.moveTo(0, 9);
        south.lineTo(3.2f, 0);
        south.lineTo(-3.2f, 0);
        south.close();
    }

    /** Modo atual: direção do carro para cima (true) ou norte para cima (false). */
    public void setHeadingUp(boolean on) {
        if (headingUp == on) return;
        headingUp = on;
        invalidate();
    }

    /** Rumo do mapa (o que está para cima), em graus. */
    public void setBearing(float b) {
        if (Math.abs(b - bearing) < 0.5f) return;
        bearing = b;
        invalidate();
    }

    @Override
    protected void drawContent(Canvas c) {
        if (headingUp) {
            // O norte do mapa está girado de -rumo na tela.
            c.rotate(-bearing);
            drawNeedle(c, 0);
        } else {
            drawNeedle(c, 3.5f);
            text.setColor(NORTH_RED);
            text.setTextSize(8.5f);
            c.drawText("N", 0, -6.5f, text);
        }
    }

    private void drawNeedle(Canvas c, float dy) {
        c.save();
        if (dy != 0) {
            c.translate(0, dy);
            c.scale(0.7f, 0.7f);
        }
        fill.setColor(NORTH_RED);
        c.drawPath(north, fill);
        fill.setColor(Ui.TEXT_SOFT);
        c.drawPath(south, fill);
        c.restore();
    }
}
