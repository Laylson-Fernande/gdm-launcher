package com.k4811.bt.music;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.bt.BtClient;
import com.k4811.common.system.NwdConstants;

/**
 * Retoma a música Bluetooth sem abrir a tela (carro ligado com a música BT como última fonte):
 * conecta no serviço de BT da NWD, pausa o MP3, avisa a central que a música BT é a fonte e
 * manda o "play forçado", como a tela faz ao conectar no A2DP. Depois para.
 */
public class BtResumeService extends Service {
    private static final String TAG = "K4811BtResume";
    private static final int BT_FORCE_PLAY = 4;
    private static final int RETRIES = 20;
    private static final long RETRY_MS = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BtClient bt;

    public static void start(Context context) {
        try {
            context.startService(new Intent(context, BtResumeService.class));
        } catch (Exception e) {
            Log.w(TAG, "não iniciou: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (bt != null) return START_NOT_STICKY; // já retomando
        bt = new BtClient(this);
        bt.connect();
        attempt(RETRIES);
        return START_NOT_STICKY;
    }

    private void attempt(int left) {
        if (bt == null) return;
        if (!bt.isConnected()) {
            if (left <= 0) {
                Log.d(TAG, "serviço de Bluetooth indisponível; música BT não retomada");
                stopSelf();
                return;
            }
            main.postDelayed(() -> attempt(left - 1), RETRY_MS);
            return;
        }
        PlayerHandoff.beforeBt(this);
        Intent in = new Intent(NwdConstants.ACTION_APP_IN_OUT);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_BT_MUSIC);
        in.putExtra(NwdConstants.EXTRA_APP_OPERATION, 1);
        in.putExtra(NwdConstants.EXTRA_APP_EVENT, 0);
        sendBroadcast(in);
        bt.setMusicMute(false);
        bt.playControl(BT_FORCE_PLAY);
        Log.d(TAG, "→ BT play forçado");
        main.postDelayed(this::stopSelf, 1000);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (bt != null) bt.release();
        bt = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
