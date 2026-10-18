package com.k4811.android.music.ui.music;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.k4811.android.music.ui.R;
import com.k4811.common.audio.PlayerHandoff;
import com.k4811.common.music.MusicRemote;
import com.k4811.common.system.NwdConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Our own file player. Keeps playing with the screen closed.
 * Notifies the head unit with app id 2 and Android source (0).
 */
public class MusicPlaybackService extends Service {

    public interface Listener {
        void onMusicState(MusicState state);
    }

    public class LocalBinder extends Binder {
        public MusicPlaybackService getService() {
            return MusicPlaybackService.this;
        }
    }

    private static final String TAG = "K4811Music";
    /** startService with this action pauses the player (used before turning on the radio). */
    public static final String ACTION_PAUSE = "com.k4811.android.music.ui.PAUSE";
    /** startService com esta action executa um comando do launcher (extra MusicRemote.EXTRA_CMD). */
    public static final String ACTION_REMOTE = "com.k4811.android.music.ui.REMOTE";
    /** startService com esta action retoma a música (carro ligado com o MP3 como última fonte). */
    public static final String ACTION_RESUME = "com.k4811.android.music.ui.RESUME";
    /** Posição repetida no broadcast de estado no máximo a cada 1 s. */
    private static final long STATE_PROGRESS_MS = 1000;

    /** Running instance (same process), so the radio can pause it synchronously. Main thread only. */
    private static MusicPlaybackService running;

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    private MediaPlayer player;
    private AudioManager audio;
    private MusicState state = new MusicState();
    private List<Integer> shuffleOrder = new ArrayList<>();
    private int shuffleAt = -1;
    private boolean focusHeld;
    private boolean progressRunning;
    /** Play requested while the list was still loading. */
    private boolean playWhenReady;
    /** Track (index) and position (ms) where the last session stopped, to pick up from there. */
    private int resumeIndex = -1;
    private int resumeMs;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            progressRunning = false;
            if (player != null && state.playing) {
                try {
                    state.positionMs = player.getCurrentPosition();
                    state.durationMs = Math.max(0, player.getDuration());
                } catch (Exception ignored) {
                }
                MusicConfig.setPosition(MusicPlaybackService.this, state.positionMs, false);
                publish();
                scheduleProgress();
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = focus -> {
        if (focus == AudioManager.AUDIOFOCUS_LOSS
            || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pauseInternal(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        state.shuffle = MusicConfig.shuffle(this);
        state.repeat = MusicConfig.repeat(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) pauseForRadio();
        if (intent != null && ACTION_RESUME.equals(intent.getAction()) && !state.playing) {
            Log.d(TAG, "Retomando: o carro ligou com o MP3 como fonte");
            toggle(); // sem lista ainda: carrega e toca a última faixa
        }
        if (intent != null && ACTION_REMOTE.equals(intent.getAction())) {
            int cmd = intent.getIntExtra(MusicRemote.EXTRA_CMD, MusicRemote.CMD_TOGGLE);
            if (cmd == MusicRemote.CMD_NEXT && !state.tracks.isEmpty()) next();
            else if (cmd == MusicRemote.CMD_PREV && !state.tracks.isEmpty()) previous();
            else if (cmd == MusicRemote.CMD_TOGGLE || state.tracks.isEmpty()) toggle();
        }
        return START_STICKY;
    }

    /** Serviço rodando neste processo, ou null. */
    public static MusicPlaybackService running() {
        return running;
    }

    /**
     * Pauses and releases audio focus right now, on the caller's (main) thread. Must run before the
     * radio takes the source: when the source leaves Android while our player still holds audio
     * focus, the NWD system (NwdProcessAdjuster) force-stops our whole package.
     * Returns false if the service isn't running (nothing is playing then).
     */
    public static boolean pauseRunning() {
        MusicPlaybackService s = running;
        if (s == null) return false;
        s.pauseForRadio();
        return true;
    }

    /** true if our player is playing (or still holds audio focus). Main thread only. */
    public static boolean isPlayingNow() {
        MusicPlaybackService s = running;
        return s != null && (s.state.playing || s.focusHeld);
    }

    private void pauseForRadio() {
        if (!state.playing && !focusHeld) return;
        Log.d(TAG, "Paused for the radio");
        pauseInternal(false);
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        main.removeCallbacksAndMessages(null);
        if (player != null && state.current() != null) {
            try {
                MusicConfig.setPosition(this, player.getCurrentPosition(), true);
            } catch (Exception ignored) {
            }
        }
        releasePlayer();
        abandonFocus();
        worker.shutdownNow();
        listeners.clear();
        super.onDestroy();
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onMusicState(state.copy());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public MusicState getState() {
        return state.copy();
    }

    /** true if a track is loaded (the home screen uses our player instead of NWD's). */
    public boolean hasSession() {
        return state.current() != null;
    }

    public void ensureLibrary() {
        if (!state.tracks.isEmpty() || state.loading) return;
        state.loading = true;
        state.error = 0;
        publish();
        worker.execute(() -> {
            List<MusicTrack> tracks = MusicLibrary.load(getApplicationContext());
            main.post(() -> applyLibrary(tracks));
        });
    }

    public void reloadLibrary() {
        if (state.loading) return;
        state.loading = true;
        state.error = 0;
        publish();
        worker.execute(() -> {
            List<MusicTrack> tracks = MusicLibrary.load(getApplicationContext());
            main.post(() -> applyLibrary(tracks));
        });
    }

    private void applyLibrary(List<MusicTrack> tracks) {
        state.loading = false;
        state.tracks = MusicState.copyTracks(tracks);
        rebuildShuffle();
        int found = indexOfPath(MusicConfig.lastPath(this));
        if (found >= 0) {
            if (player == null) {
                resumeIndex = found;
                resumeMs = MusicConfig.lastPosition(this);
            }
            state.index = found;
        } else if (!state.tracks.isEmpty() && state.index < 0) {
            state.index = 0;
        } else if (state.index >= state.tracks.size()) {
            state.index = state.tracks.isEmpty() ? -1 : 0;
        }
        if (state.tracks.isEmpty()) state.error = R.string.media_music_err_no_tracks;
        publish();
        if (playWhenReady && !state.tracks.isEmpty()) {
            playWhenReady = false;
            if (state.index < 0) state.index = 0;
            takeOver();
            open(state.index, true);
        } else {
            playWhenReady = false;
        }
    }

    public void playIndex(int index) {
        if (index < 0 || index >= state.tracks.size()) return;
        takeOver();
        open(index, true);
    }

    public void toggle() {
        if (state.playing) {
            pauseInternal(true);
            return;
        }
        if (state.tracks.isEmpty()) {
            playWhenReady = true;
            ensureLibrary();
            return;
        }
        if (state.index < 0) state.index = 0;
        takeOver();
        if (player == null) open(state.index, true);
        else resume();
    }

    public void next() {
        int n = nextIndex(true);
        if (n < 0) return;
        takeOver();
        open(n, true);
    }

    public void previous() {
        if (state.positionMs > 3000 && player != null) {
            seek(0);
            return;
        }
        int n = nextIndex(false);
        if (n < 0) return;
        takeOver();
        open(n, true);
    }

    public void seek(int ms) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            int target = Math.max(0, Math.min(ms, Math.max(dur, 0)));
            player.seekTo(target);
            state.positionMs = target;
            publish();
        } catch (Exception e) {
            Log.d(TAG, "seek: " + e.getMessage());
        }
    }

    public void setShuffle(boolean on) {
        state.shuffle = on;
        MusicConfig.setShuffle(this, on);
        rebuildShuffle();
        publish();
    }

    public void cycleRepeat() {
        if (state.repeat == MusicState.Repeat.ALL) state.repeat = MusicState.Repeat.ONE;
        else if (state.repeat == MusicState.Repeat.ONE) state.repeat = MusicState.Repeat.OFF;
        else state.repeat = MusicState.Repeat.ALL;
        MusicConfig.setRepeat(this, state.repeat);
        publish();
    }

    /** About to play: stops Bluetooth music and takes the source (which also takes the radio off). */
    private void takeOver() {
        PlayerHandoff.beforeMp3(this);
        claimSource();
    }

    /** Tells the head unit that music is the active source. */
    public void claimSource() {
        Intent source = new Intent(NwdConstants.ACTION_REQUEST_CHANGE_SOURCE);
        source.putExtra(NwdConstants.EXTRA_SOURCE_ID, (byte) 0);
        sendBroadcast(source);
        Intent in = new Intent(NwdConstants.ACTION_APP_IN_OUT);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_MUSIC);
        in.putExtra(NwdConstants.EXTRA_APP_OPERATION, 1);
        in.putExtra(NwdConstants.EXTRA_APP_EVENT, 0);
        sendBroadcast(in);
    }

    private void open(int index, boolean autoPlay) {
        if (index < 0 || index >= state.tracks.size()) return;
        MusicTrack track = state.tracks.get(index);
        releasePlayer();
        int startMs = index == resumeIndex ? resumeMs : 0;
        resumeIndex = -1;
        state.index = index;
        state.positionMs = startMs;
        state.durationMs = 0;
        state.error = 0;
        if (startMs == 0) MusicConfig.setLast(this, track.path);
        syncShuffleCursor();
        publish();

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setOnPreparedListener(p -> {
            state.durationMs = Math.max(0, p.getDuration());
            if (startMs > 0 && startMs < state.durationMs) {
                try {
                    p.seekTo(startMs);
                } catch (Exception ignored) {
                }
            }
            if (autoPlay) {
                if (!requestFocus()) {
                    state.error = R.string.media_music_err_no_focus;
                    state.playing = false;
                    publish();
                    return;
                }
                p.start();
                state.playing = true;
                scheduleProgress();
            }
            publish();
        });
        mp.setOnCompletionListener(p -> onComplete());
        mp.setOnErrorListener((p, what, extra) -> {
            Log.d(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
            state.playing = false;
            state.error = R.string.media_music_err_cannot_play;
            publish();
            main.postDelayed(this::next, 400);
            return true;
        });
        try {
            mp.setDataSource(track.path);
            mp.prepareAsync();
        } catch (IOException e) {
            Log.d(TAG, "open: " + e.getMessage());
            state.error = R.string.media_music_err_invalid_file;
            releasePlayer();
            publish();
            main.postDelayed(this::next, 400);
        }
    }

    private void resume() {
        if (player == null) return;
        if (!requestFocus()) {
            state.error = R.string.media_music_err_no_focus;
            publish();
            return;
        }
        try {
            player.start();
            state.playing = true;
            state.error = 0;
            scheduleProgress();
            publish();
        } catch (Exception e) {
            state.error = R.string.media_music_err_play_failed;
            state.playing = false;
            publish();
        }
    }

    private void pauseInternal(boolean keepFocus) {
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
                state.positionMs = player.getCurrentPosition();
            } catch (Exception ignored) {
            }
        }
        state.playing = false;
        if (state.current() != null) MusicConfig.setPosition(this, state.positionMs, true);
        main.removeCallbacks(progressTick);
        progressRunning = false;
        if (!keepFocus) abandonFocus();
        publish();
    }

    private void onComplete() {
        if (state.repeat == MusicState.Repeat.ONE) {
            seek(0);
            resume();
            return;
        }
        int n = nextIndex(true);
        if (n < 0) {
            state.playing = false;
            state.positionMs = 0;
            publish();
            return;
        }
        open(n, true);
    }

    private int nextIndex(boolean forward) {
        int size = state.tracks.size();
        if (size == 0) return -1;
        if (state.shuffle) {
            if (shuffleOrder.isEmpty()) rebuildShuffle();
            if (shuffleOrder.isEmpty()) return -1;
            if (shuffleAt < 0) syncShuffleCursor();
            if (forward) {
                if (shuffleAt + 1 >= shuffleOrder.size()) {
                    if (state.repeat == MusicState.Repeat.OFF) return -1;
                    rebuildShuffle();
                    shuffleAt = 0;
                } else {
                    shuffleAt++;
                }
            } else {
                if (shuffleAt <= 0) {
                    if (state.repeat == MusicState.Repeat.OFF) return state.index;
                    shuffleAt = shuffleOrder.size() - 1;
                } else {
                    shuffleAt--;
                }
            }
            return shuffleOrder.get(shuffleAt);
        }
        int next = state.index + (forward ? 1 : -1);
        if (next >= size) return state.repeat == MusicState.Repeat.OFF ? -1 : 0;
        if (next < 0) return state.repeat == MusicState.Repeat.OFF ? state.index : size - 1;
        return next;
    }

    private void rebuildShuffle() {
        shuffleOrder = new ArrayList<>();
        for (int i = 0; i < state.tracks.size(); i++) shuffleOrder.add(i);
        Collections.shuffle(shuffleOrder, random);
        syncShuffleCursor();
    }

    private void syncShuffleCursor() {
        shuffleAt = shuffleOrder.indexOf(state.index);
    }

    private int indexOfPath(String path) {
        if (path == null || path.isEmpty()) return -1;
        for (int i = 0; i < state.tracks.size(); i++) {
            if (path.equals(state.tracks.get(i).path)) return i;
        }
        return -1;
    }

    private boolean requestFocus() {
        if (audio == null) return true;
        int r = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN);
        focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusHeld;
    }

    private void abandonFocus() {
        if (audio != null && focusHeld) {
            audio.abandonAudioFocus(focusListener);
            focusHeld = false;
        }
    }

    private void releasePlayer() {
        main.removeCallbacks(progressTick);
        progressRunning = false;
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        state.playing = false;
    }

    private void scheduleProgress() {
        if (progressRunning) return;
        progressRunning = true;
        main.postDelayed(progressTick, 500);
    }

    private void publish() {
        MusicState snap = state.copy();
        for (Listener l : listeners) {
            try {
                l.onMusicState(snap);
            } catch (Exception ignored) {
            }
        }
        broadcastState(false);
    }

    private String lastStateKey;
    private long lastStateAt;

    /**
     * Estado para o launcher ("o que está tocando"). Mudança de faixa ou de play/pause sai na hora;
     * só a posição, no máximo 1×/s. {@code force} reenvia mesmo sem mudança (pedido do launcher).
     */
    void broadcastState(boolean force) {
        MusicTrack t = state.current();
        String key = t == null ? "" : t.path + "|" + state.playing + "|" + state.durationMs;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && key.equals(lastStateKey) && now - lastStateAt < STATE_PROGRESS_MS) return;
        lastStateKey = key;
        lastStateAt = now;
        Intent i = new Intent(MusicRemote.ACTION_STATE);
        i.putExtra(MusicRemote.EXTRA_HAS_TRACK, t != null);
        if (t != null) {
            i.putExtra(MusicRemote.EXTRA_TITLE, t.displayTitle());
            i.putExtra(MusicRemote.EXTRA_ARTIST, t.artist);
            i.putExtra(MusicRemote.EXTRA_ALBUM, t.album);
            i.putExtra(MusicRemote.EXTRA_MEDIA_ID, t.mediaId);
            i.putExtra(MusicRemote.EXTRA_PATH, t.path);
        }
        i.putExtra(MusicRemote.EXTRA_PLAYING, state.playing);
        i.putExtra(MusicRemote.EXTRA_POSITION, state.positionMs);
        i.putExtra(MusicRemote.EXTRA_DURATION, state.durationMs);
        try {
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }
}
