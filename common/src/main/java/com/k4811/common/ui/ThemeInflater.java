package com.k4811.common.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsSeekBar;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Arrays;

/**
 * Applies the theme to inflated layouts: every view's background, text colors, list divider and
 * seek bar drawables that point to a theme resource ({@code @color/text}, {@code @drawable/pill},
 * including those set through styles) are replaced by {@link ThemeDrawables}' themed versions.
 * Installed on the Activity's inflater, so dialogs (which clone it) are themed too.
 */
public final class ThemeInflater implements LayoutInflater.Factory2 {

    private static final String[] PREFIXES = {"android.widget.", "android.view.", "android.webkit."};

    /** Attributes read from each view; obtainStyledAttributes needs them sorted. */
    private static final int[] ATTRS;
    private static final int BACKGROUND;
    private static final int TEXT_COLOR;
    private static final int TEXT_COLOR_HINT;
    private static final int DIVIDER;
    private static final int PROGRESS_DRAWABLE;
    private static final int THUMB;

    static {
        int[] attrs = {
            android.R.attr.background, android.R.attr.textColor, android.R.attr.textColorHint,
            android.R.attr.divider, android.R.attr.progressDrawable, android.R.attr.thumb,
        };
        int[] sorted = attrs.clone();
        Arrays.sort(sorted);
        ATTRS = sorted;
        BACKGROUND = Arrays.binarySearch(sorted, android.R.attr.background);
        TEXT_COLOR = Arrays.binarySearch(sorted, android.R.attr.textColor);
        TEXT_COLOR_HINT = Arrays.binarySearch(sorted, android.R.attr.textColorHint);
        DIVIDER = Arrays.binarySearch(sorted, android.R.attr.divider);
        PROGRESS_DRAWABLE = Arrays.binarySearch(sorted, android.R.attr.progressDrawable);
        THUMB = Arrays.binarySearch(sorted, android.R.attr.thumb);
    }

    public static void install(Activity a) {
        LayoutInflater inflater = a.getLayoutInflater();
        if (inflater.getFactory() != null) return;
        inflater.setFactory2(new ThemeInflater());
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        View v = create(name, context, attrs);
        if (v != null) apply(v, context, attrs);
        return v;
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return onCreateView(null, name, context, attrs);
    }

    private static View create(String name, Context context, AttributeSet attrs) {
        LayoutInflater inflater = LayoutInflater.from(context);
        try {
            if (name.indexOf('.') >= 0) return inflater.createView(name, null, attrs);
            for (String prefix : PREFIXES) {
                try {
                    return inflater.createView(name, prefix, attrs);
                } catch (ClassNotFoundException ignored) {
                }
            }
        } catch (Exception e) {
            // Let the inflater create it the normal way (unthemed).
        }
        return null;
    }

    private static void apply(View v, Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, ATTRS);
        try {
            int bg = a.getResourceId(BACKGROUND, 0);
            if (bg != 0) {
                Drawable d = ThemeDrawables.drawable(context, bg);
                if (d != null) ThemeDrawables.setBackground(v, d);
            } else if (a.hasValue(BACKGROUND)) {
                // Literal color (e.g. a translucent overlay): theme it, keeping its alpha.
                int literal = a.getColor(BACKGROUND, 0);
                Integer themed = Ui.themedLiteral(literal | 0xFF000000);
                if (themed != null) {
                    v.setBackgroundColor((literal & 0xFF000000) | (themed & 0x00FFFFFF));
                }
            }
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                ColorStateList text = themedColors(a, TEXT_COLOR);
                if (text != null) t.setTextColor(text);
                ColorStateList hint = themedColors(a, TEXT_COLOR_HINT);
                if (hint != null) t.setHintTextColor(hint);
            }
            if (v instanceof ListView) {
                int div = a.getResourceId(DIVIDER, 0);
                Drawable d = div == 0 ? null : ThemeDrawables.drawable(context, div);
                if (d != null) {
                    ListView list = (ListView) v;
                    int height = list.getDividerHeight();
                    list.setDivider(d);
                    list.setDividerHeight(height);
                }
            }
            if (v instanceof ProgressBar) {
                int progress = a.getResourceId(PROGRESS_DRAWABLE, 0);
                Drawable d = progress == 0 ? null : ThemeDrawables.drawable(context, progress);
                if (d != null) ((ProgressBar) v).setProgressDrawable(d);
            }
            if (v instanceof AbsSeekBar) {
                int thumb = a.getResourceId(THUMB, 0);
                Drawable d = thumb == 0 ? null : ThemeDrawables.drawable(context, thumb);
                if (d != null) ((AbsSeekBar) v).setThumb(d);
            }
        } finally {
            a.recycle();
        }
    }

    /** Text colors: a theme resource, or a literal color that matches a default-theme token. */
    private static ColorStateList themedColors(TypedArray a, int index) {
        int res = a.getResourceId(index, 0);
        if (res != 0) return ThemeDrawables.colors(res);
        if (!a.hasValue(index)) return null;
        int literal = a.getColor(index, 0);
        Integer themed = Ui.themedLiteral(literal);
        return themed == null ? null : ColorStateList.valueOf(themed);
    }
}
