package com.garagemdomadeira.nwd.launcher.music;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lists tracks in the MediaStore and, if empty, scans mounted folders. */
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
        List<MusicTrack> fromStore = fromMediaStore(context);
        if (!fromStore.isEmpty()) {
            Log.d(TAG, "MediaStore: " + fromStore.size() + " tracks");
            return fromStore;
        }
        List<MusicTrack> fromDisk = fromDisk();
        Log.d(TAG, "Disk: " + fromDisk.size() + " tracks");
        return fromDisk;
    }

    private static List<MusicTrack> fromMediaStore(Context context) {
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
                MediaStore.Audio.Media.IS_MUSIC + "!=0",
                null,
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

    private static List<MusicTrack> fromDisk() {
        List<MusicTrack> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String root : roots()) {
            File dir = new File(root);
            if (!dir.isDirectory()) continue;
            walk(dir, 0, list, seen);
            if (list.size() >= MAX_FILES) break;
        }
        Collator order = Collator.getInstance(new Locale("pt", "BR"));
        order.setStrength(Collator.PRIMARY);
        Collections.sort(list, (a, b) -> order.compare(a.displayTitle(), b.displayTitle()));
        return list;
    }

    private static String[] roots() {
        List<String> roots = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) roots.add(ext.getAbsolutePath());
        roots.add("/storage");
        roots.add("/mnt/usb_storage");
        roots.add("/mnt/external_sd");
        roots.add("/mnt/ext_sdcard");
        roots.add("/mnt/extsd");
        roots.add("/mnt/sdcard");
        roots.add("/mnt/media_rw");
        return roots.toArray(new String[0]);
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

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.isEmpty() ? "" : t;
    }
}
