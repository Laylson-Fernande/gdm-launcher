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
 * Também guarda a última tela da suíte ({@link Apps#ACTION_SCREEN_SHOWN}, enviado pelos outros apps)
 * e cuida do player de fora que estava tocando ({@link PlayerResume}): o estado do Spotify
 * ({@code com.spotify.music.playbackstatechanged}), o desligamento (ACC indo para 0,
 * {@code ACTION_SHUTDOWN}) e a retomada junto com o vigia.
 */
public class StartReceiver extends BroadcastReceiver {
    private static final String ACTION_BACKGROUND_PLAY = "com.nwd.ACTION_BACKGROUND_MEDIA_PLAY";
    private static final String ACTION_MCU_STATE = "com.nwd.action.ACTION_MCU_STATE_CHANGE";
    private static final String EXTRA_MCU_STATE = "extra_mcu_state";
    private static final String ACTION_SPOTIFY_STATE = "com.spotify.music.playbackstatechanged";
    private static final String SPOTIFY = "com.spotify.music";

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
            PlayerResume.resume(context, true);
        } else if (ACTION_MCU_STATE.equals(action)) {
            byte state = intent.getByteExtra(EXTRA_MCU_STATE, (byte) 1);
            if (state == 0 && StartGuard.mcuState(context) != 0) PlayerResume.freeze(context, false);
            if (StartGuard.mcuWoke(context, state)) {
                StartGuard.start(context, "ACC");
                PlayerResume.resume(context, false);
            }
        } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
            PlayerResume.freeze(context, true);
        } else if (ACTION_SPOTIFY_STATE.equals(action)) {
            PlayerResume.note(context, SPOTIFY, intent.getBooleanExtra("playing", false));
        }
    }
}
