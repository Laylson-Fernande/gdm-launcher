package com.garagemdomadeira.nwd.launcher;

import android.app.Application;

import com.garagemdomadeira.nwd.launcher.home.PlayerHandoff;
import com.garagemdomadeira.nwd.launcher.system.CrashLog;
import com.garagemdomadeira.nwd.launcher.system.NwdSettings;

/** Application: enables crash logging before any screen or service. */
public class GaragemApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        com.garagemdomadeira.nwd.launcher.ui.Ui.applyLanguage(this);
        PlayerHandoff.init(this);
        NwdSettings.init(this);
    }
}
