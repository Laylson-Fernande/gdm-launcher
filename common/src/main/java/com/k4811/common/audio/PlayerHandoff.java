package com.k4811.common.audio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.k4811.common.Apps;
import com.k4811.common.bt.BtClient;
import com.k4811.common.system.NwdConstants;

/**
 * Uma fonte por vez entre rádio, MP3 (app de música) e música Bluetooth: logo antes de uma
 * começar, as outras param. O rádio sai sozinho com a troca de fonte que o MP3 (fonte 0) e o
 * Bluetooth (app id 15) já pedem; então só os players precisam pausar.
 *
 * O MP3 roda em outro app: ele pausa ao receber o broadcast {@link Apps#ACTION_PAUSE_PLAYERS}
 * (extra {@link Apps#EXTRA_FROM} = quem pediu). A música BT pausa pelo serviço de BT da NWD.
 * Mantém um BtClient conectado durante a vida do app (o serviço de BT descarta comandos enviados
 * antes da conexão).
 */
public final class PlayerHandoff {
    private PlayerHandoff() {
    }

    private static final String TAG = "K4811Handoff";
    private static final int BT_PAUSE = 1; // BTFeature.playControl: 0 play, 1 pause, 2 next, 3 prev
    private static final int BT_RETRIES = 6;
    private static final long BT_RETRY_MS = 300;
    private static final int SOURCE_ANDROID = 0;
    private static final int SOURCE_RADIO = 4;

    private static BtClient bt;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Chamado no início do app para já deixar o Bluetooth conectado. */
    public static void init(Context context) {
        if (bt != null) return;
        bt = new BtClient(context.getApplicationContext());
        bt.connect();
    }

    /** true se a fonte atual da central já é o rádio. */
    public static boolean radioIsSource(Context context) {
        return source(context) == SOURCE_RADIO;
    }

    private static int source(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
            NwdConstants.SETTING_CURRENT_SOURCE, -1);
    }

    /** Espera antes de o rádio pegar a fonte quando ela era Android (tempo de o player parar). */
    private static final long RADIO_AFTER_ANDROID_MS = 600;

    /**
     * Liga o rádio: pausa MP3 e Bluetooth e roda {@code activate} (que pega a fonte). Se a fonte
     * era Android (MP3 ou BT podiam estar tocando), espera um pouco antes, sem mostrar nada: se a
     * fonte sai do Android antes de o player parar de verdade, o sistema da NWD força a parada do
     * pacote do player. A tela já está aberta; só o som do rádio entra depois.
     */
    public static void switchToRadio(final Activity activity, final Runnable activate) {
        boolean androidPlaying = source(activity) == SOURCE_ANDROID;
        beforeRadio(activity);
        if (!androidPlaying) {
            activate.run();
            return;
        }
        main.postDelayed(() -> {
            if (!activity.isFinishing()) activate.run();
        }, RADIO_AFTER_ANDROID_MS);
    }

    /** Antes de o rádio virar a fonte: pausa MP3 e Bluetooth. */
    public static void beforeRadio(Context context) {
        Context app = context.getApplicationContext();
        pauseMp3(app);
        pauseBtFrom(app);
    }

    /** Antes de o MP3 começar: pausa a música Bluetooth. */
    public static void beforeMp3(Context context) {
        pauseBtFrom(context.getApplicationContext());
    }

    /** Antes de a música Bluetooth começar: pausa o MP3. */
    public static void beforeBt(Context context) {
        pauseMp3(context.getApplicationContext());
    }

    /** Pede ao app de música (e a outros players da suíte) que pausem. */
    private static void pauseMp3(Context app) {
        Intent i = new Intent(Apps.ACTION_PAUSE_PLAYERS);
        i.putExtra(Apps.EXTRA_FROM, app.getPackageName());
        try {
            app.sendBroadcast(i);
        } catch (Exception e) {
            Log.d(TAG, "pausar MP3: " + e.getMessage());
        }
    }

    private static void pauseBtFrom(Context app) {
        // O próprio app de música BT não pausa o Bluetooth.
        if (Apps.BT_MUSIC.equals(app.getPackageName())) return;
        init(app);
        pauseBt(BT_RETRIES);
    }

    private static void pauseBt(int triesLeft) {
        if (bt.isConnected()) {
            Log.d(TAG, "→ BT playControl(pause)");
            bt.playControl(BT_PAUSE);
            return;
        }
        if (triesLeft <= 0) {
            Log.d(TAG, "Serviço de Bluetooth indisponível; música BT não pausada");
            return;
        }
        bt.connect();
        main.postDelayed(() -> pauseBt(triesLeft - 1), BT_RETRY_MS);
    }
}
