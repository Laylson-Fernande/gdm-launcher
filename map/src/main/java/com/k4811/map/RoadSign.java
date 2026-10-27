package com.k4811.map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.k4811.common.ui.Ui;
import com.k4811.map.road.RoadCell;

/**
 * Placa do mapa: o limite de velocidade (círculo branco com borda vermelha, como a placa R-19)
 * ou o ícone de um alerta (radar, radar de semáforo, lombada, pedágio, passagem de nível), numa
 * grade de 24×24. As cores da placa de limite são as da placa de trânsito, não as do tema.
 */
public class RoadSign extends View {

    /** Branco e vermelho da placa de trânsito (fixos, iguais em qualquer tema). */
    private static final int SIGN_WHITE = 0xFFFFFFFF;
    private static final int SIGN_RED = 0xFFD32F2F;
    private static final int SIGN_TEXT = 0xFF111111;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    /** 0 = placa de limite; senão o tipo do alerta (RoadCell.SPEED_CAMERA…). */
    private int type;
    private int limit;
    private int color = 0xFFFFFFFF;

    public RoadSign(Context c) {
        this(c, null);
    }

    public RoadSign(Context c, AttributeSet a) {
        super(c, a);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Ui.semiBold(c), Typeface.BOLD));
    }

    /** Placa de limite com {@code kmh}. */
    public void setLimit(int kmh) {
        if (type == 0 && limit == kmh) return;
        type = 0;
        limit = kmh;
        invalidate();
    }

    /** Ícone de alerta, na cor {@code color}. */
    public void setAlert(int alertType, int color) {
        if (type == alertType && this.color == color) return;
        type = alertType;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        c.save();
        c.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        c.scale(size / 24f, size / 24f);
        if (type == 0) drawLimit(c);
        else drawAlert(c);
        c.restore();
    }

    private void drawLimit(Canvas c) {
        fill.setColor(SIGN_WHITE);
        c.drawCircle(12, 12, 11.5f, fill);
        stroke.setColor(SIGN_RED);
        stroke.setStrokeWidth(2.4f);
        c.drawCircle(12, 12, 10.1f, stroke);
        String s = String.valueOf(limit);
        text.setColor(SIGN_TEXT);
        text.setTextSize(s.length() >= 3 ? 7.6f : 9.6f);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, 12, 12 - (fm.ascent + fm.descent) / 2f, text);
    }

    private void drawAlert(Canvas c) {
        fill.setColor(color);
        stroke.setColor(color);
        stroke.setStrokeWidth(2f);
        path.reset();
        switch (type) {
            case RoadCell.SPEED_CAMERA:
                // Câmera: corpo, lente e o feixe.
                r.set(3, 8, 15, 16);
                c.drawRoundRect(r, 1.5f, 1.5f, fill);
                path.moveTo(15, 10.5f);
                path.lineTo(21, 7.5f);
                path.lineTo(21, 16.5f);
                path.lineTo(15, 13.5f);
                path.close();
                c.drawPath(path, fill);
                c.drawLine(7, 16, 6, 21, stroke);
                break;
            case RoadCell.RED_LIGHT_CAMERA:
                // Semáforo.
                r.set(7.5f, 2, 16.5f, 22);
                c.drawRoundRect(r, 3, 3, stroke);
                c.drawCircle(12, 7, 2.2f, fill);
                c.drawCircle(12, 12, 2.2f, fill);
                c.drawCircle(12, 17, 2.2f, fill);
                break;
            case RoadCell.TRAFFIC_CALMING:
                // Lombada: a pista e a elevação.
                c.drawLine(2, 18, 22, 18, stroke);
                path.moveTo(5, 18);
                path.cubicTo(8, 18, 9, 9, 12, 9);
                path.cubicTo(15, 9, 16, 18, 19, 18);
                path.close();
                c.drawPath(path, fill);
                break;
            case RoadCell.TOLL_BOOTH:
                // Cancela: poste e braço listrado.
                c.drawLine(5, 7, 5, 21, stroke);
                r.set(3, 19.5f, 7, 21.5f);
                c.drawRect(r, fill);
                stroke.setStrokeWidth(3f);
                c.drawLine(5, 9, 22, 9, stroke);
                stroke.setStrokeWidth(2f);
                break;
            case RoadCell.LEVEL_CROSSING:
                // Cruz de santo André.
                stroke.setStrokeWidth(3f);
                c.drawLine(4, 5, 20, 19, stroke);
                c.drawLine(20, 5, 4, 19, stroke);
                stroke.setStrokeWidth(2f);
                break;
            default:
                break;
        }
    }
}
