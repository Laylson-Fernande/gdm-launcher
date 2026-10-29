package com.k4811.common.audio;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.NwdSettings;

/**
 * Volumes relativos ao volume atual da central (Configurações › Navegação):
 * <ul>
 * <li><b>Mídia durante a voz da navegação</b>: em quantos % do volume a música fica enquanto o GPS
 * fala. Vai para a mixagem da central ({@code setGPSPromptVolumeWay} + {@code setMixedRatio}, 0 a
 * 10, as mesmas opções "Navegação abaixa a música" do Config. Veículo), que vale para qualquer app
 * de navegação. O MP3 da suíte também abaixa para esse valor quando perde o foco com "pode
 * abaixar" (com targetSdk 19 o Android não abaixa sozinho).</li>
 * <li><b>Notificações</b>: em quantos % do volume tocam os sons de notificação. O volume da central
 * (MCU) vale para todo o áudio do Android, então o stream de notificação em X% do máximo toca a
 * X% do volume atual.</li>
 * </ul>
 * -1 no {@link LauncherConfig} = nunca configurado: nada é aplicado e o sistema fica como está.
 */
public final class MixVolumes {
    private MixVolumes() {
    }

    private static final String TAG = "K4811Mix";
    public static final int DEFAULT_NAV_MEDIA = 50;
    public static final int DEFAULT_NOTIFICATION = 100;

    /** % da mídia durante a voz da navegação (padrão se nunca configurado). */
    public static int navMedia(Context c) {
        int p = LauncherConfig.navMediaPercent(c);
        return p < 0 ? DEFAULT_NAV_MEDIA : p;
    }

    /** % das notificações em relação ao volume (padrão se nunca configurado). */
    public static int notification(Context c) {
        int p = LauncherConfig.notifyPercent(c);
        return p < 0 ? DEFAULT_NOTIFICATION : p;
    }

    /** Volume do player (0 a 1) enquanto a navegação fala. */
    public static float duckVolume(Context c) {
        return navMedia(c) / 100f;
    }

    /** Grava na mixagem da central: 100% = a navegação não abaixa a música. */
    public static void applyNavMedia(int percent) {
        if (percent >= 100) {
            NwdSettings.setGpsPromptVolumeWay(2);
            return;
        }
        NwdSettings.setGpsPromptVolumeWay(1);
        // 0 quase não abaixa, 10 abaixa bastante.
        NwdSettings.setMixedRatio(Math.round((100 - Math.max(0, percent)) / 10f));
    }

    /** Põe o stream de notificação em {@code percent}% do máximo (só se mudou). */
    public static void applyNotification(Context c, int percent) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            int index = Math.round(max * Math.max(0, Math.min(100, percent)) / 100f);
            if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) != index) {
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, index, 0);
            }
        } catch (RuntimeException e) {
            // Não perturbe ligado (Android 7+) recusa mudar o volume sem acesso à política.
            Log.d(TAG, "volume da notificação: " + e.getMessage());
        }
    }

    /** O launcher chama ao abrir: a central pode ter voltado o stream de notificação. */
    public static void applySaved(Context c) {
        int p = LauncherConfig.notifyPercent(c);
        if (p >= 0) applyNotification(c, p);
    }
}
