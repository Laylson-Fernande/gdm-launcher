package com.garagemdomadeira.nwd.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import com.garagemdomadeira.nwd.launcher.R;

/**
 * Preset card: beveled right corners, dark header with number and frequency,
 * body with the station name. Selected = glowing cyan border and ◀ marker.
 */
public class PresetCardView extends View {

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint indexPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint freqPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path marker = new Path();
    private final RectF arc = new RectF();

    private int index;
    private String freqText = "";
    private String name = "";
    private boolean empty = true;
    private boolean selectedCard;

    private StaticLayout nameLayout;
    private int nameLayoutWidth = -1;

    public PresetCardView(Context c) {
        this(c, null);
    }

    public PresetCardView(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setLongClickable(true);
        // The glow's BlurMaskFilter/shadowLayer require a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        borderPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(Ui.ACCENT);
        markerPaint.setColor(Ui.ACCENT);
        indexPaint.setTypeface(Ui.medium(c));
        indexPaint.setTextSize(Ui.dp(c, 30));
        freqPaint.setTypeface(Ui.semiBold(c));
        freqPaint.setTextSize(Ui.dp(c, 32));
        namePaint.setTypeface(Ui.medium(c));
        namePaint.setTextSize(Ui.dp(c, 23));
    }

    /** @param freqText frequency text ("98.5") or empty if the preset is unused */
    public void bind(int index, String freqText, String psName, boolean empty, boolean selected) {
        String n = empty ? getContext().getString(R.string.radio_preset_empty) : (psName == null || psName.isEmpty() ? "---" : psName.toUpperCase());
        boolean changed = this.index != index || !TextUtils.equals(this.freqText, freqText)
            || !TextUtils.equals(name, n) || this.empty != empty || selectedCard != selected;
        this.index = index;
        this.freqText = freqText;
        this.empty = empty;
        this.selectedCard = selected;
        if (!TextUtils.equals(name, n)) {
            name = n;
            nameLayoutWidth = -1;
        }
        if (changed) invalidate();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        Context ctx = getContext();
        float inset = Ui.dp(ctx, 6); // room for the glow
        float l = inset;
        float t = inset;
        float r = getWidth() - inset;
        float b = getHeight() - inset;
        float cut = Ui.dp(ctx, 22);
        float round = Ui.dp(ctx, 12);
        float headH = (b - t) * 0.42f;

        buildShape(l, t, r, b, cut, round);

        // Body and header.
        bodyPaint.setColor(isPressed() ? 0xFF343A41 : Ui.SURFACE_TOP);
        c.drawPath(shape, bodyPaint);
        c.save();
        c.clipRect(l, t, r, t + headH);
        headPaint.setColor(isPressed() ? 0xFF262A30 : 0xFF1A1D21);
        c.drawPath(shape, headPaint);
        c.restore();

        // Border (+ glow when selected).
        if (selectedCard) {
            glowPaint.setStrokeWidth(Ui.dp(ctx, 6));
            glowPaint.setShadowLayer(Ui.dp(ctx, 10), 0, 0, Ui.ACCENT);
            glowPaint.setAlpha(110);
            c.drawPath(shape, glowPaint);
            borderPaint.setColor(Ui.ACCENT);
            borderPaint.setStrokeWidth(Ui.dp(ctx, 2.5f));
        } else {
            borderPaint.setColor(Ui.OUTLINE);
            borderPaint.setStrokeWidth(Ui.dp(ctx, 1.5f));
        }
        c.drawPath(shape, borderPaint);

        // Cyan divider under the selected card's header.
        if (selectedCard) {
            float y = t + headH;
            linePaint.setShader(new LinearGradient(l, y, r, y,
                new int[]{0x00FFFFFF, Ui.ACCENT, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            linePaint.setStrokeWidth(Ui.dp(ctx, 2));
            c.drawLine(l + cut, y, r - cut, y, linePaint);
        }

        // Header: "3   98.5 ◀".
        float pad = Ui.dp(ctx, 16);
        float headCenter = t + headH / 2f;
        indexPaint.setColor(selectedCard ? Ui.ACCENT : Ui.TEXT_MUTED);
        float baseIdx = headCenter - (indexPaint.descent() + indexPaint.ascent()) / 2f;
        c.drawText(String.valueOf(index + 1), l + pad, baseIdx, indexPaint);
        if (!empty) {
            freqPaint.setColor(Ui.TEXT);
            float fx = l + pad + indexPaint.measureText("00") + Ui.dp(ctx, 12);
            float baseFreq = headCenter - (freqPaint.descent() + freqPaint.ascent()) / 2f;
            c.drawText(freqText, fx, baseFreq, freqPaint);
            if (selectedCard) {
                float mx = fx + freqPaint.measureText(freqText) + Ui.dp(ctx, 12);
                float ms = Ui.dp(ctx, 9);
                marker.reset();
                marker.moveTo(mx + ms * 1.4f, headCenter - ms);
                marker.lineTo(mx + ms * 1.4f, headCenter + ms);
                marker.lineTo(mx, headCenter);
                marker.close();
                c.drawPath(marker, markerPaint);
            }
        }

        // Body: station name on up to 2 lines, centered.
        int width = (int) (r - l - pad * 2);
        if (width > 0) {
            if (nameLayout == null || nameLayoutWidth != width) {
                namePaint.setColor(Ui.TEXT);
                nameLayout = new StaticLayout(name, namePaint, width, Layout.Alignment.ALIGN_CENTER, 0.95f, 0, false);
                nameLayoutWidth = width;
            }
            namePaint.setColor(empty ? Ui.TEXT_MUTED : Ui.TEXT);
            float bodyTop = t + headH;
            float bodyH = b - bodyTop;
            int lines = Math.min(2, nameLayout.getLineCount());
            float textH = nameLayout.getLineBottom(lines - 1);
            c.save();
            c.translate(l + pad, bodyTop + (bodyH - textH) / 2f);
            c.clipRect(0, 0, width, textH);
            nameLayout.draw(c);
            c.restore();
        }
    }

    /** Rectangle with rounded left corners and beveled right corners. */
    private void buildShape(float l, float t, float r, float b, float cut, float round) {
        shape.reset();
        shape.moveTo(l + round, t);
        shape.lineTo(r - cut, t);
        shape.lineTo(r, t + cut);
        shape.lineTo(r, b - cut);
        shape.lineTo(r - cut, b);
        shape.lineTo(l + round, b);
        arc.set(l, b - 2 * round, l + 2 * round, b);
        shape.arcTo(arc, 90, 90);
        shape.lineTo(l, t + round);
        arc.set(l, t, l + 2 * round, t + 2 * round);
        shape.arcTo(arc, 180, 90);
        shape.close();
    }
}
