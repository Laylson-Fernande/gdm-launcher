package com.k4811.car.setting;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;

/**
 * Liga ou desliga o modo escuro a pedido do launcher, porque só o car-setting grava o
 * {@code launcher.json}: o interruptor da barra de brilho e, com o tema seguindo o dia e a noite, a
 * troca de período (anoiteceu: escuro; amanheceu: claro). Vale o último pedido. O launcher acompanha o
 * arquivo e recria a tela quando a mudança aparece.
 */
public class DarkModeReceiver extends BroadcastReceiver {
    private static final String TAG = "K4811Settings";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Apps.ACTION_SET_DARK_MODE.equals(intent.getAction())) return;
        if (!intent.hasExtra(Apps.EXTRA_ON)) return;
        boolean on = intent.getBooleanExtra(Apps.EXTRA_ON, true);
        if (on == LauncherConfig.darkMode(context)) return;
        boolean ok = LauncherConfig.setDarkMode(context, on);
        Log.d(TAG, "modo escuro " + (on ? "ligado" : "desligado") + " pelo launcher" + (ok ? "" : " (não gravou)"));
    }
}
