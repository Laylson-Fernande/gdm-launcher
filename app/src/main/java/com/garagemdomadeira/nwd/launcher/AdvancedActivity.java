package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.system.ExternalApps;
import com.garagemdomadeira.nwd.launcher.system.NwdSettings;
import com.garagemdomadeira.nwd.launcher.system.SystemProps;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.OptionList;
import com.garagemdomadeira.nwd.launcher.ui.PropRows;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced (behind a password in Settings): factory options, props whose effect hasn't been
 * confirmed yet, development and shortcuts to the NWD menus. Each option shows the current value and,
 * after applying, ✓ or "not applied" with the adb command. No dock.
 * Anything that could leave the unit without sound, picture or apps is kept out even here.
 */
public class AdvancedActivity extends Activity {

    private static final String ACTION_PRINT_LOG = "com.nwd.action.ACTION_PRINT_LOG_ENABLE";
    private static final String ACTION_HAND_BRAKE = "com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE";
    private static final String ACTION_BACKCAR_SETUP = "com.android.action_update_backcar_setup";
    private static final String PROP_ADB_PORT = "service.adb.tcp.port";
    private static final String PKG_ZLINK = "com.zjinnova.zlink";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OptionList list;

    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(rerender);
            handler.postDelayed(rerender, 200);
        }
    };
    private final Runnable rerender = () -> {
        if (!isFinishing()) list.renderAll();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.applyFont(findViewById(R.id.settings_root));
        TextView title = (TextView) findViewById(R.id.settings_title);
        title.setText(R.string.adv_title);
        title.setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.settings_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        TextView alert = (TextView) findViewById(R.id.settings_alert);
        alert.setText(R.string.adv_alert);
        alert.setTextColor(Ui.WARNING);
        alert.setVisibility(View.VISIBLE);

        list = new OptionList(this, (LinearLayout) findViewById(R.id.settings_content));
        buildShortcuts();
        buildDevelopment();
        buildFactoryAudio();
        buildCarPlay();
        buildCar();
        build360Factory();
        buildCarOn();
        list.renderAll();

        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, observer);
        Touch.install(findViewById(android.R.id.content));
    }

    // ------------------------------------------------------------------ shortcuts

    private void buildShortcuts() {
        String open = getString(R.string.adv_open);
        list.section(getString(R.string.adv_sec_shortcuts));
        list.action(getString(R.string.adv_car_settings), getString(R.string.adv_car_settings_sub), open,
            () -> ExternalApps.openCarSettings(this, null))
            .visibleWhen(() -> ExternalApps.installed(this, ExternalApps.PKG_CAR_SETTING));
        list.action(getString(R.string.adv_factory_menu),
            getString(R.string.adv_factory_menu_sub, ExternalApps.factoryPassword(this)), open,
            () -> ExternalApps.openFactoryMenu(this))
            .visibleWhen(() -> ExternalApps.installed(this, ExternalApps.PKG_FACTORY));
        list.action(getString(R.string.adv_android_settings), null, open, () -> ExternalApps.openAndroidSettings(this));
        list.action(getString(R.string.adv_camera_360), null, open, () -> ExternalApps.open360(this))
            .visibleWhen(() -> ExternalApps.has360(this));
        list.action(getString(R.string.adv_360_settings), getString(R.string.adv_360_settings_sub), open,
            () -> ExternalApps.open360Settings(this))
            .visibleWhen(() -> ExternalApps.hasAvm(this));
    }

    // ------------------------------------------------------------------ development

    private void buildDevelopment() {
        list.section(getString(R.string.adv_sec_development));
        list.toggle(getString(R.string.adv_adb_wifi), getString(R.string.adv_adb_wifi_sub),
            () -> "5555".equals(SystemProps.get(PROP_ADB_PORT, "").trim()),
            on -> SystemProps.requestSet(this, PROP_ADB_PORT, on ? "5555" : "-1"))
            .hint(v -> PropRows.adb(PROP_ADB_PORT, "1".equals(v) ? "5555" : "-1"));
        logToggle(getString(R.string.adv_log_logcat), "logcat", "sys.nwd.logcat.log.enable");
        logToggle(getString(R.string.adv_log_kernel), "kernel", "sys.nwd.kernel.log.enable");
        list.action(getString(R.string.adv_system_props), getString(R.string.adv_system_props_sub),
            getString(R.string.adv_open),
            () -> startActivity(new Intent(this, DevPropsActivity.class)));
    }

    private void logToggle(String title, String type, String stateProp) {
        list.toggle(title, getString(R.string.adv_log_sub),
            () -> "1".equals(SystemProps.get(stateProp, "").trim()),
            on -> {
                Intent i = new Intent(ACTION_PRINT_LOG);
                i.putExtra("extra_log_type", type);
                i.putExtra("extra_log_enable", on ? 1 : 0);
                sendBroadcast(i);
            });
    }

    // ------------------------------------------------------------------ factory audio

    private void buildFactoryAudio() {
        list.section(getString(R.string.adv_sec_factory_audio));
        PropRows.toggle(this, list, getString(R.string.adv_phase), getString(R.string.adv_phase_sub),
            "persist.nwd.audiophase", "1", "0", true);
        PropRows.toggle(this, list, getString(R.string.adv_dual_mic),
            getString(R.string.adv_dual_mic_sub),
            "persist.sys.nwd.double.mic", "1", "0", true);
        PropRows.edit(this, list, getString(R.string.adv_music_max), getString(R.string.adv_music_max_sub),
            "persist.sys.nwd.streammusic.max", PropRows.range(this, 5, 15))
            .visibleWhen(() -> !SystemProps.get("ro.nwd.allow.streammusic.max", "").trim().isEmpty());
        PropRows.edit(this, list, getString(R.string.adv_bt_unmute), getString(R.string.adv_bt_unmute_sub),
            "persist.nwd.unmute.bt.delay", PropRows.range(this, 0, 5000));
    }

    // ------------------------------------------------------------------ CarPlay

    private void buildCarPlay() {
        list.section(getString(R.string.adv_sec_carplay));
        OptionList.Check zlink = () -> ExternalApps.installed(this, PKG_ZLINK);
        list.note(getString(R.string.adv_carplay_note), Ui.TEXT_MUTED);
        PropRows.edit(this, list, getString(R.string.adv_mic_gain), null, "persist.zj.apm.micgain",
            PropRows.range(this, 0, 100))
            .visibleWhen(zlink);
        PropRows.edit(this, list, getString(R.string.adv_speaker_gain), null, "persist.zj.apm.speakergain",
            PropRows.range(this, 0, 100)).visibleWhen(zlink);
        PropRows.toggle(this, list, getString(R.string.adv_noise), getString(R.string.adv_noise_sub),
            "persist.zj.apm.rnn", "true", "false", true).visibleWhen(zlink);
        PropRows.edit(this, list, getString(R.string.adv_aec_type), null, "persist.zj.apm.aectype",
            PropRows.range(this, 0, 10)).visibleWhen(zlink);
        PropRows.edit(this, list, getString(R.string.adv_aec_delay), getString(R.string.adv_in_ms),
            "persist.zj.apm.delay",
            PropRows.range(this, 0, 2000)).visibleWhen(zlink);
    }

    // ------------------------------------------------------------------ car

    private Integer mcu(String key) {
        return NwdSettings.has(this, key) ? NwdSettings.getInt(this, key, 0) : null;
    }

    private void buildCar() {
        list.section(getString(R.string.adv_sec_car));
        String[] regions = getResources().getStringArray(R.array.adv_radio_regions);
        // Regions 8..11 only exist with the matching bit set in mcu_radio_area_feature.
        int extra = NwdSettings.getInt(this, "mcu_radio_area_feature", 0);
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int r = 0; r < regions.length; r++) {
            if (r >= 8 && (extra & (256 << (r - 8))) == 0) continue;
            labels.add(regions[r]);
            values.add(r);
        }
        int[] vals = new int[values.size()];
        for (int i = 0; i < vals.length; i++) vals[i] = values.get(i);
        list.choice(getString(R.string.adv_radio_region), getString(R.string.adv_radio_region_sub),
            labels.toArray(new String[0]), vals, () -> mcu("mcu_radio_area_current"), NwdSettings::setRadioRegion);

        list.toggle(getString(R.string.adv_hand_brake), getString(R.string.adv_hand_brake_sub),
            () -> NwdSettings.systemBit(this, NwdSettings.BIT_HAND_BRAKE),
            on -> {
                NwdSettings.setSystemFeature(NwdSettings.FEATURE_HAND_BRAKE, on ? 1 : 0);
                sendBroadcast(new Intent(ACTION_HAND_BRAKE));
            });
        list.toggle(getString(R.string.adv_any_key_power), null,
            () -> NwdSettings.systemBit(this, NwdSettings.BIT_ANY_KEY_POWER),
            on -> NwdSettings.setSystemFeature(NwdSettings.FEATURE_ANY_KEY_POWER, on ? 1 : 0));
        list.toggle(getString(R.string.adv_auto_wakeup), getString(R.string.adv_auto_wakeup_sub),
            () -> "enable".equals(SystemProps.get("persist.nwd.autowakeup", "").trim()),
            NwdSettings::setAutoWakeup)
            .visibleWhen(() -> NwdSettings.isInstalled(this));
        backcarToggle(getString(R.string.adv_backcar_track), "factory_backcar_track_switch");
        backcarToggle(getString(R.string.adv_backcar_assist), "factory_backcar_assist_line_switch");
        list.info(getString(R.string.adv_autorun), () -> {
            String v = NwdSettings.getString(this, "appautorun_packagename");
            return v == null || v.isEmpty() || "[]".equals(v) ? getString(R.string.adv_autorun_none) : v;
        }).setSubtitle(getString(R.string.adv_autorun_sub));
    }

    private void backcarToggle(String title, String key) {
        list.toggle(title, getString(R.string.adv_backcar_sub),
            () -> NwdSettings.has(this, key) ? NwdSettings.getInt(this, key, 0) == 1 : null,
            on -> {
                NwdSettings.putInt(this, key, on ? 1 : 0);
                sendBroadcast(new Intent(ACTION_BACKCAR_SETUP));
            });
    }

    // ------------------------------------------------------------------ 360 (factory)

    private void build360Factory() {
        list.section(getString(R.string.adv_sec_360));
        OptionList.Check avm = () -> ExternalApps.hasAvm(this);
        list.note(getString(R.string.adv_360_note), Ui.TEXT_MUTED);
        list.action(getString(R.string.adv_360_app_settings), null, getString(R.string.adv_open),
            () -> ExternalApps.open360Settings(this))
            .visibleWhen(avm);
        list.action(getString(R.string.adv_calibration), getString(R.string.adv_calibration_sub),
            getString(R.string.adv_open),
            () -> ExternalApps.open360Calibration(this))
            .visibleWhen(avm);
    }

    // ------------------------------------------------------------------ on car start

    private void buildCarOn() {
        list.section(getString(R.string.adv_sec_car_on));
        list.note(getString(R.string.adv_car_on_note), Ui.TEXT_MUTED);
        PropRows.toggle(this, list, getString(R.string.adv_wake_wifi), null, "persist.sys.wake_start_wifi",
            "true", "false", false);
        PropRows.toggle(this, list, getString(R.string.adv_wake_hotspot), null, "persist.sys.wake_start_wifihot",
            "true", "false", false);
        PropRows.toggle(this, list, getString(R.string.adv_touch_wake), null, "persist.sys.power.touch",
            "1", "0", false);
        PropRows.toggle(this, list, getString(R.string.adv_multi_window), null, "persist.nwd.multi.window.enable",
            "true", "false", false);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        list.renderAll();
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacksAndMessages(null);
        list.release();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }
}
