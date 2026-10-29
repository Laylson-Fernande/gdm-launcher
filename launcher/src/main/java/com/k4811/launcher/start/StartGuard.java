package com.k4811.launcher.start;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;

/**
 * A suíte na frente quando o carro liga.
 *
 * Os apps de fábrica que continuam instalados (no build pessoal, o player de música e o de música
 * Bluetooth da NWD) abrem a própria tela quando o sistema manda {@code com.nwd.ACTION_BACKGROUND_MEDIA_PLAY}
 * ao ligar o carro com aquela fonte, e ficam no lugar do launcher. Um app comum não sabe qual app
 * está na frente; por isso o vigia é limitado a esse momento: ao receber esse aviso, o boot ou a saída
 * do ACC ({@link StartReceiver}), reabre a tela de destino algumas vezes nos segundos seguintes
 * ({@link #REOPEN_MS}), por cima do que abriu sozinho.
 *
 * Destino: a última destas telas em uso antes de desligar ({@link #noteScreen}): tela inicial,
 * navegação, rádio, música Bluetooth ou MP3. As outras (gaveta, Configurações, telefone, EQ,
 * ventoinha, apps de fora) não contam: não mudam o destino. Sem nenhuma, ou com o app desinstalado,
 * a tela inicial.
 *
 * Para na hora em que o motorista mexe: toque numa tela do launcher ({@link #userActed}) ou outra
 * tela da suíte vindo para a frente. Opção {@code start_guard} (Configurações › Sistema).
 */
public final class StartGuard {

    private static final String TAG = "K4811Start";
    private static final String PREFS = "start_guard";
    private static final String KEY_LAST = "last_screen";
    private static final String KEY_MCU_STATE = "mcu_state";
    /** Depois do aviso: o app de fábrica abre logo, mas pode demorar (central acordando). */
    private static final long[] REOPEN_MS = {1200, 3000, 6000, 10000};
    /** Até quando uma tela da suíte vindo para a frente é a reabertura do vigia, não o motorista. */
    private static final long OWN_LAUNCH_MS = 1500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable REOPEN = StartGuard::reopen;

    private static Context app;
    private static int left;
    private static long lastLaunch;
    /** Telas do launcher na frente agora (resumed). */
    private static int launcherResumed;

    private StartGuard() {
    }

    /** Carro ligou (ou a central acordou) com uma fonte: começa a vigiar. */
    public static void start(Context c, String why) {
        app = c.getApplicationContext();
        if (!LauncherConfig.startGuard(app)) return;
        Log.d(TAG, "vigiando a tela da frente (" + why + "), destino " + target(app));
        MAIN.removeCallbacks(REOPEN);
        left = REOPEN_MS.length;
        MAIN.postDelayed(REOPEN, REOPEN_MS[0]);
    }

    /** O motorista tocou numa tela do launcher: ele decide o que fica na frente. */
    public static void userActed() {
        if (left == 0) return;
        stop("toque");
    }

    private static void stop(String why) {
        if (left > 0) Log.d(TAG, "parou de vigiar (" + why + ")");
        left = 0;
        MAIN.removeCallbacks(REOPEN);
    }

    /** Uma tela da suíte veio para a frente (do launcher ou de outro app, por {@code SCREEN_SHOWN}). */
    public static void noteScreen(Context c, String component) {
        if (component == null) return;
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) return;
        if (eligible(cn)) {
            SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!component.equals(prefs.getString(KEY_LAST, null))) {
                prefs.edit().putString(KEY_LAST, component).apply();
                Log.d(TAG, "última tela: " + cn.flattenToShortString());
            }
        }
        // Outra tela da suíte que não foi o vigia que abriu: é o motorista navegando.
        if (left > 0 && SystemClock.elapsedRealtime() - lastLaunch > OWN_LAUNCH_MS
            && !Apps.LAUNCHER.equals(cn.getPackageName())) {
            stop("outra tela da suíte");
        }
    }

    public static void launcherResumed(boolean resumed) {
        launcherResumed = Math.max(0, launcherResumed + (resumed ? 1 : -1));
    }

    /** Último estado da MCU guardado (1 = ligado, se nunca veio). */
    static int mcuState(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MCU_STATE, 1);
    }

    /** Estado da MCU guardado: o processo pode morrer entre "dormindo" (0) e "ligado" (1). */
    static boolean mcuWoke(Context c, int now) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int before = prefs.getInt(KEY_MCU_STATE, 1);
        prefs.edit().putInt(KEY_MCU_STATE, now).apply();
        return before == 0 && now == 1;
    }

    private static void reopen() {
        if (left <= 0 || app == null) return;
        left--;
        Intent i = target(app);
        boolean launcherTarget = Apps.LAUNCHER.equals(i.getPackage())
            || (i.getComponent() != null && Apps.LAUNCHER.equals(i.getComponent().getPackageName()));
        // Tela do launcher já na frente: nada a fazer agora (o app de fábrica pode abrir depois).
        if (!(launcherTarget && launcherResumed > 0)) {
            try {
                lastLaunch = SystemClock.elapsedRealtime();
                app.startActivity(i);
                Log.d(TAG, "reaberto: " + describe(i));
            } catch (Exception e) {
                Log.w(TAG, "não reabriu " + describe(i) + ": " + e);
            }
        }
        int done = REOPEN_MS.length - left;
        if (left > 0) MAIN.postDelayed(REOPEN, REOPEN_MS[done] - REOPEN_MS[done - 1]);
    }

    /** Telas que podem ser o destino: tela inicial, navegação, rádio, música BT e MP3. */
    private static boolean eligible(ComponentName cn) {
        String pkg = cn.getPackageName();
        if (Apps.LAUNCHER.equals(pkg)) {
            String cls = cn.getClassName();
            return cls.endsWith(".HomeActivity") || cls.endsWith(".NavHomeActivity");
        }
        return Apps.RADIO.equals(pkg) || Apps.BT_MUSIC.equals(pkg) || Apps.MUSIC.equals(pkg);
    }

    /** Intent da tela de destino (sempre com NEW_TASK). */
    static Intent target(Context c) {
        String last = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null);
        ComponentName cn = last == null ? null : ComponentName.unflattenFromString(last);
        Intent i = null;
        if (cn != null && eligible(cn)) {
            String pkg = cn.getPackageName();
            String cls = cn.getClassName();
            if (Apps.LAUNCHER.equals(pkg)) {
                if (cls.endsWith(".NavHomeActivity")) i = new Intent(Apps.ACTION_NAV_HOME).setPackage(pkg);
            } else {
                // Rádio, música BT ou MP3: a tela principal do app (o mesmo que o dock abre).
                i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            }
        }
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(Apps.LAUNCHER);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    private static String describe(Intent i) {
        if (i.getComponent() != null) return i.getComponent().flattenToShortString();
        return i.getAction() + (i.getPackage() == null ? "" : " " + i.getPackage());
    }
}
