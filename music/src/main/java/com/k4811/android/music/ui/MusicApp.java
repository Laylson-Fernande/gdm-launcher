package com.k4811.android.music.ui;

import com.k4811.common.SuiteApp;
import com.k4811.common.audio.PlayerHandoff;

/** Aplicação: base da suíte e Bluetooth conectado (para pausar a música BT ao tocar MP3). */
public class MusicApp extends SuiteApp {
    @Override
    public void onCreate() {
        super.onCreate();
        PlayerHandoff.init(this);
    }
}
