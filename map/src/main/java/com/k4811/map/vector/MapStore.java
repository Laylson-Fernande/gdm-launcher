package com.k4811.map.vector;

import android.util.Log;

import com.k4811.common.config.NwdConfigs;
import com.k4811.map.MapConfig;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Os arquivos do mapa offline ({@code /sdcard/K4811/nav/maps/cells/}) e um cache em memória das
 * seções já lidas. Ler só fora da thread principal (as threads dos tiles).
 *
 * Sabe também quais arquivos existem no servidor (o {@code index.json} baixado pelo
 * {@link com.k4811.map.road.RoadSync}): um quadrado que não está no índice não tem nada (mar aberto,
 * fora do recorte), e o tile pode ser desenhado sem ele.
 */
public final class MapStore {

    private static final String TAG = "K4811MapData";
    private static final MapStore INSTANCE = new MapStore();

    private final LinkedHashMap<String, MapSection> memory = new LinkedHashMap<>(64, 0.75f, true);
    private int memoryBytes;
    private int maxBytes = 24 * 1024 * 1024;
    /** Arquivos no disco (nomes sem .bin); null = ainda não listou. */
    private Set<String> files;
    /** Arquivos no servidor (do índice); null = sem índice. */
    private Set<String> remote;
    private boolean remoteLoaded;
    private volatile int generation;

    public static MapStore get() {
        return INSTANCE;
    }

    public static File dir() {
        return new File(MapConfig.dir(), "maps");
    }

    public static File cellsDir() {
        return new File(dir(), "cells");
    }

    /** Limite de memória das seções lidas (conforme a memória do app). */
    public synchronized void setMaxBytes(int bytes) {
        maxBytes = bytes;
    }

    public int generation() {
        return generation;
    }

    /** O arquivo está no disco. */
    public synchronized boolean has(String name) {
        if (files == null) {
            files = new HashSet<>();
            String[] list = cellsDir().list();
            if (list != null) {
                for (String f : list) if (f.endsWith(".bin")) files.add(f.substring(0, f.length() - 4));
            }
        }
        return files.contains(name);
    }

    /** O servidor tem esse arquivo: true/false; null se ainda não há índice baixado. */
    public synchronized Boolean inIndex(String name) {
        if (!remoteLoaded) {
            remoteLoaded = true;
            JSONObject index = NwdConfigs.read(new File(dir(), "index.json"));
            JSONObject cells = index == null ? null : index.optJSONObject("cells");
            if (cells != null) {
                remote = new HashSet<>();
                Iterator<String> it = cells.keys();
                while (it.hasNext()) remote.add(it.next());
            }
        }
        return remote == null ? null : remote.contains(name);
    }

    /** A seção, do cache ou do disco; null se não houver (arquivo ausente, sem a seção, corrompido). */
    public synchronized MapSection section(String name, int index) {
        String k = name + "#" + index;
        MapSection s = memory.get(k);
        if (s != null) return s;
        if (!has(name)) return null;
        try {
            s = MapSection.read(new File(cellsDir(), name + ".bin"), index);
        } catch (Exception e) {
            Log.w(TAG, name + "#" + index + ": " + e);
            return null;
        }
        if (s == null) return null;
        memory.put(k, s);
        memoryBytes += s.memoryBytes();
        Iterator<Map.Entry<String, MapSection>> it = memory.entrySet().iterator();
        while (memoryBytes > maxBytes && memory.size() > 1 && it.hasNext()) {
            Map.Entry<String, MapSection> e = it.next();
            if (e.getKey().equals(k)) continue;
            memoryBytes -= e.getValue().memoryBytes();
            it.remove();
        }
        return s;
    }

    /** Um arquivo foi baixado de novo ou apagado. */
    public synchronized void invalidate(String name) {
        generation++;
        Iterator<Map.Entry<String, MapSection>> it = memory.entrySet().iterator();
        String prefix = name + "#";
        while (it.hasNext()) {
            Map.Entry<String, MapSection> e = it.next();
            if (!e.getKey().startsWith(prefix)) continue;
            memoryBytes -= e.getValue().memoryBytes();
            it.remove();
        }
        files = null;
    }

    /** O índice do servidor mudou. */
    public synchronized void indexChanged() {
        generation++;
        remoteLoaded = false;
        remote = null;
    }

    /** Devolve a memória (tela do mapa fechada). */
    public synchronized void trimMemory() {
        memory.clear();
        memoryBytes = 0;
    }
}
