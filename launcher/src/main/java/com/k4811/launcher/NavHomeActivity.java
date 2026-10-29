package com.k4811.launcher;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.Apps;
import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.home.GpsClient;
import com.k4811.common.home.NavApps;
import com.k4811.common.music.AlbumArt;
import com.k4811.common.radio.Band;
import com.k4811.common.radio.RadioClient;
import com.k4811.common.radio.RadioState;
import com.k4811.common.radio.StationArt;
import com.k4811.common.ui.DockNavigator;
import com.k4811.common.ui.DockView;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.home.HomeSession;
import com.k4811.launcher.nav.MediaSessions;
import com.k4811.map.MapConfig;
import com.k4811.launcher.waze.WazeScreen;
import com.k4811.map.MapScreen;
import com.k4811.map.NavProgress;
import com.k4811.map.TurnIcon;
import com.k4811.launcher.nav.SplitScreen;
import com.k4811.launcher.start.StartGuard;
import com.k4811.launcher.status.DayNightWatcher;
import com.k4811.launcher.status.StatusBar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela de navegação ("Home 2"): o app de navegação (Waze, Maps…) numa metade da tela dividida e,
 * nesta, um painel com a informação escolhida (relógio ou velocidade), o que está tocando (a mesma
 * fonte do cartão "Tocando agora" da tela inicial: rádio, MP3, música Bluetooth ou o player de outro
 * app, pelo {@link HomeSession}) e atalhos. Não é a HOME do sistema: o Android sempre mostra a HOME
 * em tela cheia, então esta tela é uma Activity comum, redimensionável, numa tarefa própria.
 *
 * Abre o app de navegação ao ser aberta (pelo dock ou pelas Configurações), não a cada volta.
 */
public class NavHomeActivity extends Activity {

    /** Idioma da suíte no contexto da tela (sobrevive às mudanças de janela, ver Ui.withLanguage). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.withLanguage(base));
    }

    /** Settings.Secure com os serviços de notificação liberados pelo usuário. */
    private static final String LISTENERS_SETTING = "enabled_notification_listeners";
    /** Tempo para conferir se o Android realmente dividiu a tela. */
    private static final long SPLIT_CHECK_MS = 1500;
    private static final int SOURCE_ANDROID = 0;
    private static final int SOURCE_RADIO = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);

    /** O que está tocando (rádio, MP3, BT ou outro app), como na tela inicial. */
    private HomeSession session;
    private HomeSession.Snapshot snap;
    /** Só observa o rádio (frequência, nome da estação); não liga nada. */
    private RadioClient radio;
    private RadioState radioState = new RadioState();
    private boolean radioReady;
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private String artKey;
    private int artGen;
    /** Painel visível (onStart..onStop): fora disso não redesenha. */
    private boolean visible;
    /** Idioma, tema (que pode seguir o dia e a noite), fonte e dock com que a tela foi montada. */
    private String appliedLook;
    private GpsClient gps;
    private String info;
    /** Pediu para entrar na metade primária e espera o Android dividir a tela. */
    private boolean waitingSplit;
    /** Dia/noite (tocar: brilho), voltímetro e RAM/CPU acima do relógio, como na tela inicial. */
    private StatusBar statusBar;
    /** Mapa integrado (OpenStreetMap) em vez do app externo em tela dividida; decidido ao abrir. */
    private boolean integrated;
    private MapScreen mapScreen;
    /** Waze modificado embutido no lugar do mapa (o launcher é o host do modo carro dele); decidido ao abrir. */
    private boolean embedded;
    private WazeScreen wazeScreen;

    private TextView clockTime;
    private TextView clockDate;
    private TextView speedValue;
    private TextView speedUnit;
    private ImageView art;
    private IconView artIcon;
    private ImageView appIcon;
    private TextView appName;
    private TextView title;
    private TextView artist;
    private View access;
    private IconView play;
    private View route;
    private ImageView routeIcon;
    private TurnIcon routeTurn;
    private TextView routeDistance;
    private TextView routeCue;
    private TextView routeEta;
    private TextView statusClock;
    /** Identifica esta instância nos avisos ao app de navegação (Apps.EXTRA_INSTANCE). */
    private final long instance = android.os.SystemClock.elapsedRealtime();
    /** O app de navegação está mandando a rota (NAV_STATE com active). */
    private boolean routeActive;

    /**
     * Rota publicada pelo Waze modificado (Apps.ACTION_NAV_STATE) e a pergunta dele sobre este
     * painel (Apps.ACTION_NAV_PANEL_QUERY: com o painel visível, ele esconde os próprios cartões).
     */
    private final BroadcastReceiver navState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Apps.ACTION_NAV_PANEL_QUERY.equals(intent.getAction())) sendPanelShown(true);
            else renderRoute(intent);
        }
    };

    private final ContentObserver accessObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            session.refresh();
        }
    };

    /** Tema seguindo o dia e a noite: troca com a tela aberta. */
    private final DayNightWatcher.Listener dayNightListener = night -> checkMode();

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderClock();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        appliedLook = Ui.lookKey(this);
        boolean entering = getIntent().getBooleanExtra(SplitScreen.EXTRA_ENTERING, false);
        integrated = !entering && wantsIntegrated();
        embedded = !integrated && !entering && wantsEmbeddedWaze();
        if (integrated) setUpIntegrated();
        else if (embedded) setUpEmbeddedWaze();
        else setContentView(R.layout.activity_nav_home);
        Ui.applyFont(findViewById(R.id.nav_root));

        clockTime = (TextView) findViewById(R.id.nav_clock_time);
        clockTime.setTypeface(Ui.semiBold(this));
        clockDate = (TextView) findViewById(R.id.nav_clock_date);
        speedValue = (TextView) findViewById(R.id.nav_speed_value);
        speedValue.setTypeface(Ui.semiBold(this));
        speedUnit = (TextView) findViewById(R.id.nav_speed_unit);
        art = (ImageView) findViewById(R.id.nav_art);
        artIcon = (IconView) findViewById(R.id.nav_art_icon);
        artIcon.set(IconView.Icon.MUSIC, IconView.Style.NONE);
        appIcon = (ImageView) findViewById(R.id.nav_app_icon);
        appName = (TextView) findViewById(R.id.nav_app_name);
        title = (TextView) findViewById(R.id.nav_title);
        title.setTypeface(Ui.semiBold(this));
        artist = (TextView) findViewById(R.id.nav_artist);
        access = findViewById(R.id.nav_access);
        route = findViewById(R.id.nav_route);
        routeIcon = (ImageView) findViewById(R.id.nav_route_icon);
        routeTurn = (TurnIcon) findViewById(R.id.nav_route_turn);
        if (routeTurn != null) routeTurn.setColor(Ui.TEXT);
        routeDistance = (TextView) findViewById(R.id.nav_route_distance);
        routeCue = (TextView) findViewById(R.id.nav_route_cue);
        routeEta = (TextView) findViewById(R.id.nav_route_eta);
        statusClock = (TextView) findViewById(R.id.nav_status_clock);
        if (routeDistance != null) routeDistance.setTypeface(Ui.semiBold(this));

        session = new HomeSession(this);
        session.setListener(s -> {
            snap = s;
            if (visible) renderMedia();
        });
        session.start();
        radio = new RadioClient(this);
        radio.setListener(state -> {
            radioState = state;
            if (state.connection == RadioState.Connection.CONNECTED) radioReady = true;
            if (visible) renderMedia();
        });
        radio.connect(false); // só observa: não manda tocar
        DayNightWatcher.get(this).addListener(dayNightListener);
        gps = new GpsClient(this);
        gps.setListener(this::renderSpeed);

        icon(R.id.nav_prev, IconView.Icon.SKIP_PREV, IconView.Style.NONE).setOnClickListener(v -> transport(-1));
        icon(R.id.nav_next, IconView.Icon.SKIP_NEXT, IconView.Style.NONE).setOnClickListener(v -> transport(1));
        play = icon(R.id.nav_play, IconView.Icon.PLAY, IconView.Style.CIRCLE);
        play.setOnClickListener(v -> transport(0));
        findViewById(R.id.nav_art_frame).setOnClickListener(v -> openPlayer());
        findViewById(R.id.nav_access_button).setOnClickListener(v -> openAccessSettings());
        icon(R.id.nav_home, IconView.Icon.HOME, IconView.Style.NONE).setOnClickListener(v -> goHome());
        IconView open = icon(R.id.nav_open, IconView.Icon.NAVIGATION, IconView.Style.NONE);
        open.setOnClickListener(v -> openNavigation());
        icon(R.id.nav_apps, IconView.Icon.APPS, IconView.Style.NONE).setOnClickListener(v -> openApps());
        Touch.install(findViewById(R.id.nav_root));
        statusBar = new StatusBar(this, findViewById(R.id.nav_root));
        // Modo escuro trocado pela barra de brilho: como uma troca nas Configurações (navegando, espera).
        statusBar.setOnLookChanged(this::checkMode);

        if (savedInstanceState != null || integrated || embedded) return;
        if (entering) {
            // Reaberta por SplitScreen.enter: a navegação abre ao lado quando a tela dividir.
            waitingSplit = true;
            handler.postDelayed(this::splitTimeout, SPLIT_CHECK_MS);
        } else {
            handler.postDelayed(this::openNavigation, 300);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (checkMode()) return;
        if (integrated || embedded) return;
        // Aberta de novo pelo dock ou pelas Configurações: traz a navegação de volta para o lado.
        handler.postDelayed(this::openNavigation, 300);
    }

    // ------------------------------------------------------------------ mapa integrado

    /**
     * Mapa integrado? Pela opção das Configurações; no automático, quando não há internet; e sempre
     * que não houver app de navegação instalado.
     */
    private boolean wantsIntegrated() {
        String mode = LauncherConfig.navMode(this);
        if (LauncherConfig.NAV_MODE_INTEGRATED.equals(mode)) return true;
        if (NavApps.chosen(this) == null) return true;
        if (LauncherConfig.NAV_MODE_EXTERNAL.equals(mode)) return false;
        return !MapConfig.online(this);
    }

    /**
     * Waze embutido? Com o app externo escolhido sendo o Waze modificado (projeto recompile-optimized),
     * o mapa dele entra no lugar do integrado, sem tela dividida.
     */
    private boolean wantsEmbeddedWaze() {
        return "com.waze".equals(NavApps.chosen(this)) && WazeScreen.available(this);
    }

    /** Painel e Waze lado a lado, como o mapa integrado; a rota vai para o cartão do painel. */
    private void setUpEmbeddedWaze() {
        setContentView(R.layout.activity_nav_home_waze);
        ViewGroup holder = (ViewGroup) findViewById(R.id.nav_map);
        wazeScreen = new WazeScreen(this);
        holder.addView(wazeScreen, new ViewGroup.LayoutParams(-1, -1));
        setUpPanelBesideMap(holder);
        wazeScreen.setRouteListener(this::renderRoute);
    }

    /** Painel e mapa lado a lado, o dock embaixo (Navegação aceso), a música no lado escolhido. */
    private void setUpIntegrated() {
        setContentView(R.layout.activity_nav_home_map);
        setUpPanelBesideMap(findViewById(R.id.nav_map));
        mapScreen = (MapScreen) findViewById(R.id.nav_map);
        // Como com o Waze: a manobra e a chegada vão para o cartão do painel, no lugar da hora.
        if (findViewById(R.id.nav_route) != null) mapScreen.setRouteOutside(true);
    }

    /** Lado da música, cantos do mapa, sem os atalhos (há o dock), capa menor e o dock. */
    private void setUpPanelBesideMap(View map) {
        ViewGroup row = (ViewGroup) findViewById(R.id.nav_map_row);
        View panel = findViewById(R.id.nav_root);
        if (LauncherConfig.navPanelRight(this)) {
            row.removeView(panel);
            row.addView(panel);
            // Mapa à esquerda: o afastamento da borda da tela (alinhado ao dock) vai para a esquerda.
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) map.getLayoutParams();
            mlp.leftMargin = mlp.rightMargin;
            mlp.rightMargin = 0;
            map.setLayoutParams(mlp);
        }
        roundCorners(map);
        findViewById(R.id.nav_shortcuts).setVisibility(View.GONE);
        // Painel mais estreito que na tela dividida: capa menor.
        View frame = findViewById(R.id.nav_art_frame);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        lp.width = lp.height = Math.round(Ui.dp(this, 112));
        frame.setLayoutParams(lp);
        DockView dock = DockNavigator.attachDock(this, DockView.Item.NAVIGATION);
        dock.setListener(this::onDock);
    }

    /** Cantos arredondados como os dos cartões (drawable panel, 22 dp); Android 5+. */
    private void roundCorners(View v) {
        if (Build.VERSION.SDK_INT < 21) return;
        final float r = Ui.dp(this, 22);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private void onDock(DockView.Item item) {
        if (item == DockView.Item.NAVIGATION) return;
        if (item == DockView.Item.HOME) {
            goHome();
            return;
        }
        DockNavigator.navigate(this, DockView.Item.NAVIGATION, item);
    }

    /**
     * O modo mudou nas Configurações (ou a internet, no automático): monta a tela de novo.
     * @return true se vai recriar (o chamador não continua)
     */
    private boolean checkMode() {
        if (isFinishing() || (mapScreen != null && mapScreen.isNavigating())
            || (wazeScreen != null && wazeScreen.isNavigating())) {
            return false;
        }
        if (!Ui.lookKey(this).equals(appliedLook)) {
            // Tema, modo escuro (inclusive o dia/noite), idioma, fonte ou dock mudaram: monta tudo de
            // novo, mapa incluído. Navegando, espera a rota acabar (a tela continua como está).
            recreate();
            return true;
        }
        boolean wantIntegrated = wantsIntegrated();
        if (wantIntegrated == integrated && (!wantIntegrated && wantsEmbeddedWaze()) == embedded) return false;
        // Tarefa nova, como uma abertura normal: sai da tela dividida se estava nela e, no app
        // externo, abre a navegação ao lado (recreate() guardaria o estado e não abriria).
        SplitScreen.restart(this);
        return true;
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindow) {
        super.onMultiWindowModeChanged(inMultiWindow);
        if (inMultiWindow) splitReady();
    }

    /** A tela dividiu (ou já estava dividida ao abrir): abre a navegação ao lado. */
    private void splitReady() {
        if (!waitingSplit) return;
        waitingSplit = false;
        openNavigationBeside();
    }

    private void splitTimeout() {
        if (!waitingSplit || isFinishing()) return;
        if (inSplit()) {
            splitReady();
            return;
        }
        waitingSplit = false;
        noSplit();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // Na tela dividida esta tela fica pausada enquanto o motorista usa o mapa: o painel continua
        // vivo enquanto estiver visível (onStart/onStop), não só com o foco.
        if (mapScreen != null) {
            mapScreen.setVoiceEnabled(LauncherConfig.navVoice(this));
            mapScreen.onStart();
            if (route != null) {
                NavProgress.addListener(integratedRoute);
                renderIntegrated(NavProgress.current());
            }
        }
        if (wazeScreen != null) wazeScreen.onStart();
        info = LauncherConfig.navInfo(this);
        showInfo();
        if (route != null && !integrated && !embedded) {
            // A rota do app de navegação ao lado: pede o estado atual e escuta as mudanças.
            IntentFilter f = new IntentFilter(Apps.ACTION_NAV_STATE);
            f.addAction(Apps.ACTION_NAV_PANEL_QUERY);
            registerReceiver(navState, f);
            String pkg = NavApps.chosen(this);
            if (pkg != null) sendBroadcast(new Intent(Apps.ACTION_NAV_QUERY).setPackage(pkg));
            sendPanelShown(true);
        }
        // Relógio grande ou a hora pequena da barra (com a rota no lugar do relógio): vira o minuto.
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        renderClock();
        if (LauncherConfig.NAV_INFO_SPEED.equals(info)) {
            renderSpeed(null);
            gps.start();
        }
        // O acesso a notificações pode ser ligado com esta tela aberta (na outra metade).
        getContentResolver().registerContentObserver(
            Settings.Secure.getUriFor(LISTENERS_SETTING), false, accessObserver);
        session.refresh();
        renderMedia();
        statusBar.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        if (checkMode()) return;
        if (SplitScreen.supported() && inSplit()) splitReady();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(tick);
        if (route != null && !integrated && !embedded) {
            unregisterReceiver(navState);
            sendPanelShown(false);
        }
        gps.stop();
        getContentResolver().unregisterContentObserver(accessObserver);
        visible = false;
        statusBar.stop();
        if (mapScreen != null) {
            NavProgress.removeListener(integratedRoute);
            mapScreen.onStop();
        }
        if (wazeScreen != null) wazeScreen.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        DayNightWatcher.get(this).removeListener(dayNightListener);
        session.setListener(null);
        session.stop();
        radio.setListener(null);
        radio.release();
        artLoader.shutdownNow();
        if (wazeScreen != null) wazeScreen.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Ui.hideSystemBars(getWindow());
            // Na tela dividida, voltar a tocar nesta metade não passa por onResume.
            checkMode();
        }
    }

    /** Toque na tela: o vigia da partida não a reabre mais (o motorista decide). */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        StartGuard.userActed();
    }

    @Override
    public void onBackPressed() {
        if (mapScreen != null && mapScreen.onBackPressed()) return;
        if (wazeScreen != null && wazeScreen.onBackPressed()) return;
        goHome();
    }

    // ------------------------------------------------------------------ ações

    /**
     * Mapa ao lado: põe esta tela na metade primária (se ainda não está) e, quando o Android dividir,
     * abre o app de navegação na outra. Sem tela dividida, avisa e abre o app em tela cheia.
     */
    private void openNavigation() {
        if (isFinishing()) return;
        if (navIntent() == null) {
            Toast.makeText(this, R.string.home_nav_no_app, Toast.LENGTH_LONG).show();
            return;
        }
        if (SplitScreen.supported() && inSplit()) {
            openNavigationBeside();
            return;
        }
        if (SplitScreen.adjacentSplits()) {
            // Android 12+: o sistema divide ao abrir o app ao lado; se não dividir, avisa.
            openNavigationBeside();
            handler.postDelayed(this::checkAdjacentSplit, SPLIT_CHECK_MS);
            return;
        }
        // Esta tela fecha e reabre já na metade primária (onCreate com EXTRA_ENTERING).
        if (!SplitScreen.enter(this)) noSplit();
    }

    /** Android 12+: o app abriu ao lado; se a tela não dividiu, ele ficou em tela cheia. */
    private void checkAdjacentSplit() {
        if (isFinishing() || inSplit()) return;
        Toast.makeText(this, R.string.home_nav_no_split, Toast.LENGTH_LONG).show();
    }

    private void openNavigationBeside() {
        Intent launch = navIntent();
        if (launch != null) SplitScreen.openBeside(this, launch);
    }

    private void noSplit() {
        Toast.makeText(this, R.string.home_nav_no_split, Toast.LENGTH_LONG).show();
        Intent launch = navIntent();
        if (launch != null) SplitScreen.openBeside(this, launch);
    }

    /**
     * Tela inicial do app de navegação escolhido; null se não há nenhum. Leva o modo escuro da
     * suíte (o Waze modificado segue; os outros apps ignoram o extra).
     */
    private Intent navIntent() {
        String pkg = NavApps.chosen(this);
        Intent launch = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) launch.putExtra(Apps.EXTRA_DARK_MODE, LauncherConfig.darkMode(this));
        return launch;
    }

    @TargetApi(24)
    private boolean inSplit() {
        return isInMultiWindowMode();
    }

    /**
     * Volta para a tela inicial. Na tela dividida, esta tarefa sai da metade primária: o Android
     * desfaz a divisão e o app de navegação continua em tela cheia, atrás da tela inicial (senão
     * ele ficaria como uma faixa estreita na borda da tela inicial).
     */
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setPackage(getPackageName());
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        overridePendingTransition(R.anim.screen_close_enter, R.anim.screen_close_exit);
        if (SplitScreen.supported() && inSplit()) leaveSplit();
    }

    @TargetApi(24)
    private void leaveSplit() {
        finishAndRemoveTask();
    }

    private void openApps() {
        Intent apps = new Intent(Apps.ACTION_APPS);
        apps.setPackage(getPackageName());
        apps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(apps);
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(MediaSessions.ACTION_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.home_nav_media_access, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ desenho

    private IconView icon(int id, IconView.Icon icon, IconView.Style style) {
        IconView v = (IconView) findViewById(id);
        v.set(icon, style);
        return v;
    }

    private void renderClock() {
        long now = System.currentTimeMillis();
        String time = timeFormat.format(new Date(now));
        clockTime.setText(time);
        clockDate.setText(DateUtils.formatDateTime(this, now,
            DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR));
        if (statusClock != null) {
            // Hora e data curtas na barra de status (com a rota no lugar do relógio grande).
            statusClock.setText(time + "  " + DateUtils.formatDateTime(this, now,
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR
                    | DateUtils.FORMAT_ABBREV_ALL));
        }
    }

    /** Avisa o app de navegação se este painel (com a rota) está visível ao lado dele. */
    private void sendPanelShown(boolean shown) {
        String pkg = NavApps.chosen(this);
        if (pkg == null) return;
        sendBroadcast(new Intent(Apps.ACTION_NAV_PANEL).setPackage(pkg)
            .putExtra(Apps.EXTRA_SHOWN, shown).putExtra(Apps.EXTRA_INSTANCE, instance));
    }

    /** Relógio ou velocidade (a opção das Configurações); escondidos enquanto a rota aparece. */
    private void showInfo() {
        if (statusClock != null) statusClock.setVisibility(routeActive ? View.VISIBLE : View.GONE);
        findViewById(R.id.nav_clock).setVisibility(!routeActive && LauncherConfig.NAV_INFO_CLOCK.equals(info) ? View.VISIBLE : View.GONE);
        findViewById(R.id.nav_speed).setVisibility(!routeActive && LauncherConfig.NAV_INFO_SPEED.equals(info) ? View.VISIBLE : View.GONE);
    }

    /** Rota do mapa integrado (o mapa esconde as próprias faixas, ver MapScreen.setRouteOutside). */
    private final NavProgress.Listener integratedRoute = this::renderIntegrated;

    private void renderIntegrated(NavProgress.State s) {
        setRouteActive(s != null);
        if (s == null) return;
        routeIcon.setVisibility(View.GONE);
        ((View) routeIcon.getParent()).setVisibility(View.VISIBLE);
        routeTurn.setVisibility(View.VISIBLE);
        routeTurn.setShape(s.shape);
        setIfChanged(routeDistance, s.distance);
        setIfChanged(routeCue, s.cue);
        setIfChanged(routeEta, s.eta);
        routeEta.setVisibility(s.eta == null ? View.GONE : View.VISIBLE);
    }

    /** Com rota, o cartão fica no lugar do relógio/velocidade e a hora vai pequena para a barra de status. */
    private void setRouteActive(boolean active) {
        if (active == routeActive) return;
        routeActive = active;
        route.setVisibility(active ? View.VISIBLE : View.GONE);
        showInfo();
    }

    /** Manobra, distância, instrução e chegada mandadas pelo app de navegação (NAV_STATE). */
    private void renderRoute(Intent i) {
        String pkg = NavApps.chosen(this);
        if (route == null || pkg == null || !pkg.equals(i.getStringExtra("app"))) return;
        boolean active = i.getBooleanExtra("active", false);
        setRouteActive(active);
        if (!active) return;
        if (routeTurn != null) routeTurn.setVisibility(View.GONE);
        routeEta.setVisibility(View.VISIBLE);
        android.graphics.Bitmap icon = i.getParcelableExtra("icon");
        routeIcon.setImageBitmap(icon);
        routeIcon.setColorFilter(Ui.TEXT);
        routeIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        // Sem seta (o Waze não manda no começo da rota): a moldura sai e o texto encosta na borda.
        ((View) routeIcon.getParent()).setVisibility(icon == null ? View.GONE : View.VISIBLE);
        setIfChanged(routeDistance, i.getStringExtra("distance"));
        setIfChanged(routeCue, i.getStringExtra("cue"));
        StringBuilder eta = new StringBuilder();
        String arrival = i.getStringExtra("arrival");
        if (arrival != null) eta.append(getString(R.string.home_nav_arrival, arrival));
        for (String part : new String[]{i.getStringExtra("remaining"), i.getStringExtra("remaining_distance")}) {
            if (part == null) continue;
            if (eta.length() > 0) eta.append("  ·  ");
            eta.append(part);
        }
        setIfChanged(routeEta, eta.toString());
    }

    private static void setIfChanged(TextView v, String text) {
        String t = text == null ? "" : text;
        if (!t.contentEquals(v.getText())) v.setText(t);
    }

    /** {@code fix} null = sem posição recente. */
    private void renderSpeed(GpsClient.Fix fix) {
        if (fix == null || Float.isNaN(fix.speedKmh)) {
            speedValue.setText(R.string.home_gps_none);
            speedUnit.setText(gps.available() ? R.string.home_gps_waiting : R.string.home_gps_unavailable);
            return;
        }
        // Abaixo do passo de uma pessoa é ruído do GPS: mostra 0.
        speedValue.setText(String.valueOf(fix.moving() ? Math.round(fix.speedKmh) : 0));
        speedUnit.setText(R.string.home_speed_unit);
    }

    /**
     * O que está tocando, como o cartão "Tocando agora" da tela inicial: rádio (estação), MP3 (faixa e
     * capa), música Bluetooth (faixa do celular) ou o player de outro app (nome, ícone e capa).
     */
    private void renderMedia() {
        HomeSession.Snapshot s = snap;
        HomeSession.Kind kind = s == null ? HomeSession.Kind.NONE : s.kind;
        // O acesso a notificações só serve para ler os players de outros apps.
        boolean needsAccess = Build.VERSION.SDK_INT >= 21 && !NavApps.mediaAccess(this)
            && (kind == HomeSession.Kind.NONE || kind == HomeSession.Kind.APP);
        access.setVisibility(needsAccess ? View.VISIBLE : View.GONE);
        String line1;
        String line2 = "";
        boolean playing = false;
        switch (kind) {
            case RADIO: {
                renderSource(R.string.home_now_playing_radio);
                if (!radioReady) {
                    line1 = getString(radioState.connection == RadioState.Connection.NOT_FOUND
                        ? R.string.home_radio_unavailable : R.string.home_radio);
                } else {
                    boolean fm = Band.isFm(radioState.band);
                    String freq = Band.formatValue(radioState.frequency, fm) + " " + Band.unit(fm);
                    boolean named = radioState.psName != null && !radioState.psName.isEmpty();
                    line1 = named ? radioState.psName : freq;
                    line2 = Band.label(radioState.band) + (named ? "  ·  " + freq : "");
                }
                playing = s.source == SOURCE_RADIO;
                break;
            }
            case USB:
                renderSource(R.string.home_now_playing_mp3);
                line1 = s.title.isEmpty() ? getString(R.string.home_music) : s.title;
                line2 = s.artist;
                playing = s.playing;
                break;
            case BT:
                renderSource(R.string.home_now_playing_bt);
                if (s.a2dpKnown && !s.a2dpConnected && s.btTitle.isEmpty()) {
                    line1 = getString(R.string.home_phone_disconnected);
                } else {
                    line1 = s.btTitle.isEmpty() ? getString(R.string.home_bt_music) : s.btTitle;
                    line2 = s.btArtist;
                }
                playing = s.btPlaying;
                break;
            case APP: {
                MediaSessions.Track t = s.app;
                renderApp(t.pkg);
                line1 = t.title == null || t.title.isEmpty() ? appName.getText().toString() : t.title;
                line2 = t.artist == null ? "" : t.artist;
                playing = t.playing;
                break;
            }
            default:
                setIfChanged(appName, "");
                appIcon.setVisibility(View.GONE);
                line1 = getString(R.string.home_nothing_playing);
                break;
        }
        setIfChanged(title, line1);
        setIfChanged(artist, line2);
        if (kind == HomeSession.Kind.RADIO) {
            play.set(IconView.Icon.POWER, IconView.Style.CIRCLE);
            play.setContentDescription(getString(playing ? R.string.home_radio_turn_off : R.string.home_radio_turn_on));
        } else {
            play.set(playing ? IconView.Icon.PAUSE : IconView.Icon.PLAY, IconView.Style.CIRCLE);
            play.setContentDescription(getString(playing ? R.string.home_pause : R.string.home_play));
        }
        renderArt(s, kind);
    }

    private static void setIfChanged(TextView v, CharSequence text) {
        if (!TextUtils.equals(v.getText(), text)) v.setText(text);
    }

    /** Fonte da suíte (rádio, MP3, BT): o nome dela no lugar do app. */
    private void renderSource(int label) {
        appIcon.setVisibility(View.GONE);
        setIfChanged(appName, getString(label));
    }

    private void renderApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            setIfChanged(appName, pm.getApplicationLabel(ai));
            appIcon.setImageDrawable(pm.getApplicationIcon(ai));
            appIcon.setVisibility(View.VISIBLE);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            setIfChanged(appName, pkg);
            appIcon.setVisibility(View.GONE);
        }
    }

    /** Capa do outro app ou do MP3, imagem da estação; sem imagem, o ícone da fonte. */
    private void renderArt(HomeSession.Snapshot s, HomeSession.Kind kind) {
        artIcon.set(kind == HomeSession.Kind.RADIO ? IconView.Icon.RADIO
            : kind == HomeSession.Kind.BT ? IconView.Icon.BLUETOOTH : IconView.Icon.MUSIC, IconView.Style.NONE);
        if (kind == HomeSession.Kind.APP) {
            Bitmap cover = s.app.art;
            String key = cover == null ? null : "app:" + s.app.pkg + ":" + System.identityHashCode(cover);
            if (key == null) showArt(null, null);
            else if (!key.equals(artKey)) showArt(cover, key);
            return;
        }
        long id = 0;
        if (kind == HomeSession.Kind.USB && s.mediaId != null) {
            try {
                id = Long.parseLong(s.mediaId.trim());
            } catch (NumberFormatException ignored) {
                // sem id: tenta pelo arquivo
            }
        }
        String path = kind == HomeSession.Kind.USB ? s.path : null;
        boolean station = kind == HomeSession.Kind.RADIO && radioReady && radioState.frequency > 0;
        int band = radioState.band;
        int frequency = radioState.frequency;
        String key = station ? "radio:" + StationArt.baseName(band, frequency)
            : id > 0 ? "id:" + id : path != null ? "path:" + path : null;
        if (key == null) {
            showArt(null, null);
            return;
        }
        if (key.equals(artKey)) return;
        showArt(null, key);
        int gen = artGen;
        long mediaId = id;
        int size = art.getWidth() > 0 ? art.getWidth() : Math.round(Ui.dp(this, 160));
        try {
            artLoader.execute(() -> {
                Bitmap bmp = station ? StationArt.load(band, frequency, size)
                    : AlbumArt.decode(getApplicationContext(), mediaId, path);
                handler.post(() -> {
                    if (isFinishing() || gen != artGen || bmp == null) return;
                    art.setImageBitmap(bmp);
                    art.setVisibility(View.VISIBLE);
                    artIcon.setVisibility(View.GONE);
                });
            });
        } catch (Exception ignored) {
            // executor já desligado (tela saindo)
        }
    }

    /** Mostra a capa (null = o ícone da fonte) e marca qual imagem é ({@code key}). */
    private void showArt(Bitmap bitmap, String key) {
        artGen++;
        artKey = key;
        art.setImageBitmap(bitmap);
        art.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        artIcon.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ comandos da música

    /** −1 anterior, 0 tocar/pausar (no rádio: liga/desliga), +1 próxima. */
    private void transport(int what) {
        HomeSession.Snapshot s = snap;
        HomeSession.Kind kind = s == null ? HomeSession.Kind.NONE : s.kind;
        switch (kind) {
            case RADIO:
                if (what == 0) toggleRadio();
                else tuneRadio(what > 0);
                break;
            case USB:
                session.usbTransport(what < 0 ? HomeSession.USB_PREV
                    : what > 0 ? HomeSession.USB_NEXT : HomeSession.USB_TOGGLE);
                break;
            case BT:
                if (what < 0) session.btTransport(HomeSession.BT_PREV);
                else if (what > 0) session.btTransport(HomeSession.BT_NEXT);
                else session.btTransport(s.btPlaying ? HomeSession.BT_PAUSE : HomeSession.BT_PLAY);
                break;
            default:
                // Outro app (ou nada): o player do Android; sem sessão, teclas de mídia.
                session.appTransport(what);
                break;
        }
    }

    /** Memória anterior/próxima; se o rádio não é a fonte, liga antes (o toque é o que liga). */
    private void tuneRadio(boolean next) {
        if (snap == null || snap.source != SOURCE_RADIO) {
            PlayerHandoff.switchToRadio(this, radio::activateSource);
            session.assumeSource(SOURCE_RADIO);
        }
        radio.presetStep(next);
    }

    private void toggleRadio() {
        if (snap != null && snap.source == SOURCE_RADIO) {
            radio.deactivateSource();
            session.assumeSource(SOURCE_ANDROID);
        } else {
            PlayerHandoff.switchToRadio(this, radio::activateSource);
            session.assumeSource(SOURCE_RADIO);
        }
    }

    /** Toque na capa: abre o app da fonte. */
    private void openPlayer() {
        HomeSession.Kind kind = snap == null ? HomeSession.Kind.NONE : snap.kind;
        switch (kind) {
            case RADIO:
                DockNavigator.navigate(this, DockView.Item.NAVIGATION, DockView.Item.RADIO);
                break;
            case USB:
                DockNavigator.navigateMp3(this, DockView.Item.NAVIGATION);
                break;
            case BT:
                DockNavigator.navigate(this, DockView.Item.NAVIGATION, DockView.Item.BT_MUSIC);
                break;
            default:
                session.openApp();
                break;
        }
    }
}
