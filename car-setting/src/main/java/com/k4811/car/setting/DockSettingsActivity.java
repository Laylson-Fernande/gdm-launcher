package com.k4811.car.setting;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.ui.DockView;
import com.k4811.common.ui.IconView;
import com.k4811.car.setting.ui.OptionList;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

import java.util.Set;

/**
 * Dock icons (Settings › Customize › Customize dock): Home and Apps always stay; the driver picks
 * up to {@link LauncherConfig#MAX_DOCK_TOGGLES} of the others, and the name the Z-Link item shows.
 * Items whose app or hardware doesn't exist on this head unit are hidden. No dock.
 */
public class DockSettingsActivity extends Activity {

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
        counter = list.info(getString(R.string.settings_dock_items), () -> LauncherConfig.dockToggles(this).size()
            + " / " + LauncherConfig.MAX_DOCK_TOGGLES);
        counter.setSubtitle(getString(R.string.settings_dock_hint, LauncherConfig.MAX_DOCK_TOGGLES));
        for (DockView.Item item : DockView.Item.values()) {
            if (!item.toggleable) {
                list.info(item.title(this), () -> getString(R.string.settings_always_visible));
                continue;
            }
            list.toggle(item.title(this), null,
                () -> LauncherConfig.dockToggles(this).contains(item),
                on -> {
                    Set<DockView.Item> set = LauncherConfig.dockToggles(this);
                    if (on) set.add(item);
                    else set.remove(item);
                    LauncherConfig.setDockToggles(this, set);
                })
                .local()
                .visibleWhen(() -> item.isAvailable(this))
                // With 5 enabled, the disabled ones stay greyed out until a slot frees up.
                .enabledWhen(() -> {
                    Set<DockView.Item> set = LauncherConfig.dockToggles(this);
                    return set.contains(item) || set.size() < LauncherConfig.MAX_DOCK_TOGGLES;
                });
        }
        // Z-Link's dock label: the app's name, or what's plugged in (CarPlay, Android Auto).
        int[] values = new int[LauncherConfig.ZLINK_NAMES.length];
        for (int i = 0; i < values.length; i++) values[i] = i;
        list.choice(getString(R.string.settings_zlink_name), getString(R.string.settings_zlink_name_hint),
            LauncherConfig.ZLINK_NAMES, values,
            () -> LauncherConfig.zlinkName(this),
            i -> LauncherConfig.setZlinkName(this, i))
            .local()
            .visibleWhen(() -> DockView.Item.ZLINK.isAvailable(this));
        list.action(getString(R.string.settings_dock_reset), getString(R.string.settings_dock_reset_hint),
            getString(R.string.settings_reset), () -> {
            LauncherConfig.resetDockToggles(this);
            list.renderAll();
        });
    }

    private void renderCounter() {
        int n = LauncherConfig.dockToggles(this).size();
        counter.setNote(n >= LauncherConfig.MAX_DOCK_TOGGLES
            ? getString(R.string.settings_dock_full, LauncherConfig.MAX_DOCK_TOGGLES) : null, false);
    }

    // ------------------------------------------------------------------ lifecycle

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
