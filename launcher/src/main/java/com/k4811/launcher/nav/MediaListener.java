package com.k4811.launcher.nav;

import android.annotation.TargetApi;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Serviço de notificações vazio. Existe só porque o Android libera a lista de players
 * ({@code MediaSessionManager.getActiveSessions}) para quem tem um destes com o acesso a
 * notificações ligado pelo usuário. Ver {@link MediaSessions}.
 */
@TargetApi(18)
public class MediaListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }
}
