package com.k4811.launcher;

import android.app.Activity;
import android.os.Bundle;

import com.k4811.common.SuiteApp;
import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.system.ExternalApps;
import com.k4811.common.system.NwdSettings;
import com.k4811.launcher.home.ZlinkMonitor;
import com.k4811.launcher.overlay.EdgeMenuService;
import com.k4811.launcher.overlay.VolumeOverlayService;
import com.k4811.launcher.start.StartGuard;
import com.k4811.launcher.status.DayNightWatcher;
import com.k4811.map.road.RoadSync;

/**
 * Aplicação: base da suíte, Bluetooth conectado (o widget pausa a música BT), SettingService, o
 * aviso de volume e o menu da borda de baixo por cima dos apps, e o dia/noite ({@link DayNightWatcher}).
 */
public class LauncherApp extends SuiteApp {
    @Override
    public void onCreate() {
        super.onCreate();
        PlayerHandoff.init(this);
        NwdSettings.init(this);
        VolumeOverlayService.start(this);
        EdgeMenuService.start(this);
        // Acompanha o ZLINK desde o boot: "conectar/abrir automaticamente" valem sem a tela inicial aberta.
        if (ExternalApps.hasZlink(this)) ZlinkMonitor.get(this);
        // Dia e noite por horário (brilho) e tema seguindo o dia e a noite, com ou sem tela aberta.
        DayNightWatcher.get(this);
        // Dados do mapa (vias, mapa offline, rota e endereços): carga inicial logo após instalar e
        // conferência periódica, com ou sem o mapa aberto.
        RoadSync.start(this);
        // Telas do launcher na frente: o vigia da partida não as reabre por cima delas mesmas.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                StartGuard.launcherResumed(true);
            }

            @Override
            public void onActivityPaused(Activity a) {
                StartGuard.launcherResumed(false);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
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

    /** Última tela do launcher: guardada aqui mesmo (os outros apps mandam SCREEN_SHOWN). */
    @Override
    protected void screenShown(Activity a) {
        StartGuard.noteScreen(this, a.getComponentName().flattenToString());
    }
}
