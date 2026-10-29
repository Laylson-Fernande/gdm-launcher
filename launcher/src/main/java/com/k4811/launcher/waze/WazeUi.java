package com.k4811.launcher.waze;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Visual dos cartões do Waze embutido (paleta e fonte do próprio Waze), montado no código.
 * Ícones k4811_ic_* e fonte vêm dos recursos do Waze modificado; tamanhos em "dp" do desenho.
 */
final class WazeUi {
    private WazeUi() {
    }

    // ---- paleta: a do próprio Waze (res/values*/colors.xml do APK) ----

    static boolean dark;

    /** Cartões: background_default (White / Grey900). */
    static int card() {
        return dark ? 0xFF202124 : 0xFFFFFFFF;
    }

    /** Contorno só no escuro (separator_default, Grey800); no claro a sombra separa. */
    static int cardBorder() {
        return dark ? 0xFF3C4043 : 0;
    }

    /** primary: Blue500 (dia) / Blue300 (noite). */
    static int primary() {
        return dark ? 0xFF33CCFF : 0xFF0099FF;
    }

    /** on_primary: White (dia) / Grey900 (noite). */
    static int onPrimary() {
        return dark ? 0xFF202124 : 0xFFFFFFFF;
    }

    /** Fundo de seleção (linha ou ficha escolhida): Blue300 a 20%. */
    static int primarySoft() {
        return 0x3333CCFF;
    }

    /** Botão secundário (Cancelar): background_variant (Grey100 / Grey800). */
    static int secondary() {
        return dark ? 0xFF3C4043 : 0xFFF2F4F7;
    }

    /** Encerrar: Red500. */
    static int danger() {
        return 0xFFFF5252;
    }

    /** Cartão da manobra: o azul da barra de navegação do Waze (Blue500 / Blue700). */
    static int maneuver() {
        return dark ? 0xFF1469C6 : 0xFF0099FF;
    }

    /** Parte "Depois" do cartão da manobra (Blue600 / Blue800). */
    static int maneuverDark() {
        return dark ? 0xFF225AA8 : 0xFF0075E3;
    }

    static int onManeuver() {
        return 0xFFFFFFFF;
    }

    /** content_default (Grey900 / Grey200). */
    static int text() {
        return dark ? 0xFFE8EAED : 0xFF202124;
    }

    /** content_p2 (Grey700 / Grey300). */
    static int text2() {
        return dark ? 0xFFD5D7DB : 0xFF55595E;
    }

    static int ripple() {
        return dark ? 0x33FFFFFF : 0x1F000000;
    }

    /** Fundo das telas sem mapa (background_variant). */
    static int screen() {
        return dark ? 0xFF000000 : 0xFFF2F4F7;
    }

    /** Painel de baixo (chegada, rota): preto à noite, branco de dia. */
    static int sheet() {
        return dark ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Tempo restante quando o Waze não manda cor: verde. */
    static int etaGreen() {
        return dark ? 0xFF86CEA0 : 0xFF1B873F;
    }

    /** Alça do painel de baixo. */
    static int handle() {
        return 0xFF979CA0;
    }

    /** Botões redondos: pretos à noite, brancos de dia. */
    static int roundFill() {
        return dark ? 0xFF000000 : 0xFFFFFFFF;
    }

    static final int LIMIT_RED = 0xFFD32F2F;

    static final String WAZE = "com.waze";
    private static Resources wazeRes;

    /** Recursos do Waze modificado (ícones k4811_ic_* e a fonte Proxima Nova vão no APK dele). */
    static Resources wazeRes(Context c) {
        if (wazeRes == null) {
            try {
                wazeRes = c.getPackageManager().getResourcesForApplication(WAZE);
            } catch (Exception e) {
                return null;
            }
        }
        return wazeRes;
    }

    // ---- escala ----

    /**
     * 1 dp = 1/1280 da largura (ou 1/720 da altura, o que for menor), a mesma ideia do
     * applyDesignDensity da suíte. Fica numa variável própria (e não na DisplayMetrics)
     * porque o Waze e o sistema reaplicam a densidade depois do onCreate.
     */
    private static float scale = 1f;

    /** Escala: px por "dp" do desenho 1280×720 (a densidade da suíte, Ui.applyDesignDensity). */
    static void setScale(float pxPerDp) {
        if (pxPerDp > 0) scale = pxPerDp;
    }

    static int dp(float v) {
        return Math.round(v * scale);
    }

    static void textSize(TextView t, float sp) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * scale);
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        textSize(t, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTypeface(font(c, bold ? "proximanova_bold" : "proximanova_semibold"));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static final java.util.Map<String, Typeface> FONTS = new java.util.HashMap<>();

    /** Fonte do Waze (res/font/proximanova_*.otf); sem ela, a do sistema. */
    static Typeface font(Context c, String name) {
        if (FONTS.containsKey(name)) return FONTS.get(name);
        Typeface t = null;
        Resources res = wazeRes(c);
        int id = res == null ? 0 : res.getIdentifier(name, "font", WAZE);
        if (id != 0) {
            try {
                t = res.getFont(id);
            } catch (RuntimeException ignored) {
            }
        }
        if (t == null) t = Typeface.create("sans-serif-medium", name.endsWith("bold") ? Typeface.BOLD : Typeface.NORMAL);
        FONTS.put(name, t);
        return t;
    }

    // ---- fundos ----

    static GradientDrawable rounded(int color, float radius, int border, float borderWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (borderWidth > 0) g.setStroke(Math.max(1, Math.round(borderWidth)), border);
        return g;
    }

    /** Cartão: branco (ou Grey900) com cantos de 28; a sombra vem da elevação (lift). */
    static Drawable cardBg() {
        return rounded(card(), dp(28), cardBorder(), dark ? dp(1.5f) : 0);
    }

    /** Cantos diferentes (em px): superior esquerdo, superior direito, inferior direito, inferior esquerdo. */
    static GradientDrawable corners(int color, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return g;
    }

    /** Pílula (botões do Waze): cantos totalmente redondos. */
    static Drawable pill(int color) {
        return rounded(color, dp(200), 0, 0);
    }

    /** Botões redondos do mapa: círculo branco com sombra, como os do Waze. */
    static Drawable roundButtonBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(roundFill());
        return touch(g);
    }

    /**
     * Sombra suave desenhada no próprio fundo (camadas translúcidas embaixo da forma).
     * Não usa setElevation: a sombra do renderizador derrubava o processo do Waze com a
     * GPU do emulador (SIGSEGV na RenderThread ao navegar), e a central tem GPU própria.
     * radius < 0: círculo.
     */
    static void lift(android.view.View v, float radius) {
        Drawable content = v.getBackground();
        if (content == null || dark) return;
        int s = dp(5);
        boolean oval = radius < 0;
        int[] alphas = {0x0A, 0x10, 0x16};
        Drawable[] layers = new Drawable[alphas.length + 1];
        for (int i = 0; i < alphas.length; i++) {
            GradientDrawable g = new GradientDrawable();
            if (oval) g.setShape(GradientDrawable.OVAL);
            else g.setCornerRadius(radius + s - i * dp(1.5f));
            g.setColor(alphas[i] << 24);
            layers[i] = g;
        }
        layers[alphas.length] = content;
        LayerDrawable l = new LayerDrawable(layers);
        l.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
        for (int i = 0; i < alphas.length; i++) {
            int in = Math.round(i * s / 3f);
            l.setLayerInset(i, in, in + dp(1), in, Math.max(0, in - dp(2)));
        }
        l.setLayerInset(alphas.length, s, s - dp(2), s, s + dp(1));
        v.setBackground(l);
    }

    static Drawable touch(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(ripple()), content,
                content == null ? rounded(0xFFFFFFFF, dp(16), 0, 0) : null);
    }

    // ---- ícones ----

    /** Ícone copiado para res/drawable/k4811_ic_<nome>.xml do Waze, no tamanho pedido. */
    static Drawable icon(Context c, String name, int sizePx, int color) {
        Resources res = wazeRes(c);
        int id = res == null ? 0 : res.getIdentifier("k4811_ic_" + name, "drawable", WAZE);
        if (id == 0) return null;
        Drawable d = res.getDrawable(id, null).mutate();
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return sized(d, sizePx);
    }

    /** Desenha um Drawable num quadrado de sizePx, mantendo a proporção. */
    static Drawable sized(Drawable d, int sizePx) {
        if (d == null) return null;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        int w = sizePx, h = sizePx;
        if (iw > 0 && ih > 0) {
            if (iw > ih) h = Math.round(sizePx * ih / (float) iw);
            else w = Math.round(sizePx * iw / (float) ih);
        }
        LayerDrawable l = new LayerDrawable(new Drawable[]{d});
        l.setLayerSize(0, w, h);
        l.setLayerGravity(0, android.view.Gravity.CENTER);
        return l;
    }
}
