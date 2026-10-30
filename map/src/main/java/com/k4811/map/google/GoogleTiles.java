package com.k4811.map.google;

import android.content.Context;
import android.util.Log;

import com.k4811.common.config.LauncherConfig;
import com.k4811.common.config.NwdConfigs;
import com.k4811.map.MapConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Tiles 2D do Google (Map Tiles API, {@code roadmap}), para o mapa integrado no modo Google. Uma
 * sessão por tema (claro e escuro, com o estilo noturno), guardada em
 * {@code K4811/nav/google/session-<tema>.json} e refeita perto de vencer (vale ~2 semanas).
 *
 * Cada tile baixado conta na cota {@link GoogleQuota.Sku#TILES}. O cache em disco segue o
 * {@code Cache-Control} da resposta (os termos não deixam guardar mais que isso): ver
 * {@link com.k4811.map.tiles.Tiles}.
 */
public final class GoogleTiles {
    private GoogleTiles() {
    }

    private static final String TAG = "K4811Google";
    private static final String CREATE = "https://tile.googleapis.com/v1/createSession";
    private static final String TILE = "https://tile.googleapis.com/v1/2dtiles/";
    private static final String VIEWPORT = "https://tile.googleapis.com/tile/v1/viewport";
    /** Sessão refeita um dia antes de vencer. */
    private static final long RENEW_MS = 24 * 3600 * 1000L;

    /** Estilo noturno (cores do exemplo "Night mode" do Google), só no tema escuro. */
    private static final String NIGHT_STYLE = "["
        + "{\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#242f3e\"}]},"
        + "{\"elementType\":\"labels.text.stroke\",\"stylers\":[{\"color\":\"#242f3e\"}]},"
        + "{\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#9aa3ae\"}]},"
        + "{\"featureType\":\"poi\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#d59563\"}]},"
        + "{\"featureType\":\"poi.park\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#263c3f\"}]},"
        + "{\"featureType\":\"road\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#38414e\"}]},"
        + "{\"featureType\":\"road\",\"elementType\":\"geometry.stroke\",\"stylers\":[{\"color\":\"#212a37\"}]},"
        + "{\"featureType\":\"road\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#9ca5b3\"}]},"
        + "{\"featureType\":\"road.highway\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#746855\"}]},"
        + "{\"featureType\":\"road.highway\",\"elementType\":\"geometry.stroke\",\"stylers\":[{\"color\":\"#1f2835\"}]},"
        + "{\"featureType\":\"transit\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#2f3948\"}]},"
        + "{\"featureType\":\"water\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#17263c\"}]},"
        + "{\"featureType\":\"water\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#515c6d\"}]}"
        + "]";

    private static final String[] session = new String[2];
    private static final long[] expiry = new long[2];
    private static final String[] sessionLang = new String[2];

    /** Um tile baixado: a imagem e até quando ela pode ficar em cache (0 = não guardar). */
    public static final class Tile {
        public final byte[] data;
        public final long expiresAt;

        Tile(byte[] data, long expiresAt) {
            this.data = data;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Baixa um tile. null se não há cota, a sessão não pôde ser criada ou o pedido falhou (a falha de
     * chave ou de cota suspende o Google, ver {@link GoogleQuota#failed}).
     */
    public static Tile download(Context c, int z, int x, int y, boolean dark) {
        GoogleQuota quota = GoogleQuota.get();
        if (quota.blocked()) return null;
        String token;
        try {
            token = session(c, dark);
        } catch (Exception e) {
            quota.failed(c, e);
            return null;
        }
        if (token == null || !quota.take(c, GoogleQuota.Sku.TILES)) return null;
        try {
            GoogleHttp.Response r = GoogleHttp.request(c, TILE + z + "/" + x + "/" + y + "?session=" + token, null, null);
            long expires = r.maxAgeSeconds > 0 ? System.currentTimeMillis() + r.maxAgeSeconds * 1000 : 0;
            return new Tile(r.body, expires);
        } catch (Exception e) {
            quota.failed(c, e);
            if (e instanceof GoogleHttp.HttpError && ((GoogleHttp.HttpError) e).code == 401) forget(dark);
            return null;
        }
    }

    /**
     * Texto de atribuição exigido pelo Google para a área na tela ("Map data ©2026 Google"), ou null
     * se não deu. Não é cobrado; chamar com moderação (a tela pede só quando a área muda bastante).
     */
    public static String copyright(Context c, int zoom, double north, double south, double east, double west,
                                   boolean dark) {
        try {
            String token = session(c, dark);
            if (token == null) return null;
            String url = String.format(Locale.US, "%s?session=%s&zoom=%d&north=%.5f&south=%.5f&east=%.5f&west=%.5f",
                VIEWPORT, token, zoom, north, south, east, west);
            JSONObject j = new JSONObject(GoogleHttp.request(c, url, null, null).text());
            String text = j.optString("copyright", "").trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            Log.d(TAG, "viewport: " + e);
            return null;
        }
    }

    /** Token da sessão do tema; cria (e grava) quando não há, venceu ou o idioma mudou. */
    private static synchronized String session(Context c, boolean dark) throws Exception {
        int i = dark ? 1 : 0;
        String lang = language(c);
        long now = System.currentTimeMillis();
        if (session[i] == null) loadSession(i);
        if (session[i] != null && now < expiry[i] - RENEW_MS && lang.equals(sessionLang[i])) return session[i];

        JSONObject body = new JSONObject();
        body.put("mapType", "roadmap");
        body.put("language", lang);
        body.put("region", region());
        body.put("imageFormat", "png");
        body.put("scale", "scaleFactor1x");
        if (dark) body.put("styles", new JSONArray(NIGHT_STYLE));
        JSONObject r = new JSONObject(GoogleHttp.request(c, CREATE, null, body.toString()).text());
        String token = r.optString("session", "");
        if (token.isEmpty()) throw new java.io.IOException("createSession sem sessão");
        long exp = r.optLong("expiry", 0) * 1000L;
        if (exp <= now) exp = now + 13L * 24 * 3600 * 1000;
        session[i] = token;
        expiry[i] = exp;
        sessionLang[i] = lang;
        saveSession(i);
        return token;
    }

    private static synchronized void forget(boolean dark) {
        int i = dark ? 1 : 0;
        session[i] = null;
        expiry[i] = 0;
        sessionFile(i).delete();
    }

    /** Idioma da suíte com a região do aparelho ("pt-BR"), como o Google pede. */
    private static String language(Context c) {
        String lang = LauncherConfig.language(c);
        String country = region();
        return country.isEmpty() ? lang : lang + "-" + country;
    }

    private static String region() {
        String country = Locale.getDefault().getCountry();
        return country == null || country.length() != 2 ? "BR" : country.toUpperCase(Locale.ROOT);
    }

    private static File sessionFile(int i) {
        return new File(MapConfig.dir(), "google/session-" + (i == 1 ? "dark" : "light") + ".json");
    }

    private static void loadSession(int i) {
        JSONObject j = NwdConfigs.read(sessionFile(i));
        if (j == null) return;
        session[i] = j.optString("session", null);
        expiry[i] = j.optLong("expiry", 0);
        sessionLang[i] = j.optString("language", "");
    }

    private static void saveSession(int i) {
        try {
            File f = sessionFile(i);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            NwdConfigs.write(f, new JSONObject().put("session", session[i]).put("expiry", expiry[i])
                .put("language", sessionLang[i]));
        } catch (JSONException ignored) {
        }
    }
}
