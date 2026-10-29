package com.k4811.map;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.home.GpsClient;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.ThemeDrawables;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;
import com.k4811.map.route.Navigator;
import com.k4811.map.route.Phrases;
import com.k4811.map.route.Places;
import com.k4811.map.route.Route;
import com.k4811.map.route.Router;
import com.k4811.map.road.RoadCell;
import com.k4811.map.road.RoadSync;
import com.k4811.map.road.RoadWatcher;
import com.k4811.map.route.Voice;
import com.k4811.map.tiles.Tiles;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mapa integrado com busca e navegação, como um app de GPS: buscar → prévia da rota (Ir) →
 * navegação com a próxima manobra, voz, recálculo ao sair da rota e chegada.
 *
 * Quem usa chama {@link #onStart()}/{@link #onStop()} (GPS, voz) e {@link #onBackPressed()}.
 * Sem internet: o mapa vem do cache e dos .mbtiles; busca e rota nova ficam indisponíveis, mas uma
 * navegação já calculada continua (sem recálculo).
 *
 * Com os dados das vias baixados (ver {@link RoadSync}): placa do limite de velocidade, velocidade em
 * vermelho acima dele e o próximo alerta (radar, lombada, pedágio, passagem de nível), com voz nos radares.
 *
 * Ao abrir a busca: os favoritos ({@link Favorites}) e os recentes. A estrela de cada linha e a da prévia
 * da rota salvam ou tiram o lugar dos favoritos. Tocar e segurar no mapa marca o ponto com o endereço
 * dele (busca reversa), o jeito de acertar a casa quando o número não está no mapa.
 *
 * A bússola troca entre direção do carro para cima e norte para cima (guardado nas preferências deste
 * app, como a câmera); com a direção para cima e {@code nav_perspective}, o mapa fica em perspectiva.
 * Navegando, o zoom dinâmico ({@code nav_auto_zoom}) usa a distância até a próxima manobra ou o próximo
 * alerta, o que vier antes.
 *
 * O botão de alertas liga e desliga, só neste mapa, os alertas (radares, lombadas, pedágios, passagens
 * de nível; tela e voz) que estão ligados nas Configurações; a placa de limite continua. Guardado nas
 * preferências deste app: só o car-setting grava o {@code launcher.json}.
 */
public class MapScreen extends FrameLayout implements GpsClient.Listener, MapView.Listener, Navigator.Listener,
    RoadWatcher.Listener {

    private enum State { BROWSE, PREVIEW, NAVIGATE }

    private static final long MESSAGE_MS = 3500;
    private static final long ARRIVED_CLOSE_MS = 8000;
    /** Acima do limite mais isso, a velocidade fica vermelha. */
    private static final int OVER_LIMIT_KMH = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "K4811MapNet");
        t.setDaemon(true);
        return t;
    });
    private final MapView map;
    private final EditText search;
    private final IconView searchClear;
    private final View searchBox;
    private final ScrollView resultsScroll;
    private final LinearLayout results;
    private final View banner;
    private final TurnIcon turn;
    /** ✕ no topo dos botões: encerra a rota com a manobra e a chegada no painel ao lado. */
    private final IconView endRoute;
    /** A tela em volta mostra a manobra e a chegada (NavProgress): o mapa fica sem as faixas. */
    private boolean routeOutside;
    private final TextView turnDistance;
    private final TextView turnStreet;
    private final View then;
    private final TurnIcon thenTurn;
    private final TextView message;
    private final IconView recenter;
    private final CompassButton compass;
    private final AlertsButton alertsButton;
    private final TextView speed;
    private final View bottom;
    private final TextView bottomTitle;
    private final TextView bottomDetail;
    private final TextView primary;
    private final TextView secondary;
    /** Estrela da prévia da rota: salva o destino nos favoritos. */
    private final IconView bottomStar;
    private final View alert;
    private final RoadSign alertIcon;
    private final TextView alertDistance;
    private final TextView alertLabel;
    private final RoadSign limitSign;

    private final Phrases phrases;
    private final GpsClient gps;
    private Voice voice;
    private boolean voiceEnabled = true;
    private State state = State.BROWSE;
    private Route route;
    private Navigator navigator;
    private Places.Place destination;
    private boolean rerouting;
    private int requestGen;
    private boolean started;
    private RoadWatcher roads;
    private int roadLimit;
    private float speedKmh;
    /** Metros até o alerta na tela (da última leitura do RoadWatcher); NaN sem alerta. */
    private double alertMeters = Double.NaN;

    private final Runnable hideMessage = new Runnable() {
        @Override
        public void run() {
            message.setVisibility(GONE);
        }
    };

    public MapScreen(Context c) {
        this(c, null);
    }

    public MapScreen(Context c, AttributeSet a) {
        super(c, a);
        LayoutInflater.from(c).inflate(R.layout.map_screen, this, true);
        map = (MapView) findViewById(R.id.map_view);
        search = (EditText) findViewById(R.id.map_search);
        searchClear = (IconView) findViewById(R.id.map_search_clear);
        searchBox = findViewById(R.id.map_search_box);
        resultsScroll = (ScrollView) findViewById(R.id.map_results_scroll);
        results = (LinearLayout) findViewById(R.id.map_results);
        banner = findViewById(R.id.map_banner);
        turn = (TurnIcon) findViewById(R.id.map_turn);
        endRoute = floating(R.id.map_end_route, IconView.Icon.CLOSE);
        endRoute.color(Ui.DANGER);
        endRoute.setOnClickListener(v -> endRoute());
        turnDistance = (TextView) findViewById(R.id.map_turn_distance);
        turnStreet = (TextView) findViewById(R.id.map_turn_street);
        then = findViewById(R.id.map_then);
        thenTurn = (TurnIcon) findViewById(R.id.map_then_turn);
        message = (TextView) findViewById(R.id.map_message);
        recenter = (IconView) findViewById(R.id.map_recenter);
        compass = (CompassButton) findViewById(R.id.map_compass);
        alertsButton = (AlertsButton) findViewById(R.id.map_alerts);
        speed = (TextView) findViewById(R.id.map_speed);
        bottom = findViewById(R.id.map_bottom);
        bottomTitle = (TextView) findViewById(R.id.map_bottom_title);
        bottomDetail = (TextView) findViewById(R.id.map_bottom_detail);
        primary = (TextView) findViewById(R.id.map_primary);
        secondary = (TextView) findViewById(R.id.map_secondary);
        bottomStar = (IconView) findViewById(R.id.map_bottom_star);
        bottomStar.setOnClickListener(v -> toggleFavorite(destination, bottomStar));
        alert = findViewById(R.id.map_alert);
        alertIcon = (RoadSign) findViewById(R.id.map_alert_icon);
        alertDistance = (TextView) findViewById(R.id.map_alert_distance);
        alertLabel = (TextView) findViewById(R.id.map_alert_label);
        limitSign = (RoadSign) findViewById(R.id.map_limit);
        phrases = new Phrases(c);
        gps = new GpsClient(c);
        gps.setListener(this);
        map.setListener(this);

        Ui.applyFont(this);
        turnDistance.setTypeface(Ui.semiBold(c));
        bottomTitle.setTypeface(Ui.semiBold(c));
        primary.setTypeface(Ui.semiBold(c));
        speed.setTypeface(Ui.semiBold(c));
        alertDistance.setTypeface(Ui.semiBold(c));
        turn.setColor(Ui.TEXT);
        thenTurn.setColor(Ui.TEXT_SOFT);
        primary.setActivated(true);

        ((IconView) findViewById(R.id.map_search_icon)).set(IconView.Icon.SEARCH, IconView.Style.NONE);
        ((IconView) findViewById(R.id.map_search_icon)).color(Ui.TEXT_MUTED);
        searchClear.set(IconView.Icon.CLOSE, IconView.Style.NONE);
        searchClear.setOnClickListener(v -> closeSearch());
        floating(R.id.map_zoom_in, IconView.Icon.PLUS).setOnClickListener(v -> map.zoomBy(1));
        floating(R.id.map_zoom_out, IconView.Icon.MINUS).setOnClickListener(v -> map.zoomBy(-1));
        floating(R.id.map_recenter, IconView.Icon.LOCATE).setOnClickListener(v -> map.setFollow(true));
        compass.setOnClickListener(v -> toggleHeadingUp());
        alertsButton.setOnClickListener(v -> toggleAlerts());

        search.setOnFocusChangeListener((v, focus) -> {
            if (focus) openSearch();
        });
        search.setOnClickListener(v -> openSearch());
        search.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                runSearch(search.getText().toString().trim());
                return true;
            }
            return false;
        });
        Touch.install(this);
        // O foco começa no mapa, não na busca (senão o teclado abre sozinho).
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        restoreCamera();
        boolean headingUp = prefs().getBoolean("heading_up", true);
        map.setHeadingUp(headingUp);
        compass.setHeadingUp(headingUp);
        alertsButton.setOn(alertsOn());
    }

    private IconView floating(int id, IconView.Icon icon) {
        IconView v = (IconView) findViewById(id);
        v.set(icon, IconView.Style.CIRCLE);
        return v;
    }

    // ------------------------------------------------------------------ ciclo de vida

    /**
     * A tela em volta mostra a próxima manobra e a chegada (lendo {@link NavProgress}), como o painel da
     * tela de navegação faz com o Waze: navegando, o mapa esconde a faixa da manobra e a de chegada (fica
     * mais mapa à vista) e o ✕ de encerrar vai para o topo dos botões.
     */
    public void setRouteOutside(boolean outside) {
        routeOutside = outside;
        renderNavigateChrome();
    }

    /** Faixas da navegação conforme {@link #routeOutside}. */
    private void renderNavigateChrome() {
        boolean nav = state == State.NAVIGATE;
        banner.setVisibility(nav && !routeOutside ? VISIBLE : GONE);
        if (nav) bottom.setVisibility(routeOutside ? GONE : VISIBLE);
        endRoute.setVisibility(nav && routeOutside ? VISIBLE : GONE);
    }

    public void onStart() {
        if (started) return;
        started = true;
        boolean dark = LauncherConfig.darkMode(getContext());
        map.setDark(dark);
        map.setOnline(MapConfig.online(getContext()));
        map.setAutoZoom(LauncherConfig.navAutoZoom(getContext()));
        map.setPerspective(LauncherConfig.navPerspective(getContext()));
        gps.start();
        if (voice == null) voice = new Voice(getContext(), voiceLocale());
        voice.setEnabled(voiceEnabled);
        if (roads == null) {
            roads = new RoadWatcher(this);
            if (state == State.NAVIGATE) roads.setRoute(route);
        }
        applyRoadOptions();
        if (!map.hasLocation()) centerOnLastKnown();
        if (resultsScroll.getVisibility() != VISIBLE) {
            search.clearFocus();
            requestFocus();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // A tela de navegação fechou: a rota acabou junto.
        NavProgress.clear();
    }

    public void onStop() {
        if (!started) return;
        started = false;
        // Navegando, o GPS e a voz continuam com a tela fora (como no Waze); o mapa só não é desenhado.
        if (state != State.NAVIGATE) {
            gps.stop();
            // Fora da tela e sem navegar: devolve a memória dos tiles (ficam no cache em disco).
            Tiles.get(getContext()).trimMemory();
        }
        saveCamera();
        // Navegando, a voz continua pronta para quando a tela voltar; fora disso, libera o motor.
        if (state != State.NAVIGATE && voice != null) {
            voice.shutdown();
            voice = null;
        }
        if (state != State.NAVIGATE) stopRoads();
    }

    /** Para os alertas e devolve a memória dos dados das vias. */
    private void stopRoads() {
        if (roads == null) return;
        roads.quit(true);
        roads = null;
        roadLimit = 0;
        alertMeters = Double.NaN;
        alert.setVisibility(GONE);
        limitSign.setVisibility(GONE);
    }

    /** Fecha a busca ou a prévia; false se não havia nada para fechar (a tela decide). */
    public boolean onBackPressed() {
        if (resultsScroll.getVisibility() == VISIBLE || search.hasFocus()) {
            closeSearch();
            return true;
        }
        if (state == State.PREVIEW) {
            endRoute();
            return true;
        }
        return false;
    }

    public void setVoiceEnabled(boolean on) {
        voiceEnabled = on;
        if (voice != null) voice.setEnabled(on);
    }

    public boolean isNavigating() {
        return state == State.NAVIGATE;
    }

    // ------------------------------------------------------------------ GPS

    @Override
    public void onGps(GpsClient.Fix fix) {
        if (fix == null) return;
        float mps = Float.isNaN(fix.speedKmh) ? 0 : fix.speedKmh / 3.6f;
        double lat = fix.latitude;
        double lon = fix.longitude;
        float heading = fix.bearing;
        if (navigator != null) {
            navigator.update(lat, lon, mps, 0);
            if (navigator.onRoute() && !Double.isNaN(navigator.snappedLat())) {
                // Seta em cima da rua da rota e apontando para onde ela vai, como no Waze.
                lat = navigator.snappedLat();
                lon = navigator.snappedLon();
                if (fix.moving()) heading = navigator.routeBearing();
            }
            map.setRouteProgress(navigator.segment());
            renderNavigation();
        }
        updateFocus();
        map.setMyLocation(lat, lon, heading, mps);
        speed.setVisibility(fix.moving() || state == State.NAVIGATE ? VISIBLE : GONE);
        speed.setText(String.valueOf(fix.moving() ? Math.round(fix.speedKmh) : 0));
        speedKmh = fix.moving() ? fix.speedKmh : 0;
        updateSpeedColor();
        if (roads != null) {
            boolean onRoute = state == State.NAVIGATE && navigator != null && navigator.onRoute();
            roads.update(lat, lon, heading, mps, onRoute ? navigator.traveled() : Double.NaN);
        }
        // Raio de 100 km dos dados das vias e do mapa offline: confere se venceu ou se o carro se afastou.
        RoadSync.areaAll(getContext(), fix.latitude, fix.longitude);
    }

    // ------------------------------------------------------------------ dados das vias

    @Override
    public void onRoadState(RoadWatcher.State s) {
        if (roads == null) return;
        roadLimit = s.limit;
        if (s.limit > 0) limitSign.setLimit(s.limit);
        limitSign.setVisibility(s.limit > 0 ? VISIBLE : GONE);
        updateSpeedColor();
        RoadWatcher.Alert a = s.alert;
        alertMeters = a == null ? Double.NaN : s.distance;
        if (a == null) {
            alert.setVisibility(GONE);
        } else {
            alertIcon.setAlert(a.type, a.isCamera() ? Ui.DANGER : Ui.WARNING);
            alertDistance.setText(phrases.distance(s.distance));
            String name = getContext().getString(alertName(a.type));
            int limit = a.speed > 0 ? a.speed : a.type == RoadCell.SPEED_CAMERA ? s.limit : 0;
            alertLabel.setText(limit > 0 ? getContext().getString(R.string.map_alert_with_limit, name, limit) : name);
            alert.setVisibility(VISIBLE);
        }
        if (voice == null) return;
        RoadWatcher.Alert say = s.announce;
        if (say != null) {
            String dist = phrases.spokenDistance(Math.max(50, (int) (Math.round(s.distance / 50.0) * 50)));
            int limit = say.speed > 0 ? say.speed : s.limit;
            if (say.type == RoadCell.RED_LIGHT_CAMERA) {
                voice.sayAfter(getContext().getString(R.string.map_say_red_light, dist));
            } else if (limit > 0) {
                voice.sayAfter(getContext().getString(R.string.map_say_camera_limit, dist, limit));
            } else {
                voice.sayAfter(getContext().getString(R.string.map_say_camera, dist));
            }
        }
        if (s.slowDown) voice.sayAfter(getContext().getString(R.string.map_say_slow_down));
    }

    private static int alertName(int type) {
        switch (type) {
            case RoadCell.RED_LIGHT_CAMERA:
                return R.string.map_alert_red_light;
            case RoadCell.TRAFFIC_CALMING:
                return R.string.map_alert_bump;
            case RoadCell.TOLL_BOOTH:
                return R.string.map_alert_toll;
            case RoadCell.LEVEL_CROSSING:
                return R.string.map_alert_rail;
            default:
                return R.string.map_alert_camera;
        }
    }

    private void updateSpeedColor() {
        int color = roadLimit > 0 && speedKmh > roadLimit + OVER_LIMIT_KMH ? Ui.DANGER : Ui.TEXT;
        if (speed.getCurrentTextColor() != color) speed.setTextColor(color);
    }

    /** Zoom dinâmico: a próxima manobra (na rota) ou o próximo alerta, o que vier antes. */
    private void updateFocus() {
        double d = Double.NaN;
        if (state == State.NAVIGATE && navigator != null && navigator.onRoute() && navigator.nextStep() != null) {
            d = navigator.distanceToNext();
        }
        if (!Double.isNaN(alertMeters) && (Double.isNaN(d) || alertMeters < d)) d = alertMeters;
        map.setFocusAhead(d);
    }

    /** Alertas ligados no botão do mapa (os das Configurações valem por baixo). */
    private boolean alertsOn() {
        return prefs().getBoolean("alerts_on", true);
    }

    /** O que vale nos dados das vias: as Configurações e, nos alertas, o botão do mapa. */
    private void applyRoadOptions() {
        Context c = getContext();
        boolean cameras = LauncherConfig.navCameras(c);
        boolean others = LauncherConfig.navRoadAlerts(c);
        // Com os alertas desligados nas Configurações, o botão não tem o que ligar.
        alertsButton.setVisibility(cameras || others ? VISIBLE : GONE);
        boolean on = alertsOn();
        if (roads != null) roads.setOptions(cameras && on, LauncherConfig.navSpeedLimit(c), others && on);
    }

    private void toggleAlerts() {
        boolean on = !alertsOn();
        prefs().edit().putBoolean("alerts_on", on).apply();
        alertsButton.setOn(on);
        applyRoadOptions();
        if (!on) {
            // Some já, sem esperar a próxima leitura do GPS.
            alertMeters = Double.NaN;
            alert.setVisibility(GONE);
            updateFocus();
        }
        showMessage(on ? R.string.map_alerts_on : R.string.map_alerts_off);
    }

    private void toggleHeadingUp() {
        boolean on = !map.isHeadingUp();
        map.setHeadingUp(on);
        compass.setHeadingUp(on);
        prefs().edit().putBoolean("heading_up", on).apply();
        showMessage(on ? R.string.map_heading_up : R.string.map_north_up);
    }

    @Override
    public void onBearingChanged(float bearing) {
        compass.setBearing(bearing);
    }

    @Override
    public void onFollowChanged(boolean following) {
        recenter.setVisibility(following ? INVISIBLE : VISIBLE);
    }

    @Override
    public void onLongPress(double lat, double lon) {
        if (state == State.NAVIGATE) return;
        closeSearch();
        Places.Place pin = new Places.Place(getContext().getString(R.string.map_dropped_pin),
            String.format(Locale.US, "%.5f, %.5f", lat, lon), lat, lon);
        if (!MapConfig.online(getContext())) {
            pick(pin);
            return;
        }
        // O endereço do ponto (rua e número), para o destino e os favoritos terem nome.
        showMessage(R.string.map_finding_address);
        map.setDestination(lat, lon);
        int gen = ++requestGen;
        String lang = LauncherConfig.language(getContext());
        net.execute(() -> {
            Places.Place found = null;
            try {
                found = Places.reverse(getContext(), lat, lon, lang);
            } catch (Exception e) {
                android.util.Log.w("K4811Map", "endereço do ponto: " + e);
            }
            Places.Place named = found == null ? pin : found;
            main.post(() -> {
                if (gen != requestGen) return;
                pick(named);
            });
        });
    }

    // ------------------------------------------------------------------ busca

    private void openSearch() {
        if (state == State.NAVIGATE) return;
        searchClear.setVisibility(VISIBLE);
        if (search.getText().length() == 0) showRecents();
        search.requestFocus();
        keyboard(true);
    }

    private void closeSearch() {
        resultsScroll.setVisibility(GONE);
        results.removeAllViews();
        searchClear.setVisibility(GONE);
        search.setText("");
        search.clearFocus();
        keyboard(false);
        requestFocus();
    }

    /** Busca aberta e vazia: os favoritos e, depois, os recentes que não são favoritos. */
    private void showRecents() {
        net.execute(() -> {
            List<Places.Place> favorites = Favorites.load();
            List<Places.Place> recents = Recents.load();
            for (int i = recents.size() - 1; i >= 0; i--) {
                if (Favorites.contains(favorites, recents.get(i))) recents.remove(i);
            }
            main.post(() -> {
                if (search.getText().length() > 0 || !search.hasFocus()) return;
                results.removeAllViews();
                addSection(favorites, R.string.map_favorites, favorites);
                addSection(recents, R.string.map_recent, favorites);
                fitResults(favorites.size() + recents.size());
            });
        });
    }

    private void runSearch(String query) {
        if (query.isEmpty()) return;
        keyboard(false);
        if (!MapConfig.online(getContext())) {
            showMessage(R.string.map_offline);
            return;
        }
        showMessage(R.string.map_searching);
        int gen = ++requestGen;
        double nearLat = map.hasLocation() ? map.carLat() : map.centerLat();
        double nearLon = map.hasLocation() ? map.carLon() : map.centerLon();
        String lang = LauncherConfig.language(getContext());
        net.execute(() -> {
            List<Places.Place> found = null;
            try {
                found = Places.search(getContext(), query, nearLat, nearLon, lang);
            } catch (Exception e) {
                android.util.Log.w("K4811Map", "busca: " + e);
            }
            List<Places.Place> list = found;
            main.post(() -> {
                if (gen != requestGen) return;
                if (list == null) {
                    showMessage(R.string.map_search_error);
                    return;
                }
                hideMessageNow();
                if (list.isEmpty()) showMessage(R.string.map_no_results);
                showResults(list);
            });
        });
    }

    /** Resultados de uma busca (a estrela de cada um precisa saber quais já são favoritos). */
    private void showResults(List<Places.Place> list) {
        net.execute(() -> {
            List<Places.Place> favorites = Favorites.load();
            main.post(() -> {
                results.removeAllViews();
                addSection(list, 0, favorites);
                fitResults(list.size());
            });
        });
    }

    /** Título (0 = sem título) e as linhas; nada se a lista está vazia. */
    private void addSection(List<Places.Place> list, int header, List<Places.Place> favorites) {
        if (list.isEmpty()) return;
        if (header != 0) {
            TextView h = new TextView(getContext());
            h.setText(getContext().getString(header).toUpperCase(Locale.getDefault()));
            h.setTextColor(Ui.TEXT_MUTED);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            h.setPadding(dp(20), dp(8), dp(20), dp(4));
            results.addView(h);
        }
        for (Places.Place p : list) results.addView(row(p, Favorites.contains(favorites, p)));
    }

    private void fitResults(int count) {
        Ui.applyFont(results);
        resultsScroll.setVisibility(count == 0 ? GONE : VISIBLE);
        // A lista não passa da área do mapa (fica por cima dele, abaixo da busca).
        int max = getHeight() - dp(16 + 72 + 32);
        resultsScroll.measure(MeasureSpec.makeMeasureSpec(searchBox.getWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        ViewGroup.LayoutParams lp = resultsScroll.getLayoutParams();
        lp.height = max > 0 && resultsScroll.getMeasuredHeight() > max ? max : ViewGroup.LayoutParams.WRAP_CONTENT;
        resultsScroll.setLayoutParams(lp);
    }

    /** Nome e endereço (e o aviso de número aproximado), com a estrela dos favoritos à direita. */
    private View row(Places.Place p, boolean favorite) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(8), dp(12));
        // O drawable do tema em uso (o do XML é sempre o escuro).
        row.setBackground(ThemeDrawables.drawable(getContext(), R.drawable.list_row));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(getContext());
        name.setText(p.name);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        // Nome e endereço inteiros, quebrando linha (cortados, vários resultados ficam iguais).
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView detail = new TextView(getContext());
        detail.setText(p.detail);
        detail.setTextColor(Ui.TEXT_MUTED);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        detail.setMaxLines(3);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        if (!p.detail.isEmpty()) texts.addView(detail);
        if (p.approximate) {
            TextView note = new TextView(getContext());
            note.setText(getContext().getString(R.string.map_number_approx, p.number));
            note.setTextColor(Ui.ACCENT); // legível no tema claro e no escuro
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            texts.addView(note);
        }
        IconView star = new IconView(getContext());
        renderStar(star, favorite);
        star.setOnClickListener(v -> toggleFavorite(p, star));
        row.addView(star, new LinearLayout.LayoutParams(dp(64), dp(64)));
        row.setOnClickListener(v -> {
            closeSearch();
            pick(p);
        });
        return row;
    }

    // ------------------------------------------------------------------ favoritos

    private void renderStar(IconView star, boolean favorite) {
        star.set(favorite ? IconView.Icon.STAR_FILLED : IconView.Icon.STAR, IconView.Style.NONE);
        star.color(favorite ? Ui.WARNING : Ui.TEXT_MUTED);
        star.setActivated(favorite);
        star.setContentDescription(getContext().getString(
            favorite ? R.string.map_favorite_remove : R.string.map_favorite_add));
    }

    /** Salva ou tira {@code p} dos favoritos; a estrela muda na hora e o aviso confirma. */
    private void toggleFavorite(Places.Place p, IconView star) {
        if (p == null) return;
        boolean next = !star.isActivated();
        renderStar(star, next);
        net.execute(() -> {
            boolean saved = Favorites.toggle(p);
            main.post(() -> {
                renderStar(star, saved);
                showMessage(saved ? R.string.map_favorite_saved : R.string.map_favorite_removed);
            });
        });
    }

    // ------------------------------------------------------------------ rota

    /** Destino escolhido: guarda nos recentes, calcula e mostra a prévia. */
    private void pick(Places.Place p) {
        destination = p;
        net.execute(() -> Recents.add(p));
        map.setDestination(p.lat, p.lon);
        calculate(false);
    }

    private void calculate(boolean reroute) {
        Places.Place p = destination;
        if (p == null) return;
        if (!MapConfig.online(getContext())) {
            showMessage(R.string.map_offline);
            if (reroute) rerouting = false;
            return;
        }
        boolean fromCar = map.hasLocation();
        double fromLat = fromCar ? map.carLat() : map.centerLat();
        double fromLon = fromCar ? map.carLon() : map.centerLon();
        double heading = fromCar && !Float.isNaN(map.carHeading()) ? map.carHeading() : Double.NaN;
        showMessage(reroute ? R.string.map_recalculating : R.string.map_calculating);
        if (reroute && voice != null) voice.say(getContext().getString(R.string.map_recalculating));
        int gen = ++requestGen;
        net.execute(() -> {
            Route r = null;
            try {
                r = Router.route(getContext(), fromLat, fromLon, heading, p.lat, p.lon, p.name);
            } catch (Exception e) {
                android.util.Log.w("K4811Map", "rota: " + e);
            }
            Route result = r;
            main.post(() -> {
                if (gen != requestGen) return;
                rerouting = false;
                if (result == null) {
                    showMessage(R.string.map_route_error);
                    return;
                }
                hideMessageNow();
                route = result;
                map.setRoute(result);
                // Dados das vias dos quadrados por onde a rota passa (inclusive fora do raio de 100 km).
                RoadSync.routeAll(getContext(), result);
                if (reroute && state == State.NAVIGATE) {
                    navigator = new Navigator(result, this);
                    if (roads != null) roads.setRoute(result);
                    renderNavigation();
                } else {
                    showPreview();
                }
            });
        });
    }

    private void showPreview() {
        state = State.PREVIEW;
        searchBox.setVisibility(GONE);
        banner.setVisibility(GONE);
        bottom.setVisibility(VISIBLE);
        bottomTitle.setText(destination.name);
        bottomDetail.setText(phrases.duration(route.duration) + "  ·  " + phrases.distance(route.distance));
        primary.setText(R.string.map_go);
        primary.setOnClickListener(v -> startNavigation());
        bottomStar.setVisibility(VISIBLE);
        Places.Place shown = destination;
        renderStar(bottomStar, false);
        net.execute(() -> {
            boolean favorite = Favorites.contains(Favorites.load(), shown);
            main.post(() -> {
                if (destination == shown) renderStar(bottomStar, favorite);
            });
        });
        secondary.setText(R.string.map_cancel);
        secondary.setOnClickListener(v -> endRoute());
        map.setNavigating(false);
        post(() -> map.showWholeRoute(route, dp(16), bottom.getHeight() + dp(32)));
    }

    private void startNavigation() {
        if (route == null) return;
        state = State.NAVIGATE;
        navigator = new Navigator(route, this);
        if (roads != null) roads.setRoute(route);
        searchBox.setVisibility(GONE);
        secondary.setVisibility(GONE);
        bottomStar.setVisibility(GONE);
        primary.setText(R.string.map_end);
        primary.setOnClickListener(v -> endRoute());
        map.setNavigating(true);
        map.setFollow(true);
        renderNavigateChrome();
        if (!map.hasLocation()) showMessage(R.string.map_waiting_gps);
        navigator.start();
        renderNavigation();
    }

    /** Fim da prévia ou da navegação: volta ao mapa livre com a busca. */
    private void endRoute() {
        requestGen++;
        state = State.BROWSE;
        route = null;
        navigator = null;
        destination = null;
        rerouting = false;
        map.setRoute(null);
        map.setDestination(Double.NaN, Double.NaN);
        map.setNavigating(false);
        NavProgress.clear();
        if (roads != null) roads.setRoute(null);
        banner.setVisibility(GONE);
        bottom.setVisibility(GONE);
        endRoute.setVisibility(GONE);
        secondary.setVisibility(VISIBLE);
        searchBox.setVisibility(VISIBLE);
        hideMessageNow();
        if (!started) {
            gps.stop();
            if (voice != null) {
                voice.shutdown();
                voice = null;
            }
            stopRoads();
        }
    }

    private void renderNavigation() {
        Navigator n = navigator;
        if (n == null || state != State.NAVIGATE) return;
        Route.Step next = n.nextStep();
        String cue = null;
        if (next != null) {
            turn.setShape(Phrases.shape(next));
            turnDistance.setText(phrases.distance(n.distanceToNext()));
            String street = Phrases.street(next);
            cue = street.isEmpty() ? phrases.action(next) : street;
            turnStreet.setText(cue);
            Route.Step after = n.stepAfterNext();
            boolean soon = after != null && n.route().along[after.index] - n.route().along[next.index] < 150;
            then.setVisibility(soon ? VISIBLE : GONE);
            if (soon) thenTurn.setShape(Phrases.shape(after));
        }
        double left = n.remainingTime();
        Date eta = new Date(System.currentTimeMillis() + (long) (left * 1000));
        bottomTitle.setText(getContext().getString(R.string.map_arrival, DateFormat.getTimeFormat(getContext()).format(eta)));
        bottomDetail.setText(phrases.duration(left) + "  ·  " + phrases.distance(n.remainingDistance()));
        // A tela inicial mostra a próxima manobra no bloco do relógio (NavProgress).
        if (next != null) {
            NavProgress.publish(new NavProgress.State(Phrases.shape(next), phrases.distance(n.distanceToNext()),
                cue, bottomTitle.getText() + "  ·  " + bottomDetail.getText()));
        }
    }

    // ------------------------------------------------------------------ Navigator.Listener

    @Override
    public void onAnnounce(Route.Step step, int meters) {
        if (voice != null) voice.say(phrases.spoken(step, meters));
    }

    @Override
    public void onOffRoute() {
        if (rerouting) return;
        rerouting = true;
        calculate(true);
    }

    @Override
    public void onArrived() {
        if (voice != null) voice.say(getContext().getString(R.string.map_act_arrive));
        turn.setShape(Phrases.Shape.ARRIVE);
        turnDistance.setText(R.string.map_act_arrive);
        turnStreet.setText(destination == null ? "" : destination.name);
        then.setVisibility(GONE);
        NavProgress.publish(new NavProgress.State(Phrases.Shape.ARRIVE,
            getContext().getString(R.string.map_act_arrive), destination == null ? "" : destination.name, null));
        main.postDelayed(() -> {
            if (navigator != null && navigator.arrived()) endRoute();
        }, ARRIVED_CLOSE_MS);
    }

    // ------------------------------------------------------------------ apoio

    private void showMessage(int res) {
        message.setText(res);
        message.setVisibility(VISIBLE);
        main.removeCallbacks(hideMessage);
        main.postDelayed(hideMessage, MESSAGE_MS);
    }

    private void hideMessageNow() {
        main.removeCallbacks(hideMessage);
        message.setVisibility(GONE);
    }

    private void keyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** Idioma da voz: o do app, com o país mais comum para cada um. */
    private Locale voiceLocale() {
        String tag = LauncherConfig.language(getContext());
        switch (tag) {
            case "pt":
                return new Locale("pt", "BR");
            case "es":
                return new Locale("es", "ES");
            case "fr":
                return Locale.FRANCE;
            case "ja":
                return Locale.JAPAN;
            case "zh":
                return Locale.CHINA;
            case "ru":
                return new Locale("ru", "RU");
            case "hi":
                return new Locale("hi", "IN");
            default:
                return Locale.US;
        }
    }

    private int dp(float v) {
        return Math.round(Ui.dp(getContext(), v));
    }

    /** Começa onde o mapa estava da última vez, ou na última posição conhecida do aparelho. */
    private void restoreCamera() {
        SharedPreferences p = prefs();
        if (p.contains("lat")) {
            map.setCenter(p.getFloat("lat", 0), p.getFloat("lon", 0), p.getFloat("zoom", 15));
        }
    }

    private void saveCamera() {
        double lat = map.hasLocation() ? map.carLat() : map.centerLat();
        double lon = map.hasLocation() ? map.carLon() : map.centerLon();
        prefs().edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon)
            .putFloat("zoom", Math.max(12, Math.min(17, map.getZoom()))).apply();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("k4811_map", Context.MODE_PRIVATE);
    }

    private void centerOnLastKnown() {
        if (prefs().contains("lat")) return;
        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            Location l = lm == null ? null : lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null && lm != null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) map.setCenter(l.getLatitude(), l.getLongitude(), 15);
        } catch (Exception ignored) {
        }
    }
}
