package com.garagemdomadeira.nwd.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import com.garagemdomadeira.nwd.launcher.R;

/**
 * Preset card: beveled right corners, the memory number large on top and the station
 * (frequency · name) in small type at the bottom. Selected = glowing cyan border.
 */
public class PresetCardView extends View {

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint indexPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint stationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF arc = new RectF();

    private int index;
    private String station = "";
    private boolean empty = true;
    private boolean selectedCard;

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
        indexPaint.setTypeface(Ui.semiBold(c));
        indexPaint.setTextSize(Ui.dp(c, 40));
        indexPaint.setTextAlign(Paint.Align.CENTER);
        stationPaint.setTypeface(Ui.medium(c));
        stationPaint.setTextSize(Ui.dp(c, 15));
    }

    /** @param freqText frequency text ("98.5") or empty if the preset is unused */
    public void bind(int index, String freqText, String psName, boolean empty, boolean selected) {
        String st;
        if (empty) st = getContext().getString(R.string.radio_preset_empty);
        else if (psName == null || psName.trim().isEmpty()) st = freqText;
        else st = freqText + " · " + psName.trim().toUpperCase();
        boolean changed = this.index != index || !TextUtils.equals(station, st)
            || this.empty != empty || selectedCard != selected;
        this.index = index;
        this.station = st;
        this.empty = empty;
        this.selectedCard = selected;
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
        float headH = (b - t) * 0.66f;

        buildShape(l, t, r, b, cut, round);

        // Body and header.
        bodyPaint.setColor(isPressed() ? Ui.OUTLINE : Ui.SURFACE_TOP);
        c.drawPath(shape, bodyPaint);
        c.save();
        c.clipRect(l, t, r, t + headH);
        headPaint.setColor(isPressed() ? Ui.SURFACE_HIGH : Ui.SURFACE);
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
                new int[]{Ui.ACCENT & 0x00FFFFFF, Ui.ACCENT, Ui.ACCENT & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            linePaint.setStrokeWidth(Ui.dp(ctx, 2));
            c.drawLine(l + cut, y, r - cut, y, linePaint);
        }

        // Top: memory number, large and centered.
        float headCenter = t + headH / 2f;
        indexPaint.setColor(selectedCard ? Ui.ACCENT : empty ? Ui.TEXT_MUTED : Ui.TEXT);
        float baseIdx = headCenter - (indexPaint.descent() + indexPaint.ascent()) / 2f;
        c.drawText(String.valueOf(index + 1), (l + r) / 2f, baseIdx, indexPaint);

        // Bottom: the station in small type, one line (cut with … if it doesn't fit).
        float pad = Ui.dp(ctx, 12);
        float width = r - l - pad * 2;
        if (width > 0) {
            stationPaint.setColor(empty ? Ui.TEXT_DIM : Ui.TEXT_SOFT);
            String text = TextUtils.ellipsize(station, stationPaint, width, TextUtils.TruncateAt.END).toString();
            float bodyCenter = t + headH + (b - t - headH) / 2f;
            float baseSt = bodyCenter - (stationPaint.descent() + stationPaint.ascent()) / 2f;
            c.drawText(text, (l + r - stationPaint.measureText(text)) / 2f, baseSt, stationPaint);
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
