package com.k4811.car.setting;

import android.content.Context;
import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.home.MusicApps;
import com.k4811.common.home.NavApps;
import com.k4811.common.ui.DockView;
import com.k4811.common.ui.IconView;
import com.k4811.car.setting.ui.OptionList;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

import java.util.List;
import java.util.Set;

/**
 * Ícones do dock (Configurações › Customizar › Customizar dock): Início, Navegação e Apps ficam sempre;
 * o motorista escolhe até {@link LauncherConfig#MAX_DOCK_TOGGLES} dos outros, e o nome do Z-Link.
 * Cada linha tem título, texto explicativo e botão de ajuda. Sem dock nesta tela.
 */
public class DockSettingsActivity extends Activity {

    /** Idioma da suíte no contexto da tela (sobrevive às mudanças de janela, ver Ui.withLanguage). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.withLanguage(base));
    }

    private OptionList list;
    private OptionList.Row counter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.applyFont(findViewById(R.id.settings_root));
        TextView title = (TextView) findViewById(R.id.settings_title);
        title.setText(R.string.settings_dock_section);
        title.setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.settings_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        list = new OptionList(this, (LinearLayout) findViewById(R.id.settings_content));
        build();
        list.setAfterRender(this::renderCounter);
        list.renderAll();
        Touch.install(findViewById(android.R.id.content));
    }

    private void build() {
        int max = LauncherConfig.MAX_DOCK_TOGGLES;
        counter = list.info(getString(R.string.settings_dock_items), () -> LauncherConfig.dockToggles(this).size()
            + " / " + max);
        counter.setSubtitle(getString(R.string.settings_dock_hint, max));
        counter.help(getString(R.string.settings_help_dock_items, max));

        // Altura do dock (com ícones e textos): a tela inicial redistribui os módulos no espaço que sobra.
        String[] sizes = {getString(R.string.settings_dock_size_minimum), getString(R.string.settings_dock_size_compact), getString(R.string.settings_dock_size_small),
            getString(R.string.settings_dock_size_normal), getString(R.string.settings_dock_size_large)};
        list.choice(getString(R.string.settings_dock_size), getString(R.string.settings_dock_size_hint),
            sizes, LauncherConfig.DOCK_SIZES,
            () -> LauncherConfig.dockSize(this),
            percent -> LauncherConfig.setDockSize(this, percent))
            .local().help(getString(R.string.settings_help_dock_size));

        for (DockView.Item item : DockView.Item.values()) {
            if (!item.toggleable) {
                OptionList.Row fixed = list.info(item.title(this), () -> getString(R.string.settings_always_visible));
                fixed.setSubtitle(getString(R.string.settings_dock_fixed_hint));
                fixed.help(getString(R.string.settings_help_dock_fixed));
                continue;
            }
            list.toggle(item.title(this), getString(R.string.settings_dock_item_hint),
                () -> LauncherConfig.dockToggles(this).contains(item),
                on -> {
                    Set<DockView.Item> set = LauncherConfig.dockToggles(this);
                    if (on) set.add(item);
                    else set.remove(item);
                    LauncherConfig.setDockToggles(this, set);
                })
                .local()
                .help(getString(R.string.settings_help_dock_item, max))
                .visibleWhen(() -> item.isAvailable(this))
                .enabledWhen(() -> {
                    Set<DockView.Item> set = LauncherConfig.dockToggles(this);
                    return set.contains(item) || set.size() < max;
                });
        }

        // Botão Música: o MP3 da suíte ou um player instalado (Spotify, YouTube Music…).
        List<String> players = MusicApps.installed(this);
        String[] playerLabels = new String[players.size() + 1];
        int[] playerValues = new int[players.size() + 1];
        playerLabels[0] = getString(R.string.settings_music_app_suite);
        for (int i = 0; i < players.size(); i++) {
            playerLabels[i + 1] = NavApps.label(this, players.get(i));
            playerValues[i + 1] = i + 1;
        }
        list.choice(getString(R.string.settings_music_app), getString(R.string.settings_music_app_hint),
            playerLabels, playerValues,
            () -> {
                String pkg = LauncherConfig.musicApp(this);
                return pkg == null ? 0 : players.indexOf(pkg) + 1;
            },
            i -> LauncherConfig.setMusicApp(this, i == 0 ? null : players.get(i - 1)))
            .local()
            .help(getString(R.string.settings_help_music_app))
            .visibleWhen(() -> !players.isEmpty());

        int[] values = new int[LauncherConfig.ZLINK_NAMES.length];
        for (int i = 0; i < values.length; i++) values[i] = i;
        list.choice(getString(R.string.settings_zlink_name), getString(R.string.settings_zlink_name_hint),
            LauncherConfig.ZLINK_NAMES, values,
            () -> LauncherConfig.zlinkName(this),
            i -> LauncherConfig.setZlinkName(this, i))
            .local()
            .help(getString(R.string.settings_help_zlink_name))
            .visibleWhen(() -> DockView.Item.ZLINK.isAvailable(this));
        // Aplicadas pelo launcher (launcher/.../home/ZlinkMonitor) quando o celular conecta.
        list.toggle(getString(R.string.settings_zlink_auto_connect), getString(R.string.settings_zlink_auto_connect_hint),
            () -> LauncherConfig.zlinkAutoConnect(this),
            on -> LauncherConfig.setZlinkAutoConnect(this, on))
            .local()
            .help(getString(R.string.settings_help_zlink_auto_connect))
            .visibleWhen(() -> DockView.Item.ZLINK.isAvailable(this));
        String[] openLabels = {getString(R.string.settings_zlink_open_no), getString(R.string.settings_zlink_open_yes),
            getString(R.string.settings_zlink_open_last)};
        list.choice(getString(R.string.settings_zlink_auto_open), getString(R.string.settings_zlink_auto_open_hint),
            openLabels, new int[]{LauncherConfig.ZLINK_OPEN_NO, LauncherConfig.ZLINK_OPEN_YES, LauncherConfig.ZLINK_OPEN_LAST},
            () -> LauncherConfig.zlinkAutoOpen(this),
            mode -> LauncherConfig.setZlinkAutoOpen(this, mode))
            .local()
            .help(getString(R.string.settings_help_zlink_auto_open))
            .visibleWhen(() -> DockView.Item.ZLINK.isAvailable(this));
        list.action(getString(R.string.settings_dock_reset), getString(R.string.settings_dock_reset_hint),
            getString(R.string.settings_reset), () -> {
            LauncherConfig.resetDockToggles(this);
            list.renderAll();
        }).help(getString(R.string.settings_help_dock_reset));
    }

    private void renderCounter() {
        int n = LauncherConfig.dockToggles(this).size();
        counter.setNote(n >= LauncherConfig.MAX_DOCK_TOGGLES
            ? getString(R.string.settings_dock_full, LauncherConfig.MAX_DOCK_TOGGLES) : null, false);
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
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }
}
