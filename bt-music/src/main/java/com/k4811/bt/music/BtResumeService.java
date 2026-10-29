package com.k4811.bt.music;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.bt.BtClient;
import com.k4811.common.system.NwdConstants;

/**
 * Retoma a música Bluetooth sem abrir a tela (carro ligado com a música BT como última fonte).
 *
 * Ao ligar o carro o serviço de BT da NWD sobe antes de o celular reconectar: um "play forçado"
 * nessa hora se perde, e o celular depois volta a tocar sozinho (AVRCP diz "tocando") com a fonte
 * e o mudo da música BT ainda como estavam, sem som até alguém pular a faixa. Por isso o serviço
 * fica de olho por até {@value #WATCH_MS} ms: quando o A2DP conecta, ou o celular começa a tocar,
 * pega a fonte (app 15), tira o mudo e manda o play forçado, como a tela faz. Depois para.
 */
public class BtResumeService extends Service {
    private static final String TAG = "K4811BtResume";
    private static final int BT_FORCE_PLAY = 4;
    /** Quanto tempo espera o celular reconectar depois do carro ligar. */
    private static final long WATCH_MS = 90000;
    /** Consulta o estado do A2DP a cada tanto (o broadcast pode ter saído antes de o app subir). */
    private static final long POLL_MS = 2000;
    /** Depois do A2DP conectar, espera o canal de áudio abrir antes de pedir o som. */
    private static final long SETTLE_MS = 1500;
    /** Segundo pedido, para o celular que começou a tocar logo depois do primeiro. */
    private static final long CONFIRM_MS = 4000;

    private static final String ACTION_A2DP = "com.bt.ACTION_A2DP_ESTABLISHED";
    private static final String ACTION_PLAY = "com.bt.ACTION_BT_MUSIC_PLAY";

    private final Handler main = new Handler(Looper.getMainLooper());
    private BtClient bt;
    private boolean receiverRegistered;
    /** Já pediu o som com o A2DP conectado; falta só a confirmação. */
    private boolean resumed;

    private final Runnable poll = this::poll;
    private final Runnable resume = this::resume;
    private final Runnable confirm = this::confirm;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_A2DP.equals(action)) {
                Log.d(TAG, "A2DP conectou");
                scheduleResume(SETTLE_MS);
            } else if (ACTION_PLAY.equals(action)) {
                // O celular voltou a tocar sozinho: garante a fonte e o som dele.
                Log.d(TAG, "celular tocando");
                if (resumed) claim();
                else scheduleResume(SETTLE_MS);
            }
        }
    };

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
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_A2DP);
        filter.addAction(ACTION_PLAY);
        registerReceiver(receiver, filter);
        receiverRegistered = true;
        main.post(poll);
        main.postDelayed(this::giveUp, WATCH_MS);
        return START_NOT_STICKY;
    }

    /** O A2DP já está conectado? (o broadcast pode ter saído antes deste serviço subir) */
    private void poll() {
        if (bt == null || resumed) return;
        bt.queryMusicInfo((a2dp, playing, name, pin) -> {
            if (a2dp && !resumed) scheduleResume(playing ? 0 : SETTLE_MS);
        });
        main.postDelayed(poll, POLL_MS);
    }

    private void scheduleResume(long delay) {
        main.removeCallbacks(resume);
        main.postDelayed(resume, delay);
    }

    /** A2DP conectado: pausa o MP3, pega a fonte, tira o mudo e manda o play forçado. */
    private void resume() {
        if (bt == null) return;
        resumed = true;
        main.removeCallbacks(poll);
        PlayerHandoff.beforeBt(this);
        claim();
        bt.playControl(BT_FORCE_PLAY);
        Log.d(TAG, "→ BT play forçado");
        main.removeCallbacks(confirm);
        main.postDelayed(confirm, CONFIRM_MS);
    }

    /** Fonte da música BT e som liberado (o que "pular a faixa" fazia destravar). */
    private void claim() {
        Intent in = new Intent(NwdConstants.ACTION_APP_IN_OUT);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_BT_MUSIC);
        in.putExtra(NwdConstants.EXTRA_APP_OPERATION, 1);
        in.putExtra(NwdConstants.EXTRA_APP_EVENT, 0);
        sendBroadcast(in);
        if (bt != null) bt.setMusicMute(false);
    }

    /** Confere de novo: tocando, reforça a fonte; parado, pede o play mais uma vez. Depois termina. */
    private void confirm() {
        if (bt == null) return;
        bt.queryMusicInfo((a2dp, playing, name, pin) -> {
            if (a2dp) {
                claim();
                if (!playing) bt.playControl(BT_FORCE_PLAY);
                Log.d(TAG, "confirmação: " + (playing ? "tocando" : "play de novo"));
            }
            main.postDelayed(this::stopSelf, 1000);
        });
    }

    private void giveUp() {
        if (!resumed) Log.d(TAG, "celular não conectou; música BT não retomada");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        if (bt != null) bt.release();
        bt = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
