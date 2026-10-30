package com.k4811.car.setting;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.k4811.car.setting.ui.OptionList;
import com.k4811.common.Apps;
import com.k4811.common.audio.MixVolumes;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.config.NwdConfigs;
import com.k4811.common.home.NavApps;
import com.k4811.common.system.ExternalApps;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Navegação (Configurações › Customizar › Customizar navegação): a tela de navegação do launcher,
 * o mapa integrado (voz, zoom dinâmico), radares e alertas, e o app externo. Cada linha tem título,
 * texto explicativo e botão de ajuda. Sem dock nesta tela.
 */
public class NavSettingsActivity extends Activity {

    /** Idioma da suíte no contexto da tela (sobrevive às mudanças de janela, ver Ui.withLanguage). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.withLanguage(base));
    }

    private OptionList list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.applyFont(findViewById(R.id.settings_root));
        TextView title = (TextView) findViewById(R.id.settings_title);
        title.setText(R.string.settings_nav_section);
        title.setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.settings_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        list = new OptionList(this, (LinearLayout) findViewById(R.id.settings_content));
        build();
        list.renderAll();
        Touch.install(findViewById(android.R.id.content));
    }

    private void build() {
        // Tela de navegação: modo, lado da música, informação.
        list.section(getString(R.string.settings_nav_home_section));
        list.action(getString(R.string.settings_nav_home_open), getString(R.string.settings_nav_home_open_hint),
            getString(R.string.settings_open), this::openNavHome)
            .visibleWhen(() -> ExternalApps.installed(this, Apps.LAUNCHER))
            .help(getString(R.string.settings_help_nav_home_open));

        String[] modeLabels = {getString(R.string.settings_nav_home_mode_integrated),
            getString(R.string.settings_nav_home_mode_external), getString(R.string.settings_nav_home_mode_auto)};
        list.choice(getString(R.string.settings_nav_home_mode), getString(R.string.settings_nav_home_mode_hint),
            modeLabels, new int[]{0, 1, 2},
            () -> Arrays.asList(LauncherConfig.NAV_MODES).indexOf(LauncherConfig.navMode(this)),
            i -> LauncherConfig.setNavMode(this, LauncherConfig.NAV_MODES[i]))
            .local().help(getString(R.string.settings_help_nav_home_mode));

        String[] sides = {getString(R.string.settings_edge_menu_left), getString(R.string.settings_edge_menu_right)};
        list.choice(getString(R.string.settings_nav_home_panel_side), getString(R.string.settings_nav_home_panel_side_hint),
            sides, new int[]{0, 1},
            () -> LauncherConfig.navPanelRight(this) ? 1 : 0,
            i -> LauncherConfig.setNavPanelRight(this, i == 1))
            .local().help(getString(R.string.settings_help_nav_home_panel_side));

        String[] infoLabels = {getString(R.string.settings_home_module_clock),
            getString(R.string.settings_home_module_speed), getString(R.string.settings_home_hidden)};
        list.choice(getString(R.string.settings_nav_home_info), getString(R.string.settings_nav_home_info_hint),
            infoLabels, new int[]{0, 1, 2},
            () -> Arrays.asList(LauncherConfig.NAV_INFOS).indexOf(LauncherConfig.navInfo(this)),
            i -> LauncherConfig.setNavInfo(this, LauncherConfig.NAV_INFOS[i]))
            .local().help(getString(R.string.settings_help_nav_home_info));

        OptionList.Row access = list.action(getString(R.string.settings_nav_home_media_access),
            getString(R.string.settings_nav_home_media_access_hint), getString(R.string.settings_open), () -> {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (RuntimeException e) {
                    // Central sem a tela de acesso a notificações: nada a abrir.
                }
            })
            .visibleWhen(() -> Build.VERSION.SDK_INT >= 21 && ExternalApps.installed(this, Apps.LAUNCHER))
            .help(getString(R.string.settings_help_nav_home_media_access));

        // Mapa integrado (map/MapScreen).
        list.section(getString(R.string.settings_nav_map_section));
        list.toggle(getString(R.string.settings_nav_home_voice), getString(R.string.settings_nav_home_voice_hint),
            () -> LauncherConfig.navVoice(this),
            on -> LauncherConfig.setNavVoice(this, on))
            .local().help(getString(R.string.settings_help_nav_home_voice));
        list.toggle(getString(R.string.settings_nav_home_auto_zoom), getString(R.string.settings_nav_home_auto_zoom_hint),
            () -> LauncherConfig.navAutoZoom(this),
            on -> LauncherConfig.setNavAutoZoom(this, on))
            .local().help(getString(R.string.settings_help_nav_home_auto_zoom));
        list.toggle(getString(R.string.settings_nav_perspective), getString(R.string.settings_nav_perspective_hint),
            () -> LauncherConfig.navPerspective(this),
            on -> LauncherConfig.setNavPerspective(this, on))
            .local().help(getString(R.string.settings_help_nav_perspective));
        // Mapa offline (baixado pelo launcher como os dados das vias, ver map/.../road/RoadSync).
        OptionList.Row offlineMap = list.toggle(getString(R.string.settings_nav_offline_map), null,
            () -> LauncherConfig.navOfflineMap(this),
            on -> {
                LauncherConfig.setNavOfflineMap(this, on);
                checkMapData();
            })
            .local();

        // Serviços do mapa integrado: OpenStreetMap ou Google (map/.../google/, docs/plano-mapa-google.md).
        list.section(getString(R.string.settings_nav_google_section));
        String[] providerLabels = {getString(R.string.settings_nav_provider_osm),
            getString(R.string.settings_nav_provider_google)};
        list.choice(getString(R.string.settings_nav_map_provider), getString(R.string.settings_nav_map_provider_hint),
            providerLabels, new int[]{0, 1},
            () -> Arrays.asList(LauncherConfig.NAV_PROVIDERS).indexOf(LauncherConfig.navMapProvider(this)),
            i -> LauncherConfig.setNavMapProvider(this, LauncherConfig.NAV_PROVIDERS[i]))
            .local().help(getString(R.string.settings_help_nav_map_provider));
        // A chave vem só do build (k4811.googleMapsKey no local.properties): aqui só o estado dela.
        OptionList.Row googleKey = list.plain(getString(R.string.settings_nav_google_key),
            getString(R.string.settings_nav_google_key_hint))
            .help(getString(R.string.settings_help_nav_google_key));
        list.toggle(getString(R.string.settings_nav_google_traffic), getString(R.string.settings_nav_google_traffic_hint),
            () -> LauncherConfig.navGoogleTraffic(this),
            on -> LauncherConfig.setNavGoogleTraffic(this, on))
            .visibleWhen(this::googleOn)
            .local().help(getString(R.string.settings_help_nav_google_traffic));
        OptionList.Row googleUsage = list.plain(getString(R.string.settings_nav_google_usage), null)
            .visibleWhen(this::googleOn)
            .help(getString(R.string.settings_help_nav_google_usage));

        // Volumes relativos ao volume atual (common/.../audio/MixVolumes), de 10 em 10%.
        list.section(getString(R.string.settings_nav_audio_section));
        list.stepper(getString(R.string.settings_nav_media_volume), getString(R.string.settings_nav_media_volume_hint),
            () -> 0, () -> 10,
            () -> Math.round(MixVolumes.navMedia(this) / 10f),
            step -> {
                LauncherConfig.setNavMediaPercent(this, step * 10);
                MixVolumes.applyNavMedia(step * 10);
            },
            step -> (step * 10) + "%")
            .local().help(getString(R.string.settings_help_nav_media_volume));
        list.stepper(getString(R.string.settings_notify_volume), getString(R.string.settings_notify_volume_hint),
            () -> 0, () -> 10,
            () -> Math.round(MixVolumes.notification(this) / 10f),
            step -> {
                LauncherConfig.setNotifyPercent(this, step * 10);
                MixVolumes.applyNotification(this, step * 10);
            },
            step -> (step * 10) + "%")
            .local().help(getString(R.string.settings_help_notify_volume));

        // Dados das vias do mapa integrado (baixados pelo launcher, ver map/.../road/RoadSync).
        list.section(getString(R.string.settings_nav_alerts_section));
        list.toggle(getString(R.string.settings_nav_home_cameras), getString(R.string.settings_nav_home_cameras_hint),
            () -> LauncherConfig.navCameras(this),
            on -> LauncherConfig.setNavCameras(this, on))
            .local().help(getString(R.string.settings_help_nav_home_cameras));
        list.toggle(getString(R.string.settings_nav_home_speed_limit), getString(R.string.settings_nav_home_speed_limit_hint),
            () -> LauncherConfig.navSpeedLimit(this),
            on -> LauncherConfig.setNavSpeedLimit(this, on))
            .local().help(getString(R.string.settings_help_nav_home_speed_limit));
        list.toggle(getString(R.string.settings_nav_home_road_alerts), getString(R.string.settings_nav_home_road_alerts_hint),
            () -> LauncherConfig.navRoadAlerts(this),
            on -> LauncherConfig.setNavRoadAlerts(this, on))
            .local().help(getString(R.string.settings_help_nav_home_road_alerts));
        String[] dayLabels = {getString(R.string.settings_nav_home_data_day),
            getString(R.string.settings_nav_home_data_week), getString(R.string.settings_nav_home_data_month)};
        OptionList.Row roadData = list.choice(getString(R.string.settings_nav_home_data_days), null,
            dayLabels, LauncherConfig.NAV_DATA_DAYS,
            () -> LauncherConfig.navDataDays(this),
            days -> {
                LauncherConfig.setNavDataDays(this, days);
                checkMapData();
            })
            .local();
        // Raio de tudo o que é baixado (vias, mapa, rota, endereços); o launcher confere de novo ao mudar.
        String[] kmLabels = new String[LauncherConfig.NAV_DATA_KM.length];
        for (int i = 0; i < kmLabels.length; i++) {
            kmLabels[i] = getString(R.string.settings_nav_data_km_value, LauncherConfig.NAV_DATA_KM[i]);
        }
        list.choice(getString(R.string.settings_nav_data_km), getString(R.string.settings_nav_data_km_hint),
            kmLabels, LauncherConfig.NAV_DATA_KM,
            () -> LauncherConfig.navDataKm(this),
            km -> {
                LauncherConfig.setNavDataKm(this, km);
                checkMapData();
            })
            .local().help(getString(R.string.settings_help_nav_data_km));

        // App externo: automático + os apps de navegação instalados agora.
        list.section(getString(R.string.settings_nav_external_section));
        List<String> apps = NavApps.installed(this);
        String[] appLabels = new String[apps.size() + 1];
        int[] appValues = new int[apps.size() + 1];
        appLabels[0] = getString(R.string.settings_nav_home_app_auto);
        for (int i = 0; i < apps.size(); i++) {
            appLabels[i + 1] = NavApps.label(this, apps.get(i));
            appValues[i + 1] = i + 1;
        }
        list.choice(getString(R.string.settings_nav_home_app), getString(R.string.settings_nav_home_app_hint),
            appLabels, appValues,
            () -> {
                String pkg = LauncherConfig.navApp(this);
                return pkg == null ? 0 : apps.indexOf(pkg) + 1;
            },
            i -> LauncherConfig.setNavApp(this, i == 0 ? null : apps.get(i - 1)))
            .local().help(getString(R.string.settings_help_nav_home_app));

        list.setAfterRender(() -> {
            boolean on = NavApps.mediaAccess(this);
            access.setNote(getString(on ? R.string.settings_nav_home_media_access_on
                : R.string.settings_nav_home_media_access_off), !on);
            // Textos com o raio escolhido (mudam junto com a opção do raio).
            int km = LauncherConfig.navDataKm(this);
            int move = Math.max(8, km * 3 / 10);
            roadData.setSubtitle(getString(R.string.settings_nav_home_data_days_hint, km));
            roadData.help(getString(R.string.settings_help_nav_home_data_days, km, move));
            offlineMap.setSubtitle(getString(R.string.settings_nav_offline_map_hint, km));
            offlineMap.help(getString(R.string.settings_help_nav_offline_map, km));
            roadData.setNote(dataStatus("roads"), false);
            // Mapa, grafo da rota e índice de endereços: o tamanho é a soma dos três.
            offlineMap.setNote(dataStatus("maps", "graph", "places"), false);
            String key = com.k4811.common.BuildConfig.GOOGLE_MAPS_KEY == null ? ""
                : com.k4811.common.BuildConfig.GOOGLE_MAPS_KEY.trim();
            googleKey.setNote(key.isEmpty() ? getString(R.string.settings_nav_google_key_none)
                : getString(R.string.settings_nav_google_key_set, key.substring(Math.max(0, key.length() - 4))),
                key.isEmpty() && googleOn());
            googleUsage.setSubtitle(googleUsage());
            googleUsage.setNote(googleError(), true);
        });
    }

    private boolean googleOn() {
        return LauncherConfig.NAV_PROVIDER_GOOGLE.equals(LauncherConfig.navMapProvider(this));
    }

    /**
     * "Mapa 1.234/90.000 · Rotas 12/4.500 + 0/9.000 · Busca 40/9.000 · Lugares 8/9.000", do uso gravado
     * pelo launcher em K4811/nav/google/usage.json (map/.../google/GoogleQuota).
     */
    private String googleUsage() {
        JSONObject j = NwdConfigs.read(new File(Environment.getExternalStorageDirectory(), "K4811/nav/google/usage.json"));
        JSONObject used = j == null ? null : j.optJSONObject("used");
        JSONObject limits = j == null ? null : j.optJSONObject("limits");
        // O mês da cobrança do Google (fuso de Los Angeles), como o GoogleQuota grava.
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
        String month = fmt.format(new java.util.Date());
        if (used == null || limits == null || !month.equals(j.optString("month"))) {
            return getString(R.string.settings_nav_google_usage_none);
        }
        return getString(R.string.settings_nav_google_usage_value,
            pair(used, limits, "tiles"), pair(used, limits, "routes_pro"), pair(used, limits, "routes"),
            pair(used, limits, "autocomplete"), pair(used, limits, "details"));
    }

    private static String pair(JSONObject used, JSONObject limits, String id) {
        java.text.NumberFormat f = java.text.NumberFormat.getIntegerInstance();
        return f.format(used.optInt(id, 0)) + "/" + f.format(limits.optInt(id, 0));
    }

    /** Último erro do Google (chave recusada, cota), se ainda está suspenso; null se não. */
    private String googleError() {
        JSONObject j = NwdConfigs.read(new File(Environment.getExternalStorageDirectory(), "K4811/nav/google/usage.json"));
        if (j == null || j.optLong("blocked_until", 0) < System.currentTimeMillis()) return null;
        String e = j.optString("last_error", "");
        return e.isEmpty() ? null : getString(R.string.settings_nav_google_error, e);
    }

    /**
     * "52 áreas, 4,5 MB, conferido em 27/10/2026", do estado gravado pelo launcher (RoadSync) em
     * K4811/nav/{@code pack}/state.json ("roads" = dados das vias, "maps" = mapa offline, "graph" = grafo
     * da rota, "places" = índice de endereços). Com mais de um pacote, as áreas e a data são as do
     * primeiro e o tamanho é a soma.
     */
    private String dataStatus(String... packs) {
        JSONObject state = state(packs[0]);
        JSONObject cells = state == null ? null : state.optJSONObject("cells");
        if (cells == null || cells.length() == 0) return getString(R.string.settings_nav_home_data_none);
        long updated = state.optLong("updated", 0);
        long bytes = 0;
        for (String pack : packs) {
            JSONObject s = state(pack);
            if (s != null) bytes += s.optLong("bytes", 0);
        }
        String when = updated > 0 ? android.text.format.DateFormat.getDateFormat(this).format(new java.util.Date(updated)) : "—";
        return getString(R.string.settings_nav_home_data_status, cells.length(),
            android.text.format.Formatter.formatShortFileSize(this, bytes), when);
    }

    private static JSONObject state(String pack) {
        return NwdConfigs.read(new File(Environment.getExternalStorageDirectory(), "K4811/nav/" + pack + "/state.json"));
    }

    /** O launcher confere já os dados baixados com a opção nova (raio, período, mapa offline). */
    private void checkMapData() {
        Intent check = new Intent(Apps.ACTION_NAV_DATA_CHECK);
        check.setPackage(Apps.LAUNCHER);
        // A NWD dá force-stop em apps: sem a flag, o launcher parado não recebe.
        check.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(check);
    }

    private void openNavHome() {
        Intent nav = new Intent(Apps.ACTION_NAV_HOME);
        nav.setPackage(Apps.LAUNCHER);
        nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(nav);
        } catch (RuntimeException e) {
            // Launcher antigo, sem a tela de navegação.
        }
    }

    @Override
    protected void onDestroy() {
        list.release();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        // Volta da tela de acesso a notificações: atualiza a nota.
        list.renderAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }
}
