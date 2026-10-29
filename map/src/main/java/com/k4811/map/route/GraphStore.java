package com.k4811.map.route;

import android.util.Log;

import com.k4811.map.MapConfig;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Os quadrados baixados do grafo de ruas ({@code /sdcard/K4811/nav/graph/cells/<cx>_<cy>.bin}, ver
 * {@link com.k4811.map.road.RoadSync#graph}) e um cache em memória dos últimos lidos. Ler só fora da
 * thread principal.
 */
public final class GraphStore {

    private static final String TAG = "K4811Route";
    /** Memória dos quadrados guardados entre uma rota e outra (durante o cálculo, os usados ficam todos). */
    private static final int MEMORY_BYTES = 24 * 1024 * 1024;

    private static GraphStore instance;

    private final File cellsDir;
    private final LinkedHashMap<Long, GraphCell> memory = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Long> broken = new HashSet<>();
    private int memoryBytes;
    /** Arquivos no disco (nomes sem .bin); null = ainda não listou. */
    private Set<String> files;

    public static synchronized GraphStore get() {
        if (instance == null) instance = new GraphStore(new File(dir(), "cells"));
        return instance;
    }

    public static File dir() {
        return new File(MapConfig.dir(), "graph");
    }

    GraphStore(File cellsDir) {
        this.cellsDir = cellsDir;
    }

    static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xffffffffL);
    }

    static int cellX(int lonE6) {
        return Math.floorDiv(lonE6 + 180000000, 250000);
    }

    static int cellY(int latE6) {
        return Math.floorDiv(latE6 + 90000000, 250000);
    }

    /** O quadrado está baixado. */
    synchronized boolean has(int cx, int cy) {
        if (files == null) {
            files = new HashSet<>();
            String[] list = cellsDir.list();
            if (list != null) for (String f : list) if (f.endsWith(".bin")) files.add(f.substring(0, f.length() - 4));
        }
        return files.contains(cx + "_" + cy);
    }

    /** O quadrado, do cache ou do disco; null se não foi baixado (ou está corrompido). */
    synchronized GraphCell cell(int cx, int cy) {
        long k = key(cx, cy);
        GraphCell c = memory.get(k);
        if (c != null) return c;
        if (broken.contains(k) || !has(cx, cy)) return null;
        try {
            long t0 = System.currentTimeMillis();
            c = GraphCell.read(new File(cellsDir, cx + "_" + cy + ".bin"));
            Log.i(TAG, "grafo " + cx + "_" + cy + ": " + c.nodes + " nós, " + c.edges + " arestas, "
                + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception e) {
            Log.w(TAG, "grafo " + cx + "_" + cy + ": " + e);
            broken.add(k);
            return null;
        }
        memory.put(k, c);
        memoryBytes += c.memoryBytes();
        Iterator<Map.Entry<Long, GraphCell>> it = memory.entrySet().iterator();
        while (memoryBytes > MEMORY_BYTES && memory.size() > 1 && it.hasNext()) {
            Map.Entry<Long, GraphCell> e = it.next();
            if (e.getKey() == k) continue;
            memoryBytes -= e.getValue().memoryBytes();
            it.remove();
        }
        return c;
    }

    /** Um quadrado foi baixado de novo ou apagado. */
    public synchronized void invalidate(String name) {
        int u = name.indexOf('_');
        long k = key(Integer.parseInt(name.substring(0, u)), Integer.parseInt(name.substring(u + 1)));
        GraphCell c = memory.remove(k);
        if (c != null) memoryBytes -= c.memoryBytes();
        broken.remove(k);
        files = null;
    }

    /** Devolve a memória (tela do mapa fechada). */
    public synchronized void trimMemory() {
        memory.clear();
        memoryBytes = 0;
    }
}
