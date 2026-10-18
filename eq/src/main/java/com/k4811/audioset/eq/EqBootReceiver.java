package com.k4811.audioset.eq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Quando reaplicar o som (como o StartReceiver e o AccStatusBroadcast do app original):
 * <ul>
 *   <li>{@code BOOT_COMPLETED};</li>
 *   <li>{@code com.nwd.action.ACTION_MCU_STATE_CHANGE} com {@code extra_mcu_state} indo de 0
 *       (ACC desligado, central dormindo) para 1 (carro ligado);</li>
 *   <li>{@code com.nwd.action.ACTION_REINIT_DSP_DATA}, pedido do sistema.</li>
 * </ul>
 */
public class EqBootReceiver extends BroadcastReceiver {
    private static final String ACTION_MCU_STATE = "com.nwd.action.ACTION_MCU_STATE_CHANGE";
    private static final String EXTRA_MCU_STATE = "extra_mcu_state";
    private static final String ACTION_REINIT = "com.nwd.action.ACTION_REINIT_DSP_DATA";
    private static final String PREFS = "eq_boot";
    private static final String KEY_MCU_STATE = "mcu_state";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_REINIT.equals(action)) {
            EqBootService.start(context);
            return;
        }
        if (!ACTION_MCU_STATE.equals(action)) return;
        // O processo pode não estar vivo entre os dois avisos: o último estado fica gravado.
        byte now = intent.getByteExtra(EXTRA_MCU_STATE, (byte) 1);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int before = prefs.getInt(KEY_MCU_STATE, 1);
        prefs.edit().putInt(KEY_MCU_STATE, now).apply();
        if (before == 0 && now == 1) EqBootService.start(context);
    }
}
