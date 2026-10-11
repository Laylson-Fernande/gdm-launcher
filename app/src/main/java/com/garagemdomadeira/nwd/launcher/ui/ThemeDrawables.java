package com.garagemdomadeira.nwd.launcher.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;

import com.garagemdomadeira.nwd.launcher.R;

/**
 * Themed versions of the XML drawables and color selectors in res/. XML resources are fixed at
 * build time, so every resource that carries a theme color is rebuilt here from {@link Ui}'s
 * current tokens. {@link ThemeInflater} swaps them in when layouts are inflated; code uses
 * {@link #drawable} directly. Resources not listed here are returned unchanged (null).
 */
public final class ThemeDrawables {
    private ThemeDrawables() {
    }

    private static final int[] PRESSED = {android.R.attr.state_pressed};
    private static final int[] ACTIVATED = {android.R.attr.state_activated};
    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] DEFAULT = {};

    /** Themed color for a color resource id, or null if it isn't a theme color. */
    public static Integer color(int resId) {
        if (resId == R.color.background) return Ui.BG;
        if (resId == R.color.surface) return Ui.SURFACE;
        if (resId == R.color.surface_high) return Ui.SURFACE_HIGH;
        if (resId == R.color.surface_top) return Ui.SURFACE_TOP;
        if (resId == R.color.outline) return Ui.OUTLINE;
        if (resId == R.color.outline_strong) return Ui.OUTLINE_STRONG;
        if (resId == R.color.text) return Ui.TEXT;
        if (resId == R.color.text_muted) return Ui.TEXT_MUTED;
        if (resId == R.color.text_dim) return Ui.TEXT_DIM;
        if (resId == R.color.text_soft) return Ui.TEXT_SOFT;
        if (resId == R.color.caption) return Ui.TEXT_MUTED;
        if (resId == R.color.accent) return Ui.ACCENT;
        if (resId == R.color.hairline) return Ui.HAIRLINE;
        if (resId == R.color.hairline_strong) return Ui.HAIRLINE_STRONG;
        return null;
    }

    /** Themed color state list for a color resource id (selectors included), or null. */
    public static ColorStateList colors(int resId) {
        if (resId == R.color.line_text) {
            return new ColorStateList(new int[][]{ACTIVATED, PRESSED, DEFAULT},
                new int[]{Ui.ACCENT, Ui.ACCENT, Ui.TEXT_MUTED});
        }
        if (resId == R.color.toggle_text) {
            return new ColorStateList(new int[][]{ACTIVATED, DEFAULT}, new int[]{Ui.ACCENT, Ui.TEXT_MUTED});
        }
        Integer c = color(resId);
        return c == null ? null : ColorStateList.valueOf(c);
    }

    /** Themed drawable for a drawable or color resource id, or null if it isn't themed. */
    public static Drawable drawable(Context ctx, int resId) {
        Integer c = color(resId);
        if (c != null) return new ColorDrawable(c);
        float d = ctx.getResources().getDisplayMetrics().density;
        if (resId == R.drawable.main_bg) return new ColorDrawable(Ui.BG);
        if (resId == R.drawable.art_frame) return shape(Ui.BG_SOFT, Ui.HAIRLINE_STRONG, d, 0);
        if (resId == R.drawable.chip) return shape(Ui.SURFACE_TOP, 0, 0, 8 * d);
        if (resId == R.drawable.log_bg) return shape(Ui.BG_SOFT, 0, 0, 10 * d);
        if (resId == R.drawable.panel) return shape(Ui.SURFACE, Ui.HAIRLINE_STRONG, d, 22 * d);
        if (resId == R.drawable.progress_fill) return shape(Ui.TEXT_MUTED, 0, 0, 2 * d);
        if (resId == R.drawable.progress_track) return shape(Ui.SURFACE_HIGH, 0, 0, 2 * d);
        if (resId == R.drawable.dock_item) {
            return states(shape(Ui.PRESSED, 0, 0, 18 * d), null, null);
        }
        if (resId == R.drawable.pill) {
            return states(shape(Ui.OUTLINE, Ui.ACCENT, 1.5f * d, 10 * d),
                shape(Ui.ACCENT_FILL, Ui.ACCENT, 1.5f * d, 10 * d),
                shape(Ui.SURFACE_TOP, Ui.OUTLINE, d, 10 * d));
        }
        if (resId == R.drawable.toggle) {
            return states(shape(Ui.SURFACE_TOP, Ui.TEXT, 1.5f * d, 4 * d),
                shape(Ui.ACCENT_FILL, Ui.ACCENT, 1.5f * d, 4 * d),
                shape(0, Ui.OUTLINE_STRONG, 1.5f * d, 4 * d));
        }
        if (resId == R.drawable.line_button) {
            return states(shape(Ui.PRESSED, Ui.OUTLINE_STRONG, d, 2 * d),
                shape(0, Ui.ACCENT, d, 2 * d),
                shape(0, Ui.HAIRLINE_STRONG, d, 2 * d));
        }
        if (resId == R.drawable.list_row) {
            return states(new ColorDrawable(Ui.PRESSED), new Bar(Ui.ACCENT, Gravity.LEFT, 2 * d), null);
        }
        if (resId == R.drawable.tab_line) {
            return states(new ColorDrawable(Ui.PRESSED), new Bar(Ui.ACCENT, Gravity.BOTTOM, 2 * d), null);
        }
        if (resId == R.drawable.status_dot) {
            return states(null, oval(Ui.ACCENT, 0), oval(Ui.TEXT_DIM, 0));
        }
        if (resId == R.drawable.seek_thumb) return oval(Ui.ACCENT, Math.round(14 * d));
        if (resId == R.drawable.seek_line) {
            LayerDrawable l = new LayerDrawable(new Drawable[]{
                new Bar(Ui.HAIRLINE_STRONG, Gravity.CENTER_VERTICAL, 2 * d),
                new ClipDrawable(new Bar(Ui.ACCENT, Gravity.CENTER_VERTICAL, 2 * d), Gravity.LEFT,
                    ClipDrawable.HORIZONTAL),
            });
            l.setId(0, android.R.id.background);
            l.setId(1, android.R.id.progress);
            return l;
        }
        if (resId == R.drawable.field_line) {
            StateListDrawable s = new StateListDrawable();
            s.addState(FOCUSED, underline(Ui.TEXT, d));
            s.addState(DEFAULT, underline(Ui.HAIRLINE_STRONG, d));
            return s;
        }
        if (resId == R.drawable.grid_cell) {
            StateListDrawable s = new StateListDrawable();
            s.addState(PRESSED, new LayerDrawable(new Drawable[]{
                new ColorDrawable(Ui.PRESSED), cellBorder(d)}));
            s.addState(DEFAULT, cellBorder(d));
            return s;
        }
        if (resId == R.drawable.grid_frame) {
            LayerDrawable l = new LayerDrawable(new Drawable[]{shape(0, Ui.HAIRLINE_STRONG, d, 0)});
            int px = Math.round(d);
            l.setLayerInset(0, 0, 0, -px, -px);
            return l;
        }
        return null;
    }

    /** {@link #drawable}, falling back to the resource itself when it isn't themed. */
    @SuppressWarnings("deprecation")
    public static Drawable get(Context ctx, int resId) {
        Drawable d = drawable(ctx, resId);
        return d != null ? d : ctx.getResources().getDrawable(resId);
    }

    /** Sets a themed background, keeping the view's padding. */
    @SuppressWarnings("deprecation")
    public static void setBackground(android.view.View v, Drawable d) {
        int l = v.getPaddingLeft();
        int t = v.getPaddingTop();
        int r = v.getPaddingRight();
        int b = v.getPaddingBottom();
        v.setBackgroundDrawable(d);
        v.setPadding(l, t, r, b);
    }

    public static void setBackground(android.view.View v, int resId) {
        setBackground(v, get(v.getContext(), resId));
    }

    private static Drawable states(Drawable pressed, Drawable activated, Drawable normal) {
        StateListDrawable s = new StateListDrawable();
        if (pressed != null) s.addState(PRESSED, pressed);
        if (activated != null) s.addState(ACTIVATED, activated);
        s.addState(DEFAULT, normal != null ? normal : new ColorDrawable(0));
        return s;
    }

    private static GradientDrawable shape(int fill, int stroke, float strokeWidth, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        if (stroke != 0 && strokeWidth > 0) g.setStroke(Math.max(1, Math.round(strokeWidth)), stroke);
        if (radius > 0) g.setCornerRadius(radius);
        return g;
    }

    private static GradientDrawable oval(int fill, int size) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (size > 0) g.setSize(size, size);
        return g;
    }

    /** Bottom line only: a stroked rectangle pushed out on the left, right and top. */
    private static Drawable underline(int color, float d) {
        LayerDrawable l = new LayerDrawable(new Drawable[]{shape(0, color, d, 0)});
        int out = Math.round(2 * d);
        l.setLayerInset(0, -out, -out, -out, 0);
        return l;
    }

    /** Right and bottom border of a grid cell (left/top belong to the neighbors). */
    private static Drawable cellBorder(float d) {
        LayerDrawable l = new LayerDrawable(new Drawable[]{shape(0, Ui.HAIRLINE_STRONG, d, 0)});
        int px = Math.round(d);
        l.setLayerInset(0, -px, -px, 0, 0);
        return l;
    }

    /** Solid bar along one edge (LEFT, BOTTOM) or a centered horizontal line (CENTER_VERTICAL). */
    static final class Bar extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int gravity;
        private final float thickness;

        Bar(int color, int gravity, float thickness) {
            paint.setColor(color);
            this.gravity = gravity;
            this.thickness = thickness;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (gravity == Gravity.LEFT) {
                canvas.drawRect(b.left, b.top, b.left + thickness, b.bottom, paint);
            } else if (gravity == Gravity.BOTTOM) {
                canvas.drawRect(b.left, b.bottom - thickness, b.right, b.bottom, paint);
            } else {
                float cy = b.exactCenterY();
                canvas.drawRect(b.left, cy - thickness / 2, b.right, cy + thickness / 2, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
