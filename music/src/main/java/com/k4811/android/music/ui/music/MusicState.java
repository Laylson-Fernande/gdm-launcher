package com.k4811.android.music.ui.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Snapshot of the player, delivered to the screen on every change. */
public final class MusicState {
    public enum Repeat { ALL, ONE, OFF }

    public List<MusicTrack> tracks = Collections.emptyList();
    public int index = -1;
    public boolean playing;
    public boolean loading;
    public int positionMs;
    public int durationMs;
    public boolean shuffle;
    public Repeat repeat = Repeat.ALL;
    /** String resource of the error to show, or 0 when there is none. */
    public int error;

    public MusicTrack current() {
        if (index < 0 || index >= tracks.size()) return null;
        return tracks.get(index);
    }

    public MusicState copy() {
        MusicState c = new MusicState();
        c.tracks = tracks;
        c.index = index;
        c.playing = playing;
        c.loading = loading;
        c.positionMs = positionMs;
        c.durationMs = durationMs;
        c.shuffle = shuffle;
        c.repeat = repeat;
        c.error = error;
        return c;
    }

    public static List<MusicTrack> copyTracks(List<MusicTrack> list) {
        return list == null ? Collections.<MusicTrack>emptyList() : new ArrayList<>(list);
    }
}
