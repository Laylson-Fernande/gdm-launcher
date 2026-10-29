package com.k4811.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.k4811.common.ui.Ui;

/**
 * Botão redondo do mapa, no mesmo círculo dos botões de zoom (IconView, estilo CIRCLE). A subclasse
 * desenha o conteúdo numa grade de 24×24 centrada em (0,0).
 */
abstract class MapButton extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

    MapButton(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setFocusable(true);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(c, 1.5f));
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w, h) / 2f - border.getStrokeWidth();
        float cx = w / 2f;
        float cy = h / 2f;
        boolean pressed = isPressed();
        bg.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.2f, pressed ? Ui.OUTLINE : Ui.SURFACE_TOP,
            Ui.PRESSED, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, bg);
        border.setColor(pressed ? Ui.ACCENT : Ui.OUTLINE);
        c.drawCircle(cx, cy, r, border);

        float unit = Math.min(w, h) * 0.5f / 24f;
        c.save();
        c.translate(cx, cy);
        c.scale(unit, unit);
        drawContent(c);
        c.restore();
    }

    /** Conteúdo na grade de 24×24 centrada em (0,0). */
    protected abstract void drawContent(Canvas c);
}
