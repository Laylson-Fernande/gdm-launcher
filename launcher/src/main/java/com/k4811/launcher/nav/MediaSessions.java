package com.k4811.launcher.nav;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.k4811.common.Apps;
import com.k4811.common.home.NavApps;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * O player de música do Android que está ativo (Spotify, YouTube Music…), pela MediaSession dele:
 * faixa, capa, tocando ou não, e os comandos. Usado pela tela de navegação e pelo "Tocando agora"
 * da tela inicial ({@code HomeSession}).
 *
 * Ler as sessões exige o acesso a notificações ligado para o {@link MediaListener} (Android 5+).
 * Sem ele, os comandos vão como teclas de mídia, que o Android entrega ao último player que tocou.
 */
public final class MediaSessions {

    public interface Listener {
        void onMediaChanged();
    }

    /** O que o painel mostra. Campos null = desconhecido. */
    public static final class Track {
        public String pkg;
        public String title;
        public String artist;
        public Bitmap art;
        public boolean playing;
    }

    /** Ação de configurações do acesso a notificações (Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, API 22). */
    public static final String ACTION_ACCESS_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private static final String TAG = "K4811Nav";

    /**
     * Sessões que não são "o app de música": os players da suíte (têm cartão próprio na tela
     * inicial) e as do sistema (Bluetooth do Android, telefone).
     */
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
        Apps.LAUNCHER, Apps.RADIO, Apps.MUSIC, Apps.BT_MUSIC, Apps.PHONE,
        "com.android.bluetooth", "com.android.server.telecom"));

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    /** MediaSessionManager.OnActiveSessionsChangedListener (Object para não carregar a classe antes do Android 5). */
    private Object sessionsListener;
    /** MediaController atual e o callback registrado nele. */
    private Object controller;
    private Object callback;
    private String lastKey;

    public MediaSessions(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** O usuário ligou o acesso a notificações para o launcher (necessário para ler a faixa). */
    public boolean hasAccess() {
        return NavApps.mediaAccess(context);
    }

    private ComponentName component() {
        return new ComponentName(context, MediaListener.class);
    }

    /** Começa a acompanhar os players (se houver acesso). Chamar de novo depois que o acesso mudar. */
    public void start() {
        stop();
        if (!hasAccess()) return;
        startSessions();
    }

    public void stop() {
        lastKey = null;
        if (Build.VERSION.SDK_INT < 21) return;
        stopSessions();
    }

    /** Faixa atual; null se não há player (ou sem acesso). */
    public Track track() {
        if (controller == null || Build.VERSION.SDK_INT < 21) return null;
        return read();
    }

    public void playPause() {
        Track t = track();
        if (t != null && Build.VERSION.SDK_INT >= 21) {
            transport(t.playing ? 0 : 1);
            return;
        }
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public void next() {
        if (controller != null && Build.VERSION.SDK_INT >= 21) transport(2);
        else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void previous() {
        if (controller != null && Build.VERSION.SDK_INT >= 21) transport(3);
        else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /** Abre o app do player atual; false se não há player. */
    public boolean openPlayer() {
        Track t = track();
        if (t == null || t.pkg == null) return false;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(t.pkg);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tecla de mídia para o último player que tocou (funciona sem o acesso a notificações). */
    private void mediaKey(int code) {
        if (Build.VERSION.SDK_INT < 19) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        dispatch(am, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        dispatch(am, new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    @TargetApi(19)
    private static void dispatch(AudioManager am, KeyEvent e) {
        am.dispatchMediaKeyEvent(e);
    }

    /**
     * Avisa a tela só quando muda algo que ela mostra: os players mandam o estado várias vezes por
     * segundo (posição da música), e redesenhar a cada vez gasta CPU à toa.
     */
    private void changed() {
        Track t = track();
        String key = t == null ? "" : t.pkg + '|' + t.title + '|' + t.artist + '|' + t.playing
            + '|' + (t.art == null ? 0 : t.art.getGenerationId() * 31 + System.identityHashCode(t.art));
        if (key.equals(lastKey)) return;
        lastKey = key;
        if (listener != null) listener.onMediaChanged();
    }

    // ------------------------------------------------------------------ Android 5+

    @TargetApi(21)
    private void startSessions() {
        MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        MediaSessionManager.OnActiveSessionsChangedListener l = this::pick;
        try {
            msm.addOnActiveSessionsChangedListener(l, component(), handler);
            sessionsListener = l;
            pick(msm.getActiveSessions(component()));
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions", e);
        }
    }

    @TargetApi(21)
    private void stopSessions() {
        if (sessionsListener != null) {
            MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.removeOnActiveSessionsChangedListener((MediaSessionManager.OnActiveSessionsChangedListener) sessionsListener);
            sessionsListener = null;
        }
        follow(null);
    }

    /** Escolhe o player: o primeiro que está tocando; senão o primeiro da lista (o mais recente). */
    @TargetApi(21)
    private void pick(List<MediaController> sessions) {
        MediaController best = null;
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (IGNORED.contains(c.getPackageName())) continue;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            if (best == null) {
                for (MediaController c : sessions) {
                    if (IGNORED.contains(c.getPackageName())) continue;
                    best = c;
                    break;
                }
            }
        }
        MediaController current = (MediaController) controller;
        if (best != null && current != null && best.getSessionToken().equals(current.getSessionToken())) {
            changed();
            return;
        }
        follow(best);
        changed();
    }

    @TargetApi(21)
    private void follow(MediaController next) {
        MediaController current = (MediaController) controller;
        if (current != null && callback != null) current.unregisterCallback((MediaController.Callback) callback);
        controller = next;
        callback = null;
        if (next == null) return;
        MediaController.Callback cb = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                // Outro player pode ter começado a tocar: reavalia a lista toda.
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) refreshList();
                changed();
            }

            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                changed();
            }

            @Override
            public void onSessionDestroyed() {
                refreshList();
            }
        };
        next.registerCallback(cb, handler);
        callback = cb;
    }

    @TargetApi(21)
    private void refreshList() {
        if (sessionsListener == null) return;
        MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            pick(msm.getActiveSessions(component()));
        } catch (SecurityException e) {
            follow(null);
            changed();
        }
    }

    @TargetApi(21)
    private Track read() {
        MediaController c = (MediaController) controller;
        Track t = new Track();
        t.pkg = c.getPackageName();
        PlaybackState s = c.getPlaybackState();
        t.playing = s != null && (s.getState() == PlaybackState.STATE_PLAYING
            || s.getState() == PlaybackState.STATE_BUFFERING);
        MediaMetadata m = c.getMetadata();
        if (m != null) {
            t.title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            t.artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t.artist == null) t.artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            t.art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (t.art == null) t.art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (t.art == null) t.art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        return t;
    }

    /** 0 pausar, 1 tocar, 2 próxima, 3 anterior. */
    @TargetApi(21)
    private void transport(int cmd) {
        MediaController.TransportControls tc = ((MediaController) controller).getTransportControls();
        switch (cmd) {
            case 0:
                tc.pause();
                break;
            case 1:
                tc.play();
                break;
            case 2:
                tc.skipToNext();
                break;
            default:
                tc.skipToPrevious();
                break;
        }
    }
}
