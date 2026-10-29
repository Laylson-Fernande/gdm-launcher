package com.k4811.launcher.status;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.Brightness;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.ThemeDrawables;
import com.k4811.common.ui.ToggleSwitch;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.R;

/**
 * Barra de brilho que abre ao tocar no sol/lua: um cartão logo abaixo do ícone, com o sol, a barra e a
 * porcentagem. Muda o brilho enquanto arrasta (no máximo a cada {@value #APPLY_MS} ms, para não encher a
 * fila da MCU) e fecha sozinha depois de {@value #CLOSE_MS} ms sem toque, ou ao tocar fora.
 *
 * Embaixo, o interruptor do modo escuro. Só o car-setting grava o launcher.json: o pedido vai por
 * {@link Apps#ACTION_SET_DARK_MODE} e, quando o arquivo muda, a tela é refeita ({@code onLookChanged}).
 * Com o tema seguindo o dia e a noite, a escolha vale até a próxima troca de período (que ganha, por
 * ser a última); o aviso embaixo do nome lembra disso.
 */
final class BrightnessPopup {

    private static final long APPLY_MS = 120;
    private static final long CLOSE_MS = 5000;
    private static final int WIDTH = 560;
    /** Espera o car-setting gravar o modo escuro, conferindo a cada {@value #DARK_POLL_MS} ms. */
    private static final long DARK_POLL_MS = 100;
    private static final long DARK_TIMEOUT_MS = 3000;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PopupWindow window;
    private final BrightnessBar bar;
    private final TextView percent;
    private final ToggleSwitch dark;
    private final TextView darkHint;
    private final View darkRow;
    /** Tela refeita com o tema novo (a tela inicial recria; a navegação espera a rota acabar). */
    private final Runnable onLookChanged;
    private long darkAsked;
    private int pending = Integer.MIN_VALUE;
    private long lastApply;

    private final Runnable apply = this::applyPending;
    private final Runnable close = this::dismiss;

    BrightnessPopup(Activity a, Runnable onLookChanged) {
        activity = a;
        this.onLookChanged = onLookChanged;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // O drawable do tema em uso (o do XML é sempre o escuro).
        box.setBackground(ThemeDrawables.drawable(a, R.drawable.panel));
        int pad = dp(20);
        box.setPadding(pad, 0, pad, 0);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setContentDescription(a.getString(R.string.settings_brightness));
        box.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        IconView sun = new IconView(a);
        sun.set(IconView.Icon.SUN, IconView.Style.NONE).color(Ui.TEXT_SOFT);
        sun.setClickable(false);
        card.addView(sun, new LinearLayout.LayoutParams(dp(40), dp(40)));

        bar = new BrightnessBar(a);
        bar.setPadding(dp(12), 0, dp(12), 0);
        card.addView(bar, new LinearLayout.LayoutParams(0, dp(64), 1f));

        percent = new TextView(a);
        percent.setTextColor(Ui.TEXT);
        percent.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(a, 24));
        percent.setTypeface(Ui.semiBold(a));
        percent.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        card.addView(percent, new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));

        bar.setListener((value, done) -> {
            renderPercent(value);
            pending = value;
            handler.removeCallbacks(close);
            handler.postDelayed(close, CLOSE_MS);
            long wait = APPLY_MS - (System.currentTimeMillis() - lastApply);
            handler.removeCallbacks(apply);
            if (done || wait <= 0) applyPending();
            else handler.postDelayed(apply, wait);
        });

        // Modo escuro: lua, nome (e "segue o dia e a noite", se for o caso) e o interruptor.
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        darkRow = row;
        IconView moon = new IconView(a);
        moon.set(IconView.Icon.MOON, IconView.Style.NONE).color(Ui.TEXT_SOFT);
        moon.setClickable(false);
        row.addView(moon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(12), 0);
        TextView label = new TextView(a);
        label.setText(R.string.settings_dark_mode);
        label.setTextColor(Ui.TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(a, 22));
        label.setTypeface(Ui.medium(a));
        texts.addView(label);
        darkHint = new TextView(a);
        darkHint.setText(R.string.home_dark_mode_auto);
        darkHint.setTextColor(Ui.TEXT_MUTED);
        darkHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(a, 16));
        darkHint.setTypeface(Ui.regular(a));
        texts.addView(darkHint);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dark = new ToggleSwitch(a);
        row.addView(dark);
        row.setOnClickListener(v -> toggleDark());
        dark.setOnClickListener(v -> toggleDark());
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        window = new PopupWindow(box, dp(WIDTH), dp(88 + 72 + 8), false);
        // Fundo transparente: sem ele o toque fora não fecha a janela em alguns Androids.
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setOutsideTouchable(true);
        window.setOnDismissListener(() -> {
            handler.removeCallbacks(close);
            applyPending();
        });
    }

    boolean isShowing() {
        return window.isShowing();
    }

    /**
     * Abre logo abaixo de {@code anchor}, com o brilho atual: alinhada ao ícone e, se não couber, deslocada
     * para dentro de {@code area}, sem passar da largura dela (o painel estreito da tela de navegação).
     */
    void show(View anchor, View area) {
        int min = Brightness.min(activity);
        int max = Brightness.max(activity);
        int value = Brightness.get(activity);
        bar.setRange(min, max);
        bar.setValue(value);
        renderPercent(Math.max(min, Math.min(max, value)));
        renderDark();
        int width = Math.max(dp(320), Math.min(dp(WIDTH), area.getWidth() - dp(24)));
        window.setWidth(width);
        int[] a = new int[2];
        int[] r = new int[2];
        anchor.getLocationOnScreen(a);
        area.getLocationOnScreen(r);
        int left = Math.max(r[0] + dp(12), Math.min(a[0] - dp(8), r[0] + area.getWidth() - width - dp(12)));
        int x = left - a[0];
        window.showAsDropDown(anchor, x, dp(8));
        handler.postDelayed(close, CLOSE_MS);
    }

    /** Estado do interruptor; com o tema no automático (dia/noite), avisa até quando a escolha vale. */
    private void renderDark() {
        boolean auto = LauncherConfig.dayNightTheme(activity);
        dark.setOn(LauncherConfig.darkMode(activity), false);
        darkHint.setVisibility(auto ? View.VISIBLE : View.GONE);
    }

    /** Pede a troca ao car-setting e espera o launcher.json mudar para refazer a tela. */
    private void toggleDark() {
        if (darkAsked != 0) return;
        boolean want = !LauncherConfig.darkMode(activity);
        dark.setOn(want, true);
        handler.removeCallbacks(close);
        Intent i = new Intent(Apps.ACTION_SET_DARK_MODE);
        i.setPackage(Apps.SETTINGS);
        // A NWD para apps à força (NwdProcessAdjuster); parado, o car-setting só recebe com esta flag.
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        i.putExtra(Apps.EXTRA_ON, want);
        activity.sendBroadcast(i);
        darkAsked = System.currentTimeMillis();
        handler.postDelayed(() -> waitDark(want), DARK_POLL_MS);
    }

    private void waitDark(boolean want) {
        if (LauncherConfig.darkMode(activity) == want) {
            darkAsked = 0;
            dismiss();
            onLookChanged.run();
            return;
        }
        if (System.currentTimeMillis() - darkAsked > DARK_TIMEOUT_MS) {
            // O car-setting não gravou (não instalado?): volta o interruptor.
            darkAsked = 0;
            renderDark();
            handler.postDelayed(close, CLOSE_MS);
            return;
        }
        handler.postDelayed(() -> waitDark(want), DARK_POLL_MS);
    }

    void dismiss() {
        if (window.isShowing()) window.dismiss();
    }

    private void applyPending() {
        if (pending == Integer.MIN_VALUE) return;
        Brightness.set(activity, pending);
        pending = Integer.MIN_VALUE;
        lastApply = System.currentTimeMillis();
    }

    /** Em % do máximo: a barra começa no mínimo ({@link Brightness#min}, 20%), não em 0%. */
    private void renderPercent(int value) {
        int max = Brightness.max(activity);
        int p = Math.round(100f * value / Math.max(1, max));
        percent.setText(p + "%");
    }

    private int dp(float v) {
        return Math.round(Ui.dp(activity, v));
    }
}
