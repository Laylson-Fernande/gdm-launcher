package com.garagemdomadeira.nwd.launcher;

import android.app.Application;

import com.garagemdomadeira.nwd.launcher.audio.AudioGains;
import com.garagemdomadeira.nwd.launcher.home.PlayerHandoff;
import com.garagemdomadeira.nwd.launcher.system.BottomBar;
import com.garagemdomadeira.nwd.launcher.system.CrashLog;
import com.garagemdomadeira.nwd.launcher.system.NwdSettings;

/**
 * Application: enables crash logging before any screen or service, hides the NWD bottom bar
 * while Garagem is in front, and reapplies the per-source volumes set in Settings › Audio.
 */
public class GaragemApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        com.garagemdomadeira.nwd.launcher.ui.Ui.applyLanguage(this);
        PlayerHandoff.init(this);
        NwdSettings.init(this);
        BottomBar.install(this);
        AudioGains.reapply(this);
    }
}
