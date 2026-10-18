package com.k4811.audioset.eq;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Reaplica o som salvo no boot e quando o carro sai do ACC (papel do DspServices do app
 * original). Sobe pelo {@link EqBootReceiver} ou pela action {@code com.nwd.ACTION_DSP_SERVICE}
 * (o sistema da NWD pode iniciá-lo por ela), reaplica uma vez e para.
 */
public class EqBootService extends Service {
    private static final String TAG = "K4811EqBoot";

    private EqController controller;

    public static void start(Context context) {
        try {
            context.startService(new Intent(context, EqBootService.class));
        } catch (Exception e) {
            Log.w(TAG, "não iniciou: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (controller != null) return START_NOT_STICKY; // já reaplicando
        Log.d(TAG, "reaplicando o som salvo");
        controller = new EqController(this);
        controller.reapplySaved(() -> {
            Log.d(TAG, "pronto");
            stopSelf();
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        controller = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
