package com.k4811.launcher.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;

/**
 * Head unit master mute: sends com.nwd.action.ACTION_SET_MUTE (extra_mute) and tracks
 * com.nwd.ACTION_MUTE_STATE_CHANGE (extra_mute_state, 1 = muted) and Settings.System mcu_mute_state.
 */
public final class MuteClient {

    public interface Listener {
        void onMute(boolean muted);
    }

    private static final String ACTION_SET_MUTE = "com.nwd.action.ACTION_SET_MUTE";
    private static final String EXTRA_MUTE = "extra_mute";
    private static final String ACTION_MUTE_STATE_CHANGE = "com.nwd.ACTION_MUTE_STATE_CHANGE";
    private static final String EXTRA_MUTE_STATE = "extra_mute_state";
    private static final String KEY_MUTE_STATE = "mcu_mute_state";

    private final Context context;
    private Listener listener;
    private boolean muted;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            update(intent.getByteExtra(EXTRA_MUTE_STATE, (byte) (muted ? 1 : 0)) == 1);
        }
    };

    public MuteClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        if (!registered) {
            try {
                context.registerReceiver(receiver, new IntentFilter(ACTION_MUTE_STATE_CHANGE));
                registered = true;
            } catch (Exception ignored) {
            }
        }
        refresh();
    }

    public void refresh() {
        int v = 0;
        try {
            v = Settings.System.getInt(context.getContentResolver(), KEY_MUTE_STATE, 0);
        } catch (Exception ignored) {
        }
        muted = v == 1;
        if (listener != null) listener.onMute(muted);
    }

    public boolean isMuted() {
        return muted;
    }

    public void toggle() {
        setMuted(!muted);
    }

    public void setMuted(boolean on) {
        Intent i = new Intent(ACTION_SET_MUTE);
        i.putExtra(EXTRA_MUTE, on);
        context.sendBroadcast(i);
        // Update now; the head unit's state broadcast confirms (or corrects) it afterwards.
        update(on);
    }

    private void update(boolean m) {
        muted = m;
        if (listener != null) listener.onMute(m);
    }

    public void stop() {
        if (registered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            registered = false;
        }
    }
}
