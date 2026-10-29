package com.k4811.common.bt;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.bt.BTDevice;
import com.bt.BTFeature;
import com.bt.BTPIMCallback;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client of the head unit's Bluetooth service. Binder calls run off the main thread.
 */
public final class BtClient {

    public interface QueryListener {
        void onResult(int callState, boolean musicPlaying, boolean a2dpConnected);
    }

    public interface MusicInfoListener {
        void onInfo(boolean a2dpConnected, boolean musicPlaying, String deviceName, String pin);
    }

    public interface ContactListener {
        void onContact(String name, String number);

        void onCallLog(int type, String name, String number, String extra);
    }

    public interface PhoneInfoListener {
        void onInfo(boolean hfp, boolean powered, String localName, String pin,
                    String remoteName, int autoFeatures, int callState);
    }

    private static final String TAG = "K4811Bt";
    private static final String ACTION_START = "com.bt.ACTION_START_SERVICE";

    public static final int AUTO_ANSWER = 1;
    public static final int AUTO_CONNECT = 2;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ContactListener> contactListeners = new CopyOnWriteArrayList<>();

    private volatile BTFeature feature;
    private QueryListener pendingQuery;
    private MusicInfoListener pendingMusicInfo;
    private boolean bound;
    private boolean released;
    private boolean reconnectPending;
    private boolean loggedMissing;
    private boolean pimRegistered;

    private final BTPIMCallback.Stub pimCallback = new BTPIMCallback.Stub() {
        @Override
        public void onGetContact(String name, String number) {
            final String n = name == null ? "" : name.trim();
            final String num = number == null ? "" : number.trim();
            main.post(() -> {
                for (ContactListener l : contactListeners) {
                    try {
                        l.onContact(n, num);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @Override
        public void onGetCallLog(int type, String name, String number, String extra) {
            // The 2nd text is the number.
            final String n = name == null ? "" : name.trim();
            final String num = number == null ? "" : number.trim();
            final String x = extra == null ? "" : extra.trim();
            main.post(() -> {
                for (ContactListener l : contactListeners) {
                    try {
                        l.onCallLog(type, n, num, x);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @Override
        public void onGetSMS(String address, String body, int type) {
        }

        @Override
        public void onGetA2dpProgress(int position, int duration) {
        }
    };

    public BtClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /** true when already connected to the Bluetooth service (commands are not dropped). */
    public boolean isConnected() {
        return feature != null;
    }

    public void connect() {
        if (released || bound || feature != null) return;
        Intent intent = resolve();
        if (intent == null) {
            if (!loggedMissing) {
                loggedMissing = true;
                Log.d(TAG, "Service " + ACTION_START + " not found");
            }
            scheduleReconnect();
            return;
        }
        loggedMissing = false;
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.d(TAG, "startService failed: " + e.getMessage());
        }
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.d(TAG, "bindService failed: " + e.getMessage());
            bound = false;
        }
        if (!bound) scheduleReconnect();
    }

    public void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        pendingQuery = null;
        pendingMusicInfo = null;
        contactListeners.clear();
        final BTFeature f = feature;
        feature = null;
        worker.execute(() -> {
            try {
                if (f != null && pimRegistered) f.unregistPIMCallback(pimCallback);
            } catch (Exception ignored) {
            }
            pimRegistered = false;
        });
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Exception ignored) {
            }
        }
        bound = false;
        worker.shutdown();
    }

    public void addContactListener(ContactListener listener) {
        if (listener != null) contactListeners.add(listener);
    }

    public void removeContactListener(ContactListener listener) {
        contactListeners.remove(listener);
    }

    public void query(QueryListener listener) {
        if (released || listener == null) return;
        if (feature == null) {
            pendingQuery = listener;
            connect();
            return;
        }
        runQuery(listener);
    }

    public void queryMusicInfo(MusicInfoListener listener) {
        if (released || listener == null) return;
        if (feature == null) {
            pendingMusicInfo = listener;
            connect();
            return;
        }
        runMusicInfo(listener);
    }

    public void queryPhoneInfo(PhoneInfoListener listener) {
        if (released || listener == null) return;
        worker.execute(() -> {
            BTFeature f = feature;
            if (f == null) {
                connect();
                main.postDelayed(() -> queryPhoneInfo(listener), 400);
                return;
            }
            boolean hfp = false;
            boolean powered = false;
            String local = "";
            String pin = "";
            String remote = "";
            int auto = 0;
            int call = 0;
            try {
                hfp = f.isConnectHFP();
            } catch (Exception e) {
                Log.d(TAG, "isConnectHFP: " + e.getMessage());
            }
            try {
                powered = f.isBlueToothPowerOn();
            } catch (Exception e) {
                Log.d(TAG, "isBlueToothPowerOn: " + e.getMessage());
            }
            try {
                String n = f.getLocalDeviceName();
                if (n != null) local = n.trim();
            } catch (Exception e) {
                Log.d(TAG, "getLocalDeviceName: " + e.getMessage());
            }
            try {
                String p = f.getPinCode();
                if (p != null) pin = p.trim();
            } catch (Exception e) {
                Log.d(TAG, "getPinCode: " + e.getMessage());
            }
            try {
                BTDevice d = f.getConnectDevice();
                if (d != null && d.name != null) remote = d.name.trim();
            } catch (Exception e) {
                Log.d(TAG, "getConnectDevice: " + e.getMessage());
            }
            try {
                auto = f.getAutoFeature();
            } catch (Exception e) {
                Log.d(TAG, "getAutoFeature: " + e.getMessage());
            }
            try {
                call = f.getCallState();
            } catch (Exception e) {
                Log.d(TAG, "getCallState: " + e.getMessage());
            }
            boolean h = hfp;
            boolean pwr = powered;
            String ln = local;
            String pn = pin;
            String rn = remote;
            int af = auto;
            int cs = call;
            main.post(() -> {
                if (!released) listener.onInfo(h, pwr, ln, pn, rn, af, cs);
            });
        });
    }

    public void playControl(int cmd) {
        command("playControl(" + cmd + ")", f -> f.playControl(cmd));
    }

    public void setMusicMute(boolean mute) {
        command("setBtMusicMute(" + mute + ")", f -> f.setBtMusicMute(mute));
    }

    public void queryId3() {
        command("queryA2dpId3Info()", BTFeature::queryA2dpId3Info);
    }

    public void answer() {
        command("answerCall()", BTFeature::answerCall);
    }

    public void hangup() {
        command("cancelCall()", BTFeature::cancelCall);
    }

    public void dial(String number) {
        if (number == null || number.isEmpty()) return;
        command("dial_BC(" + number + ")", f -> f.dial_BC(number));
    }

    public void sendDtmf(char digit) {
        command("sendDTMF(" + digit + ")", f -> f.sendDTMF(digit));
    }

    public void setMicMute(boolean mute) {
        command("setMute(" + mute + ")", f -> f.setMute(mute));
    }

    /**
     * Zera o volume do alto-falante do BT (silencia o toque sem recusar a chamada) e devolve, na
     * thread principal, o volume que estava (-1 se não deu).
     */
    public void muteSpeaker(ValueListener previous) {
        speakerCall(f -> {
            int v = f.getSpeakerVolume();
            f.setSpeakerVolume(0);
            return v;
        }, previous, 10);
    }

    /** Devolve o volume do alto-falante do BT; {@code done} recebe o valor gravado (-1 se não deu). */
    public void setSpeakerVolume(int volume, ValueListener done) {
        speakerCall(f -> {
            f.setSpeakerVolume(volume);
            return volume;
        }, done, 10);
    }

    public interface ValueListener {
        void onValue(int value);
    }

    private interface IntRemote {
        int run(BTFeature f) throws Exception;
    }

    /** Como {@link #command}, mas espera o serviço conectar (até {@code tries} × 400 ms) e sempre responde. */
    private void speakerCall(IntRemote remote, ValueListener listener, int tries) {
        if (released) {
            if (listener != null) listener.onValue(-1);
            return;
        }
        worker.execute(() -> {
            BTFeature f = feature;
            if (f == null) {
                connect();
                if (tries > 0) {
                    main.postDelayed(() -> speakerCall(remote, listener, tries - 1), 400);
                } else if (listener != null) {
                    main.post(() -> listener.onValue(-1));
                }
                return;
            }
            int v = -1;
            try {
                v = remote.run(f);
            } catch (Exception e) {
                Log.d(TAG, "speaker volume failed: " + e.getMessage());
            }
            int r = v;
            if (listener != null) main.post(() -> listener.onValue(r));
        });
    }

    public void changeSoundChannel() {
        command("changeSoundChannel()", BTFeature::changeSoundChannel);
    }

    public void requestContacts() {
        ensurePim();
        command("getContact_CB()", BTFeature::getContact_CB);
    }

    public void loadContactsFromPhone() {
        ensurePim();
        command("loadContactFromPhone()", BTFeature::loadContactFromPhone);
    }

    public void requestCallLog() {
        ensurePim();
        command("getCallLog_CB()", BTFeature::getCallLog_CB);
    }

    public void setLocalName(String name) {
        if (name == null) return;
        command("setLocalDeviceName()", f -> f.setLocalDeviceName(name));
    }

    public void setPin(String pin) {
        if (pin == null) return;
        command("setPinCode()", f -> f.setPinCode(pin));
    }

    public void setAutoFeature(int feature, boolean on) {
        command("setAutoFeature_BC(" + feature + "," + on + ")", f -> f.setAutoFeature_BC(feature, on));
    }

    private void ensurePim() {
        command("registPIMCallback()", f -> {
            if (!pimRegistered) {
                f.registPIMCallback(pimCallback);
                pimRegistered = true;
            }
        });
    }

    private void runQuery(QueryListener listener) {
        worker.execute(() -> {
            BTFeature f = feature;
            if (f == null || released) return;
            int call = 0;
            boolean playing = false;
            boolean a2dp = false;
            try {
                call = f.getCallState();
            } catch (Exception e) {
                Log.d(TAG, "getCallState: " + e.getMessage());
            }
            try {
                playing = f.isBTMusicPlaying();
            } catch (Exception e) {
                Log.d(TAG, "isBTMusicPlaying: " + e.getMessage());
            }
            try {
                a2dp = f.isConnectA2DP();
            } catch (Exception e) {
                Log.d(TAG, "isConnectA2DP: " + e.getMessage());
            }
            if (playing) {
                try {
                    f.queryA2dpId3Info();
                } catch (Exception e) {
                    Log.d(TAG, "queryA2dpId3Info: " + e.getMessage());
                }
            }
            int callState = call;
            boolean music = playing;
            boolean connected = a2dp;
            main.post(() -> {
                if (!released) listener.onResult(callState, music, connected);
            });
        });
    }

    private void runMusicInfo(MusicInfoListener listener) {
        worker.execute(() -> {
            BTFeature f = feature;
            if (f == null || released) return;
            boolean playing = false;
            boolean a2dp = false;
            String name = "";
            String pin = "";
            try {
                a2dp = f.isConnectA2DP();
            } catch (Exception e) {
                Log.d(TAG, "isConnectA2DP: " + e.getMessage());
            }
            try {
                playing = f.isBTMusicPlaying();
            } catch (Exception e) {
                Log.d(TAG, "isBTMusicPlaying: " + e.getMessage());
            }
            try {
                BTDevice device = f.getConnectDevice();
                if (device != null && device.name != null) name = device.name.trim();
            } catch (Exception e) {
                Log.d(TAG, "getConnectDevice: " + e.getMessage());
            }
            try {
                String p = f.getPinCode();
                if (p != null) pin = p.trim();
            } catch (Exception e) {
                Log.d(TAG, "getPinCode: " + e.getMessage());
            }
            if (a2dp) {
                try {
                    f.queryA2dpId3Info();
                } catch (Exception e) {
                    Log.d(TAG, "queryA2dpId3Info: " + e.getMessage());
                }
            }
            boolean music = playing;
            boolean connected = a2dp;
            String deviceName = name;
            String pinCode = pin;
            main.post(() -> {
                if (!released) listener.onInfo(connected, music, deviceName, pinCode);
            });
        });
    }

    private interface Remote {
        void run(BTFeature f) throws Exception;
    }

    private void command(String name, Remote remote) {
        if (released) return;
        worker.execute(() -> {
            BTFeature f = feature;
            if (f == null) {
                Log.d(TAG, "Not connected, ignored: " + name);
                return;
            }
            try {
                remote.run(f);
            } catch (Exception e) {
                Log.d(TAG, name + " failed: " + e.getMessage());
            }
        });
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            feature = BTFeature.Stub.asInterface(service);
            Log.d(TAG, "Connected to the Bluetooth service");
            main.post(() -> {
                QueryListener q = pendingQuery;
                pendingQuery = null;
                if (q != null && !released) runQuery(q);
                MusicInfoListener m = pendingMusicInfo;
                pendingMusicInfo = null;
                if (m != null && !released) runMusicInfo(m);
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            feature = null;
            bound = false;
            pimRegistered = false;
            Log.d(TAG, "Bluetooth service disconnected");
            scheduleReconnect();
        }
    };

    private void scheduleReconnect() {
        if (released || reconnectPending) return;
        reconnectPending = true;
        main.postDelayed(() -> {
            reconnectPending = false;
            if (released || feature != null) return;
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (Exception ignored) {
                }
                bound = false;
            }
            connect();
        }, loggedMissing ? 60000 : 15000); // serviço que nem existe no aparelho: tenta bem menos
    }

    private Intent resolve() {
        Intent implicit = new Intent(ACTION_START);
        List<ResolveInfo> list;
        try {
            list = context.getPackageManager().queryIntentServices(implicit, 0);
        } catch (Exception e) {
            return null;
        }
        if (list == null || list.isEmpty()) return null;
        ServiceInfo info = list.get(0).serviceInfo;
        Intent explicit = new Intent(implicit);
        explicit.setComponent(new ComponentName(info.packageName, info.name));
        return explicit;
    }
}
