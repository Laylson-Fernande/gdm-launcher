package com.k4811.launcher.nav;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Tela dividida do Android (7+) para a tela de navegação, como no Android Auto.
 *
 * A tela de navegação fica na metade "primária" e o app de navegação na outra. Assim ela controla
 * a divisão pela própria tarefa: cria uma nova para entrar e, ao fechar a sua, o Android volta à
 * tela cheia. Uma tarefa que já existe (a do app de navegação, por exemplo) nunca muda de modo ao
 * vir para a frente; ao dividir, o Android só a põe na outra metade.
 *
 * Não há API pública para entrar em tela dividida: usa {@code ActivityOptions.setLaunchWindowingMode}
 * (oculta; liberada para apps com targetSdk antigo). Quem chama confere o resultado com
 * {@link Activity#isInMultiWindowMode()}.
 */
public final class SplitScreen {
    private SplitScreen() {
    }

    private static final String TAG = "K4811Nav";
    /** Extra do pedido que {@link #enter} faz à própria tela (ela não deve reagir como a uma abertura nova). */
    public static final String EXTRA_ENTERING = "com.k4811.launcher.extra.SPLIT_ENTERING";
    /** WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY. */
    private static final int WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3;

    /** O Android deste aparelho tem tela dividida. */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    /**
     * Põe a própria tela ({@code self}) na metade primária (à esquerda, com a tela deitada; o
     * Android 10 ignora o lado pedido ao criar a tarefa). O Android só divide a tela ao criar uma
     * tarefa (uma que já existe vem para a frente sem mudar de modo), então {@code self} fecha a
     * tarefa dela e abre de novo, com o extra {@link #EXTRA_ENTERING}.
     * @return false se não deu nem para pedir (Android antigo, API indisponível); aí nada mudou
     */
    public static boolean enter(Activity self) {
        if (!supported()) return false;
        Bundle options = splitOptions();
        if (options == null) return false;
        Intent again = new Intent(self, self.getClass());
        again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        again.putExtra(EXTRA_ENTERING, true);
        Context app = self.getApplicationContext();
        removeTask(self);
        return start(app, again, options);
    }

    /**
     * Reabre {@code self} numa tarefa nova, sem tela dividida (a tarefa atual, dividida, é fechada e
     * o Android desfaz a divisão).
     */
    public static void restart(Activity self) {
        Intent again = new Intent(self, self.getClass());
        again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Context app = self.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 21) removeTask(self);
        else self.finish();
        start(app, again, null);
    }

    @TargetApi(21)
    private static void removeTask(Activity a) {
        a.finishAndRemoveTask();
    }

    /** Abre {@code intent} na outra metade (se a tela já estiver dividida) ou em tela cheia. */
    public static boolean openBeside(Activity from, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (supported()) intent.addFlags(adjacentFlag());
        return start(from, intent, null);
    }

    private static boolean start(Context from, Intent intent, Bundle options) {
        try {
            if (options == null) from.startActivity(intent);
            else from.startActivity(intent, options);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open " + intent, e);
            return false;
        }
    }

    @TargetApi(24)
    private static int adjacentFlag() {
        return Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
    }

    @TargetApi(24)
    private static Bundle splitOptions() {
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            Method mode = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            mode.invoke(o, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
            return o.toBundle();
        } catch (Exception e) {
            Log.w(TAG, "setLaunchWindowingMode", e);
            return null;
        }
    }
}
