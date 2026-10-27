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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.home.GpsClient;
import com.k4811.common.home.NavApps;
import com.k4811.common.ui.DockNavigator;
import com.k4811.common.ui.DockView;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.nav.MediaSessions;
import com.k4811.map.MapConfig;
import com.k4811.map.MapScreen;
import com.k4811.launcher.nav.SplitScreen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tela de navegação ("Home 2"): o app de navegação (Waze, Maps…) numa metade da tela dividida e,
 * nesta, um painel com a informação escolhida (relógio ou velocidade), a música do player ativo
 * (Spotify, YouTube Music…) e atalhos. Não é a HOME do sistema: o Android sempre mostra a HOME
 * em tela cheia, então esta tela é uma Activity comum, redimensionável, numa tarefa própria.
 *
 * Abre o app de navegação ao ser aberta (pelo dock ou pelas Configurações), não a cada volta.
 */
public class NavHomeActivity extends Activity {

    /** Settings.Secure com os serviços de notificação liberados pelo usuário. */
    private static final String LISTENERS_SETTING = "enabled_notification_listeners";
    /** Tempo para conferir se o Android realmente dividiu a tela. */
    private static final long SPLIT_CHECK_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);

    private MediaSessions media;
    private GpsClient gps;
    private String info;
    /** Pediu para entrar na metade primária e espera o Android dividir a tela. */
    private boolean waitingSplit;
    /** Mapa integrado (OpenStreetMap) em vez do app externo em tela dividida; decidido ao abrir. */
    private boolean integrated;
    private MapScreen mapScreen;

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

    private final ContentObserver accessObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            media.start();
            renderMedia();
        }
    };

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
        boolean entering = getIntent().getBooleanExtra(SplitScreen.EXTRA_ENTERING, false);
        integrated = !entering && wantsIntegrated();
        if (integrated) setUpIntegrated();
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

        media = new MediaSessions(this);
        media.setListener(this::renderMedia);
        gps = new GpsClient(this);
        gps.setListener(this::renderSpeed);

        icon(R.id.nav_prev, IconView.Icon.SKIP_PREV, IconView.Style.NONE).setOnClickListener(v -> media.previous());
        icon(R.id.nav_next, IconView.Icon.SKIP_NEXT, IconView.Style.NONE).setOnClickListener(v -> media.next());
        play = icon(R.id.nav_play, IconView.Icon.PLAY, IconView.Style.CIRCLE);
        play.setOnClickListener(v -> media.playPause());
        findViewById(R.id.nav_art_frame).setOnClickListener(v -> media.openPlayer());
        findViewById(R.id.nav_access_button).setOnClickListener(v -> openAccessSettings());
        icon(R.id.nav_home, IconView.Icon.HOME, IconView.Style.NONE).setOnClickListener(v -> goHome());
        IconView open = icon(R.id.nav_open, IconView.Icon.NAVIGATION, IconView.Style.NONE);
        open.setOnClickListener(v -> openNavigation());
        icon(R.id.nav_apps, IconView.Icon.APPS, IconView.Style.NONE).setOnClickListener(v -> openApps());
        Touch.install(findViewById(R.id.nav_root));

        if (savedInstanceState != null || integrated) return;
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
        if (integrated) return;
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

    /** Painel e mapa lado a lado, o dock embaixo (Navegação aceso), a música no lado escolhido. */
    private void setUpIntegrated() {
        setContentView(R.layout.activity_nav_home_map);
        ViewGroup row = (ViewGroup) findViewById(R.id.nav_map_row);
        View panel = findViewById(R.id.nav_root);
        if (LauncherConfig.navPanelRight(this)) {
            row.removeView(panel);
            row.addView(panel);
        }
        findViewById(R.id.nav_shortcuts).setVisibility(View.GONE);
        // Painel mais estreito que na tela dividida: capa menor.
        View frame = findViewById(R.id.nav_art_frame);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        lp.width = lp.height = Math.round(Ui.dp(this, 112));
        frame.setLayoutParams(lp);
        mapScreen = (MapScreen) findViewById(R.id.nav_map);
        DockView dock = DockNavigator.attachDock(this, DockView.Item.NAVIGATION);
        dock.setListener(this::onDock);
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
        if (isFinishing() || (mapScreen != null && mapScreen.isNavigating())) return false;
        if (wantsIntegrated() == integrated) return false;
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
        // Na tela dividida esta tela fica pausada enquanto o motorista usa o mapa: o painel continua
        // vivo enquanto estiver visível (onStart/onStop), não só com o foco.
        if (mapScreen != null) {
            mapScreen.setVoiceEnabled(LauncherConfig.navVoice(this));
            mapScreen.onStart();
        }
        info = LauncherConfig.navInfo(this);
        findViewById(R.id.nav_clock).setVisibility(LauncherConfig.NAV_INFO_CLOCK.equals(info) ? View.VISIBLE : View.GONE);
        findViewById(R.id.nav_speed).setVisibility(LauncherConfig.NAV_INFO_SPEED.equals(info) ? View.VISIBLE : View.GONE);
        if (LauncherConfig.NAV_INFO_CLOCK.equals(info)) {
            registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
            renderClock();
        }
        if (LauncherConfig.NAV_INFO_SPEED.equals(info)) {
            renderSpeed(null);
            gps.start();
        }
        // O acesso a notificações pode ser ligado com esta tela aberta (na outra metade).
        getContentResolver().registerContentObserver(
            Settings.Secure.getUriFor(LISTENERS_SETTING), false, accessObserver);
        media.start();
        renderMedia();
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
        if (LauncherConfig.NAV_INFO_CLOCK.equals(info)) unregisterReceiver(tick);
        gps.stop();
        getContentResolver().unregisterContentObserver(accessObserver);
        media.stop();
        if (mapScreen != null) mapScreen.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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

    @Override
    public void onBackPressed() {
        if (mapScreen != null && mapScreen.onBackPressed()) return;
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
        // Esta tela fecha e reabre já na metade primária (onCreate com EXTRA_ENTERING).
        if (!SplitScreen.enter(this)) noSplit();
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

    /** Tela inicial do app de navegação escolhido; null se não há nenhum. */
    private Intent navIntent() {
        String pkg = NavApps.chosen(this);
        return pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
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
        clockTime.setText(timeFormat.format(new Date(now)));
        clockDate.setText(DateUtils.formatDateTime(this, now,
            DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR));
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

    private void renderMedia() {
        MediaSessions.Track t = media.track();
        access.setVisibility(Build.VERSION.SDK_INT >= 21 && !media.hasAccess() ? View.VISIBLE : View.GONE);
        play.set(t != null && t.playing ? IconView.Icon.PAUSE : IconView.Icon.PLAY, IconView.Style.CIRCLE);
        if (t == null) {
            title.setText(R.string.home_nothing_playing);
            artist.setText("");
            appName.setText("");
            appIcon.setVisibility(View.GONE);
            showArt(null);
            return;
        }
        title.setText(t.title == null ? getString(R.string.home_nothing_playing) : t.title);
        artist.setText(t.artist == null ? "" : t.artist);
        renderApp(t.pkg);
        showArt(t.art);
    }

    private void renderApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appName.setText(pm.getApplicationLabel(ai));
            appIcon.setImageDrawable(pm.getApplicationIcon(ai));
            appIcon.setVisibility(View.VISIBLE);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            appName.setText(pkg);
            appIcon.setVisibility(View.GONE);
        }
    }

    private void showArt(android.graphics.Bitmap bitmap) {
        art.setImageBitmap(bitmap);
        art.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        artIcon.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
    }
}
