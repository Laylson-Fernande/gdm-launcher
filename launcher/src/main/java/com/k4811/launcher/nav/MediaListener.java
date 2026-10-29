package com.k4811.launcher.nav;

import android.annotation.TargetApi;
import android.app.Notification;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.k4811.common.config.LauncherConfig;

/**
 * Serviço de notificações do launcher. Existe porque o Android libera a lista de players
 * ({@code MediaSessionManager.getActiveSessions}) para quem tem um destes com o acesso a
 * notificações ligado pelo usuário (ver {@link MediaSessions}). Com "Bloquear notificações"
 * ({@link LauncherConfig#blockNotifications}), também apaga cada notificação assim que ela aparece,
 * o que tira o aviso flutuante da tela. Não lê o conteúdo; deixa as de player (para o controle de
 * música continuar) e as fixas, que o Android não deixa apagar.
 */
@TargetApi(18)
public class MediaListener extends NotificationListenerService {
    private static final String TAG = "K4811Notify";

    /** A opção estava ligada na última notificação vista (ao ligar, apaga também as que já estavam). */
    private boolean blocking;

    @Override
    public void onListenerConnected() {
        blocking = LauncherConfig.blockNotifications(this);
        if (blocking) blockAll();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        boolean was = blocking;
        blocking = LauncherConfig.blockNotifications(this);
        if (!blocking) return;
        if (was) {
            block(sbn);
        } else {
            blockAll();
        }
    }

    /** Apaga as que já estão na barra (chegaram antes ou com a opção desligada). */
    private void blockAll() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification sbn : all) block(sbn);
        } catch (RuntimeException e) {
            Log.w(TAG, "sem a lista de notificações", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }

    @SuppressWarnings("deprecation")
    private void block(StatusBarNotification sbn) {
        if (sbn == null || !sbn.isClearable() || isPlayer(sbn.getNotification())) return;
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                cancelNotification(sbn.getKey());
            } else {
                cancelNotification(sbn.getPackageName(), sbn.getTag(), sbn.getId());
            }
            Log.d(TAG, "bloqueada: " + sbn.getPackageName());
        } catch (RuntimeException e) {
            Log.w(TAG, "não apagou " + sbn.getPackageName(), e);
        }
    }

    /** Notificação de player (Spotify pausado, por exemplo): apagar tiraria o controle de música. */
    private static boolean isPlayer(Notification n) {
        if (n == null || n.extras == null) return false;
        return n.extras.containsKey("android.mediaSession")
            || Notification.CATEGORY_TRANSPORT.equals(n.category);
    }
}
