package com.k4811.launcher;

import com.k4811.common.SuiteApp;
import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.system.NwdSettings;
import com.k4811.launcher.overlay.EdgeMenuService;
import com.k4811.launcher.overlay.VolumeOverlayService;

/**
 * Aplicação: base da suíte, Bluetooth conectado (o widget pausa a música BT), SettingService, o
 * aviso de volume e o menu da borda de baixo por cima dos apps.
 */
public class LauncherApp extends SuiteApp {
    @Override
    public void onCreate() {
        super.onCreate();
        PlayerHandoff.init(this);
        NwdSettings.init(this);
        VolumeOverlayService.start(this);
        EdgeMenuService.start(this);
    }
}
