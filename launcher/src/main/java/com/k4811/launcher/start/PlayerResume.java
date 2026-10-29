package com.k4811.launcher.start;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.k4811.common.Apps;
import com.k4811.common.home.NavApps;
import com.k4811.common.system.NwdConstants;
import com.k4811.launcher.nav.MediaListener;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player de fora da suíte (Spotify, YouTube Music…) que estava tocando quando o carro desligou:
 * volta a tocar, em segundo plano, quando o carro liga (boot ou saída do ACC, {@link StartReceiver}).
 *
 * O estado vem de duas fontes, o que estiver disponível:
 * <ul>
 *   <li>as MediaSessions, acompanhadas enquanto o {@link MediaListener} está ligado (o acesso a
 *       notificações do launcher, o mesmo que mostra a música na tela de navegação);</li>
 *   <li>o aviso do Spotify {@code com.spotify.music.playbackstatechanged} (opção "Transmissão do
 *       status do dispositivo" do Spotify), recebido pelo {@link StartReceiver}.</li>
 * </ul>
 * Ao desligar (ACC indo para 0 ou o desligamento do Android) a resposta fica congelada em
 * {@link #freeze}: a central pode pausar o player nesse momento, e uma pausa de poucos segundos antes
 * conta como "tocando".
 *
 * Para tocar: com a sessão do player viva, o comando dela; senão a tecla de mídia PLAY, que o Android
 * entrega ao último player que tocou e que abre o app em segundo plano (é assim que o Bluetooth do
 * carro retoma a música). Só retoma se a fonte da central for o Android (rádio, AUX e DVR não).
 */
public final class PlayerResume {
    private static final String TAG = "K4811Resume";
    private static final String PREFS = "player_resume";
    private static final String KEY_PKG = "pkg";
    private static final String KEY_PLAYING = "playing";
    private static final String KEY_CHANGED = "changed_at";
    /** Congelado ao desligar: o player a retomar ("" = nenhum); ausente = não houve desligamento visto. */
    private static final String KEY_RESUME = "resume_pkg";
    /** Pausa até este tempo antes de desligar ainda conta como tocando (a central pausa ao desligar). */
    private static final long PAUSE_GRACE_MS = 15000;
    private static final long BOOT_DELAY_MS = 8000;
    private static final long WAKE_DELAY_MS = 3000;
    /** Confere se começou a tocar; se não, tenta mais uma vez com a tecla de mídia. */
    private static final long CHECK_MS = 10000;
    private static final int SOURCE_ANDROID = 0;

    /** Players da suíte (cada um retoma do seu jeito) e sessões do sistema. */
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
        Apps.LAUNCHER, Apps.RADIO, Apps.MUSIC, Apps.BT_MUSIC, Apps.PHONE,
        "com.android.bluetooth", "com.android.server.telecom"));

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** MediaSessionManager.OnActiveSessionsChangedListener e os callbacks (Object: classes do Android 5). */
    private static Object sessionsListener;
    private static final Map<Object, Object> callbacks = new HashMap<>();

    private PlayerResume() {
    }

    // ------------------------------------------------------------------ estado

    /** Um player de fora começou ou parou de tocar. */
    public static void note(Context c, String pkg, boolean playing) {
        if (pkg == null || IGNORED.contains(pkg)) return;
        SharedPreferences p = prefs(c);
        boolean was = p.getBoolean(KEY_PLAYING, false);
        String last = p.getString(KEY_PKG, null);
        // Pausa de outro player não apaga o que está tocando.
        if (!playing && !pkg.equals(last)) return;
        if (was == playing && pkg.equals(last)) return;
        p.edit().putString(KEY_PKG, pkg).putBoolean(KEY_PLAYING, playing)
            .putLong(KEY_CHANGED, System.currentTimeMillis()).apply();
        Log.d(TAG, pkg + (playing ? " tocando" : " parou"));
    }

    /**
     * O carro está desligando: guarda se o player estava tocando agora (ou até há pouco).
     * {@code keepEarlier}: o desligamento do Android, que vem depois do ACC; vale o que o ACC guardou.
     */
    public static void freeze(Context c, boolean keepEarlier) {
        SharedPreferences p = prefs(c);
        if (keepEarlier && p.contains(KEY_RESUME)) return;
        String pkg = p.getString(KEY_PKG, null);
        boolean playing = p.getBoolean(KEY_PLAYING, false);
        long since = System.currentTimeMillis() - p.getLong(KEY_CHANGED, 0);
        boolean resume = pkg != null && (playing || (since >= 0 && since < PAUSE_GRACE_MS));
        p.edit().putString(KEY_RESUME, resume ? pkg : "").commit();
        Log.d(TAG, "desligando: " + (resume ? "retomar " + pkg : "nada tocando"));
    }

    /** O carro ligou: retoma o player, se ele estava tocando. */
    public static void resume(Context c, boolean boot) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        String pkg = p.getString(KEY_RESUME, null);
        // Sem desligamento visto (energia cortada): o último estado acompanhado.
        if (pkg == null && p.getBoolean(KEY_PLAYING, false)) pkg = p.getString(KEY_PKG, null);
        p.edit().remove(KEY_RESUME).apply();
        if (pkg == null || pkg.isEmpty()) return;
        if (app.getPackageManager().getLaunchIntentForPackage(pkg) == null) return;
        final String target = pkg;
        MAIN.postDelayed(() -> play(app, target, true), boot ? BOOT_DELAY_MS : WAKE_DELAY_MS);
    }

    private static void play(Context app, String pkg, boolean first) {
        int source = Settings.System.getInt(app.getContentResolver(), NwdConstants.SETTING_CURRENT_SOURCE, SOURCE_ANDROID);
        if (source != SOURCE_ANDROID) {
            Log.d(TAG, "fonte " + source + ": não retoma " + pkg);
            return;
        }
        Object session = Build.VERSION.SDK_INT >= 21 ? session(app, pkg) : null;
        if (session != null && isPlaying(session)) return;
        if (session != null && first) {
            Log.d(TAG, "tocar " + pkg + " (sessão)");
            transportPlay(session);
        } else {
            Log.d(TAG, "tocar " + pkg + " (tecla de mídia)");
            mediaKey(app, KeyEvent.KEYCODE_MEDIA_PLAY);
        }
        if (first && Build.VERSION.SDK_INT >= 21 && NavApps.mediaAccess(app)) {
            MAIN.postDelayed(() -> play(app, pkg, false), CHECK_MS);
        }
    }

    // ------------------------------------------------------------------ MediaSessions

    /** Acompanha os players enquanto o acesso a notificações está ligado ({@link MediaListener}). */
    public static void track(Context c) {
        if (Build.VERSION.SDK_INT < 21 || sessionsListener != null) return;
        startTracking(c.getApplicationContext());
    }

    public static void untrack(Context c) {
        if (Build.VERSION.SDK_INT < 21 || sessionsListener == null) return;
        stopTracking(c.getApplicationContext());
    }

    @TargetApi(21)
    private static void startTracking(Context app) {
        MediaSessionManager msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        MediaSessionManager.OnActiveSessionsChangedListener l = list -> follow(app, list);
        try {
            msm.addOnActiveSessionsChangedListener(l, component(app), MAIN);
            sessionsListener = l;
            follow(app, msm.getActiveSessions(component(app)));
        } catch (SecurityException e) {
            Log.w(TAG, "sem acesso às sessões", e);
        }
    }

    @TargetApi(21)
    private static void stopTracking(Context app) {
        MediaSessionManager msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        msm.removeOnActiveSessionsChangedListener((MediaSessionManager.OnActiveSessionsChangedListener) sessionsListener);
        sessionsListener = null;
        follow(app, null);
    }

    /** Registra um callback em cada sessão de fora (as que sumiram perdem o seu). */
    @TargetApi(21)
    private static void follow(Context app, List<MediaController> list) {
        for (Map.Entry<Object, Object> e : callbacks.entrySet()) {
            ((MediaController) e.getKey()).unregisterCallback((MediaController.Callback) e.getValue());
        }
        callbacks.clear();
        if (list == null) return;
        for (MediaController mc : list) {
            String pkg = mc.getPackageName();
            if (IGNORED.contains(pkg)) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    note(app, pkg, playing(state));
                }

                @Override
                public void onSessionDestroyed() {
                    note(app, pkg, false);
                }
            };
            mc.registerCallback(cb, MAIN);
            callbacks.put(mc, cb);
            if (playing(mc.getPlaybackState())) note(app, pkg, true);
        }
    }

    @TargetApi(21)
    private static Object session(Context app, String pkg) {
        if (!NavApps.mediaAccess(app)) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(component(app))) {
                if (pkg.equals(mc.getPackageName())) return mc;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "sem acesso às sessões", e);
        }
        return null;
    }

    @TargetApi(21)
    private static boolean isPlaying(Object session) {
        return playing(((MediaController) session).getPlaybackState());
    }

    @TargetApi(21)
    private static void transportPlay(Object session) {
        ((MediaController) session).getTransportControls().play();
    }

    @TargetApi(21)
    private static boolean playing(PlaybackState s) {
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static ComponentName component(Context c) {
        return new ComponentName(c, MediaListener.class);
    }

    @TargetApi(19)
    private static void mediaKey(Context app, int code) {
        if (Build.VERSION.SDK_INT < 19) return;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
