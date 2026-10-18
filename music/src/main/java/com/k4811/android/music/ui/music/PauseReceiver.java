package com.k4811.android.music.ui.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.k4811.common.Apps;

/**
 * Outro app do suíte vai virar a fonte de áudio (broadcast com.k4811.action.PAUSE_PLAYERS):
 * pausa o player. Ignora o próprio aviso, que este app também envia ao tocar.
 */
public class PauseReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Apps.ACTION_PAUSE_PLAYERS.equals(intent.getAction())) return;
        if (context.getPackageName().equals(intent.getStringExtra(Apps.EXTRA_FROM))) return;
        // Mesmo processo: pausa e solta o foco na hora (o rádio troca a fonte logo depois).
        if (MusicPlaybackService.pauseRunning()) return;
        Intent pause = new Intent(context, MusicPlaybackService.class);
        pause.setAction(MusicPlaybackService.ACTION_PAUSE);
        try {
            context.startService(pause);
        } catch (Exception ignored) {
            // Serviço não estava rodando e o sistema não deixou iniciar: nada tocando para pausar.
        }
    }
}
