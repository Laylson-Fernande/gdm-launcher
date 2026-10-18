package com.k4811.android.music.ui.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * O carro ligou com o MP3 como última fonte: o sistema da NWD manda
 * {@code com.nwd.ACTION_BACKGROUND_MEDIA_PLAY} com {@code extra_app_id} = 2 (música). Como o
 * GlobalBroadcastReceiver do app original, retoma a música, mas sem abrir a tela: o serviço
 * carrega a lista e toca a última faixa.
 */
public class BackgroundPlayReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.nwd.ACTION_BACKGROUND_MEDIA_PLAY";
    private static final String EXTRA_APP_ID = "extra_app_id";
    private static final int APP_ID_MUSIC = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        if (intent.getIntExtra(EXTRA_APP_ID, 0) != APP_ID_MUSIC) return;
        Intent resume = new Intent(context, MusicPlaybackService.class);
        resume.setAction(MusicPlaybackService.ACTION_RESUME);
        try {
            context.startService(resume);
        } catch (Exception ignored) {
        }
    }
}
