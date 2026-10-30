package com.k4811.map.google;

import android.content.Context;

import com.k4811.common.config.NwdConfigs;
import com.k4811.map.MapConfig;
import com.k4811.map.route.Places;
import com.k4811.map.search.Text;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Busca do mapa integrado no modo Google (Places API New), do jeito mais barato:
 * <ul>
 * <li>{@link #autocomplete}: sugestões enquanto digita (Autocomplete com token de sessão; cada pedido
 * conta como "Autocomplete Requests"). Só nome, endereço e {@code place_id}: o lugar vem sem
 * coordenadas.</li>
 * <li>{@link #details}: o lugar escolhido (Place Details só com campos Essentials: {@code location},
 * {@code formattedAddress}, {@code shortFormattedAddress}), que encerra a sessão.</li>
 * </ul>
 * Text Search não é usado: com nome e coordenadas ele é cobrado como Pro.
 *
 * Cache: sugestões em memória por 1 hora (texto normalizado + posição arredondada em ~1 km); lugares
 * em memória e em {@code K4811/nav/google/places.json} por 30 dias (o máximo que os termos permitem
 * guardar coordenadas). Bloqueia: chamar fora da thread principal.
 */
public final class GooglePlaces {
    private GooglePlaces() {
    }

    private static final String AUTOCOMPLETE = "https://places.googleapis.com/v1/places:autocomplete";
    private static final String DETAILS = "https://places.googleapis.com/v1/places/";
    private static final String AUTOCOMPLETE_FIELDS =
        "suggestions.placePrediction.placeId,suggestions.placePrediction.structuredFormat";
    /** Só campos Essentials: um campo Pro (displayName, por exemplo) muda o preço do pedido. */
    private static final String DETAILS_FIELDS = "location,formattedAddress,shortFormattedAddress";
    private static final long SUGGEST_TTL_MS = 3600 * 1000L;
    private static final int SUGGEST_CACHE = 120;
    /** Raio da preferência pela posição (máximo do Google: 50 km). */
    private static final double BIAS_M = 50000;

    private static final class Cached {
        final long at;
        final List<Places.Place> list;

        Cached(long at, List<Places.Place> list) {
            this.at = at;
            this.list = list;
        }
    }

    private static final LinkedHashMap<String, Cached> suggestions = new LinkedHashMap<>(SUGGEST_CACHE, 0.75f, true);
    private static LinkedHashMap<String, JSONObject> places;
    private static String sessionToken;

    /** Começa uma sessão nova de busca (ao abrir a busca e depois de escolher um lugar). */
    public static synchronized void newSession() {
        sessionToken = UUID.randomUUID().toString();
    }

    private static synchronized String session() {
        if (sessionToken == null) newSession();
        return sessionToken;
    }

    /**
     * Sugestões para {@code text}, do cache ou do Google. Lista vazia se não há cota ou o Google falhou
     * (quem chamou mostra só as outras).
     */
    public static List<Places.Place> autocomplete(Context c, String text, double lat, double lon, String lang) {
        String key = Text.normalize(text) + "|" + (Double.isNaN(lat) ? "-" : String.format(Locale.US, "%.2f,%.2f", lat, lon))
            + "|" + lang;
        long now = System.currentTimeMillis();
        synchronized (suggestions) {
            Cached hit = suggestions.get(key);
            if (hit != null && now - hit.at < SUGGEST_TTL_MS) return hit.list;
        }
        GoogleQuota quota = GoogleQuota.get();
        if (!quota.take(c, GoogleQuota.Sku.AUTOCOMPLETE)) return Collections.emptyList();
        List<Places.Place> out = new ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            body.put("input", text);
            body.put("sessionToken", session());
            body.put("languageCode", lang);
            if (!Double.isNaN(lat)) {
                JSONObject center = new JSONObject().put("latitude", lat).put("longitude", lon);
                body.put("locationBias", new JSONObject().put("circle",
                    new JSONObject().put("center", center).put("radius", BIAS_M)));
                body.put("origin", new JSONObject().put("latitude", lat).put("longitude", lon));
            }
            JSONObject r = new JSONObject(GoogleHttp.request(c, AUTOCOMPLETE, AUTOCOMPLETE_FIELDS, body.toString()).text());
            JSONArray all = r.optJSONArray("suggestions");
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject p = all.getJSONObject(i).optJSONObject("placePrediction");
                if (p == null) continue;
                String id = p.optString("placeId", "");
                JSONObject f = p.optJSONObject("structuredFormat");
                String main = f == null ? "" : text(f.optJSONObject("mainText"));
                String second = f == null ? "" : text(f.optJSONObject("secondaryText"));
                if (id.isEmpty() || main.isEmpty()) continue;
                Places.Place known = fromCache(id, main);
                out.add(known != null ? known
                    : new Places.Place(main, second, Double.NaN, Double.NaN, false, null, id, 0));
            }
        } catch (Exception e) {
            quota.failed(c, e);
            return Collections.emptyList();
        }
        synchronized (suggestions) {
            suggestions.put(key, new Cached(now, out));
            Iterator<Map.Entry<String, Cached>> it = suggestions.entrySet().iterator();
            while (suggestions.size() > SUGGEST_CACHE && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return out;
    }

    /**
     * O lugar com coordenadas: do cache (menos de 30 dias) ou do Place Details. null se não há cota ou
     * o Google falhou.
     */
    public static Places.Place details(Context c, Places.Place p, String lang) {
        if (p.placeId == null) return p;
        Places.Place known = fromCache(p.placeId, p.name);
        if (known != null) return known;
        GoogleQuota quota = GoogleQuota.get();
        if (!quota.take(c, GoogleQuota.Sku.DETAILS)) return null;
        try {
            String url = DETAILS + p.placeId + "?languageCode=" + lang + "&sessionToken=" + session();
            JSONObject r = new JSONObject(GoogleHttp.request(c, url, DETAILS_FIELDS, null).text());
            newSession(); // o Details encerra a sessão
            JSONObject loc = r.optJSONObject("location");
            if (loc == null) return null;
            double lat = loc.optDouble("latitude");
            double lon = loc.optDouble("longitude");
            if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
            String address = r.optString("shortFormattedAddress", "");
            if (address.isEmpty()) address = r.optString("formattedAddress", "");
            String detail = p.detail.isEmpty() ? address : p.detail;
            long now = System.currentTimeMillis();
            remember(p.placeId, new JSONObject().put("lat", lat).put("lon", lon).put("detail", detail).put("at", now));
            return new Places.Place(p.name, detail, lat, lon, false, null, p.placeId, now);
        } catch (Exception e) {
            newSession();
            quota.failed(c, e);
            return null;
        }
    }

    private static String text(JSONObject o) {
        return o == null ? "" : o.optString("text", "").trim();
    }

    // ------------------------------------------------------------------ cache dos lugares

    private static File file() {
        return new File(MapConfig.dir(), "google/places.json");
    }

    /** Lugar guardado há menos de 30 dias, com o nome dado; null se não há. */
    private static synchronized Places.Place fromCache(String id, String name) {
        load();
        JSONObject o = places.get(id);
        if (o == null) return null;
        long at = o.optLong("at", 0);
        if (System.currentTimeMillis() - at > Places.Place.GOOGLE_COORDS_MS) {
            places.remove(id);
            return null;
        }
        return new Places.Place(name, o.optString("detail", ""), o.optDouble("lat"), o.optDouble("lon"),
            false, null, id, at);
    }

    private static synchronized void remember(String id, JSONObject o) {
        load();
        places.put(id, o);
        // Até 500 lugares; os vencidos saem ao gravar.
        long now = System.currentTimeMillis();
        JSONObject all = new JSONObject();
        try {
            Iterator<Map.Entry<String, JSONObject>> it = places.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JSONObject> e = it.next();
                if (now - e.getValue().optLong("at", 0) > Places.Place.GOOGLE_COORDS_MS || places.size() > 500) {
                    it.remove();
                    continue;
                }
                all.put(e.getKey(), e.getValue());
            }
            File f = file();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            NwdConfigs.write(f, new JSONObject().put("places", all));
        } catch (JSONException ignored) {
        }
    }

    private static void load() {
        if (places != null) return;
        places = new LinkedHashMap<>();
        JSONObject j = NwdConfigs.read(file());
        JSONObject all = j == null ? null : j.optJSONObject("places");
        if (all == null) return;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject o = all.optJSONObject(id);
            if (o != null) places.put(id, o);
        }
    }
}
