package com.k4811.car.setting;

import com.k4811.common.SuiteApp;
import com.k4811.common.audio.AudioGains;
import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.NwdSettings;

/**
 * Aplicação: base da suíte, SettingService da NWD, o launcher.json (este app é o dono: cria com
 * os padrões para o launcher e os apps lerem) e os volumes por fonte de Configurações › Áudio.
 */
public class SettingApp extends SuiteApp {
    @Override
    public void onCreate() {
        super.onCreate();
        NwdSettings.init(this);
        LauncherConfig.ensureExists(this);
        AudioGains.reapply(this);
    }
}
