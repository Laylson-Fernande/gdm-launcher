package com.k4811.android.music.ui.music;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Client of {@link MusicPlaybackService}. Used by the music screen and the home card.
 */
public final class MusicClient {

    public interface Listener {
        void onMusicState(MusicState state);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private MusicPlaybackService service;
    private boolean bound;
    private boolean released;
    private MusicState last = new MusicState();

    private final MusicPlaybackService.Listener bridge = state -> {
        last = state;
        Listener l = listener;
        if (l != null) l.onMusicState(state);
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            MusicPlaybackService.LocalBinder local = (MusicPlaybackService.LocalBinder) binder;
            service = local.getService();
            service.addListener(bridge);
            service.ensureLibrary();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    public MusicClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onMusicState(last);
    }

    public void connect() {
        if (released || bound) return;
        Intent intent = new Intent(context, MusicPlaybackService.class);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    public void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        listener = null;
        if (service != null) {
            service.removeListener(bridge);
            service = null;
        }
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
    }

    public MusicState getState() {
        return service != null ? service.getState() : last;
    }

    public boolean hasSession() {
        return service != null && service.hasSession();
    }

    public void claimSource() {
        if (service != null) service.claimSource();
    }

    public void reload() {
        if (service != null) service.reloadLibrary();
    }

    public void playIndex(int index) {
        if (service != null) service.playIndex(index);
    }

    public void toggle() {
        if (service != null) service.toggle();
    }

    public void next() {
        if (service != null) service.next();
    }

    public void previous() {
        if (service != null) service.previous();
    }

    public void seek(int ms) {
        if (service != null) service.seek(ms);
    }

    public void setShuffle(boolean on) {
        if (service != null) service.setShuffle(on);
    }

    public void cycleRepeat() {
        if (service != null) service.cycleRepeat();
    }
}
