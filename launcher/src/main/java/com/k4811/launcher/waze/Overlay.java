package com.k4811.launcher.waze;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenha os templates do modo carro do Waze por cima do mapa, no layout da tela de
 * navegação da suíte:
 * - navegando: cartão da manobra em cima à esquerda, cartão de chegada embaixo no meio;
 * - sem rota: barra "Para onde?" em cima à esquerda;
 * - prévia da rota: cartão embaixo com o destino, Cancelar e Ir;
 * - botões redondos do mapa na coluna da direita;
 * - listas, busca e mensagens num painel à esquerda (ou em tela cheia, sem mapa).
 * Velocidade, limite e alertas de radar são desenhados pelo próprio Waze no mapa.
 * Portado do projeto recompile-optimized (Sources/waze-ui), onde roda dentro do Waze.
 */
final class Overlay {
    interface Host {
        /**
         * visible: área do mapa fora dos cartões (o Waze centraliza o carro nela);
         * stable: área que não muda com os cartões da esquerda (o Waze põe o velocímetro nela).
         */
        void onMapArea(Rect visible, Rect stable);

        /** Mostra ou esconde o mapa (templates sem mapa ocupam a tela toda). */
        void onMapShown(boolean shown);

        /** Navegando: manobra e chegada, para a tela de navegação do launcher. */
        void onRoute(CarBundle.Obj routing, CarBundle.Obj estimate);

        void back();

        /** O Waze modificado tem o receiver de comandos (encerrar a navegação). */
        boolean canStopNavigation();

        void stopNavigation();
    }

    private static final int M = 16;
    /** Largura da coluna da esquerda (manobra, painel de baixo, listas). */
    private static final int LEFT_W = 612;
    private static final int ROUND = 84;
    private static final int ROUND_GAP = 22;
    /** Espaço em volta dos botões para a sombra não ser cortada. */
    private static final int SHADOW_ROOM = 14;
    /** Marca dos botões só com ícone (textButton sem título). */
    private static final String ICON_ONLY = "icon";
    /** Ação de busca do Waze (vira a barra "Para onde?"): pelo nome do ícone ou pelo título. */
    private static final String[] SEARCH_HINTS = {"search", "busca", "pesquis", "buscar"};

    private final Context c;
    private final FrameLayout root;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private CarBundle.Obj template;
    private String templateId;
    private boolean panMode;
    /** A manobra e a chegada aparecem no painel do launcher ao lado: sem os cartões de rota. */
    private boolean routeElsewhere;
    /** Navegando com os cartões escondidos: o ✕ de encerrar vai para a coluna de botões. */
    private boolean endInColumn;
    private boolean mapTemplate = true;

    /** Áreas que cobrem o mapa (para calcular a área visível). */
    private final List<View> covers = new ArrayList<>();
    private View rightColumn;
    private View leftPanel;

    // Busca: a caixa de texto continua a mesma entre atualizações do template.
    private EditText searchField;
    private LinearLayout searchResults;
    private String searchKey;
    private final Runnable searchChanged = this::sendSearchText;

    // Alerta (showAlert).
    private View alertView;
    private CarBundle.Obj alert;
    private final Runnable alertTimeout = () -> closeAlert(1);

    private View status;

    Overlay(Context c, FrameLayout root, Host host) {
        this.c = c;
        this.root = root;
        this.host = host;
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // Tela dividida entrando ou saindo: a largura muda e o layout (compacto ou não) também.
            if (r - l != or - ol && template != null) v.post(() -> draw(true));
            reportArea();
        });
    }

    // ---- entrada ----

    void status(String text) {
        if (status != null) root.removeView(status);
        status = null;
        if (text == null) return;
        TextView t = WazeUi.text(c, 28, WazeUi.text(), true);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(WazeUi.dp(40), WazeUi.dp(28), WazeUi.dp(40), WazeUi.dp(28));
        t.setBackground(WazeUi.cardBg());
        root.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        status = t;
    }

    /** TemplateWrapper do getTemplate. */
    void render(CarBundle.Obj wrapper) {
        CarBundle.Obj t = wrapper.obj("mTemplate");
        if (t == null) return;
        String id = wrapper.str("mId");
        boolean sameScreen = id != null && id.equals(templateId);
        if (!sameScreen) panMode = false;
        template = t;
        templateId = id;
        draw(sameScreen);
    }

    void setRouteElsewhere(boolean elsewhere) {
        if (elsewhere == routeElsewhere) return;
        routeElsewhere = elsewhere;
        if (template != null) draw(true);
    }

    /** Tema mudou (dia/noite): redesenha o último template. */
    void redraw() {
        if (template != null) draw(false);
        if (alert != null) showAlert(alert);
    }

    // ---- desenho ----

    private void draw(boolean sameScreen) {
        String kind = template.simpleName();
        // Busca: atualizar só os resultados, para não perder o texto e o teclado.
        if ("SearchTemplate".equals(kind) && searchField != null) {
            fillSearchResults(template);
            return;
        }
        int scroll = sameScreen && leftPanel != null ? scrollOf(leftPanel) : 0;
        clear();
        boolean map = true;
        switch (kind) {
            case "NavigationTemplate":
                drawNavigation(template);
                break;
            case "RoutePreviewNavigationTemplate":
                drawRoutePreview(template);
                break;
            case "PlaceListNavigationTemplate":
                drawListPanel(template, template.obj("mItemList"), null);
                drawMapButtons(template, template.obj("mMapActionStrip"), template.obj("mActionStrip"), false);
                break;
            case "MapWithContentTemplate": {
                CarBundle.Obj content = template.obj("mContentTemplate");
                CarBundle.Obj controller = template.obj("mMapController");
                if (content != null) drawContentPanel(content);
                drawMapButtons(controller, controller == null ? null : controller.obj("mMapActionStrip"),
                        template.obj("mActionStrip"), false);
                break;
            }
            case "MapTemplate": {
                CarBundle.Obj controller = template.obj("mMapController");
                CarBundle.Obj pane = template.obj("mPane");
                if (isPlaceCard(pane)) drawPlaceCard(template, pane);
                else if (pane != null) drawPanePanel(template, pane);
                else drawListPanel(template, template.obj("mItemList"), null);
                drawMapButtons(controller, controller == null ? null : controller.obj("mMapActionStrip"),
                        template.obj("mActionStrip"), false);
                break;
            }
            case "SearchTemplate":
                drawSearch(template);
                break;
            default:
                // ListTemplate, GridTemplate, MessageTemplate, LongMessageTemplate, PaneTemplate.
                map = false;
                drawFullScreen(template);
                break;
        }
        if (map != mapTemplate) {
            mapTemplate = map;
            host.onMapShown(map);
        }
        if (scroll > 0) restoreScroll(leftPanel, scroll);
        if (status != null) status.bringToFront();
        if (alertView != null) alertView.bringToFront();
        root.requestLayout();
    }

    private void clear() {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if (v != status && v != alertView) root.removeViewAt(i);
        }
        covers.clear();
        rightColumn = null;
        leftPanel = null;
        if (searchField != null) hideKeyboard(searchField);
        searchField = null;
        searchResults = null;
        searchKey = null;
    }

    // ---- NavigationTemplate ----

    private void drawNavigation(CarBundle.Obj t) {
        CarBundle.Obj info = t.obj("mNavigationInfo");
        CarBundle.Obj estimate = t.obj("mDestinationTravelEstimate");
        CarBundle.Obj strip = t.obj("mActionStrip");
        CarBundle.Obj search = findSearch(strip);

        boolean searchBar = false;
        boolean routing = info != null && info.is("RoutingInfo");
        endInColumn = routing && routeElsewhere && host.canStopNavigation();
        if (routing && routeElsewhere) {
            host.onRoute(info, estimate);
        } else if (routing) {
            addTop(maneuverBlock(info));
            host.onRoute(info, estimate);
        } else if (info != null && info.is("MessageInfo")) {
            addTop(messageCard(info));
        } else if (search != null && !panMode) {
            addTop(searchBar(search));
            searchBar = true;
        }
        if (android.util.Log.isLoggable(CarSession.TAG, android.util.Log.DEBUG)) {
            for (CarBundle.Obj a : actions(strip)) {
                android.util.Log.d(CarSession.TAG, "ação " + Texts.plain(a.get("mTitle")) + " ícone=" + Icons.resourceName(c, a.obj("mIcon")));
            }
        }
        if (estimate != null && !panMode && !routeElsewhere) addSheet(etaSheet(estimate, titledActions(strip)));
        drawMapButtons(t, t.obj("mMapActionStrip"), strip, searchBar);
    }

    /**
     * Manobra no estilo do Android Auto: cartão com a seta (e a distância embaixo dela) e a
     * instrução em letra grande; a próxima manobra numa aba presa embaixo ("Depois, ↱").
     */
    private View maneuverBlock(CarBundle.Obj routing) {
        CarBundle.Obj step = routing.obj("mCurrentStep");
        CarBundle.Obj maneuver = step == null ? null : step.obj("mManeuver");
        CarBundle.Obj next = routing.obj("mNextStep");
        CarBundle.Obj nextManeuver = next == null ? null : next.obj("mManeuver");
        ImageView nextIcon = image(nextManeuver == null ? null : nextManeuver.obj("mIcon"), 40, WazeUi.onManeuver());
        float r = WazeUi.dp(22);

        LinearLayout block = vertical();
        LinearLayout card = new LinearLayout(c);
        card.setClickable(true);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(WazeUi.corners(WazeUi.maneuver(), r, r, r, nextIcon == null ? r : 0));
        card.setPadding(WazeUi.dp(24), WazeUi.dp(16), WazeUi.dp(24), WazeUi.dp(16));
        card.setMinimumHeight(WazeUi.dp(127));

        LinearLayout arrow = vertical();
        arrow.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = image(maneuver == null ? null : maneuver.obj("mIcon"), 64, WazeUi.onManeuver());
        if (icon != null) arrow.addView(icon);
        TextView dist = WazeUi.text(c, 24, WazeUi.onManeuver(), true);
        String d = Texts.distance(routing.obj("mCurrentDistance"));
        dist.setText(routing.b("mIsLoading") ? "…" : d == null ? "" : d);
        arrow.addView(dist, margins(-2, -2, 0, 6, 0, 0));
        card.addView(arrow, margins(WazeUi.dp(100), -2, 0, 0, 20, 0));

        LinearLayout texts = vertical();
        CharSequence cue = step == null ? null : Texts.of(c, step.get("mCue"), WazeUi.dp(34));
        if (cue == null && step != null) cue = Texts.of(c, step.get("mRoad"), WazeUi.dp(34));
        TextView cueView = WazeUi.text(c, 34, WazeUi.onManeuver(), true);
        cueView.setSingleLine(false);
        cueView.setMaxLines(2);
        cueView.setText(cue == null ? "" : cue);
        texts.addView(cueView);
        // Faixas (imagem pronta do Waze), embaixo da instrução.
        ImageView lanes = step == null ? null : image(step.obj("mLanesImage"), 0, false);
        if (lanes != null) {
            lanes.setAdjustViewBounds(true);
            lanes.setScaleType(ImageView.ScaleType.FIT_START);
            texts.addView(lanes, margins(-2, WazeUi.dp(40), 0, 10, 0, 0));
        }
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        block.addView(card, new LinearLayout.LayoutParams(-1, -2));

        if (nextIcon != null) {
            LinearLayout tab = new LinearLayout(c);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setBackground(WazeUi.corners(WazeUi.maneuverDark(), 0, 0, r, r));
            tab.setPadding(WazeUi.dp(28), 0, WazeUi.dp(24), 0);
            TextView then = WazeUi.text(c, 30, WazeUi.onManeuver(), true);
            then.setText(Texts.then() + ",");
            tab.addView(then);
            tab.addView(nextIcon, margins(WazeUi.dp(40), WazeUi.dp(40), 14, 0, 0, 0));
            block.addView(tab, new LinearLayout.LayoutParams(-2, WazeUi.dp(69)));
        }
        return block;
    }

    private View messageCard(CarBundle.Obj msg) {
        LinearLayout card = card(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(WazeUi.dp(28), WazeUi.dp(24), WazeUi.dp(28), WazeUi.dp(24));
        ImageView icon = image(msg.obj("mImage"), 72, true);
        if (icon != null) card.addView(icon, margins(WazeUi.dp(72), WazeUi.dp(72), 0, 0, 24, 0));
        LinearLayout texts = vertical();
        CharSequence title = Texts.of(c, msg.get("mTitle"), WazeUi.dp(34));
        if (title != null) {
            TextView tv = WazeUi.text(c, 34, WazeUi.text(), true);
            tv.setText(title);
            texts.addView(tv);
        }
        CharSequence text = Texts.of(c, msg.get("mText"), WazeUi.dp(24));
        if (text != null) {
            TextView tv = WazeUi.text(c, 24, WazeUi.text2(), false);
            tv.setSingleLine(false);
            tv.setMaxLines(3);
            tv.setText(text);
            texts.addView(tv, margins(-2, -2, 0, 8, 0, 0));
        }
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        return card;
    }

    private View searchBar(CarBundle.Obj action) {
        LinearLayout bar = card(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(WazeUi.dp(32), 0, WazeUi.dp(32), 0);
        bar.setMinimumHeight(WazeUi.dp(90));
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(WazeUi.icon(c, "search", WazeUi.dp(34), WazeUi.text2()));
        bar.addView(icon, margins(WazeUi.dp(40), WazeUi.dp(40), 0, 0, 24, 0));
        TextView hint = WazeUi.text(c, 32, WazeUi.text2(), false);
        hint.setText(Texts.whereTo());
        bar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        bar.setOnClickListener(v -> Calls.click(action));
        bar.setBackground(WazeUi.touch(WazeUi.cardBg()));
        WazeUi.lift(bar, WazeUi.dp(28));
        return bar;
    }

    /**
     * Painel de baixo durante a navegação (preso à borda, à esquerda): ✕ para encerrar,
     * tempo restante grande, "1,8 km • 21:31" embaixo.
     */
    private View etaSheet(CarBundle.Obj estimate, List<CarBundle.Obj> actions) {
        LinearLayout sheet = sheet();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(WazeUi.dp(20), 0, WazeUi.dp(20), WazeUi.dp(14));

        View end = null;
        if (!actions.isEmpty()) {
            CarBundle.Obj a = actions.get(0);
            end = circleButton(WazeUi.icon(c, "close", WazeUi.dp(36), WazeUi.text()), v -> Calls.click(a));
        } else if (host.canStopNavigation()) {
            // O modo carro do Waze não tem "parar": o ✕ chama o stopNavigation dele.
            end = circleButton(WazeUi.icon(c, "close", WazeUi.dp(36), WazeUi.text()), v -> host.stopNavigation());
        }
        if (end != null) row.addView(end, new LinearLayout.LayoutParams(WazeUi.dp(80), WazeUi.dp(80)));
        else row.addView(new View(c), new LinearLayout.LayoutParams(WazeUi.dp(80), 1));

        LinearLayout texts = vertical();
        texts.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView time = WazeUi.text(c, 40, Colors.of(estimate.obj("mRemainingTimeColor"), WazeUi.dark, WazeUi.etaGreen()), true);
        long secs = estimate.l("mRemainingTimeSeconds", -1);
        time.setText(secs < 0 ? "" : Texts.duration(secs).replace(" min", "min"));
        texts.addView(time, new LinearLayout.LayoutParams(-2, -2));
        StringBuilder sub = new StringBuilder();
        String dist = Texts.distance(estimate.obj("mRemainingDistance"));
        if (dist != null) sub.append(dist);
        String clock = Texts.clock(estimate.obj("mArrivalTimeAtDestination"));
        if (clock != null) sub.append(sub.length() > 0 ? "  •  " : "").append(clock);
        TextView tv = WazeUi.text(c, 24, WazeUi.text2(), false);
        tv.setText(sub);
        texts.addView(tv, margins(-2, -2, 0, 6, 0, 0));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        if (actions.size() > 1) {
            CarBundle.Obj a = actions.get(1);
            row.addView(circleButton(tinted(Icons.drawable(c, a.obj("mIcon"), WazeUi.dark), a.obj("mIcon")), v -> Calls.click(a)),
                    new LinearLayout.LayoutParams(WazeUi.dp(80), WazeUi.dp(80)));
        } else {
            row.addView(new View(c), new LinearLayout.LayoutParams(WazeUi.dp(80), 1));
        }
        sheet.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return sheet;
    }

    /** Painel de baixo: cantos de cima redondos, alça no meio. */
    private LinearLayout sheet() {
        LinearLayout sheet = vertical();
        sheet.setClickable(true);
        float r = WazeUi.dp(26);
        sheet.setBackground(WazeUi.corners(WazeUi.sheet(), r, r, 0, 0));
        sheet.setMinimumHeight(WazeUi.dp(118));
        View handle = new View(c);
        handle.setBackground(WazeUi.pill(WazeUi.handle()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WazeUi.dp(36), WazeUi.dp(5));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = WazeUi.dp(10);
        lp.bottomMargin = WazeUi.dp(4);
        sheet.addView(handle, lp);
        return sheet;
    }

    /** Botão redondo dos painéis (✕): fundo do painel com contorno. */
    private View circleButton(Drawable icon, View.OnClickListener l) {
        ImageView b = new ImageView(c);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageDrawable(WazeUi.sized(icon, WazeUi.dp(36)));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(WazeUi.sheet());
        g.setStroke(Math.max(1, WazeUi.dp(2)), WazeUi.dark ? 0xFF3C4043 : 0xFFD5D7DB);
        b.setBackground(WazeUi.touch(g));
        b.setOnClickListener(l);
        return b;
    }

    // ---- RoutePreviewNavigationTemplate ----

    private void drawRoutePreview(CarBundle.Obj t) {
        CarBundle.Obj list = t.obj("mItemList");
        List<Object> items = list == null ? new ArrayList<>() : list.list("mItems");
        int selected = list == null ? 0 : Math.max(0, Math.min(items.size() - 1, list.i("mSelectedIndex", 0)));
        CarBundle.Obj row = items.isEmpty() ? null : (CarBundle.Obj) items.get(selected);
        CharSequence title = headerTitle(t);
        if (title == null && row != null) title = Texts.of(c, row.get("mTitle"), WazeUi.dp(32));

        List<View> buttons = new ArrayList<>();
        CarBundle.Obj back = headerStartAction(t);
        if (back != null) buttons.add(textButton(back, Texts.cancel(), false));
        CarBundle.Obj go = t.obj("mNavigateAction");
        if (go != null) buttons.add(textButton(go, null, true));

        LinearLayout sheet = infoSheet(title, t.b("mIsLoading") ? "…" : routeSummary(row, title), null, buttons);
        // Mais de uma rota: fichas no painel, acima dos botões.
        if (items.size() > 1) {
            LinearLayout chips = new LinearLayout(c);
            chips.setPadding(WazeUi.dp(28), 0, WazeUi.dp(28), WazeUi.dp(4));
            for (int i = 0; i < items.size(); i++) {
                final int index = i;
                CarBundle.Obj r = (CarBundle.Obj) items.get(i);
                TextView chip = WazeUi.text(c, 22, i == selected ? WazeUi.onPrimary() : WazeUi.text(), i == selected);
                CharSequence label = Texts.of(c, r.get("mTitle"), WazeUi.dp(22));
                chip.setText(label == null ? String.valueOf(i + 1) : label);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(WazeUi.dp(22), 0, WazeUi.dp(22), 0);
                chip.setBackground(WazeUi.touch(WazeUi.pill(i == selected ? WazeUi.primary() : WazeUi.secondary())));
                chip.setOnClickListener(v -> Calls.select(list, index));
                chips.addView(chip, margins(-2, WazeUi.dp(52), i == 0 ? 0 : 10, 0, 0, 0));
            }
            sheet.addView(chips, sheet.getChildCount() - 1);
        }
        addSheet(sheet);
        drawMapButtons(t, t.obj("mMapActionStrip"), t.obj("mActionStrip"), false);
    }

    /** Painel de baixo com título, resumo, ícones extras e uma linha de botões. */
    private LinearLayout infoSheet(CharSequence title, CharSequence sub, List<View> icons, List<View> buttons) {
        LinearLayout sheet = sheet();
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(WazeUi.dp(28), WazeUi.dp(4), WazeUi.dp(20), WazeUi.dp(10));
        LinearLayout texts = vertical();
        TextView name = WazeUi.text(c, 32, WazeUi.text(), true);
        name.setText(title == null ? "" : title);
        texts.addView(name);
        if (sub != null && sub.length() > 0) {
            TextView sv = WazeUi.text(c, 22, WazeUi.text2(), false);
            sv.setText(sub);
            texts.addView(sv, margins(-2, -2, 0, 8, 0, 0));
        }
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (icons != null) for (View v : icons) top.addView(v, margins(WazeUi.dp(64), WazeUi.dp(64), 8, 0, 0, 0));
        sheet.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        row.setPadding(WazeUi.dp(20), WazeUi.dp(6), WazeUi.dp(20), WazeUi.dp(18));
        for (int i = 0; i < buttons.size(); i++) {
            View b = buttons.get(i);
            // Só ícone: quadrado; os de texto dividem o resto da largura.
            boolean icon = ICON_ONLY.equals(b.getTag());
            LinearLayout.LayoutParams lp = margins(icon ? WazeUi.dp(95) : 0, WazeUi.dp(80), i == 0 ? 0 : 12, 0, 0, 0);
            if (!icon) lp.weight = 1;
            row.addView(b, lp);
        }
        sheet.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return sheet;
    }

    /** "4 min · 2,8 km": título da rota (duração) e a primeira linha de texto. */
    private CharSequence routeSummary(CarBundle.Obj row, CharSequence destination) {
        if (row == null) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence rt = Texts.of(c, row.get("mTitle"), WazeUi.dp(24));
        if (rt != null && (destination == null || !rt.toString().equals(destination.toString()))) sb.append(rt);
        for (Object o : row.list("mTexts")) {
            CharSequence s = Texts.of(c, o, WazeUi.dp(24));
            if (s == null || s.length() == 0) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s);
            break;
        }
        return sb.toString();
    }

    // ---- lugar escolhido (painel pequeno): cartão de baixo ----

    private static boolean isPlaceCard(CarBundle.Obj pane) {
        if (pane == null || pane.b("mIsLoading")) return false;
        return pane.list("mRows").size() <= 2 && !pane.list("mActionList").isEmpty()
                && pane.list("mActionList").size() <= 2;
    }

    /** Nome do lugar, "19 min · 5,1 km", ícones do cabeçalho, Cancelar e Ir. */
    private void drawPlaceCard(CarBundle.Obj t, CarBundle.Obj pane) {
        List<Object> rows = pane.list("mRows");
        CarBundle.Obj first = rows.isEmpty() ? null : (CarBundle.Obj) rows.get(0);
        CarBundle.Obj last = rows.isEmpty() ? null : (CarBundle.Obj) rows.get(rows.size() - 1);
        CharSequence title = first == null ? null : Texts.of(c, first.get("mTitle"), WazeUi.dp(32));
        if (title == null) title = headerTitle(t);
        List<Object> lines = last == null ? new ArrayList<>() : last.list("mTexts");
        CharSequence sub = lines.isEmpty() ? null : Texts.of(c, lines.get(lines.size() - 1), WazeUi.dp(22));
        List<View> icons = new ArrayList<>();
        CarBundle.Obj h = t.obj("mHeader");
        if (h != null) {
            for (Object o : h.list("mEndHeaderActions")) {
                if (o instanceof CarBundle.Obj && !hasTitle((CarBundle.Obj) o)) icons.add(smallAction((CarBundle.Obj) o));
            }
        }
        List<View> buttons = new ArrayList<>();
        CarBundle.Obj back = headerStartAction(t);
        if (back != null) buttons.add(textButton(back, Texts.cancel(), false));
        for (Object o : pane.list("mActionList")) {
            if (o instanceof CarBundle.Obj) buttons.add(textButton((CarBundle.Obj) o, null, true));
        }
        addSheet(infoSheet(title, sub, icons, buttons));
    }

    // ---- painel à esquerda (listas, lugares, busca, painel) ----

    private void drawContentPanel(CarBundle.Obj content) {
        if (content.is("MessageTemplate") || content.is("LongMessageTemplate")) {
            drawMessagePanel(content);
        } else if (content.is("PaneTemplate")) {
            if (isPlaceCard(content.obj("mPane"))) drawPlaceCard(content, content.obj("mPane"));
            else drawPanePanel(content, content.obj("mPane"));
        } else {
            drawListPanel(content, content.obj("mSingleList"), content.list("mSectionedLists"));
        }
    }

    private LinearLayout panel(CarBundle.Obj t, boolean fullScreen) {
        LinearLayout panel = card(LinearLayout.VERTICAL);
        panel.setPadding(WazeUi.dp(8), WazeUi.dp(8), WazeUi.dp(8), WazeUi.dp(8));
        View header = header(t);
        if (header != null) panel.addView(header);
        FrameLayout.LayoutParams lp;
        if (fullScreen) {
            lp = new FrameLayout.LayoutParams(Math.min(WazeUi.dp(980), root.getWidth() > 0 ? root.getWidth() - WazeUi.dp(2 * M) : WazeUi.dp(980)),
                    -1, Gravity.CENTER_HORIZONTAL);
            lp.topMargin = lp.bottomMargin = WazeUi.dp(M);
        } else {
            lp = new FrameLayout.LayoutParams(leftWidth(), -2, Gravity.START | Gravity.TOP);
            lp.leftMargin = lp.topMargin = lp.bottomMargin = WazeUi.dp(M);
        }
        root.addView(panel, lp);
        leftPanel = panel;
        if (!fullScreen) covers.add(panel);
        return panel;
    }

    private void drawListPanel(CarBundle.Obj t, CarBundle.Obj single, List<Object> sections) {
        LinearLayout panel = panel(t, false);
        fillList(panel, t, single, sections);
    }

    private void fillList(LinearLayout panel, CarBundle.Obj t, CarBundle.Obj single, List<Object> sections) {
        ScrollView scroll = new ScrollView(c);
        LinearLayout rows = vertical();
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        if (t.b("mIsLoading")) {
            rows.addView(note("…"));
            return;
        }
        if (single != null) addItems(rows, single);
        if (sections != null) {
            for (Object o : sections) {
                if (!(o instanceof CarBundle.Obj)) continue;
                CarBundle.Obj s = (CarBundle.Obj) o;
                CharSequence h = Texts.of(c, s.get("mHeader"), WazeUi.dp(22));
                if (h != null) {
                    TextView hv = WazeUi.text(c, 22, WazeUi.text2(), true);
                    hv.setText(h);
                    hv.setPadding(WazeUi.dp(20), WazeUi.dp(20), WazeUi.dp(20), WazeUi.dp(8));
                    rows.addView(hv);
                }
                addItems(rows, s.obj("mItemList"));
            }
        }
        addActionButtons(panel, t.list("mActions"));
    }

    private void addItems(LinearLayout rows, CarBundle.Obj list) {
        if (list == null) return;
        List<Object> items = list.list("mItems");
        if (items.isEmpty()) {
            CharSequence empty = Texts.of(c, list.get("mNoItemsMessage"), WazeUi.dp(24));
            if (empty != null) rows.addView(note(empty));
            return;
        }
        boolean selectable = list.obj("mOnSelectedDelegate") != null;
        int selected = list.i("mSelectedIndex", -1);
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (!(o instanceof CarBundle.Obj)) continue;
            rows.addView(row((CarBundle.Obj) o, list, i, selectable, selectable && i == selected));
        }
    }

    private View row(CarBundle.Obj item, CarBundle.Obj list, int index, boolean selectable, boolean selected) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(WazeUi.dp(92));
        r.setPadding(WazeUi.dp(20), WazeUi.dp(12), WazeUi.dp(16), WazeUi.dp(12));
        int decoration = item.i("mNumericDecoration", -1);
        if (decoration >= 0) {
            TextView n = WazeUi.text(c, 24, WazeUi.text(), true);
            n.setText(String.valueOf(decoration));
            n.setGravity(Gravity.CENTER);
            r.addView(n, margins(WazeUi.dp(44), -2, 0, 0, 16, 0));
        }
        ImageView img = image(item.obj("mImage"), 56, false);
        if (img != null) r.addView(img, margins(WazeUi.dp(56), WazeUi.dp(56), 0, 0, 20, 0));
        LinearLayout texts = vertical();
        CharSequence title = Texts.of(c, item.get("mTitle"), WazeUi.dp(26));
        TextView tv = WazeUi.text(c, 26, WazeUi.text(), selected);
        tv.setText(title == null ? "" : title);
        texts.addView(tv);
        List<Object> lines = item.list("mTexts");
        if (lines.isEmpty() && item.get("mText") != null) lines = java.util.Collections.singletonList(item.get("mText"));
        for (Object line : lines) {
            CharSequence s = Texts.of(c, line, WazeUi.dp(21));
            if (s == null) continue;
            TextView lv = WazeUi.text(c, 21, WazeUi.text2(), false);
            lv.setText(s);
            texts.addView(lv, margins(-2, -2, 0, 6, 0, 0));
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        CarBundle.Obj toggle = item.obj("mToggle");
        if (toggle != null) {
            Switch sw = new Switch(c);
            sw.setChecked(toggle.b("mIsChecked"));
            sw.setOnCheckedChangeListener((b, on) -> Calls.checked(toggle, on));
            r.addView(sw);
            r.setOnClickListener(v -> sw.toggle());
        }
        for (Object a : item.list("mActions")) {
            if (a instanceof CarBundle.Obj) r.addView(smallAction((CarBundle.Obj) a), margins(WazeUi.dp(64), WazeUi.dp(64), 8, 0, 0, 0));
        }
        if (item.b("mIsBrowsable")) {
            ImageView chevron = new ImageView(c);
            Drawable back = WazeUi.icon(c, "back", WazeUi.dp(28), WazeUi.text2());
            chevron.setImageDrawable(back);
            chevron.setRotation(180);
            r.addView(chevron, margins(WazeUi.dp(32), WazeUi.dp(32), 8, 0, 0, 0));
        }
        boolean enabled = !item.fields.containsKey("mIsEnabled") || item.b("mIsEnabled");
        if (toggle == null && enabled) {
            if (selectable) r.setOnClickListener(v -> Calls.select(list, index));
            else if (item.obj("mOnClickDelegate") != null) r.setOnClickListener(v -> Calls.click(item));
        }
        if (!enabled) r.setAlpha(0.5f);
        r.setBackground(WazeUi.touch(selected ? WazeUi.rounded(WazeUi.primarySoft(), WazeUi.dp(20), 0, 0) : null));
        return r;
    }

    private void drawPanePanel(CarBundle.Obj t, CarBundle.Obj pane) {
        LinearLayout panel = panel(t, false);
        fillPane(panel, pane);
    }

    private void fillPane(LinearLayout panel, CarBundle.Obj pane) {
        ScrollView scroll = new ScrollView(c);
        LinearLayout rows = vertical();
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        if (pane == null) return;
        if (pane.b("mIsLoading")) {
            rows.addView(note("…"));
            return;
        }
        ImageView img = image(pane.obj("mImage"), 0, false);
        if (img != null) {
            img.setAdjustViewBounds(true);
            rows.addView(img, margins(-1, WazeUi.dp(200), 0, 0, 0, 8));
        }
        for (Object o : pane.list("mRows")) {
            if (o instanceof CarBundle.Obj) rows.addView(row((CarBundle.Obj) o, null, 0, false, false));
        }
        addActionButtons(panel, pane.list("mActionList"));
    }

    private void drawMessagePanel(CarBundle.Obj t) {
        LinearLayout panel = panel(t, false);
        fillMessage(panel, t);
    }

    private void fillMessage(LinearLayout panel, CarBundle.Obj t) {
        ScrollView scroll = new ScrollView(c);
        LinearLayout body = vertical();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(WazeUi.dp(24), WazeUi.dp(16), WazeUi.dp(24), WazeUi.dp(16));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        ImageView icon = image(t.obj("mIcon"), 96, false);
        if (icon != null) body.addView(icon, margins(WazeUi.dp(96), WazeUi.dp(96), 0, 0, 0, 20));
        CharSequence msg = Texts.of(c, t.get("mMessage"), WazeUi.dp(28));
        TextView tv = WazeUi.text(c, 28, WazeUi.text(), false);
        tv.setSingleLine(false);
        tv.setMaxLines(Integer.MAX_VALUE);
        tv.setEllipsize(null);
        tv.setGravity(t.is("LongMessageTemplate") ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        tv.setLineSpacing(WazeUi.dp(6), 1f);
        tv.setText(t.b("mIsLoading") ? "…" : msg == null ? "" : msg);
        body.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        addActionButtons(panel, t.list("mActionList"));
    }

    private void drawFullScreen(CarBundle.Obj t) {
        View bg = new View(c);
        bg.setBackgroundColor(WazeUi.screen());
        bg.setClickable(true);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = panel(t, true);
        if (t.is("MessageTemplate") || t.is("LongMessageTemplate")) fillMessage(panel, t);
        else if (t.is("PaneTemplate")) fillPane(panel, t.obj("mPane"));
        else fillList(panel, t, t.obj("mSingleList"), t.list("mSectionedLists"));
    }

    // ---- busca ----

    private void drawSearch(CarBundle.Obj t) {
        LinearLayout col = vertical();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(leftWidth(), -2, Gravity.START | Gravity.TOP);
        lp.leftMargin = lp.topMargin = lp.bottomMargin = WazeUi.dp(M);
        root.addView(col, lp);
        covers.add(col);

        LinearLayout bar = card(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(WazeUi.dp(16), 0, WazeUi.dp(16), 0);
        CarBundle.Obj back = headerStartAction(t);
        ImageView backView = new ImageView(c);
        backView.setImageDrawable(WazeUi.icon(c, "back", WazeUi.dp(36), WazeUi.text()));
        backView.setScaleType(ImageView.ScaleType.CENTER);
        backView.setBackground(WazeUi.touch(null));
        backView.setOnClickListener(v -> {
            if (back == null || !Calls.click(back)) host.back();
        });
        bar.addView(backView, new LinearLayout.LayoutParams(WazeUi.dp(72), WazeUi.dp(72)));
        EditText field = new EditText(c);
        WazeUi.textSize(field, 30);
        field.setTextColor(WazeUi.text());
        field.setHintTextColor(WazeUi.text2());
        String hint = t.str("mSearchHint");
        field.setHint(hint == null || hint.isEmpty() ? Texts.whereTo() : hint);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        String initial = t.str("mInitialSearchText");
        if (initial != null) {
            field.setText(initial);
            field.setSelection(initial.length());
        }
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                main.removeCallbacks(searchChanged);
                main.postDelayed(searchChanged, 350);
            }
        });
        field.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                main.removeCallbacks(searchChanged);
                Calls.search(template, v.getText().toString(), true);
                hideKeyboard(v);
                return true;
            }
            return false;
        });
        bar.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(bar, new LinearLayout.LayoutParams(-1, WazeUi.dp(106)));

        LinearLayout results = card(LinearLayout.VERTICAL);
        results.setPadding(WazeUi.dp(8), WazeUi.dp(8), WazeUi.dp(8), WazeUi.dp(8));
        col.addView(results, margins(-1, -2, 0, 16, 0, 0));
        ((LinearLayout.LayoutParams) results.getLayoutParams()).weight = 1;
        searchField = field;
        searchResults = results;
        leftPanel = results;
        fillSearchResults(t);
        if (t.b("mShowKeyboardByDefault") || initial == null || initial.isEmpty()) {
            field.requestFocus();
            field.post(() -> {
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, 0);
            });
        }
        drawMapButtons(null, null, t.obj("mActionStrip"), false);
    }

    private void fillSearchResults(CarBundle.Obj t) {
        if (searchResults == null) return;
        CarBundle.Obj list = t.obj("mItemList");
        int count = list == null ? 0 : list.list("mItems").size();
        String key = t.b("mIsLoading") + "/" + count + "/" + (list == null ? "" : list.toString().hashCode());
        if (key.equals(searchKey)) return;
        searchKey = key;
        searchResults.removeAllViews();
        searchResults.setVisibility(count == 0 && !t.b("mIsLoading") ? View.INVISIBLE : View.VISIBLE);
        fillList(searchResults, t, list, null);
    }

    private void sendSearchText() {
        if (searchField != null) Calls.search(template, searchField.getText().toString(), false);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---- botões do mapa (coluna da direita) ----

    /**
     * Coluna da direita: ícones do ActionStrip (menos a busca, que virou a barra) e o
     * MapActionStrip (zoom, centralizar, mover). Ações com texto ficam nos cartões.
     */
    private void drawMapButtons(CarBundle.Obj panOwner, CarBundle.Obj mapStrip, CarBundle.Obj strip, boolean skipSearch) {
        // Uma coluna só, à direita, presa embaixo: ícones do ActionStrip (busca, rotas,
        // som...) e o MapActionStrip (mover, zoom, centralizar, reportar).
        List<CarBundle.Obj> buttons = new ArrayList<>();
        CarBundle.Obj search = skipSearch ? findSearch(strip) : null;
        for (CarBundle.Obj a : actions(strip)) {
            if (a == search || hasTitle(a) || a.obj("mIcon") == null && !isStandard(a)) continue;
            buttons.add(a);
        }
        for (CarBundle.Obj a : actions(mapStrip)) {
            String icon = Icons.resourceName(c, a.obj("mIcon"));
            if (android.util.Log.isLoggable(CarSession.TAG, android.util.Log.DEBUG)) {
                android.util.Log.d(CarSession.TAG, "botão do mapa tipo=" + a.i("mType", 1) + " ícone=" + icon);
            }
            if (isHiddenMapButton(a, icon)) continue;
            buttons.add(a);
        }
        boolean end = endInColumn && panOwner == template;
        endInColumn = false;
        if (buttons.isEmpty() && !end) return;
        int n = buttons.size() + (end ? 1 : 0);
        int avail = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom() - WazeUi.dp(2 * M);
        int size = WazeUi.dp(ROUND), gap = WazeUi.dp(ROUND_GAP);
        if (avail > 0 && n * size + (n - 1) * gap > avail) {
            gap = WazeUi.dp(10);
            size = Math.max(WazeUi.dp(56), (avail - (n - 1) * gap) / n);
        }
        LinearLayout col = vertical();
        col.setClipToPadding(false);
        col.setPadding(WazeUi.dp(SHADOW_ROOM), 0, WazeUi.dp(SHADOW_ROOM), 0);
        if (end) {
            // Encerrar (os cartões de rota estão no painel do launcher): ✕ vermelho no topo.
            ImageView b = new ImageView(c);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setImageDrawable(WazeUi.icon(c, "close", WazeUi.dp(36), 0xFFFFFFFF));
            b.setBackground(WazeUi.touch(WazeUi.pill(WazeUi.danger())));
            b.setOnClickListener(v -> host.stopNavigation());
            col.addView(b, new LinearLayout.LayoutParams(size, size));
        }
        for (CarBundle.Obj a : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.topMargin = col.getChildCount() == 0 ? 0 : gap;
            col.addView(roundButton(a, panOwner), lp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        lp.rightMargin = WazeUi.dp(M + 8 - SHADOW_ROOM);
        lp.bottomMargin = WazeUi.dp(M);
        root.addView(col, lp);
        rightColumn = col;
    }

    /**
     * Botões do mapa que esta tela não mostra: mover (PAN) e o zoom (+ e −). O mapa continua
     * respondendo aos gestos (arrastar, pinça e toque duplo).
     */
    private static boolean isHiddenMapButton(CarBundle.Obj action, String icon) {
        if (action.i("mType", 1) == 0x10004) return true;
        if (icon == null) return false;
        String n = icon.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("report")) return false;
        return n.contains("zoom") || n.contains("plus") || n.contains("minus");
    }

    private View roundButton(CarBundle.Obj action, CarBundle.Obj panOwner) {
        int type = action.i("mType", 1);
        ImageView b = new ImageView(c);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(WazeUi.roundButtonBg());
        WazeUi.lift(b, -1);
        Drawable icon;
        if (type == 0x10004) { // PAN: liga/desliga o modo mover o mapa
            icon = panMode ? WazeUi.icon(c, "close", WazeUi.dp(40), WazeUi.text())
                    : tinted(Icons.drawable(c, action.obj("mIcon"), WazeUi.dark), action.obj("mIcon"));
            if (icon == null) icon = WazeUi.icon(c, "back", WazeUi.dp(40), WazeUi.text());
            b.setOnClickListener(v -> {
                panMode = !panMode;
                Calls.panMode(panOwner, panMode);
                draw(true);
            });
        } else if (type == 0x10003) { // BACK
            icon = WazeUi.icon(c, "back", WazeUi.dp(40), WazeUi.text());
            b.setOnClickListener(v -> host.back());
        } else {
            icon = tinted(Icons.drawable(c, action.obj("mIcon"), WazeUi.dark), action.obj("mIcon"));
            b.setOnClickListener(v -> Calls.click(action));
        }
        b.setImageDrawable(WazeUi.sized(icon, WazeUi.dp(40)));
        b.setEnabled(!action.fields.containsKey("mIsEnabled") || action.b("mIsEnabled"));
        CharSequence title = Texts.of(c, action.get("mTitle"), WazeUi.dp(20));
        if (title != null) b.setContentDescription(title);
        return b;
    }

    // ---- ações com texto ----

    private void addActionButtons(LinearLayout panel, List<Object> actions) {
        if (actions == null || actions.isEmpty()) return;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        row.setPadding(WazeUi.dp(12), WazeUi.dp(12), WazeUi.dp(12), WazeUi.dp(8));
        int n = 0;
        for (Object o : actions) {
            if (!(o instanceof CarBundle.Obj)) continue;
            CarBundle.Obj a = (CarBundle.Obj) o;
            boolean primary = (a.i("mFlags", 0) & 1) != 0;
            row.addView(textButton(a, null, primary), margins(-2, WazeUi.dp(84), n++ == 0 ? 0 : 12, 0, 0, 0));
        }
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Botão com texto das referências: principal (Ir, Encerrar) ou secundário (Cancelar). */
    private View textButton(CarBundle.Obj action, String fallback, boolean primary) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER);
        b.setPadding(WazeUi.dp(32), 0, WazeUi.dp(32), 0);
        primary |= (action.i("mFlags", 0) & 1) != 0;
        int fg = primary ? WazeUi.onPrimary() : WazeUi.text();
        Drawable icon = tinted(Icons.drawable(c, action.obj("mIcon"), WazeUi.dark), action.obj("mIcon"), fg);
        CharSequence title = Texts.of(c, action.get("mTitle"), WazeUi.dp(28));
        if (title == null) title = fallback;
        // Só ícone: quadrado; com texto: pelo menos 180 de largura (Cancelar, Ir).
        b.setMinimumWidth(WazeUi.dp(title == null ? 95 : 180));
        if (title == null) {
            b.setPadding(0, 0, 0, 0);
            b.setTag(ICON_ONLY);
        }
        if (icon != null && title == null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(WazeUi.sized(icon, WazeUi.dp(40)));
            b.addView(iv, new LinearLayout.LayoutParams(WazeUi.dp(40), WazeUi.dp(40)));
        }
        if (title != null) {
            TextView t = WazeUi.text(c, 28, fg, primary);
            t.setText(title);
            b.addView(t);
        }
        b.setBackground(WazeUi.touch(WazeUi.pill(primary ? WazeUi.primary() : WazeUi.secondary())));
        int type = action.i("mType", 1);
        b.setOnClickListener(v -> {
            if (type == 0x10003 || !Calls.click(action)) host.back();
        });
        boolean enabled = !action.fields.containsKey("mIsEnabled") || action.b("mIsEnabled");
        b.setEnabled(enabled);
        if (!enabled) b.setAlpha(0.5f);
        return b;
    }

    private View smallAction(CarBundle.Obj action) {
        ImageView b = new ImageView(c);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageDrawable(WazeUi.sized(tinted(Icons.drawable(c, action.obj("mIcon"), WazeUi.dark), action.obj("mIcon")), WazeUi.dp(36)));
        b.setBackground(WazeUi.touch(null));
        b.setOnClickListener(v -> Calls.click(action));
        return b;
    }

    // ---- cabeçalho ----

    private View header(CarBundle.Obj t) {
        CharSequence title = headerTitle(t);
        CarBundle.Obj start = headerStartAction(t);
        List<CarBundle.Obj> end = new ArrayList<>();
        CarBundle.Obj h = t.obj("mHeader");
        if (h != null) {
            for (Object o : h.list("mEndHeaderActions")) if (o instanceof CarBundle.Obj) end.add((CarBundle.Obj) o);
        }
        if (end.isEmpty()) end.addAll(actions(t.obj("mActionStrip")));
        if (title == null && start == null && end.isEmpty()) return null;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(WazeUi.dp(88));
        row.setPadding(WazeUi.dp(8), 0, WazeUi.dp(8), 0);
        if (start != null) {
            ImageView back = new ImageView(c);
            int type = start.i("mType", 1);
            Drawable d = type == 0x10003 ? WazeUi.icon(c, "back", WazeUi.dp(36), WazeUi.text())
                    : WazeUi.sized(tinted(Icons.drawable(c, start.obj("mIcon"), WazeUi.dark), start.obj("mIcon")), WazeUi.dp(36));
            back.setImageDrawable(d);
            back.setScaleType(ImageView.ScaleType.CENTER);
            back.setBackground(WazeUi.touch(null));
            back.setOnClickListener(v -> {
                if (type == 0x10003 || !Calls.click(start)) host.back();
            });
            row.addView(back, new LinearLayout.LayoutParams(WazeUi.dp(72), WazeUi.dp(72)));
        }
        TextView tv = WazeUi.text(c, 30, WazeUi.text(), true);
        tv.setText(title == null ? "" : title);
        tv.setPadding(WazeUi.dp(start == null ? 16 : 8), 0, WazeUi.dp(8), 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        for (CarBundle.Obj a : end) {
            if (hasTitle(a)) row.addView(textButton(a, null, false), margins(-2, WazeUi.dp(64), 8, 0, 0, 0));
            else row.addView(smallAction(a), margins(WazeUi.dp(64), WazeUi.dp(64), 8, 0, 0, 0));
        }
        return row;
    }

    private CharSequence headerTitle(CarBundle.Obj t) {
        CarBundle.Obj h = t.obj("mHeader");
        CharSequence title = h == null ? null : Texts.of(c, h.get("mTitle"), WazeUi.dp(30));
        if (title == null) title = Texts.of(c, t.get("mTitle"), WazeUi.dp(30));
        return title;
    }

    private CarBundle.Obj headerStartAction(CarBundle.Obj t) {
        CarBundle.Obj h = t.obj("mHeader");
        CarBundle.Obj a = h == null ? null : h.obj("mStartHeaderAction");
        return a != null ? a : t.obj("mHeaderAction");
    }

    // ---- alertas ----

    void showAlert(CarBundle.Obj a) {
        main.removeCallbacks(alertTimeout);
        if (alertView != null) root.removeView(alertView);
        alertView = null;
        alert = a;
        if (a == null) {
            reportArea();
            return;
        }
        LinearLayout card = card(LinearLayout.VERTICAL);
        card.setPadding(WazeUi.dp(24), WazeUi.dp(20), WazeUi.dp(24), WazeUi.dp(20));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = image(a.obj("mIcon"), 56, false);
        if (icon != null) top.addView(icon, margins(WazeUi.dp(56), WazeUi.dp(56), 0, 0, 20, 0));
        LinearLayout texts = vertical();
        CharSequence title = Texts.of(c, a.get("mTitle"), WazeUi.dp(30));
        TextView tv = WazeUi.text(c, 30, WazeUi.text(), true);
        tv.setText(title == null ? "" : title);
        texts.addView(tv);
        CharSequence sub = Texts.of(c, a.get("mSubtitle"), WazeUi.dp(22));
        if (sub != null) {
            TextView sv = WazeUi.text(c, 22, WazeUi.text2(), false);
            sv.setText(sub);
            texts.addView(sv, margins(-2, -2, 0, 6, 0, 0));
        }
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);
        List<Object> actions = a.list("mActions");
        if (!actions.isEmpty()) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END);
            int n = 0;
            for (Object o : actions) {
                if (!(o instanceof CarBundle.Obj)) continue;
                CarBundle.Obj action = (CarBundle.Obj) o;
                View b = textButton(action, null, n == 0);
                b.setOnClickListener(v -> {
                    Calls.click(action);
                    closeAlert(0);
                });
                row.addView(b, margins(-2, WazeUi.dp(72), n++ == 0 ? 0 : 12, 0, 0, 0));
            }
            card.addView(row, margins(-1, -2, 0, 16, 0, 0));
        } else {
            card.setOnClickListener(v -> closeAlert(2));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(leftWidth(), -2, Gravity.START | Gravity.TOP);
        lp.leftMargin = WazeUi.dp(M);
        lp.topMargin = WazeUi.dp(M + 127 + 69 + 16);
        root.addView(card, lp);
        alertView = card;
        long duration = a.l("mDuration", 0);
        if (duration > 0) main.postDelayed(alertTimeout, duration);
    }

    private void closeAlert(int reason) {
        CarBundle.Obj a = alert;
        showAlert(null);
        if (a != null) Calls.alertClosed(a, reason);
    }

    /** O Waze fecha o alerta (dismissAlert). */
    void dismissAlert() {
        showAlert(null);
    }

    // ---- área do mapa ----

    private final Rect lastArea = new Rect();
    private final Rect lastStable = new Rect();

    private void reportArea() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        // Como no Android Auto: os cartões ocupam a coluna da esquerda e o carro fica no
        // espaço entre eles e os botões da direita.
        Rect area = new Rect(0, 0, w, h);
        Rect stable = new Rect(0, 0, w, h);
        boolean compact = compact();
        for (View v : covers) {
            if (v.getParent() == null || v.getVisibility() != View.VISIBLE || v.getWidth() == 0) continue;
            if (compact) {
                // Meia tela: cartões de largura toda; o carro fica entre o de cima e o de baixo.
                if (v.getTop() < h / 3 && v.getBottom() < h * 2 / 3) area.top = Math.max(area.top, v.getBottom());
                else if (v.getBottom() >= h - WazeUi.dp(2) && v.getTop() > h / 3) area.bottom = Math.min(area.bottom, v.getTop());
            } else if (v.getLeft() < w / 3 && v.getRight() < w * 2 / 3) {
                area.left = Math.max(area.left, v.getRight());
            }
            // Painel preso embaixo: a área estável termina em cima dele.
            if (v.getBottom() >= h - WazeUi.dp(2) && v.getTop() > h / 2) stable.bottom = Math.min(stable.bottom, v.getTop());
        }
        if (rightColumn != null && rightColumn.getWidth() > 0) {
            area.right = Math.min(area.right, rightColumn.getLeft());
            stable.right = Math.min(stable.right, rightColumn.getLeft());
        }
        if (area.width() < w / 4 || area.height() < h / 4) area.set(0, 0, w, h);
        // O Waze põe o velocímetro no canto inferior direito da área: afastar da borda e
        // da coluna de botões.
        area.bottom -= WazeUi.dp(M + 9);
        area.right -= WazeUi.dp(8);
        if (!area.equals(lastArea) || !stable.equals(lastStable)) {
            lastArea.set(area);
            lastStable.set(stable);
            host.onMapArea(new Rect(area), new Rect(stable));
        }
    }

    // ---- utilitários ----

    private List<CarBundle.Obj> actions(CarBundle.Obj strip) {
        List<CarBundle.Obj> out = new ArrayList<>();
        if (strip == null) return out;
        for (Object o : strip.list("mActions")) if (o instanceof CarBundle.Obj) out.add((CarBundle.Obj) o);
        return out;
    }

    private List<CarBundle.Obj> titledActions(CarBundle.Obj strip) {
        List<CarBundle.Obj> out = new ArrayList<>();
        for (CarBundle.Obj a : actions(strip)) if (hasTitle(a) && a != findSearch(strip)) out.add(a);
        return out;
    }

    private static boolean hasTitle(CarBundle.Obj a) {
        String t = Texts.plain(a.get("mTitle"));
        return t != null && !t.trim().isEmpty();
    }

    private static boolean isStandard(CarBundle.Obj a) {
        return (a.i("mType", 1) & 0x10000) != 0;
    }

    /** A ação de busca do Waze: título ou nome do recurso do ícone com "search"/"busca". */
    private CarBundle.Obj findSearch(CarBundle.Obj strip) {
        for (CarBundle.Obj a : actions(strip)) {
            String title = Texts.plain(a.get("mTitle"));
            if (matchesSearch(title) || matchesSearch(Icons.resourceName(c, a.obj("mIcon")))) return a;
        }
        return null;
    }

    private static boolean matchesSearch(String s) {
        if (s == null) return false;
        s = s.toLowerCase(java.util.Locale.ROOT);
        for (String h : SEARCH_HINTS) if (s.contains(h)) return true;
        return false;
    }

    /** Ícones sem cor própria (desenhados para o fundo escuro do Android Auto) ficam na cor do texto. */
    private Drawable tinted(Drawable d, CarBundle.Obj carIcon) {
        return tinted(d, carIcon, WazeUi.text());
    }

    /** Com cor própria: o branco do Waze vira a cor pedida; as outras cores ficam. */
    private Drawable tinted(Drawable d, CarBundle.Obj carIcon, int color) {
        if (d == null || carIcon == null) return d;
        CarBundle.Obj tint = carIcon.obj("mTint");
        if (tint != null) {
            if (Colors.icon(tint, WazeUi.dark, -1) != -1) return d;
        } else if (!Icons.isMonochrome(carIcon)) {
            return d;
        }
        d = d.mutate();
        d.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        return d;
    }

    /** ImageView de um CarIcon; tint = ícone de interface (manobra, ações): cor do texto. */
    private ImageView image(CarBundle.Obj carIcon, int sizeDp, boolean tint) {
        return image(carIcon, sizeDp, tint ? WazeUi.text() : 0);
    }

    /** color != 0: ícone de uma cor só pintado nessa cor (ex.: branco no cartão azul). */
    private ImageView image(CarBundle.Obj carIcon, int sizeDp, int color) {
        if (carIcon == null) return null;
        Drawable d = Icons.drawable(c, carIcon, WazeUi.dark);
        if (d == null) return null;
        if (color != 0) d = tinted(d, carIcon, color);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(d);
        if (sizeDp > 0) iv.setLayoutParams(new LinearLayout.LayoutParams(WazeUi.dp(sizeDp), WazeUi.dp(sizeDp)));
        return iv;
    }

    private LinearLayout card(int orientation) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        l.setBackground(WazeUi.cardBg());
        l.setClickable(true);
        WazeUi.lift(l, WazeUi.dp(28));
        return l;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView note(CharSequence text) {
        TextView t = WazeUi.text(c, 24, WazeUi.text2(), false);
        t.setSingleLine(false);
        t.setText(text);
        t.setPadding(WazeUi.dp(20), WazeUi.dp(24), WazeUi.dp(20), WazeUi.dp(24));
        return t;
    }

    /**
     * Janela estreita (tela dividida): a coluna da esquerda não cabe ao lado do mapa; os
     * cartões usam a largura toda, menos a coluna de botões.
     */
    private boolean compact() {
        int w = root.getWidth();
        return w > 0 && w < WazeUi.dp(LEFT_W + 380 + ROUND);
    }

    /** Largura dos cartões da esquerda: 612 do desenho, ou o que sobra na janela estreita. */
    private int leftWidth() {
        if (!compact()) return WazeUi.dp(LEFT_W);
        return root.getWidth() - WazeUi.dp(ROUND + 3 * M + 8);
    }

    private void addTop(View v) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(leftWidth(), -2, Gravity.START | Gravity.TOP);
        lp.leftMargin = lp.topMargin = WazeUi.dp(M);
        root.addView(v, lp);
        covers.add(v);
    }

    /** Painel de baixo: preso à borda de baixo, na coluna da esquerda. */
    private void addSheet(View v) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(leftWidth(), -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = WazeUi.dp(M);
        root.addView(v, lp);
        covers.add(v);
    }

    /** LayoutParams de LinearLayout com margens em dp do desenho (largura/altura já em px). */
    private static LinearLayout.LayoutParams margins(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(WazeUi.dp(left), WazeUi.dp(top), WazeUi.dp(right), WazeUi.dp(bottom));
        return lp;
    }

    private static int scrollOf(View panel) {
        ScrollView s = findScroll(panel);
        return s == null ? 0 : s.getScrollY();
    }

    private static void restoreScroll(View panel, int y) {
        ScrollView s = findScroll(panel);
        if (s != null) s.post(() -> s.scrollTo(0, y));
    }

    private static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }
}
