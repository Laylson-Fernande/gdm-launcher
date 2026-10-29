package com.k4811.launcher.home;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.system.ExternalApps;
import com.k4811.launcher.nav.MediaListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado do ZLINK (CarPlay / Android Auto) para o botão do bloco do Bluetooth e as opções
 * "conectar automaticamente" e "abrir automaticamente" (launcher.json, gravadas pelo car-setting).
 *
 * O que se sabe do ZLINK 6.0.50 (testado no emulador; o código dele é protegido):
 * <ul>
 * <li>Conectado: ele publica uma MediaSession (aparece como música tocando). Desconectado não há
 * sessão. Ler as sessões exige o acesso a notificações do {@link MediaListener} ("Acesso à música").</li>
 * <li>Ele envia o broadcast {@code com.zjinnova.zlink} com o extra {@code status}: {@code ENTER},
 * {@code MAIN_PAGE_SHOW}, {@code MAIN_PAGE_HIDDEN} (os de conexão ainda não foram vistos: todo status
 * vai para o log).</li>
 * <li>Ele recebe {@code com.zjinnova.zlink.action.DISCONNECT}, {@code ACTION_APP_SHOW} e
 * {@code ACTION_APP_HIDE} (mostrar e esconder a tela dele funcionam).</li>
 * </ul>
 * Uma instância por processo, criada pelo {@code LauncherApp}.
 */
public final class ZlinkMonitor {

    public interface Listener {
        void onZlinkChanged(boolean connected);
    }

    private static final String TAG = "K4811Zlink";
    private static final String ACTION_STATUS = ExternalApps.PKG_ZLINK;
    private static final String EXTRA_STATUS = "status";
    private static final String ACTION_DISCONNECT = "com.zjinnova.zlink.action.DISCONNECT";
    private static final String ACTION_APP_SHOW = "com.zjinnova.zlink.action.ACTION_APP_SHOW";
    private static final String ACTION_APP_HIDE = "com.zjinnova.zlink.action.ACTION_APP_HIDE";
    private static final String STATUS_SHOW = "MAIN_PAGE_SHOW";
    private static final String STATUS_HIDDEN = "MAIN_PAGE_HIDDEN";
    /** A tela do ZLINK estava na frente na última vez (vale para "abrir se estava aberto ao desligar"). */
    private static final String PREF_WAS_OPEN = "zlink_was_open";
    /** Tela do ZLINK aberta até este tempo antes da conexão conta como "o usuário pediu". */
    private static final long MANUAL_WINDOW_MS = 3000;

    private static ZlinkMonitor instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;
    private boolean connected;
    private boolean pageShown;
    /** O botão da tela inicial pediu para conectar: a próxima conexão é pedida, não automática. */
    private boolean buttonRequest;
    /** Quando a tela do ZLINK abriu com ele desconectado (uptime; 0 = não abriu). */
    private long openedAt;
    /** Tela apagada ou desligando: o "estava aberto" fica congelado até a tela voltar. */
    private boolean frozen;
    private Object sessionsListener;

    public static synchronized ZlinkMonitor get(Context c) {
        if (instance == null) instance = new ZlinkMonitor(c.getApplicationContext());
        return instance;
    }

    private ZlinkMonitor(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("zlink", Context.MODE_PRIVATE);
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SHUTDOWN);
        context.registerReceiver(receiver, f);
        start();
    }

    public boolean installed() {
        return ExternalApps.hasZlink(context);
    }

    public boolean isConnected() {
        return connected;
    }

    /** Sem acesso às sessões não dá para saber se está conectado (o botão só abre o ZLINK). */
    public boolean canDetect() {
        return sessionsListener != null;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Botão do bloco do Bluetooth: abre o ZLINK, ou desconecta se já está conectado. */
    public void toggle() {
        if (connected) {
            disconnect();
            return;
        }
        buttonRequest = true;
        ExternalApps.openZlink(context);
    }

    public void disconnect() {
        Log.i(TAG, "→ DISCONNECT");
        send(ACTION_DISCONNECT);
    }

    /** Tenta de novo ler as sessões (o acesso a notificações pode ter sido ligado agora). */
    public void start() {
        if (sessionsListener == null && Build.VERSION.SDK_INT >= 21) startSessions();
    }

    private void send(String action) {
        Intent i = new Intent(action);
        i.setPackage(ExternalApps.PKG_ZLINK);
        context.sendBroadcast(i);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a) || Intent.ACTION_SHUTDOWN.equals(a)) {
                frozen = true;
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                frozen = false;
                return;
            }
            String status = intent.getStringExtra(EXTRA_STATUS);
            Log.i(TAG, "status do ZLINK: " + status + " (conectado=" + connected + ")");
            if (STATUS_SHOW.equals(status)) {
                pageShown = true;
                if (!connected && openedAt == 0) openedAt = SystemClock.uptimeMillis();
            } else if (STATUS_HIDDEN.equals(status)) {
                pageShown = false;
                if (!connected) openedAt = 0;
            } else {
                return;
            }
            if (!frozen) prefs.edit().putBoolean(PREF_WAS_OPEN, pageShown).apply();
        }
    };

    private void setConnected(boolean now) {
        if (now == connected) return;
        connected = now;
        Log.i(TAG, now ? "conectado (sessão de mídia do ZLINK)" : "desconectado");
        if (now) onConnected();
        else {
            buttonRequest = false;
            openedAt = pageShown ? SystemClock.uptimeMillis() : 0;
        }
        for (Listener l : listeners) l.onZlinkChanged(now);
    }

    /** Aplica "conectar automaticamente" e "abrir automaticamente" a uma conexão nova. */
    private void onConnected() {
        // Pedida = pelo botão, ou com a tela do ZLINK já aberta antes; a tela abrindo junto com a
        // conexão é o próprio ZLINK, não o usuário.
        boolean openedByUser = buttonRequest
            || (openedAt > 0 && SystemClock.uptimeMillis() - openedAt >= MANUAL_WINDOW_MS);
        if (!LauncherConfig.zlinkAutoConnect(context) && !openedByUser) {
            Log.i(TAG, "conexão automática desligada: desconectando");
            disconnect();
            return;
        }
        if (openedByUser) return; // o usuário já está com o ZLINK aberto
        boolean open;
        switch (LauncherConfig.zlinkAutoOpen(context)) {
            case LauncherConfig.ZLINK_OPEN_NO:
                open = false;
                break;
            case LauncherConfig.ZLINK_OPEN_LAST:
                open = prefs.getBoolean(PREF_WAS_OPEN, false);
                break;
            default:
                open = true;
                break;
        }
        Log.i(TAG, open ? "abrindo o ZLINK" : "escondendo o ZLINK");
        // Espera o ZLINK terminar de reagir à conexão (ele pode abrir a própria tela).
        handler.postDelayed(() -> {
            if (connected) send(open ? ACTION_APP_SHOW : ACTION_APP_HIDE);
        }, 1500);
    }

    // ------------------------------------------------------------------ Android 5+

    @TargetApi(21)
    private void startSessions() {
        MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName component = new ComponentName(context, MediaListener.class);
        MediaSessionManager.OnActiveSessionsChangedListener l = this::onSessions;
        try {
            msm.addOnActiveSessionsChangedListener(l, component, handler);
            sessionsListener = l;
            onSessions(msm.getActiveSessions(component));
        } catch (SecurityException e) {
            Log.d(TAG, "sem acesso às sessões de mídia: não sabe se o ZLINK está conectado");
        }
    }

    @TargetApi(21)
    private void onSessions(List<MediaController> sessions) {
        boolean found = false;
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (ExternalApps.PKG_ZLINK.equals(c.getPackageName())) {
                    found = true;
                    break;
                }
            }
        }
        setConnected(found);
    }
}
