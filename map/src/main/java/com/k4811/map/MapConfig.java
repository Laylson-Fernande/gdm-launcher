package com.k4811.map;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;

import com.k4811.common.config.NwdConfigs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Pasta e configuração do mapa: {@code /sdcard/K4811/nav/}.
 * <ul>
 * <li>{@code nav.config.json}: servidores (tiles, rota, busca) e o tamanho do cache, editável à mão;
 * criado com os padrões na primeira vez.</li>
 * <li>{@code cache/}: tiles baixados (z/x/y.png), apagados do mais antigo quando passa do limite.</li>
 * <li>{@code *.mbtiles}: mapas offline (SQLite com tiles PNG/JPG), usados antes da internet.</li>
 * <li>{@code recent.json}: destinos recentes.</li>
 * <li>{@code roads/}: dados das vias (radares, limites), ver {@link com.k4811.map.road.RoadSync}.</li>
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
