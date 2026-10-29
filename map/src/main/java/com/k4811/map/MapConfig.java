package com.k4811.map;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;

import com.k4811.common.config.NwdConfigs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pasta e configuração do mapa: {@code /sdcard/K4811/nav/}.
 * <ul>
 * <li>{@code nav.config.json}: servidores (tiles, rota, busca) e o tamanho do cache, editável à mão;
 * criado com os padrões na primeira vez.</li>
 * <li>{@code cache/}: tiles baixados (z/x/y.png), apagados do mais antigo quando passa do limite.</li>
 * <li>{@code *.mbtiles}: mapas offline (SQLite com tiles PNG/JPG), usados antes da internet.</li>
 * <li>{@code recent.json}: destinos recentes.</li>
 * <li>{@code roads/}: dados das vias (radares, limites), ver {@link com.k4811.map.road.RoadSync}.</li>
 * <li>{@code maps/}: mapa offline (ruas, água, prédios…), desenhado pelo app, ver {@link com.k4811.map.vector.MapStore}.</li>
 * </ul>
 * Os servidores padrão são os públicos do OpenStreetMap e da FOSSGIS: uso leve, com User-Agent
 * próprio e cache, como pedem as políticas deles.
 */
public final class MapConfig {

    public static final String DEFAULT_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    public static final String DEFAULT_ROUTE = "https://routing.openstreetmap.de/routed-car/route/v1/driving/";
    public static final String DEFAULT_SEARCH = "https://nominatim.openstreetmap.org/search";
    private static final int DEFAULT_CACHE_MB = 200;
    private static final int DEFAULT_MAX_ZOOM = 19;
    private static final int DEFAULT_ROAD_DATA_MB = 300;
    private static final int DEFAULT_MAP_DATA_MB = 1024;
    /** Camadas do mapa offline que ficam de fora por padrão (desempenho na central). */
    private static final String[] DEFAULT_MAP_HIDE = {"buildings", "paths", "green", "lines"};

    public final String tiles;
    public final String route;
    public final String search;
    public final long cacheBytes;
    public final int maxZoom;
    /**
     * Endereço dos dados das vias (termina em "/"; vazio = não baixa). Padrão: o do build
     * (BuildConfig.ROAD_DATA_URL); só fica no arquivo se alguém escrever "road_data" à mão.
     */
    public final String roadData;
    /** Espaço máximo dos dados das vias; ao passar, saem os quadrados mais longe. */
    public final long roadDataBytes;
    /**
     * Endereço do mapa offline: o mesmo servidor dos dados das vias, em {@code maps/v1/} (ou
     * "map_data" no arquivo, escrito à mão).
     */
    public final String mapData;
    public final long mapDataBytes;
    /**
     * Camadas do mapa offline que não são desenhadas ("map_hide" no arquivo, lista): {@code buildings}
     * (prédios), {@code paths} (vias de serviço, calçadões, trilhas e caminhos), {@code casings}
     * (contorno das vias), {@code green} (parques, matas, areia), {@code lines} (limites, trilhos,
     * córregos), {@code road_names} (nomes das ruas), {@code antialias} (bordas suavizadas).
     */
    public final Set<String> mapHide;
    /** Tile do mapa offline mais lento que isto (ms) vai para o log ("map_log_ms"; 0 = todos). */
    public final int mapLogMs;
    /** User-Agent das requisições (as políticas do OSM pedem um que identifique o app). */
    public final String userAgent;

    private static MapConfig cached;
    private static long cachedModified = -1;

    private MapConfig(JSONObject j, String userAgent) {
        tiles = j.optString("tiles", DEFAULT_TILES);
        route = j.optString("route", DEFAULT_ROUTE);
        search = j.optString("search", DEFAULT_SEARCH);
        cacheBytes = Math.max(20, j.optInt("cache_mb", DEFAULT_CACHE_MB)) * 1024L * 1024L;
        maxZoom = Math.max(10, Math.min(20, j.optInt("max_zoom", DEFAULT_MAX_ZOOM)));
        String road = j.optString("road_data", BuildConfig.ROAD_DATA_URL).trim();
        roadData = road.isEmpty() || road.endsWith("/") ? road : road + "/";
        roadDataBytes = Math.max(20, j.optInt("road_data_mb", DEFAULT_ROAD_DATA_MB)) * 1024L * 1024L;
        String derived = roadData.endsWith("roads/v1/") ? roadData.substring(0, roadData.length() - 9) + "maps/v1/" : "";
        String map = j.optString("map_data", derived).trim();
        mapData = map.isEmpty() || map.endsWith("/") ? map : map + "/";
        mapDataBytes = Math.max(50, j.optInt("map_data_mb", DEFAULT_MAP_DATA_MB)) * 1024L * 1024L;
        Set<String> hide = new HashSet<>();
        JSONArray h = j.optJSONArray("map_hide");
        if (h == null) Collections.addAll(hide, DEFAULT_MAP_HIDE);
        else for (int i = 0; i < h.length(); i++) hide.add(h.optString(i));
        mapHide = Collections.unmodifiableSet(hide);
        mapLogMs = Math.max(0, j.optInt("map_log_ms", 400));
        this.userAgent = userAgent;
    }

    public static File dir() {
        return new File(Environment.getExternalStorageDirectory(), "K4811/nav");
    }

    public static File file() {
        return new File(dir(), "nav.config.json");
    }

    /** Configuração atual; relê o arquivo só quando ele muda. Cria o arquivo com os padrões. */
    public static synchronized MapConfig get(Context c) {
        File f = file();
        long modified = f.lastModified();
        if (cached != null && modified == cachedModified) return cached;
        JSONObject j = NwdConfigs.read(f);
        if (j == null) {
            j = defaults();
            NwdConfigs.write(f, j);
            modified = f.lastModified();
        }
        cachedModified = modified;
        cached = new MapConfig(j, userAgent(c));
        return cached;
    }

    private static JSONObject defaults() {
        JSONObject j = new JSONObject();
        try {
            j.put("tiles", DEFAULT_TILES);
            j.put("route", DEFAULT_ROUTE);
            j.put("search", DEFAULT_SEARCH);
            j.put("cache_mb", DEFAULT_CACHE_MB);
            j.put("max_zoom", DEFAULT_MAX_ZOOM);
        } catch (JSONException ignored) {
        }
        return j;
    }

    private static String userAgent(Context c) {
        String version = "?";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        return "K4811-HeadUnit/" + version + " (Android; navigation)";
    }

    /** Há uma rede conectada (não garante que a internet responda). */
    @SuppressWarnings("deprecation")
    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
