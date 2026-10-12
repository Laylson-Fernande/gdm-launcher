package com.garagemdomadeira.nwd.launcher.radio;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.nwd.radio.service.RadioCallback;
import com.nwd.radio.service.RadioFeature;
import com.nwd.radio.service.data.Frequency;
import com.nwd.radio.service.data.RadioPoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the NWD radio service. All Binder calls run on a worker thread; state
 * changes and log lines are delivered on the main thread.
 */
public final class RadioClient {

    public interface Listener {
        void onRadioState(RadioState state);
    }

    public interface LogListener {
        void onLog(List<String> lines);
    }

    private interface Mutation {
        void apply(RadioState s);
    }

    private interface Command {
        void run(RadioFeature f) throws RemoteException;
    }

    private static final String TAG = "GaragemRadio";
    /** "No memory" value for the 3rd parameter of setCurrentFrequency. */
    private static final int NO_PRESET = 0;
    private static final int MAX_LOG = 300;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> logLines = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    /**
     * Tune we requested that is not confirmed yet. During {@link #TUNE_GRACE_MS}, service notices
     * with a different frequency are considered stale (older than the request) and ignored.
     */
    private static final long TUNE_GRACE_MS = 1500;
    private volatile int pendingTune = -1;
    private volatile long pendingTuneAt;

    /**
     * false on the home screen: the connection only reads the state. Does not send
     * {@code ACTION_MEDIA_PLAY}, so the home neither turns the radio on nor cuts what is playing.
     */
    private volatile boolean playOnSync = true;

    /** Waits for the station to settle (seek, dial drag) before saving it as the last one. */
    private static final long SAVE_LAST_DELAY_MS = 2000;
    /**
     * First frequency seen on this connection: only a baseline. At power-on it is the service's
     * default (it forgets the last station), so it must not overwrite the saved one.
     */
    private volatile boolean baselineSeen;
    /** Radio screen: the last saved station was already resumed on this connection. */
    private volatile boolean resumed;
    /** Checks the resume once the stale-notification window is over (the MCU may still be starting). */
    private static final long RESUME_CHECK_MS = TUNE_GRACE_MS + 100;
    /** User commands sent so far: a resume retry must not undo what the user just did. */
    private volatile int commandCount;

    /** Pause between commands when reapplying memories: the MCU needs to tune before saving. */
    private static final long RESTORE_STEP_MS = 200;
    /** Gives up waiting for AMS to finish after this (the mirror is written along the way). */
    private static final long AMS_TIMEOUT_MS = 120_000;
    /** Memories as reported by the service, before applying the props (index = band). */
    private final Frequency[][] serviceLists = new Frequency[5][];
    /** Bands already checked against the props on this connection. */
    private final boolean[] reconciled = new boolean[5];
    /** true while memories are being reapplied to the service: tuning notifications are ignored. */
    private volatile boolean restoring;
    /** Band with AMS in progress (-1 = none). During that time the service is in charge. */
    private volatile int amsBand = -1;
    private volatile boolean amsSawBusy;

    private volatile RadioFeature feature;
    private volatile RadioState state = new RadioState();
    private Listener listener;
    private LogListener logListener;
    private boolean bound;
    private boolean released;
    private boolean receiverRegistered;

    public RadioClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) l.onRadioState(state);
    }

    public void setLogListener(LogListener l) {
        logListener = l;
        if (l != null) {
            synchronized (logLines) {
                l.onLog(new ArrayList<>(logLines));
            }
        }
    }

    public RadioState getState() {
        return state;
    }

    // ------------------------------------------------------------------ connection

    public void connect() {
        connect(true);
    }

    /** @param announcePlayback false to only observe. See {@link #playOnSync}. */
    public void connect(boolean announcePlayback) {
        if (released || bound) return;
        playOnSync = announcePlayback;
        Intent intent = resolveServiceIntent();
        if (intent == null) {
            log("Service " + NwdConstants.ACTION_RADIO_SERVICE + " not found");
            mutate(s -> s.connection = RadioState.Connection.NOT_FOUND);
            return;
        }
        mutate(s -> s.connection = RadioState.Connection.CONNECTING);
        log("Connecting to " + intent.getComponent().flattenToShortString());
        try {
            context.startService(intent);
        } catch (Exception e) {
            log("startService failed: " + e.getMessage());
        }
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            log("bindService failed: " + e.getMessage());
            bound = false;
        }
        if (!bound) {
            mutate(s -> s.connection = RadioState.Connection.NOT_FOUND);
            scheduleReconnect();
        }
        registerReceiver();
    }

    public void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        final RadioFeature f = feature;
        feature = null;
        worker.execute(() -> {
            try {
                if (f != null) f.unRegistCallback(callback);
            } catch (Exception ignored) {
            }
        });
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Exception ignored) {
            }
        }
        bound = false;
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
        }
        receiverRegistered = false;
        worker.shutdown();
    }

    private Intent resolveServiceIntent() {
        Intent implicit = new Intent(NwdConstants.ACTION_RADIO_SERVICE);
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

    private void scheduleReconnect() {
        if (released) return;
        main.postDelayed(() -> {
            if (!released && feature == null) {
                if (bound) {
                    try {
                        context.unbindService(connection);
                    } catch (Exception ignored) {
                    }
                }
                bound = false;
                connect(playOnSync);
            }
        }, 3000);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final RadioFeature f = RadioFeature.Stub.asInterface(service);
            feature = f;
            Arrays.fill(reconciled, false); // the service may have restarted and forgotten the memories
            baselineSeen = false;
            resumed = false;
            log("Connected to the radio service");
            mutate(s -> s.connection = RadioState.Connection.CONNECTED);
            worker.execute(() -> initialSync(f));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            feature = null;
            log("Service disconnected");
            mutate(s -> s.connection = RadioState.Connection.DISCONNECTED);
            scheduleReconnect();
        }
    };

    private void initialSync(RadioFeature f) {
        safe("registCallback", () -> f.registCallback(callback));
        safe("getRadioType", () -> {
            int type = f.getRadioType();
            log("Radio type: " + type + " (0 = MCU, 1 = Allwinner, 3 = SoC FM chip)");
            mutate(s -> s.radioType = type);
        });
        safe("getRadioPoint", () -> applyRadioPoints(f.getRadioPoint()));
        safe("getCurrentFrequency", () -> {
            Frequency cur = f.getCurrentFrequency();
            if (cur != null) applyFrequency(cur, -1);
        });
        safe("getRadioState", () -> {
            int v = f.getRadioState();
            mutate(s -> s.radioState = v);
        });
        safe("isHasStrero", () -> {
            boolean v = f.isHasStrero();
            mutate(s -> s.stereoSignal = v);
        });
        safe("isStreroOn", () -> {
            boolean v = f.isStreroOn();
            mutate(s -> s.stereoOn = v);
        });
        safe("isNearOn", () -> {
            boolean v = f.isNearOn();
            mutate(s -> s.locOn = v);
        });
        safe("getRtMessage", () -> {
            String rt = clean(f.getRtMessage());
            mutate(s -> s.radioText = rt);
        });
        safe("getPTYType", () -> {
            int v = f.getPTYType();
            mutate(s -> s.pty = v);
        });
        refreshRds(f);
        refreshPresets(f);
        // Ask the service to resend the whole state through the callback.
        safe("sendRadioCommand(-1,-1)", () -> f.sendRadioCommand((byte) -1, (byte) -1));
        if (playOnSync && state.radioType > 0) sendMediaPlay();
        // After the radio is the active source, so the MCU accepts the tune.
        if (playOnSync) resumeLast(f);
    }

    private void refreshRds(RadioFeature f) {
        safe("getRDSState", () -> {
            boolean af = f.getRDSState(Band.RDS_AF);
            boolean ta = f.getRDSState(Band.RDS_TA);
            boolean tp = f.getRDSState(Band.RDS_TP);
            mutate(s -> {
                s.afOn = af;
                s.taOn = ta;
                s.tp = tp;
            });
        });
    }

    private void refreshPresets(RadioFeature f) {
        safe("getPrefabFrequency", () -> {
            Frequency[] list = f.getPrefabFrequency();
            if (list != null) applyPresets(list);
        });
    }

    // ------------------------------------------------------------------ callback

    private final RadioCallback.Stub callback = new RadioCallback.Stub() {
        @Override
        public void notifyState(byte st) {
            mutate(s -> s.radioState = st);
            checkAmsDone();
        }

        @Override
        public void notifyCurrentFrequency(byte band, int frequency, String psName, int presetIndex) {
            applyFrequency(new Frequency(band, frequency, psName), presetIndex);
        }

        @Override
        public void notifyNearOn(boolean on) {
            mutate(s -> s.locOn = on);
        }

        @Override
        public void notifyStereo(boolean isStereo) {
            mutate(s -> s.stereoSignal = isStereo);
        }

        @Override
        public void notifyStereoOn(boolean on) {
            mutate(s -> s.stereoOn = on);
        }

        @Override
        public void notifyRDSStateChange() {
            final RadioFeature f = feature;
            if (f != null) worker.execute(() -> refreshRds(f));
        }

        @Override
        public void notifyCurrentPTYType(byte pty) {
            mutate(s -> s.pty = pty);
        }

        @Override
        public void notifyPrefabFrequency(Frequency[] list) {
            if (list != null) applyPresets(list);
        }

        @Override
        public void notifyPrefabPTYType(byte pty) {
        }

        @Override
        public void notifyRadioPoint(RadioPoint[] points) {
            applyRadioPoints(points);
        }

        @Override
        public void notifyCurrentIsTA(boolean ta) {
            mutate(s -> s.taActive = ta);
        }

        @Override
        public void notifyRdsShowState(boolean show) {
        }

        @Override
        public void notifyRtMessage(String rt) {
            String text = clean(rt);
            mutate(s -> s.radioText = text);
        }

        @Override
        public void notifyRadioScanState(int scan) {
            mutate(s -> s.scanState = scan);
            checkAmsDone();
        }
    };

    /** true if the received frequency is a stale notification that would undo the just-requested tune. */
    private boolean isStaleFrequency(int frequency) {
        int target = pendingTune;
        if (target < 0) return false;
        if (frequency == target) {
            pendingTune = -1; // service confirmed
            return false;
        }
        if (System.currentTimeMillis() - pendingTuneAt < TUNE_GRACE_MS) {
            log("Ignored stale notification for " + frequency + " (waiting for " + target + ")");
            return true;
        }
        pendingTune = -1;
        return false;
    }

    private void applyFrequency(Frequency f, int presetIndex) {
        if (restoring || isStaleFrequency(f.frequency)) return;
        RadioState before = state;
        boolean changed = f.frequency != before.frequency || f.bandType != before.band;
        String ps = cleanPs(f.psName);
        mutate(s -> {
            // When the station changes, the old RadioText is no longer valid.
            if (f.frequency != s.frequency || f.bandType != s.band) s.radioText = "";
            s.band = f.bandType;
            s.frequency = f.frequency;
            s.psName = ps;
            if (presetIndex >= 0) s.presetIndex = presetIndex;
        });
        if (!baselineSeen) {
            baselineSeen = true;
        } else if (changed) {
            scheduleSaveLast();
        }
    }

    // ------------------------------------------------------------------ last station

    private final Runnable saveLast = this::saveLastNow;

    private void saveLastNow() {
        RadioState s = state;
        if (restoring || amsBand >= 0 || s.isBusy()) return; // the final station triggers a new save
        PresetStore.writeLast(context, s.band, s.frequency);
    }

    /** Saves the current station as the last one once it settles. */
    private void scheduleSaveLast() {
        // Radio screen: only after resuming, or the default frequency would overwrite the saved one.
        if (released || (playOnSync && !resumed)) return;
        main.removeCallbacks(saveLast);
        main.postDelayed(saveLast, SAVE_LAST_DELAY_MS);
    }

    /**
     * Radio screen: tunes the last station saved in the props. The service forgets it when the
     * head unit powers off and starts on a default frequency. Runs on the worker thread.
     */
    private void resumeLast(RadioFeature f) {
        int[] last = PresetStore.readLast();
        RadioState s = state;
        if (last != null && (last[0] != s.band || last[1] != s.frequency)) {
            final int band = last[0];
            final int freq = last[1];
            Frequency[] svc = serviceLists[band];
            int[] stored = PresetStore.read(band);
            int slot = NO_PRESET;
            for (int i = 0; i < stored.length; i++) {
                // With the memory number, the service tunes what it has in that slot: only use it if it matches.
                boolean known = svc != null && i < svc.length && svc[i] != null && svc[i].frequency == freq;
                if (stored[i] == freq && known) {
                    slot = i + 1;
                    break;
                }
            }
            final int presetSlot = slot;
            log("Resuming last station " + Band.label(band) + " " + freq);
            pendingTune = freq;
            pendingTuneAt = System.currentTimeMillis();
            mutate(st -> {
                st.band = band;
                st.frequency = freq;
                st.psName = null;
                st.radioText = "";
                st.presetIndex = presetSlot;
            });
            safe("setCurrentFrequency(" + freq + ", " + band + ", " + presetSlot + ")",
                () -> f.setCurrentFrequency(freq, (byte) band, presetSlot));
            // At power-on the MCU may ignore the first tune: check once and send it again.
            final int commandsBefore = commandCount;
            main.postDelayed(() -> {
                RadioState now = state;
                if (released || commandCount != commandsBefore) return;
                if (now.band == band && now.frequency == freq) return;
                log("Last station not applied yet, sending again");
                pendingTune = freq;
                pendingTuneAt = System.currentTimeMillis();
                command("setCurrentFrequency(" + freq + ", " + band + ", " + presetSlot + ")",
                    cur -> cur.setCurrentFrequency(freq, (byte) band, presetSlot));
            }, RESUME_CHECK_MS);
        }
        resumed = true;
    }

    private synchronized void applyPresets(Frequency[] list) {
        if (restoring) return; // the restore ends with its own refresh
        int band = -1;
        for (Frequency f : list) {
            if (f != null) {
                band = f.bandType;
                break;
            }
        }
        if (band < 0) band = state.band; // whole list empty: the service forgot the current band's memories
        if (band < 0 || band > 4) return;
        serviceLists[band] = list;
        int[] service = new int[PresetStore.SLOTS];
        for (int i = 0; i < service.length && i < list.length; i++) {
            if (list[i] != null) service[i] = list[i].frequency;
        }

        int[] stored = PresetStore.read(band);
        if (amsBand == band || !PresetStore.hasAny(stored)) {
            // AMS just finished scanning, or nothing mirrored yet (first use): the service is in charge.
            PresetStore.writeBand(context, band, service);
            reconciled[band] = true;
            stored = service;
        } else if (!reconciled[band]) {
            reconciled[band] = true;
            if (playOnSync && differs(stored, service)) restorePresets(band, stored, service);
        }

        // The props are the source of truth; the RDS name comes from the service when the frequency matches.
        Preset[] presets = new Preset[PresetStore.SLOTS];
        for (int i = 0; i < presets.length; i++) {
            Frequency f = i < list.length ? list[i] : null;
            String ps = f != null && f.frequency == stored[i] ? cleanPs(f.psName) : null;
            presets[i] = new Preset(i, band, stored[i], ps);
        }
        final int b = band;
        mutate(s -> s.presets[b] = presets);
    }

    /** true if any slot saved in the props differs in the service. Empty slots in the props don't count. */
    private static boolean differs(int[] stored, int[] service) {
        for (int i = 0; i < stored.length; i++) {
            if (stored[i] > 0 && stored[i] != service[i]) return true;
        }
        return false;
    }

    /**
     * Re-teaches the MCU the memories it forgot: tunes and saves each mismatched slot, then
     * returns to the previous station. Runs on the worker thread; user commands wait.
     */
    private void restorePresets(int band, int[] stored, int[] service) {
        worker.execute(() -> {
            RadioFeature f = feature;
            if (f == null) {
                reconciled[band] = false;
                return;
            }
            RadioState s = state;
            int origFreq = s.frequency;
            int origBand = s.band;
            log("Reapplying " + Band.label(band) + " presets from props");
            restoring = true;
            try {
                for (int i = 0; i < stored.length; i++) {
                    if (stored[i] <= 0 || stored[i] == service[i]) continue;
                    final int slot = i;
                    log("→ M" + (slot + 1) + " = " + stored[slot]);
                    safe("restore M" + (slot + 1), () -> {
                        f.setCurrentFrequency(stored[slot], (byte) band, NO_PRESET);
                        Thread.sleep(RESTORE_STEP_MS);
                        f.saveCurrentFrequency((byte) slot);
                        Thread.sleep(RESTORE_STEP_MS);
                    });
                }
                int origSlot = NO_PRESET;
                if (origBand == band) {
                    for (int i = 0; i < stored.length; i++) {
                        if (stored[i] == origFreq) {
                            origSlot = i + 1;
                            break;
                        }
                    }
                }
                final int slot = origSlot;
                pendingTune = origFreq;
                pendingTuneAt = System.currentTimeMillis();
                safe("return to " + origFreq, () -> f.setCurrentFrequency(origFreq, (byte) origBand, slot));
            } finally {
                restoring = false;
            }
            refreshPresets(f);
        });
    }

    /** Called on every state change: when AMS stops seeking, mirrors the final list. */
    private void checkAmsDone() {
        int band = amsBand;
        if (band < 0) return;
        if (state.isBusy()) {
            amsSawBusy = true;
            return;
        }
        if (!amsSawBusy) return;
        amsSawBusy = false;
        main.postDelayed(() -> {
            RadioFeature cur = feature;
            if (cur == null || released) {
                amsBand = -1;
                return;
            }
            worker.execute(() -> {
                refreshPresets(cur);
                if (amsBand == band) amsBand = -1;
                log("AMS finished, " + Band.label(band) + " presets saved to props");
            });
        }, 800);
    }

    private void applyRadioPoints(RadioPoint[] points) {
        if (points == null) return;
        // Service order: [0] = FM, [1] = AM, [2] = FML.
        BandRange fm = points.length > 0 && points[0] != null
            ? new BandRange(points[0].frequencyMin, points[0].frequencyMax, points[0].frequencyStep) : null;
        BandRange am = points.length > 1 && points[1] != null
            ? new BandRange(points[1].frequencyMin, points[1].frequencyMax, points[1].frequencyStep) : null;
        log("Ranges: FM=" + fm + " AM=" + am);
        mutate(s -> {
            if (fm != null && fm.isValid()) s.fmRange = fm;
            if (am != null && am.isValid()) s.amRange = am;
        });
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String cleanPs(String ps) {
        if (ps == null) return null;
        String t = ps.trim();
        if (t.isEmpty() || "null".equals(t)) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 33 && c <= 126) || c > 160) return t;
        }
        return null;
    }

    // ------------------------------------------------------------------ demo

    /**
     * Fills in a sample state to preview the layout without the NWD service (emulator).
     * Enabled with: adb shell am start -n com.garagemdomadeira.nwd.launcher/.RadioActivity --ez demo true
     */
    public void loadDemo() {
        log("Demo mode (no radio service)");
        Preset[] fm1 = new Preset[12];
        int[] freqs = {8910, 9470, 9850, 0, 10170, 10390, 0, 0, 9030, 0, 0, 0};
        String[] names = {"RADIO X", null, "STATION NAME", null, "ROCK FM", null, null, null, null, null, null, null};
        for (int i = 0; i < 12; i++) fm1[i] = new Preset(i, Band.FM1, freqs[i], names[i]);
        mutate(s -> {
            s.connection = RadioState.Connection.CONNECTED;
            s.band = Band.FM1;
            s.frequency = 9850;
            s.psName = "STATION NAME";
            s.presetIndex = 3;
            s.radioText = "scrolling radio text (RadioText) — now playing: artist - song";
            s.stereoSignal = true;
            s.tp = true;
            s.pty = 10;
            s.afOn = true;
            s.stereoOn = true;
            s.presets[Band.FM1] = fm1;
        });
    }

    // ------------------------------------------------------------------ commands

    public void seek(boolean up) {
        command("seek(" + up + ")", f -> f.seek(up));
    }

    public void search(boolean up) {
        command("search(" + up + ")", f -> f.search(up));
    }

    public void autoStore() {
        int band = state.band;
        amsBand = band;
        amsSawBusy = false;
        main.postDelayed(() -> {
            if (amsBand == band) amsBand = -1;
        }, AMS_TIMEOUT_MS);
        command("AMS()", RadioFeature::AMS);
    }

    public void intro() {
        command("INTRO()", RadioFeature::INTRO);
    }

    public void nextBand() {
        command("changeBand()", RadioFeature::changeBand);
    }

    /**
     * Goes to the previous/next saved station of the current band, skipping empty memories and
     * wrapping around at the end of the list. If the current station is a memory, follows the
     * order M1..M12; if not, picks the memory with the frequency immediately above/below the
     * current one. Only uses the service's prefeb() if there is no known memory.
     */
    public void presetStep(boolean next) {
        RadioState s = state;
        Preset[] list = s.currentPresets();
        List<Preset> saved = new ArrayList<>();
        for (Preset p : list) {
            if (p != null && !p.isEmpty()) saved.add(p);
        }
        if (saved.isEmpty()) {
            command("prefeb(" + next + ")", f -> f.prefeb(next));
            return;
        }

        int current = -1; // position in "saved" of the current station, if it is a memory
        for (int i = 0; i < saved.size(); i++) {
            Preset p = saved.get(i);
            if ((s.presetIndex > 0 && p.index == s.presetIndex - 1) || p.frequency == s.frequency) {
                current = i;
                if (p.index == s.presetIndex - 1) break;
            }
        }

        Preset target;
        if (current >= 0) {
            int n = saved.size();
            target = saved.get(((current + (next ? 1 : -1)) % n + n) % n);
        } else {
            // Free station: the nearest memory above (next) or below (previous).
            Preset best = null;
            Preset wrap = null;
            for (Preset p : saved) {
                boolean candidate = next ? p.frequency > s.frequency : p.frequency < s.frequency;
                if (candidate && (best == null || (next ? p.frequency < best.frequency : p.frequency > best.frequency))) {
                    best = p;
                }
                if (wrap == null || (next ? p.frequency < wrap.frequency : p.frequency > wrap.frequency)) wrap = p;
            }
            target = best != null ? best : wrap;
        }
        log("Preset " + (next ? "next" : "previous") + " → M" + (target.index + 1));
        playPreset(target);
    }

    /** Selects FM1..AM2 directly (protocol actions 14..18). */
    public void selectBand(int band) {
        if (band < 0 || band > 4) return;
        command("sendRadioCommand(" + (14 + band) + ", 1)", f -> f.sendRadioCommand((byte) (14 + band), (byte) 1));
    }

    public void tune(int frequency) {
        RadioState s = state;
        int target = s.range().snap(frequency);
        int band = s.band;
        pendingTune = target;
        pendingTuneAt = System.currentTimeMillis();
        mutate(st -> {
            st.frequency = target;
            st.psName = null;
            st.radioText = "";
            st.presetIndex = 0;
        });
        // 3rd parameter = memory number (1..12); 0 = none.
        // Sending -1 becomes "memory 255" and the service jumps back to a saved station.
        command("setCurrentFrequency(" + target + ", " + band + ", " + NO_PRESET + ")",
            f -> f.setCurrentFrequency(target, (byte) band, NO_PRESET));
    }

    /** One frequency step (0.1 MHz on FM), wrapping around at the band edges. */
    public void step(boolean up) {
        RadioState s = state;
        BandRange r = s.range();
        int next = s.frequency + (up ? r.step : -r.step);
        if (next > r.max) next = r.min;
        if (next < r.min) next = r.max;
        tune(next);
    }

    public void playPreset(Preset p) {
        if (p == null || p.isEmpty()) return;
        pendingTune = p.frequency;
        pendingTuneAt = System.currentTimeMillis();
        mutate(s -> {
            s.frequency = p.frequency;
            s.presetIndex = p.index + 1;
        });
        // If the MCU doesn't know this memory (not reapplied yet), tune by frequency only:
        // with the memory number the service would go to the station it has saved in the slot.
        Frequency[] svc = p.band >= 0 && p.band < serviceLists.length ? serviceLists[p.band] : null;
        boolean known = svc != null && p.index < svc.length && svc[p.index] != null
            && svc[p.index].frequency == p.frequency;
        int slot = known ? p.index + 1 : NO_PRESET;
        command("setCurrentFrequency(" + p.frequency + ", " + p.band + ", " + slot + ")",
            f -> f.setCurrentFrequency(p.frequency, (byte) p.band, slot));
    }

    /** Saves the current station to the service slot (0-based) and to the matching prop. */
    public void savePreset(int index) {
        RadioState s = state;
        PresetStore.write(context, s.band, index, s.frequency);
        command("saveCurrentFrequency(" + index + ")", f -> {
            f.saveCurrentFrequency((byte) index);
            refreshPresets(f);
            main.postDelayed(() -> {
                RadioFeature cur = feature;
                if (cur != null) worker.execute(() -> refreshPresets(cur));
            }, 800);
        });
    }

    public void setStereo(boolean on) {
        command("setStreroOn(" + on + ")", f -> f.setStreroOn(on));
    }

    public void setLoc(boolean on) {
        command("setNearOn(" + on + ")", f -> f.setNearOn(on));
    }

    public void setRds(int flag, boolean on) {
        command("setRDSState(" + flag + ", " + on + ")", f -> {
            f.setRDSState((byte) flag, on);
            refreshRds(f);
        });
    }

    public void requestFullState() {
        command("sendRadioCommand(-1, -1)", f -> f.sendRadioCommand((byte) -1, (byte) -1));
    }

    private void command(String name, Command c) {
        if (released) return;
        commandCount++;
        worker.execute(() -> {
            RadioFeature f = feature;
            if (f == null) {
                log("Not connected, ignored: " + name);
                return;
            }
            log("→ " + name);
            safe(name, () -> c.run(f));
        });
    }

    // ------------------------------------------------------------------ audio source

    /** Tells the system the radio is the active source (called in onResume). */
    public void activateSource() {
        log("→ broadcast ACTION_APP_IN_OUT (app_id=8)");
        Intent in = new Intent(NwdConstants.ACTION_APP_IN_OUT);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_RADIO);
        in.putExtra(NwdConstants.EXTRA_APP_OPERATION, 1);
        in.putExtra(NwdConstants.EXTRA_APP_EVENT, 0);
        context.sendBroadcast(in);
        context.sendBroadcast(new Intent(NwdConstants.ACTION_STOP_QQ_MUSIC));
        if (state.radioType > 0) sendMediaPlay();
    }

    /** Radio on the SoC chip (not MCU) needs this extra notice to play. */
    private void sendMediaPlay() {
        log("→ broadcast ACTION_MEDIA_PLAY (app_id=8)");
        Intent in = new Intent(NwdConstants.ACTION_MEDIA_PLAY);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_RADIO);
        in.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(in);
    }

    /** Leaves the radio source (source 0) when closing. */
    public void deactivateSource() {
        log("→ broadcast ACTION_REQUEST_CHANGE_SOURCE (source=0)");
        Intent in = new Intent(NwdConstants.ACTION_REQUEST_CHANGE_SOURCE);
        in.putExtra(NwdConstants.EXTRA_SOURCE_ID, (byte) 0);
        context.sendBroadcast(in);
    }

    // ------------------------------------------------------------------ broadcasts

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!NwdConstants.ACTION_SEND_RADIO_FREQUENCE_NEW.equals(intent.getAction())) return;
            int freq = intent.getIntExtra(NwdConstants.EXTRA_FREQUENCE, -1);
            int band = intent.getByteExtra(NwdConstants.EXTRA_BAND, (byte) -1);
            if (restoring) return;
            if (freq > 0 && band >= 0 && (freq != state.frequency || band != state.band) && !isStaleFrequency(freq)) {
                mutate(s -> {
                    s.frequency = freq;
                    s.band = band;
                });
            }
        }
    };

    private void registerReceiver() {
        if (receiverRegistered) return;
        try {
            context.registerReceiver(receiver, new IntentFilter(NwdConstants.ACTION_SEND_RADIO_FREQUENCE_NEW));
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ utilities

    private interface Block {
        void run() throws Exception;
    }

    private void safe(String name, Block block) {
        try {
            block.run();
        } catch (Exception e) {
            log("Error in " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private synchronized void mutate(Mutation m) {
        RadioState next = state.copy();
        m.apply(next);
        state = next;
        main.post(() -> {
            if (listener != null) listener.onRadioState(next);
        });
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        final List<String> snapshot;
        synchronized (logLines) {
            logLines.add(timeFormat.format(new Date()) + "  " + msg);
            while (logLines.size() > MAX_LOG) logLines.remove(0);
            snapshot = new ArrayList<>(logLines);
        }
        main.post(() -> {
            if (logListener != null) logListener.onLog(snapshot);
        });
    }
}
