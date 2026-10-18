package com.k4811.bt.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * O carro ligou com a música BT como última fonte: o sistema da NWD manda
 * {@code com.nwd.ACTION_BACKGROUND_MEDIA_PLAY} com {@code extra_app_id} = 15. Como o
 * GlobalBroadcastReceiver do app original, retoma a música, mas sem abrir a tela.
 */
public class BackgroundPlayReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.nwd.ACTION_BACKGROUND_MEDIA_PLAY";
    private static final String EXTRA_APP_ID = "extra_app_id";
    private static final int APP_ID_BT_MUSIC = 15;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        if (intent.getIntExtra(EXTRA_APP_ID, 0) != APP_ID_BT_MUSIC) return;
        BtResumeService.start(context);
    }
}
