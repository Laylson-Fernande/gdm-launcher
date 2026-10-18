package com.k4811.common.ui;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.TextView;


/**
 * Line icon at the left of a text button (MP3: Shuffle, Repeat, Open folder, Refresh; EQ: Loudness,
 * Reset, Apply at startup, Influence), drawn on a 24×24 grid like IconView. Follows the button's text color, so it lights up with it when active.
 */
public final class ButtonIcon extends Drawable {

    public enum Icon { SHUFFLE, REPEAT, FOLDER, REFRESH, LOUDNESS, RESET, POWER, INFLUENCE }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Icon icon;
    private final ColorStateList colors;
    private final int size;

    /** Puts the icon at the left of {@code button}'s text. */
    public static void attach(TextView button, Icon icon) {
        ButtonIcon d = new ButtonIcon(icon, button.getTextColors(), Math.round(Ui.dp(button.getContext(), 20)));
        button.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        button.setCompoundDrawablePadding(Math.round(Ui.dp(button.getContext(), 8)));
    }

    private ButtonIcon(Icon icon, ColorStateList colors, int size) {
        this.icon = icon;
        this.colors = colors;
        this.size = size;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(2f);
        stroke.setColor(colors.getDefaultColor());
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        int c = colors.getColorForState(state, colors.getDefaultColor());
        if (c == stroke.getColor()) return false;
        stroke.setColor(c);
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float unit = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left, b.top);
        c.scale(unit, unit);
        switch (icon) {
            case SHUFFLE:
                // Two crossing lines, each ending in an arrow on the right.
                path.reset();
                path.moveTo(3, 7);
                path.lineTo(7, 7);
                path.cubicTo(12, 7, 12, 17, 17, 17);
                path.lineTo(20, 17);
                path.moveTo(3, 17);
                path.lineTo(7, 17);
                path.cubicTo(12, 17, 12, 7, 17, 7);
                path.lineTo(20, 7);
                arrowHead(20, 7);
                arrowHead(20, 17);
                c.drawPath(path, stroke);
                break;
            case REPEAT:
                // Loop of two arrows: right along the top, left along the bottom.
                path.reset();
                path.moveTo(4, 12);
                path.lineTo(4, 10);
                path.quadTo(4, 7, 7, 7);
                path.lineTo(20, 7);
                path.moveTo(17, 4);
                path.lineTo(20, 7);
                path.lineTo(17, 10);
                path.moveTo(20, 12);
                path.lineTo(20, 14);
                path.quadTo(20, 17, 17, 17);
                path.lineTo(4, 17);
                path.moveTo(7, 14);
                path.lineTo(4, 17);
                path.lineTo(7, 20);
                c.drawPath(path, stroke);
                break;
            case FOLDER:
                // Folder with a tab, opened: the front flap tilts forward.
                path.reset();
                path.moveTo(3, 18.5f);
                path.lineTo(3, 6);
                path.quadTo(3, 5, 4, 5);
                path.lineTo(9, 5);
                path.lineTo(11, 7.5f);
                path.lineTo(18, 7.5f);
                path.quadTo(19, 7.5f, 19, 8.5f);
                path.lineTo(19, 10.5f);
                path.moveTo(3, 18.5f);
                path.lineTo(6.5f, 10.5f);
                path.lineTo(21.5f, 10.5f);
                path.lineTo(18, 18.5f);
                path.close();
                c.drawPath(path, stroke);
                break;
            case REFRESH:
                circleArrow(c);
                break;
            case RESET:
                // The refresh arrow mirrored: going back (counterclockwise).
                c.scale(-1, 1, 12, 12);
                circleArrow(c);
                break;
            case LOUDNESS:
                // Speaker with two sound waves.
                path.reset();
                path.moveTo(3, 9.5f);
                path.lineTo(6.5f, 9.5f);
                path.lineTo(11, 5.5f);
                path.lineTo(11, 18.5f);
                path.lineTo(6.5f, 14.5f);
                path.lineTo(3, 14.5f);
                path.close();
                c.drawPath(path, stroke);
                rect.set(10, 8, 18, 16);
                c.drawArc(rect, -50, 100, false, stroke);
                rect.set(9, 4.5f, 22, 19.5f);
                c.drawArc(rect, -50, 100, false, stroke);
                break;
            case POWER:
                rect.set(4, 5, 20, 21);
                c.drawArc(rect, -60, 300, false, stroke);
                c.drawLine(12, 2.5f, 12, 11, stroke);
                break;
            case INFLUENCE:
                // Five bars: the middle one pulls its neighbours up, less the farther they are.
                float[] heights = {3, 7, 14, 7, 3};
                for (int i = 0; i < heights.length; i++) {
                    float x = 4 + i * 4;
                    c.drawLine(x, 20, x, 20 - heights[i], stroke);
                }
                break;
        }
        c.restore();
    }

    /** Almost-closed circle, clockwise, with an arrow at its end (upper left). */
    private void circleArrow(Canvas c) {
        rect.set(4.5f, 4.5f, 19.5f, 19.5f);
        c.drawArc(rect, -60, 300, false, stroke);
        path.reset();
        path.moveTo(4.3f, 4.8f);
        path.lineTo(8.25f, 5.5f);
        path.lineTo(6.9f, 9.3f);
        c.drawPath(path, stroke);
    }

    /** "›" arrow head with its tip at (x, y). */
    private void arrowHead(float x, float y) {
        path.moveTo(x - 3, y - 3);
        path.lineTo(x, y);
        path.lineTo(x - 3, y + 3);
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        stroke.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
