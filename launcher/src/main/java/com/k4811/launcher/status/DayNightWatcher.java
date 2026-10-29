package com.k4811.launcher.status;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.k4811.common.Apps;
import com.k4811.common.config.DayNight;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.Brightness;
import com.k4811.common.system.NwdSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Acompanha o dia e a noite ({@link DayNight}) no processo do launcher, que fica sempre vivo:
 * <ul>
 * <li><b>Brilho por horário</b> ("Por horário" e a noite muda o brilho): na troca, guarda o brilho em uso
 * como o do período que acabou e aplica o do outro (guardados aqui, no SharedPreferences do launcher; o
 * da noite começa pelo brilho noturno da MCU). Assim o ajuste feito pela barra de brilho vale para o
 * período em que foi feito. Com o farol, quem escurece é a própria MCU ("Escurecer com o farol").</li>
 * <li><b>Tema</b> (o tema segue o dia e a noite): só na <b>troca</b> de período (horário marcado ou
 * farol mudando) pede ao car-setting o modo escuro do novo período ({@link Apps#ACTION_SET_DARK_MODE}),
 * mesmo que já esteja assim. Entre as trocas vale a escolha do motorista (interruptor da barra de
 * brilho, Configurações): ganha sempre a última. O período visto fica guardado, então uma troca que
 * aconteceu com a central desligada é aplicada ao ligar. Quando o arquivo muda, avisa as telas
 * ({@link #addListener}), que se recriam.</li>
 * </ul>
 * Confere a cada minuto (TIME_TICK, que também pega mudanças das Configurações e do relógio) e quando
 * o farol muda.
 */
public final class DayNightWatcher {

    public interface Listener {
        void onDayNightChanged(boolean night);
    }

    private static final String TAG = "K4811DayNight";
    private static final String PREFS = "day_night";
    /** Período cujo brilho está aplicado: "day", "night" ou nenhum (brilho por horário desligado). */
    private static final String KEY_APPLIED = "applied";
    private static final String KEY_DAY = "brightness_day";
    private static final String KEY_NIGHT = "brightness_night";
    /** Último período visto para o tema ("day"/"night"): só a troca dele muda o modo escuro. */
    private static final String KEY_THEME_PERIOD = "theme_period";
    private static final long DARK_POLL_MS = 100;
    private static final long DARK_TIMEOUT_MS = 3000;

    private static DayNightWatcher instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();
    private Boolean night;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            check();
        }
    };

    private final ContentObserver headlights = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            check();
        }
    };

    public static synchronized DayNightWatcher get(Context c) {
        if (instance == null) instance = new DayNightWatcher(c.getApplicationContext());
        return instance;
    }

    private DayNightWatcher(Context c) {
        context = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        c.registerReceiver(tick, f);
        c.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(NwdSettings.KEY_NO_SOURCE_DEVICE), false, headlights);
        // O SettingService conecta logo depois do boot: a primeira conferência espera um pouco.
        handler.postDelayed(this::check, 3000);
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Confere agora (ex.: uma tela voltou). */
    public void check() {
        boolean now = DayNight.isNight(context);
        applyBrightness(now);
        applyTheme(now);
        if (night != null && night == now) return;
        boolean first = night == null;
        night = now;
        if (first) return;
        Log.d(TAG, now ? "noite" : "dia");
        notifyListeners(now);
    }

    private void notifyListeners(boolean isNight) {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onDayNightChanged(isNight);
    }

    /** Troca de período com o tema seguindo o dia e a noite: pede o modo escuro do novo período. */
    private void applyTheme(boolean isNight) {
        String period = isNight ? "night" : "day";
        if (period.equals(prefs.getString(KEY_THEME_PERIOD, null))) return;
        prefs.edit().putString(KEY_THEME_PERIOD, period).apply();
        if (!LauncherConfig.dayNightTheme(context)) return;
        Log.d(TAG, "tema do período: " + (isNight ? "escuro" : "claro"));
        Intent i = new Intent(Apps.ACTION_SET_DARK_MODE);
        i.setPackage(Apps.SETTINGS);
        // A NWD para apps à força (NwdProcessAdjuster); parado, o car-setting só recebe com esta flag.
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        i.putExtra(Apps.EXTRA_ON, isNight);
        context.sendBroadcast(i);
        waitDark(isNight, System.currentTimeMillis());
    }

    /** O car-setting grava em seguida: quando o arquivo mudar, as telas se recriam. */
    private void waitDark(boolean want, long since) {
        if (LauncherConfig.darkMode(context) == want) {
            notifyListeners(want);
            return;
        }
        if (System.currentTimeMillis() - since > DARK_TIMEOUT_MS) return;
        handler.postDelayed(() -> waitDark(want, since), DARK_POLL_MS);
    }

    private void applyBrightness(boolean isNight) {
        boolean active = DayNight.byTime(context) && LauncherConfig.dayNightBrightness(context);
        String applied = prefs.getString(KEY_APPLIED, "");
        String want = !active ? "" : isNight ? "night" : "day";
        if (want.equals(applied)) return;
        int current = Brightness.get(context);
        int day = prefs.getInt(KEY_DAY, current);
        int nightValue = prefs.getInt(KEY_NIGHT, Brightness.nightDefault(context));
        // O brilho em uso é o do período que está acabando (inclui o ajuste feito pela barra); sem
        // período aplicado, é o brilho normal, o do dia.
        if ("night".equals(applied)) nightValue = current;
        else day = current;
        // Desligado: volta o brilho do dia.
        int target = "night".equals(want) ? nightValue : day;
        prefs.edit().putInt(KEY_DAY, day).putInt(KEY_NIGHT, nightValue).putString(KEY_APPLIED, want).apply();
        if (target != current) {
            Log.d(TAG, "brilho " + (want.isEmpty() ? "normal" : want) + ": " + current + " → " + target);
            Brightness.set(context, target);
        }
    }
}
