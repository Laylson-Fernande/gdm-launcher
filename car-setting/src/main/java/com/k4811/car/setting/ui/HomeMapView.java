package com.k4811.car.setting.ui;

import com.k4811.common.ui.Ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import com.k4811.car.setting.R;
import com.k4811.common.home.HomeGrid;
import com.k4811.common.config.LauncherConfig;

import java.util.Map;

/**
 * Miniature of the home screen for Settings › Customize home screen: the top bar, the grid that is
 * invisible on the real home screen (each slot with its name and the module in it) and the dock,
 * with the shape of this screen (landscape columns or portrait rows).
 * Reads the saved layout on every draw, so {@link #invalidate()} is enough after a change.
 * As a picker ({@link #setPicker}), the module being placed stands out and tapping a slot picks it.
 */
public final class HomeMapView extends View {

    private static final float MAX_WIDTH_DP = 760f;
    /** Portrait screens: keep the miniature short enough to leave room for the list below. */
    private static final float MAX_HEIGHT_DP = 460f;

    private final HomeGrid.Layout grid;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint slotText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint moduleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** Each slot's rectangle on screen, from the last draw (for taps). */
    private final RectF[] slotRects = new RectF[HomeGrid.Slot.values().length];

    public interface OnPick {
        void pick(HomeGrid.Slot slot);
    }

    private HomeGrid.Module focus;
    private OnPick onPick;
    private HomeGrid.Slot pressed;

    public HomeMapView(Context context) {
        super(context);
        grid = HomeGrid.layout((Activity) context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(context, 1.5f));
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(Ui.dp(context, 1.5f));
        float dash = Ui.dp(context, 6);
        dashed.setPathEffect(new DashPathEffect(new float[]{dash, dash}, 0));
        slotText.setTypeface(Ui.medium(context));
        slotText.setTextSize(Ui.dp(context, 14));
        if (android.os.Build.VERSION.SDK_INT >= 21) slotText.setLetterSpacing(0.08f);
        moduleText.setTypeface(Ui.semiBold(context));
        moduleText.setTextSize(Ui.dp(context, 20));
        for (int i = 0; i < slotRects.length; i++) slotRects[i] = new RectF();
    }

    /** Picker mode: {@code module} stands out and tapping a slot calls {@code onPick}. */
    public void setPicker(HomeGrid.Module module, OnPick onPick) {
        this.focus = module;
        this.onPick = onPick;
        setClickable(true);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (onPick == null) return super.onTouchEvent(e);
        HomeGrid.Slot hit = null;
        for (HomeGrid.Slot slot : HomeGrid.Slot.values()) {
            if (slotRects[slot.ordinal()].contains(e.getX(), e.getY())) hit = slot;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (hit != pressed) {
                    pressed = hit;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                pressed = null;
                invalidate();
                if (hit != null) onPick.pick(hit);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        float ratio = grid.screenHeight / (float) grid.screenWidth;
        int width = Math.min(available, Math.round(Ui.dp(getContext(), MAX_WIDTH_DP)));
        width = Math.min(width, Math.round(Ui.dp(getContext(), MAX_HEIGHT_DP) / ratio));
        setMeasuredDimension(available, Math.round(width * ratio));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float height = getHeight();
        float width = height * grid.screenWidth / grid.screenHeight;
        float left = (getWidth() - width) / 2f;
        float k = width / grid.screenWidth;
        float radius = Ui.dp(getContext(), 14);

        // Screen.
        float inset = stroke.getStrokeWidth() / 2f;
        rect.set(left + inset, inset, left + width - inset, height - inset);
        fill.setColor(Ui.BG_SOFT);
        canvas.drawRoundRect(rect, radius, radius, fill);

        // Top bar: voltmeter on the left, screen off and settings on the right.
        fill.setColor(Ui.SURFACE_HIGH);
        float bar = HomeGrid.TOP_BAR * k;
        float dot = 22 * k;
        canvas.drawCircle(left + 66 * k, bar / 2f, dot, fill);
        canvas.drawCircle(left + width - 140 * k, bar / 2f, dot, fill);
        canvas.drawCircle(left + width - 58 * k, bar / 2f, dot, fill);

        // Dock.
        rect.set(left, grid.contentHeight * k, left + width, height);
        fill.setColor(Ui.SURFACE);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom - radius, fill);
        canvas.drawRoundRect(rect, radius, radius, fill);
        rect.set(left + inset, inset, left + width - inset, height - inset);
        stroke.setColor(Ui.OUTLINE);
        canvas.drawRoundRect(rect, radius, radius, stroke);
        rect.set(left, grid.contentHeight * k, left + width, height);
        slotText.setColor(Ui.TEXT_DIM);
        String dock = getContext().getString(R.string.settings_home_dock).toUpperCase();
        canvas.drawText(dock, left + (width - slotText.measureText(dock)) / 2f,
            rect.centerY() - (slotText.ascent() + slotText.descent()) / 2f, slotText);

        // Slots.
        Map<HomeGrid.Module, HomeGrid.Slot> layout = LauncherConfig.homeLayout(getContext());
        float pad = Ui.dp(getContext(), 10);
        float slotRadius = Ui.dp(getContext(), 8);
        for (HomeGrid.Slot slot : HomeGrid.Slot.values()) {
            HomeGrid.Module module = null;
            for (Map.Entry<HomeGrid.Module, HomeGrid.Slot> e : layout.entrySet()) {
                if (e.getValue() == slot) module = e.getKey();
            }
            HomeGrid.Frame f = grid.frame(slot);
            rect.set(left + f.x * k, f.y * k, left + (f.x + f.width) * k, (f.y + f.height) * k);
            slotRects[slot.ordinal()].set(rect);
            // Picker: the module being placed in the accent color, the others in gray.
            boolean highlight = module != null && (focus == null || module == focus);
            if (module != null && !highlight) {
                fill.setColor(Ui.SURFACE_HIGH);
                canvas.drawRoundRect(rect, slotRadius, slotRadius, fill);
                stroke.setColor(Ui.OUTLINE);
                canvas.drawRoundRect(rect, slotRadius, slotRadius, stroke);
            } else if (module != null) {
                fill.setColor(Ui.ACCENT_DIM);
                canvas.drawRoundRect(rect, slotRadius, slotRadius, fill);
                stroke.setColor(Ui.ACCENT);
                canvas.drawRoundRect(rect, slotRadius, slotRadius, stroke);
            } else {
                dashed.setColor(Ui.OUTLINE_STRONG);
                canvas.drawRoundRect(rect, slotRadius, slotRadius, dashed);
            }
            if (slot == pressed) {
                fill.setColor(Ui.PRESSED);
                canvas.drawRoundRect(rect, slotRadius, slotRadius, fill);
            }
            float textWidth = rect.width() - 2 * pad;
            slotText.setColor(highlight ? Ui.TEXT_SOFT : Ui.TEXT_MUTED);
            String name = TextUtils.ellipsize(slot.label,
                slotText, textWidth, TextUtils.TruncateAt.END).toString();
            canvas.drawText(name, rect.left + pad, rect.top + pad - slotText.ascent(), slotText);

            moduleText.setColor(highlight ? Ui.TEXT : module != null ? Ui.TEXT_SOFT : Ui.TEXT_DIM);
            String what = getContext().getString(module != null ? module.titleRes : R.string.settings_home_empty);
            // Long names shrink (down to 70%) before being cut.
            float full = Ui.dp(getContext(), 20);
            moduleText.setTextSize(full);
            while (moduleText.measureText(what) > textWidth && moduleText.getTextSize() > full * 0.7f) {
                moduleText.setTextSize(moduleText.getTextSize() - 1f);
            }
            what = TextUtils.ellipsize(what, moduleText, textWidth, TextUtils.TruncateAt.END).toString();
            canvas.drawText(what, rect.left + (rect.width() - moduleText.measureText(what)) / 2f,
                rect.centerY() - (moduleText.ascent() + moduleText.descent()) / 2f + pad / 2f, moduleText);
        }
    }
}
