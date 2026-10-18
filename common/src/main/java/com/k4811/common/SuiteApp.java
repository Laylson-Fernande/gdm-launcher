package com.k4811.common;

import android.app.Application;

import com.k4811.common.system.BottomBar;
import com.k4811.common.system.CrashLog;
import com.k4811.common.ui.Ui;

/**
 * Application base de todos os apps da suíte: liga o registro de crashes antes de qualquer tela,
 * aplica o idioma do launcher.json e esconde a barra flutuante da NWD enquanto um app da suíte
 * está na frente (se ligado nas Configurações).
 */
public class SuiteApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        Ui.applyLanguage(this);
        BottomBar.install(this);
    }
}
