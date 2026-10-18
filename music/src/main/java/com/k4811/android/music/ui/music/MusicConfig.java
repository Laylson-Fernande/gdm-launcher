package com.k4811.android.music.ui.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.k4811.common.system.SystemProps;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Últimas configurações do player, em {@code K4811/mp3player/mp3player.config.json} (mesmas
 * pastas e mesmo jeito do {@code radio.config.json} do rádio). Feito para ler e editar à mão:
 * <pre>
 * {
 *   "version": 1,
 *   "folder": "/storage/sdcard0/music",
 *   "last": { "path": "/storage/sdcard0/music/Rock/song.mp3", "position": 83000 },
 *   "shuffle": false,
 *   "repeat": "all"
 * }
 * </pre>
 * {@code folder} é a pasta da lista (sem ela, a pasta {@code music} do sdcard); {@code last} é a
 * faixa e a posição em ms onde parou; {@code repeat} é {@code all}, {@code one} ou {@code off}.
 *
 * Leituras vêm da memória e recarregam o arquivo quando a data dele muda (editado à mão). Escritas
 * atualizam a memória na hora e gravam em segundo plano (arquivo temporário e depois rename). Um
 * arquivo que não abre como JSON nunca é sobrescrito: até ser corrigido, as mudanças ficam só na
 * memória. Na primeira vez, sem arquivo, traz o que estava nos SharedPreferences antigos.
 */
public final class MusicConfig {
    private MusicConfig() {
    }

    private static final String TAG = "K4811MusicCfg";
    private static final String FOLDER = "K4811/mp3player";
    private static final String FILE_NAME = "mp3player.config.json";
    private static final int VERSION = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PROP_INTERNAL_PATH = "ro.nwd.internal.path";
    private static final String DEFAULT_INTERNAL_PATH = "/mnt/media_rw/sdcard";
    /** Enquanto toca, a posição só vai para o arquivo a cada tanto (pausar grava a exata). */
    private static final int POSITION_STEP_MS = 10000;

    private static final Object LOCK = new Object();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    private static String folder;
    private static String lastPath = "";
    private static int lastPosition;
    private static boolean shuffle;
    private static MusicState.Repeat repeat = MusicState.Repeat.ALL;

    private static File file;
    /** lastModified do arquivo quando foi lido ou gravado; -1 = ainda não carregado. */
    private static long stamp = -1;
    /** Gravações na fila que ainda não chegaram ao disco: o arquivo está mais velho que a memória. */
    private static int pendingSaves;
    private static boolean broken;

    // ------------------------------------------------------------------ leitura

    /** Pasta de onde o player lista as músicas (com subpastas); até escolher, /sdcard/music. */
    public static String folder(Context c) {
        synchronized (LOCK) {
            load(c);
            if (folder != null) return folder;
        }
        return new File(Environment.getExternalStorageDirectory(), "music").getAbsolutePath();
    }

    /** Caminho da última faixa tocada, ou "" se nenhuma. */
    public static String lastPath(Context c) {
        synchronized (LOCK) {
            load(c);
            return lastPath;
        }
    }

    /** Posição (ms) onde a última faixa parou. */
    public static int lastPosition(Context c) {
        synchronized (LOCK) {
            load(c);
            return lastPosition;
        }
    }

    public static boolean shuffle(Context c) {
        synchronized (LOCK) {
            load(c);
            return shuffle;
        }
    }

    public static MusicState.Repeat repeat(Context c) {
        synchronized (LOCK) {
            load(c);
            return repeat;
        }
    }

    // ------------------------------------------------------------------ escrita

    public static void setFolder(Context c, String path) {
        synchronized (LOCK) {
            load(c);
            if (path == null || path.equals(folder)) return;
            folder = path;
            save();
        }
    }

    /** Nova faixa: guarda o caminho e zera a posição. */
    public static void setLast(Context c, String path) {
        synchronized (LOCK) {
            load(c);
            String p = path == null ? "" : path;
            if (p.equals(lastPath) && lastPosition == 0) return;
            lastPath = p;
            lastPosition = 0;
            save();
        }
    }

    /**
     * Posição na faixa atual. {@code exact} (pausa, troca de faixa) grava sempre; senão (tocando)
     * só quando andou {@link #POSITION_STEP_MS}.
     */
    public static void setPosition(Context c, int ms, boolean exact) {
        synchronized (LOCK) {
            load(c);
            int v = Math.max(0, ms);
            if (v == lastPosition || (!exact && Math.abs(v - lastPosition) < POSITION_STEP_MS)) return;
            lastPosition = v;
            save();
        }
    }

    public static void setShuffle(Context c, boolean on) {
        synchronized (LOCK) {
            load(c);
            if (shuffle == on) return;
            shuffle = on;
            save();
        }
    }

    public static void setRepeat(Context c, MusicState.Repeat r) {
        synchronized (LOCK) {
            load(c);
            if (r == null || repeat == r) return;
            repeat = r;
            save();
        }
    }

    // ------------------------------------------------------------------ arquivo

    /** Lê o arquivo na primeira vez e sempre que ele mudou no disco. Chamado com LOCK. */
    private static void load(Context c) {
        if (pendingSaves > 0) return;
        File found = locate();
        if (found == null) {
            if (stamp < 0) {
                stamp = 0;
                importOldPrefs(c);
            }
            return;
        }
        long modified = found.lastModified();
        if (found.equals(file) && modified == stamp) return;
        file = found;
        stamp = modified;
        try {
            parse(readText(found));
            broken = false;
        } catch (Exception e) {
            broken = true;
            Log.w(TAG, "Invalid " + found + ": " + e.getMessage());
        }
    }

    /** Memória interna da NWD e, como reserva, a memória interna do Android (como o rádio). */
    private static Set<File> dirs() {
        Set<File> dirs = new LinkedHashSet<>();
        String internal = SystemProps.get(PROP_INTERNAL_PATH, "");
        dirs.add(new File(internal.isEmpty() ? DEFAULT_INTERNAL_PATH : internal, FOLDER));
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) dirs.add(new File(ext, FOLDER));
        return dirs;
    }

    private static File locate() {
        for (File dir : dirs()) {
            File f = new File(dir, FILE_NAME);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static void parse(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        String f = root.optString("folder", "").trim();
        folder = f.isEmpty() ? null : f;
        JSONObject l = root.optJSONObject("last");
        lastPath = l == null ? "" : l.optString("path", "");
        lastPosition = l == null ? 0 : Math.max(0, l.optInt("position", 0));
        shuffle = root.optBoolean("shuffle", false);
        repeat = repeatOf(root.optString("repeat", "all"));
    }

    /** Põe a memória na fila para o arquivo (a não ser que ele esteja quebrado). Chamado com LOCK. */
    private static void save() {
        if (broken) return;
        final String text = toJson();
        pendingSaves++;
        writer.execute(() -> {
            File written = writeFile(text);
            synchronized (LOCK) {
                pendingSaves--;
                if (written != null) {
                    file = written;
                    stamp = written.lastModified();
                }
            }
        });
    }

    /** Grava junto do arquivo atual, ou na primeira pasta que aceitar. Devolve o arquivo ou null. */
    private static File writeFile(String text) {
        List<File> targets = new ArrayList<>();
        File current;
        synchronized (LOCK) {
            current = file;
        }
        if (current != null) targets.add(current);
        for (File dir : dirs()) {
            File f = new File(dir, FILE_NAME);
            if (!targets.contains(f)) targets.add(f);
        }
        for (File target : targets) {
            File dir = target.getParentFile();
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) continue;
            File tmp = new File(dir, FILE_NAME + ".tmp");
            OutputStream out = null;
            try {
                out = new FileOutputStream(tmp);
                out.write(text.getBytes(UTF8));
                out.close();
                out = null;
                if (tmp.renameTo(target)) return target;
            } catch (Exception e) {
                Log.w(TAG, "Can't write " + target + ": " + e.getMessage());
            } finally {
                close(out);
            }
            tmp.delete();
        }
        return null;
    }

    /** Saída legível e estável: chaves sempre na mesma ordem. Chamado com LOCK. */
    private static String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": ").append(VERSION).append(",\n");
        if (folder != null) sb.append("  \"folder\": ").append(JSONObject.quote(folder)).append(",\n");
        if (!lastPath.isEmpty()) {
            sb.append("  \"last\": { \"path\": ").append(JSONObject.quote(lastPath))
                .append(", \"position\": ").append(lastPosition).append(" },\n");
        }
        sb.append("  \"shuffle\": ").append(shuffle).append(",\n");
        sb.append("  \"repeat\": \"").append(repeat.name().toLowerCase(Locale.US)).append("\"\n}\n");
        return sb.toString();
    }

    /** Primeira vez sem arquivo: traz a pasta, a última faixa, aleatório e repetir dos SharedPreferences antigos. */
    private static void importOldPrefs(Context c) {
        SharedPreferences ui = c.getSharedPreferences("music_prefs", Context.MODE_PRIVATE);
        SharedPreferences svc = c.getSharedPreferences("k4811_music", Context.MODE_PRIVATE);
        folder = ui.getString("music_folder", null);
        lastPath = svc.getString("last_path", "");
        shuffle = svc.getBoolean("shuffle", false);
        repeat = repeatOf(svc.getString("repeat", "all"));
        if (folder != null || !lastPath.isEmpty() || svc.contains("shuffle") || svc.contains("repeat")) {
            Log.i(TAG, "Importing the player settings from the old prefs into " + FILE_NAME);
            save();
        }
    }

    // ------------------------------------------------------------------ util

    private static MusicState.Repeat repeatOf(String s) {
        try {
            return MusicState.Repeat.valueOf(s.trim().toUpperCase(Locale.US));
        } catch (Exception e) {
            return MusicState.Repeat.ALL;
        }
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, UTF8);
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
