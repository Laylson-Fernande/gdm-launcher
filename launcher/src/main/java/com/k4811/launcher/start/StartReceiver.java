package com.k4811.launcher.start;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.k4811.common.Apps;

/**
 * Quando vigiar a tela da frente ({@link StartGuard}):
 * <ul>
 *   <li>{@code com.nwd.ACTION_BACKGROUND_MEDIA_PLAY}: o carro ligou com uma fonte de mídia, e é este
 *       aviso que faz os apps de fábrica abrirem a própria tela;</li>
 *   <li>{@code BOOT_COMPLETED};</li>
 *   <li>{@code com.nwd.action.ACTION_MCU_STATE_CHANGE} com {@code extra_mcu_state} indo de 0 (ACC
 *       desligado, central dormindo) para 1 (carro ligado).</li>
 * </ul>
 * Também guarda a última tela da suíte ({@link Apps#ACTION_SCREEN_SHOWN}, enviado pelos outros apps).
 */
public class StartReceiver extends BroadcastReceiver {
    private static final String ACTION_BACKGROUND_PLAY = "com.nwd.ACTION_BACKGROUND_MEDIA_PLAY";
    private static final String ACTION_MCU_STATE = "com.nwd.action.ACTION_MCU_STATE_CHANGE";
    private static final String EXTRA_MCU_STATE = "extra_mcu_state";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (Apps.ACTION_SCREEN_SHOWN.equals(action)) {
            StartGuard.noteScreen(context, intent.getStringExtra(Apps.EXTRA_COMPONENT));
        } else if (ACTION_BACKGROUND_PLAY.equals(action)) {
            StartGuard.start(context, "fonte " + intent.getIntExtra("extra_app_id", 0));
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            StartGuard.start(context, "boot");
        } else if (ACTION_MCU_STATE.equals(action)) {
            if (StartGuard.mcuWoke(context, intent.getByteExtra(EXTRA_MCU_STATE, (byte) 1))) {
                StartGuard.start(context, "ACC");
            }
        }
    }
}
