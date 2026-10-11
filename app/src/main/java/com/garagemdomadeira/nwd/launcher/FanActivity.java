package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.ui.FanBladeView;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.Screens;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

/**
 * Cooling fan preferences. Only writes Settings.System; the system/MCU
 * drives the hardware. The fan blade follows {@code mcu_smart_fan_switch}.
 */
public class FanActivity extends Activity {

    private static final String KEY_AUTO = "fan_auto_state";
    private static final String KEY_MANUAL = "fan_manual_state";
    private static final String KEY_TEMP = "fan_execute_temp";
    private static final String KEY_AMP = "fan_with_amp";
    private static final String KEY_SWITCH = "mcu_smart_fan_switch";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView autoBtn;
    private TextView manualBtn;
    private TextView ampBtn;
    private TextView tempView;
    private TextView stateView;
    private FanBladeView blade;
    private int temp = 50;
    private boolean observed;

    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            renderSwitch();
            renderAmp();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fan);
        Ui.applyFont(findViewById(R.id.fan_root));
        // Not in the dock: opened only from the fan tile on the home screen.
        Screens.attachDock(this, null);
        bindViews();
        ensureDefaults();
        renderAll();
        Touch.install(findViewById(android.R.id.content));
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.fan_title_bar)).setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.fan_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        autoBtn = (TextView) findViewById(R.id.fan_auto);
        manualBtn = (TextView) findViewById(R.id.fan_manual);
        ampBtn = (TextView) findViewById(R.id.fan_amp);
        tempView = (TextView) findViewById(R.id.fan_temp);
        stateView = (TextView) findViewById(R.id.fan_state);
        blade = (FanBladeView) findViewById(R.id.fan_blade);
        tempView.setTypeface(Ui.semiBold(this));
        stateView.setTypeface(Ui.medium(this));

        autoBtn.setOnClickListener(v -> setAuto(!isOn(KEY_AUTO)));
        manualBtn.setOnClickListener(v -> setManual(!isOn(KEY_MANUAL)));
        ampBtn.setOnClickListener(v -> putFlag(KEY_AMP, !isOn(KEY_AMP)));
        findViewById(R.id.fan_temp_minus).setOnClickListener(v -> bumpTemp(-5));
        findViewById(R.id.fan_temp_plus).setOnClickListener(v -> bumpTemp(5));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        observe(true);
        renderAll();
    }

    @Override
    protected void onPause() {
        observe(false);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    private void setAuto(boolean on) {
        putFlag(KEY_AUTO, on);
        if (on) putFlag(KEY_MANUAL, false);
        renderModes();
    }

    private void setManual(boolean on) {
        putFlag(KEY_MANUAL, on);
        if (on) putFlag(KEY_AUTO, false);
        renderModes();
    }

    private void bumpTemp(int delta) {
        temp = Math.max(0, Math.min(100, temp + delta));
        putString(KEY_TEMP, String.valueOf(temp));
        renderTemp();
    }

    private void ensureDefaults() {
        if (readString(KEY_AUTO) == null) putFlag(KEY_AUTO, false);
        if (readString(KEY_MANUAL) == null) putFlag(KEY_MANUAL, false);
        if (readString(KEY_TEMP) == null) putString(KEY_TEMP, "50");
        if (readString(KEY_AMP) == null) putFlag(KEY_AMP, false);
        temp = readInt(KEY_TEMP, 50);
        temp = Math.max(0, Math.min(100, (temp / 5) * 5));
    }

    private void renderAll() {
        temp = readInt(KEY_TEMP, 50);
        renderModes();
        renderTemp();
        renderAmp();
        renderSwitch();
    }

    private void renderModes() {
        autoBtn.setActivated(isOn(KEY_AUTO));
        manualBtn.setActivated(isOn(KEY_MANUAL));
    }

    private void renderTemp() {
        tempView.setText(getString(R.string.media_fan_temp, temp));
    }

    private void renderAmp() {
        ampBtn.setActivated(isOn(KEY_AMP));
    }

    private void renderSwitch() {
        boolean on = isOn(KEY_SWITCH);
        blade.setSpinning(on);
        stateView.setText(on ? R.string.media_fan_on : R.string.media_fan_off);
        stateView.setTextColor(on ? Ui.ACCENT : Ui.TEXT_MUTED);
    }

    private void observe(boolean on) {
        if (on == observed) return;
        if (on) {
            try {
                getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY_SWITCH), false, observer);
                getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY_AMP), false, observer);
                observed = true;
            } catch (Exception ignored) {
            }
        } else {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {
            }
            observed = false;
        }
    }

    private boolean isOn(String key) {
        String v = readString(key);
        return "1".equals(v);
    }

    private void putFlag(String key, boolean on) {
        putString(key, on ? "1" : "0");
    }

    private void putString(String key, String value) {
        try {
            Settings.System.putString(getContentResolver(), key, value);
        } catch (Exception ignored) {
        }
    }

    private String readString(String key) {
        try {
            return Settings.System.getString(getContentResolver(), key);
        } catch (Exception e) {
            return null;
        }
    }

    private int readInt(String key, int fallback) {
        String v = readString(key);
        if (v == null || v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
