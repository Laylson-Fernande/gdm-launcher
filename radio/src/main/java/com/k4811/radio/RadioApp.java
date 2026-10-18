package com.k4811.radio;

import com.k4811.common.SuiteApp;
import com.k4811.common.audio.PlayerHandoff;

/** Aplicação: base da suíte e Bluetooth conectado (para pausar a música BT ao ligar o rádio). */
public class RadioApp extends SuiteApp {
    @Override
    public void onCreate() {
        super.onCreate();
        PlayerHandoff.init(this);
    }
}
