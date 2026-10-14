package com.garagemdomadeira.nwd.launcher.music;

import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import android.util.Log;

import com.garagemdomadeira.nwd.launcher.home.LauncherPrefs;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists the tracks in the folder the driver picked for the MP3 player (subfolders too): from the
 * MediaStore and, if it has none there (not indexed yet), by scanning the folder. No folder picked
 * yet = no tracks: the player starts empty instead of gathering every song on the unit. Tracks come
 * grouped by folder (the folder's own first, then each subfolder in order), so the list can show
 * them as a tree and next/previous stay within a folder before moving on.
 */
public final class MusicLibrary {
    private static final String TAG = "GaragemMusicLib";
    private static final int MAX_FILES = 2000;
    private static final int MAX_DEPTH = 6;
    private static final String[] EXTENSIONS = {
        ".mp3", ".aac", ".m4a", ".ogg", ".flac", ".wav", ".amr", ".mid", ".midi", ".awb"
    };

    private MusicLibrary() {
    }

    public static List<MusicTrack> load(Context context) {
        String folder = LauncherPrefs.musicFolder(context);
        if (folder == null) return new ArrayList<>();
        File dir = new File(folder);
        List<MusicTrack> tracks = fromMediaStore(context, dir);
        if (!tracks.isEmpty()) {
            Log.d(TAG, "MediaStore: " + tracks.size() + " tracks in " + dir);
        } else {
            tracks = fromDisk(dir);
            Log.d(TAG, "Disk: " + tracks.size() + " tracks in " + dir);
        }
        sortByFolder(tracks, dir);
        return tracks;
    }

    /**
     * Folder segments of {@code track} below {@code root}: {@code []} for a track right in it,
     * {@code ["Rock", "80s"]} for {@code root/Rock/80s/song.mp3}.
     */
    public static String[] folders(MusicTrack track, File root) {
        String base = root.getAbsolutePath() + "/";
        String path = track.path;
        int slash = path.lastIndexOf('/');
        if (!path.startsWith(base) || slash < base.length()) return new String[0];
        return path.substring(base.length(), slash).split("/");
    }

    /** The folder's own tracks first, then each subfolder (alphabetically, depth first); title inside. */
    private static void sortByFolder(List<MusicTrack> tracks, File root) {
        Collator order = Collator.getInstance(new Locale("pt", "BR"));
        order.setStrength(Collator.PRIMARY);
        Collections.sort(tracks, (a, b) -> {
            String[] fa = folders(a, root);
            String[] fb = folders(b, root);
            for (int i = 0; i < Math.min(fa.length, fb.length); i++) {
                int c = order.compare(fa[i], fb[i]);
                if (c != 0) return c;
            }
            if (fa.length != fb.length) return fa.length - fb.length;
            return order.compare(a.displayTitle(), b.displayTitle());
        });
    }

    /** Whether {@code name} looks like a music file (the folder picker counts them). */
    public static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static List<MusicTrack> fromMediaStore(Context context, File dir) {
        List<MusicTrack> list = new ArrayList<>();
        String[] cols = {
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media._ID,
        };
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                cols,
                MediaStore.Audio.Media.IS_MUSIC + "!=0 AND " + MediaStore.Audio.Media.DATA + " LIKE ?",
                new String[]{dir.getAbsolutePath() + "/%"},
                MediaStore.Audio.Media.TITLE + " COLLATE LOCALIZED ASC");
            if (c == null) return list;
            int iPath = c.getColumnIndex(MediaStore.Audio.Media.DATA);
            int iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int iId = c.getColumnIndex(MediaStore.Audio.Media._ID);
            while (c.moveToNext() && list.size() < MAX_FILES) {
                String path = iPath >= 0 ? c.getString(iPath) : null;
                if (path == null || path.isEmpty()) continue;
                if (!new File(path).isFile()) continue;
                String title = iTitle >= 0 ? clean(c.getString(iTitle)) : "";
                String artist = iArtist >= 0 ? clean(c.getString(iArtist)) : "";
                String album = iAlbum >= 0 ? clean(c.getString(iAlbum)) : "";
                long id = iId >= 0 ? c.getLong(iId) : 0L;
                if ("<unknown>".equalsIgnoreCase(artist)) artist = "";
                if ("<unknown>".equalsIgnoreCase(album)) album = "";
                list.add(new MusicTrack(path, title, artist, album, id));
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaStore failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    private static List<MusicTrack> fromDisk(File dir) {
        List<MusicTrack> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (dir.isDirectory()) walk(dir, 0, list, seen);
        return list;
    }

    private static void walk(File dir, int depth, List<MusicTrack> out, Set<String> seen) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Exception e) {
            return;
        }
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_FILES) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (skipDir(name)) continue;
                walk(f, depth + 1, out, seen);
            } else if (f.isFile() && isAudio(name)) {
                String path = f.getAbsolutePath();
                if (seen.add(path)) {
                    out.add(new MusicTrack(path, "", "", "", 0L));
                }
            }
        }
    }

    private static boolean skipDir(String name) {
        String n = name.toLowerCase(Locale.US);
        return "android".equals(n) || "lost+found".equals(n) || "cache".equals(n)
            || "tmp".equals(n) || "temp".equals(n) || "thumbnails".equals(n);
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.isEmpty() ? "" : t;
    }
}
