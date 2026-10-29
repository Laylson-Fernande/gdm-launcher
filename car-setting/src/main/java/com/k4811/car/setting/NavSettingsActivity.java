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
        OptionList.Row offlineMap = list.toggle(getString(R.string.settings_nav_offline_map),
            getString(R.string.settings_nav_offline_map_hint),
            () -> LauncherConfig.navOfflineMap(this),
            on -> LauncherConfig.setNavOfflineMap(this, on))
            .local().help(getString(R.string.settings_help_nav_offline_map));

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
        OptionList.Row roadData = list.choice(getString(R.string.settings_nav_home_data_days),
            getString(R.string.settings_nav_home_data_days_hint),
            dayLabels, LauncherConfig.NAV_DATA_DAYS,
            () -> LauncherConfig.navDataDays(this),
            days -> LauncherConfig.setNavDataDays(this, days))
            .local().help(getString(R.string.settings_help_nav_home_data_days));

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
            roadData.setNote(dataStatus("roads"), false);
            offlineMap.setNote(dataStatus("maps"), false);
        });
    }

    /**
     * "52 áreas, 4,5 MB, conferido em 27/10/2026", do estado gravado pelo launcher (RoadSync) em
     * K4811/nav/{@code pack}/state.json ("roads" = dados das vias, "maps" = mapa offline).
     */
    private String dataStatus(String pack) {
        JSONObject state = NwdConfigs.read(new File(Environment.getExternalStorageDirectory(), "K4811/nav/" + pack + "/state.json"));
        JSONObject cells = state == null ? null : state.optJSONObject("cells");
        if (cells == null || cells.length() == 0) return getString(R.string.settings_nav_home_data_none);
        long updated = state.optLong("updated", 0);
        String when = updated > 0 ? android.text.format.DateFormat.getDateFormat(this).format(new java.util.Date(updated)) : "—";
        return getString(R.string.settings_nav_home_data_status, cells.length(),
            android.text.format.Formatter.formatShortFileSize(this, state.optLong("bytes", 0)), when);
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
