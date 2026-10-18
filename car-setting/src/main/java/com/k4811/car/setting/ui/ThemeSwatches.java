package com.k4811.car.setting.ui;

import com.k4811.common.ui.ThemeDrawables;

import com.k4811.common.ui.Palette;

import com.k4811.common.ui.Ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Theme color picker: one rectangle per theme, no labels. First column: grayscale; then one column
 * per hue. Top row: black background; bottom row: background in the theme color (shown darker).
 * The chosen theme gets a light border.
 */
public final class ThemeSwatches extends LinearLayout {

    public interface OnPick {
        void onPick(int theme);
    }

    private static final int WIDTH_DP = 64;
    private static final int HEIGHT_DP = 40;
    private static final int GAP_DP = 10;

    public ThemeSwatches(Context context, int selected, OnPick onPick) {
        super(context);
        setOrientation(VERTICAL);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(HORIZONTAL);
            LayoutParams lineLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (row > 0) lineLp.topMargin = px(GAP_DP);
            addView(line, lineLp);
            for (int i = 0; i <= Palette.HUES; i++) {
                // Column 0 is grayscale; columns 1..HUES are the hues.
                final int theme = i == 0 ? (row == 0 ? Palette.DEFAULT : Palette.GRAY_TINTED)
                    : row * Palette.HUES + i - 1;
                View swatch = new View(context);
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(Palette.swatch(theme));
                shape.setCornerRadius(px(8));
                if (theme == selected) shape.setStroke(px(3), Ui.TEXT);
                ThemeDrawables.setBackground(swatch, shape);
                swatch.setOnClickListener(v -> onPick.onPick(theme));
                LayoutParams lp = new LayoutParams(px(WIDTH_DP), px(HEIGHT_DP));
                if (i > 0) lp.leftMargin = px(GAP_DP);
                line.addView(swatch, lp);
            }
        }
    }

    private int px(int dp) {
        return Math.round(Ui.dp(getContext(), dp));
    }
}
