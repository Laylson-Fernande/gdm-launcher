package com.k4811.map.road;

import android.util.Log;

import com.k4811.map.MapConfig;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Os quadrados baixados ({@code /sdcard/K4811/nav/roads/cells/<cx>_<cy>.bin}) e um cache em
 * memória dos últimos lidos. Ler só fora da thread principal.
 */
final class RoadStore {

    private static final String TAG = "K4811Road";
    /** Memória máxima dos quadrados lidos (um quadrado do centro de São Paulo ocupa ~3 MB). */
    private static final int MEMORY_BYTES = 12 * 1024 * 1024;

    private static final RoadStore INSTANCE = new RoadStore();

    private final LinkedHashMap<Long, RoadCell> memory = new LinkedHashMap<>(16, 0.75f, true);
    /** Quadrados sem arquivo (para não procurar no disco a cada posição). */
    private final HashSet<Long> missing = new HashSet<>();
    private int memoryBytes;
    /** Muda quando algum arquivo muda (quem preparou algo com os quadrados refaz). */
    private volatile int generation;

    static RoadStore get() {
        return INSTANCE;
    }

    static File dir() {
        return new File(MapConfig.dir(), "roads");
    }

    static File cellsDir() {
        return new File(dir(), "cells");
    }

    static File file(int cx, int cy) {
        return new File(cellsDir(), RoadCell.name(cx, cy) + ".bin");
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xffffffffL);
    }

    /** O quadrado, do cache ou do disco; null se não foi baixado (ou está corrompido). */
    synchronized RoadCell cell(int cx, int cy) {
        long k = key(cx, cy);
        RoadCell c = memory.get(k);
        if (c != null) return c;
        if (missing.contains(k)) return null;
        File f = file(cx, cy);
        if (!f.isFile()) {
            missing.add(k);
            return null;
        }
        try {
            long t0 = System.currentTimeMillis();
            c = RoadCell.read(f);
            Log.i(TAG, "quadrado " + RoadCell.name(cx, cy) + ": " + c.ways + " vias, " + c.points + " pontos, "
                + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception e) {
            Log.w(TAG, "quadrado " + f + ": " + e);
            missing.add(k);
            return null;
        }
        memory.put(k, c);
        memoryBytes += c.memoryBytes();
        Iterator<Map.Entry<Long, RoadCell>> it = memory.entrySet().iterator();
        while (memoryBytes > MEMORY_BYTES && memory.size() > 1 && it.hasNext()) {
            Map.Entry<Long, RoadCell> e = it.next();
            if (e.getKey() == k) continue;
            memoryBytes -= e.getValue().memoryBytes();
            it.remove();
        }
        return c;
    }

    int generation() {
        return generation;
    }

    /** O arquivo do quadrado mudou (baixado de novo ou apagado). */
    synchronized void invalidate(int cx, int cy) {
        generation++;
        long k = key(cx, cy);
        RoadCell c = memory.remove(k);
        if (c != null) memoryBytes -= c.memoryBytes();
        missing.remove(k);
    }

    /** Devolve a memória (tela fora). */
    synchronized void trimMemory() {
        memory.clear();
        missing.clear();
        memoryBytes = 0;
    }
}
