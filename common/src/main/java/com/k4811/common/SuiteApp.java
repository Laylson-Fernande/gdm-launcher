package com.k4811.common;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import com.k4811.common.system.AppMemory;
import com.k4811.common.system.BottomBar;
import com.k4811.common.system.CrashLog;
import com.k4811.common.system.LogFile;
import com.k4811.common.ui.Ui;

/**
 * Application base de todos os apps da suíte: liga o registro de crashes antes de qualquer tela,
 * aplica o idioma do launcher.json e esconde a barra flutuante da NWD enquanto um app da suíte
 * está na frente (se ligado nas Configurações). Também liga/desliga a gravação dos logs em arquivo
 * ({@link LogFile}) ao abrir e a cada tela que volta à frente. Responde à aba Memória das
 * Configurações ({@link AppMemory}).
 */
public class SuiteApp extends Application {

    /**
     * Avisa o launcher qual tela da suíte está na frente: ao ligar o carro ele reabre a última
     * (ver {@code launcher/.../start/StartGuard}). O launcher sobrescreve e guarda direto.
     */
    protected void screenShown(Activity a) {
        try {
            Intent i = new Intent(Apps.ACTION_SCREEN_SHOWN);
            i.setPackage(Apps.LAUNCHER);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra(Apps.EXTRA_COMPONENT, a.getComponentName().flattenToString());
            sendBroadcast(i);
        } catch (Exception ignored) {
            // sem launcher: nada a guardar
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        Ui.applyLanguage(this);
        BottomBar.install(this);
        LogFile.sync(this);
        AppMemory.install(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                LogFile.sync(a);
                screenShown(a);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
    }

    /** O sistema refaz a configuração do app (idioma do sistema, fonte…): volta o idioma da suíte (janelas flutuantes, serviços). */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyLanguage(this);
    }
}
