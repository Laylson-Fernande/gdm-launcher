package com.k4811.map.tiles;

import android.app.ActivityManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.k4811.map.MapConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiles do mapa (256×256), em camadas: memória → mapas offline (.mbtiles) → disco → internet.
 *
 * Feito para central com 2 GB de RAM: bitmaps RGB_565 (128 KB cada), memória limitada a poucas
 * dezenas de tiles, 2 threads de fundo com prioridade baixa e pedidos LIFO (o que acabou de aparecer
 * na tela vem primeiro; o que já saiu dela é descartado). Uma instância por processo ({@link #get}).
 */
public final class Tiles {

    public interface Listener {
        /** Um tile chegou (na thread principal). */
        void onTileLoaded();
    }

    private static final String TAG = "K4811Map";
    private static final int THREADS = 2;
    /** Um pedido que não foi renovado por este número de quadros saiu da tela: descartado. */
    private static final int STALE_FRAMES = 20;
    /** Depois de uma falha de rede, espera antes de tentar o mesmo tile de novo. */
    private static final long RETRY_MS = 20000;
    /** Tiles do disco mais velhos que isso são baixados de novo (se houver internet). */
    private static final long REFRESH_MS = 30L * 24 * 3600 * 1000;

    private static Tiles instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<Long, Bitmap> memory;
    private final int maxMemory;
    /** Pedidos pendentes: chave → último quadro em que o tile ainda era visível. */
    private final HashMap<Long, Integer> pending = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final HashMap<Long, Long> failedAt = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<SQLiteDatabase> offline = new ArrayList<>();
    private boolean offlineOpened;
    private boolean notifyPosted;
    private volatile int frame;
    private volatile boolean online = true;
    private long diskBytes = -1;

    private Tiles(Context c) {
        context = c.getApplicationContext();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int heapMb = am == null ? 64 : am.getMemoryClass();
        // ~25 tiles cobrem a tela com a rotação; o resto guarda o entorno e o zoom anterior.
        maxMemory = heapMb >= 128 ? 72 : heapMb >= 64 ? 48 : 32;
        memory = new LinkedHashMap<>(maxMemory + 8, 0.75f, true);
        for (int i = 0; i < THREADS; i++) {
            Thread t = new Thread(this::work, "K4811Tiles" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread prune = new Thread(this::pruneDisk, "K4811TilesPrune");
        prune.setDaemon(true);
        prune.start();
    }

    public static synchronized Tiles get(Context c) {
        if (instance == null) instance = new Tiles(c);
        return instance;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Sem internet não baixa (só memória, .mbtiles e disco). */
    public void setOnline(boolean on) {
        online = on;
    }

    /** Começo de um quadro: pedidos não renovados há {@link #STALE_FRAMES} quadros caducam. */
    public void beginFrame() {
        frame++;
    }

    public static long key(int z, int x, int y) {
        return ((long) z << 58) | ((long) x << 29) | y;
    }

    /** Tile já em memória, sem pedir (para o "tile pai" esticado enquanto o certo não chega). */
    public Bitmap peek(int z, int x, int y) {
        return memory.get(key(z, x, y));
    }

    /** Tile em memória; se não estiver, pede (na thread principal) e devolve null. */
    public Bitmap get(int z, int x, int y) {
        long k = key(z, x, y);
        Bitmap b = memory.get(k);
        if (b != null) return b;
        synchronized (queue) {
            Integer seen = pending.get(k);
            if (seen == null) {
                Long failed = failedAt.get(k);
                if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
                queue.addLast(k);
                queue.notify();
            }
            pending.put(k, frame);
        }
        return null;
    }

    /** Libera a memória (tela do mapa fechada). */
    public void trimMemory() {
        memory.clear();
    }

    // ------------------------------------------------------------------ fundo

    private void work() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            long k;
            synchronized (queue) {
                while (true) {
                    Long next = queue.pollLast();
                    if (next == null) {
                        try {
                            queue.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    Integer seen = pending.get(next);
                    if (seen == null) continue;
                    if (frame - seen > STALE_FRAMES) {
                        pending.remove(next);
                        continue;
                    }
                    k = next;
                    break;
                }
            }
            int z = (int) (k >>> 58);
            int x = (int) ((k >>> 29) & 0x1FFFFFFF);
            int y = (int) (k & 0x1FFFFFFF);
            Bitmap b = null;
            try {
                b = load(z, x, y);
            } catch (Throwable t) {
                Log.w(TAG, "tile " + z + "/" + x + "/" + y + ": " + t);
            }
            final Bitmap loaded = b;
            synchronized (queue) {
                if (loaded == null) failedAt.put(k, System.currentTimeMillis());
                else failedAt.remove(k);
            }
            main.post(() -> {
                synchronized (queue) {
                    pending.remove(k);
                }
                if (loaded == null) return;
                put(k, loaded);
                notifyLoaded();
            });
        }
    }

    private void put(long k, Bitmap b) {
        memory.put(k, b);
        if (memory.size() <= maxMemory) return;
        Iterator<Map.Entry<Long, Bitmap>> it = memory.entrySet().iterator();
        while (memory.size() > maxMemory && it.hasNext()) {
            it.next();
            it.remove(); // o Bitmap fica para o GC (pode estar na lista de desenho do quadro atual)
        }
    }

    /** Vários tiles chegando juntos geram um redesenho só. */
    private void notifyLoaded() {
        if (notifyPosted) return;
        notifyPosted = true;
        main.post(() -> {
            notifyPosted = false;
            for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onTileLoaded();
        });
    }

    private Bitmap load(int z, int x, int y) throws Exception {
        byte[] data = fromOffline(z, x, y);
        if (data != null) return decode(data);
        File f = new File(MapConfig.dir(), "cache/" + z + "/" + x + "/" + y + ".png");
        if (f.isFile()) {
            boolean old = System.currentTimeMillis() - f.lastModified() > REFRESH_MS;
            if (!old || !online) {
                Bitmap b = decode(readFile(f));
                if (b != null) return b;
            }
        }
        if (!online) return null;
        data = download(z, x, y);
        if (data == null) return f.isFile() ? decode(readFile(f)) : null;
        Bitmap b = decode(data);
        if (b != null) save(f, data);
        return b;
    }

    private static Bitmap decode(byte[] data) {
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private byte[] download(int z, int x, int y) {
        MapConfig cfg = MapConfig.get(context);
        String url = cfg.tiles.replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x))
            .replace("{y}", String.valueOf(y));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", cfg.userAgent);
            int code = c.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "tile HTTP " + code + " " + url);
                return null;
            }
            return readAll(c.getInputStream());
        } catch (Exception e) {
            Log.d(TAG, "tile " + url + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void save(File f, byte[] data) {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
        synchronized (this) {
            if (diskBytes >= 0) diskBytes += data.length;
            if (diskBytes > MapConfig.get(context).cacheBytes) {
                diskBytes = -1;
                Thread t = new Thread(this::pruneDisk, "K4811TilesPrune");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    /** Cache em disco acima do limite: apaga os tiles mais antigos até ficar em 80% dele. */
    private void pruneDisk() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
        File root = new File(MapConfig.dir(), "cache");
        List<File> files = new ArrayList<>();
        collect(root, files);
        long total = 0;
        for (File f : files) total += f.length();
        long limit = MapConfig.get(context).cacheBytes;
        if (total > limit) {
            File[] sorted = files.toArray(new File[0]);
            final long[] stamps = new long[sorted.length];
            Integer[] order = new Integer[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                stamps[i] = sorted[i].lastModified();
                order[i] = i;
            }
            Arrays.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return stamps[a] < stamps[b] ? -1 : stamps[a] > stamps[b] ? 1 : 0;
                }
            });
            for (Integer i : order) {
                if (total <= limit * 0.8) break;
                long len = sorted[i].length();
                if (sorted[i].delete()) total -= len;
            }
        }
        synchronized (this) {
            diskBytes = total;
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    // ------------------------------------------------------------------ .mbtiles

    private byte[] fromOffline(int z, int x, int y) {
        synchronized (offline) {
            if (!offlineOpened) {
                offlineOpened = true;
                File[] files = MapConfig.dir().listFiles((d, name) -> name.toLowerCase().endsWith(".mbtiles"));
                if (files != null) {
                    for (File f : files) {
                        try {
                            offline.add(SQLiteDatabase.openDatabase(f.getPath(), null,
                                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS));
                            Log.i(TAG, "mapa offline: " + f.getName());
                        } catch (Exception e) {
                            Log.w(TAG, "mbtiles " + f + ": " + e);
                        }
                    }
                }
            }
            if (offline.isEmpty()) return null;
            // MBTiles usa a numeração TMS: a linha 0 é a de baixo.
            String[] args = {String.valueOf(z), String.valueOf(x), String.valueOf((1 << z) - 1 - y)};
            for (SQLiteDatabase db : offline) {
                try (Cursor c = db.rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", args)) {
                    if (c.moveToFirst()) return c.getBlob(0);
                } catch (Exception e) {
                    Log.w(TAG, "mbtiles: " + e);
                }
            }
            return null;
        }
    }

    /** Há algum mapa offline (.mbtiles) na pasta. */
    public static boolean hasOfflineMaps() {
        File[] files = MapConfig.dir().listFiles((d, name) -> name.toLowerCase().endsWith(".mbtiles"));
        return files != null && files.length > 0;
    }

    // ------------------------------------------------------------------ arquivos

    private static byte[] readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
