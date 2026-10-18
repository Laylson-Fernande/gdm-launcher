package com.k4811.android.music.ui.music;

/** A track in the list. The path is the key. */
public final class MusicTrack {
    public final String path;
    public final String title;
    public final String artist;
    public final String album;
    public final long mediaId;

    public MusicTrack(String path, String title, String artist, String album, long mediaId) {
        this.path = path;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.mediaId = mediaId;
    }

    public String displayTitle() {
        if (!title.isEmpty()) return title;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
