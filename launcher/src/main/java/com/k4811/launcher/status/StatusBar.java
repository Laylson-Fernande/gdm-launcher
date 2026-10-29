package com.k4811.launcher.status;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.k4811.common.config.DayNight;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.NwdSettings;
import com.k4811.common.system.SysMonitor;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;
import com.k4811.launcher.R;

import java.util.Locale;

/**
 * Indicadores da barra de cima, iguais na tela inicial e na de navegação:
 * <ul>
 * <li><b>Dia/noite</b> ({@code show_day_night}): sol de dia, lua à noite ({@link DayNight}: com o farol
 * aceso ou pelo horário, conforme as Configurações). Tocar abre a barra de brilho ({@link BrightnessPopup}).</li>
 * <li><b>Voltímetro</b> ({@code show_voltmeter}): tensão da bateria pela CAN, pedida a cada 10 s; sem
 * resposta (carro sem CAN), fica escondido.</li>
 * <li><b>RAM e CPU</b> ({@code show_sys_monitor}): a cada 2 s ({@link SysMonitor}).</li>
 * </ul>
 * Os ids no layout: {@code day_night}, {@code voltage} (com {@code voltage_icon} e {@code voltage_text})
 * e {@code sys_monitor}. Quem usa chama {@link #start()} quando a tela aparece e {@link #stop()} quando sai.
 */
public final class StatusBar {

    private static final String ACTION_QUERY_VOLTAGE = "com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE";
    private static final String ACTION_VOLTAGE = "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE";
    private static final String EXTRA_VOLTAGE = "extra_voltage_value";
    private static final long VOLTAGE_PERIOD_MS = 10000;
    private static final long SYS_MONITOR_PERIOD_MS = 2000;

    private final Activity activity;
    /** Área onde a barra de brilho se centraliza (a tela inteira ou o painel da navegação). */
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IconView dayNight;
    private final View voltage;
    private final TextView voltageText;
    private final TextView sysMonitorView;
    private final SysMonitor sysMonitor;
    private BrightnessPopup brightness;
    private boolean started;
    /** Modo escuro trocado pela barra de brilho: refaz a tela (padrão: recreate). */
    private Runnable onLookChanged;

    private final Runnable voltageTick = new Runnable() {
        @Override
        public void run() {
            try {
                activity.sendBroadcast(new Intent(ACTION_QUERY_VOLTAGE));
            } catch (Exception ignored) {
                // Sem o sistema da NWD: o voltímetro só fica escondido.
            }
            handler.postDelayed(this, VOLTAGE_PERIOD_MS);
        }
    };

    private final Runnable sysMonitorTick = new Runnable() {
        @Override
        public void run() {
            SysMonitor.Sample s = sysMonitor.sample();
            String text = s.cpuPercent < 0
                ? activity.getString(R.string.home_sys_ram, s.ramPercent)
                : activity.getString(R.string.home_sys_ram_cpu, s.ramPercent, s.cpuPercent);
            if (!text.contentEquals(sysMonitorView.getText())) sysMonitorView.setText(text);
            handler.postDelayed(this, SYS_MONITOR_PERIOD_MS);
        }
    };

    private final BroadcastReceiver voltageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!LauncherConfig.showVoltmeter(activity)) return;
            int raw = intent.getIntExtra(EXTRA_VOLTAGE, 0);
            float volts = Math.abs(raw) / 10f;
            if (volts <= 0f) return; // resposta sem valor: fica o anterior
            volts = Math.max(8f, Math.min(18f, volts));
            voltageText.setText(String.format(Locale.getDefault(), "%.1f V", volts));
            if (voltage.getVisibility() != View.VISIBLE) {
                voltage.setVisibility(View.VISIBLE);
                Touch.fadeIn(voltage);
            }
        }
    };

    /** Farol aceso ou apagado: sol ⇄ lua. */
    private final ContentObserver headlightObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            renderDayNight();
        }
    };

    /** Troca por horário. */
    private final DayNightWatcher.Listener dayNightListener = n -> renderDayNight();

    public StatusBar(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        dayNight = (IconView) root.findViewById(R.id.day_night);
        voltage = root.findViewById(R.id.voltage);
        voltageText = (TextView) root.findViewById(R.id.voltage_text);
        sysMonitorView = (TextView) root.findViewById(R.id.sys_monitor);
        sysMonitor = new SysMonitor(activity);
        voltageText.setTypeface(Ui.medium(activity));
        sysMonitorView.setTypeface(Ui.medium(activity));
        ((IconView) root.findViewById(R.id.voltage_icon)).set(IconView.Icon.BATTERY, IconView.Style.NONE)
            .color(Ui.TEXT_MUTED);
        dayNight.setOnClickListener(v -> toggleBrightness());
        onLookChanged = activity::recreate;
    }

    /** O que fazer quando o modo escuro muda pela barra de brilho (a navegação não recria navegando). */
    public void setOnLookChanged(Runnable r) {
        onLookChanged = r;
    }

    /** Tela visível: liga o que estiver ativo nas Configurações. Pode ser chamado de novo (relê as opções). */
    public void start() {
        if (!started) {
            started = true;
            activity.registerReceiver(voltageReceiver, new IntentFilter(ACTION_VOLTAGE));
            activity.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(NwdSettings.KEY_NO_SOURCE_DEVICE), false, headlightObserver);
            DayNightWatcher.get(activity).addListener(dayNightListener);
        }
        handler.removeCallbacks(voltageTick);
        if (LauncherConfig.showVoltmeter(activity)) handler.post(voltageTick);
        else voltage.setVisibility(View.GONE);
        handler.removeCallbacks(sysMonitorTick);
        boolean monitor = LauncherConfig.showSysMonitor(activity);
        sysMonitorView.setVisibility(monitor ? View.VISIBLE : View.GONE);
        if (monitor) handler.post(sysMonitorTick);
        renderDayNight();
    }

    /** Tela fora: para os pedidos periódicos e fecha a barra de brilho. */
    public void stop() {
        handler.removeCallbacksAndMessages(null);
        if (brightness != null) brightness.dismiss();
        if (!started) return;
        started = false;
        try {
            activity.unregisterReceiver(voltageReceiver);
        } catch (Exception ignored) {
            // já estava fora
        }
        activity.getContentResolver().unregisterContentObserver(headlightObserver);
        DayNightWatcher.get(activity).removeListener(dayNightListener);
    }

    private void renderDayNight() {
        if (!LauncherConfig.showDayNight(activity)) {
            dayNight.setVisibility(View.GONE);
            return;
        }
        boolean night = DayNight.isNight(activity);
        dayNight.set(night ? IconView.Icon.MOON : IconView.Icon.SUN, IconView.Style.NONE).color(Ui.TEXT_SOFT);
        dayNight.setContentDescription(activity.getString(night ? R.string.home_night : R.string.home_day));
        dayNight.setVisibility(View.VISIBLE);
    }

    private void toggleBrightness() {
        if (brightness == null) brightness = new BrightnessPopup(activity, () -> onLookChanged.run());
        if (brightness.isShowing()) brightness.dismiss();
        else brightness.show(dayNight, root);
    }
}
