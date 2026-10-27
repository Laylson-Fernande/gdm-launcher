package com.k4811.map.route;

import android.content.Context;

import com.k4811.map.MapConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Busca de endereços e lugares pelo Nominatim ({@link MapConfig#search}). A política do serviço
 * pede no máximo 1 busca por segundo e nada de autocompletar: busca só quando o motorista confirma.
 * Bloqueia: chamar fora da thread principal.
 */
public final class Places {
    private Places() {
    }

    public static final class Place {
        public final String name;
        public final String detail;
        public final double lat;
        public final double lon;

        public Place(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static long lastSearch;

    /**
     * @param nearLat posição atual (NaN se não houver), para preferir resultados por perto
     * @param language idioma das respostas (ex.: "pt")
     */
    public static List<Place> search(Context c, String query, double nearLat, double nearLon, String language)
        throws Exception {
        MapConfig cfg = MapConfig.get(c);
        synchronized (Places.class) {
            long wait = 1100 - (System.currentTimeMillis() - lastSearch);
            if (wait > 0) Thread.sleep(wait);
            lastSearch = System.currentTimeMillis();
        }
        StringBuilder url = new StringBuilder(cfg.search);
        url.append("?format=jsonv2&limit=8&q=").append(URLEncoder.encode(query, "UTF-8"));
        if (language != null) url.append("&accept-language=").append(language);
        if (!Double.isNaN(nearLat)) {
            // Caixa de ~100 km em volta: prefere o que está perto, sem excluir o resto.
            url.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                nearLon - 0.5, nearLat + 0.5, nearLon + 0.5, nearLat - 0.5));
        }
        JSONArray arr = new JSONArray(Net.get(url.toString(), cfg.userAgent));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String display = o.optString("display_name", "");
            String name = o.optString("name", "");
            if (name.isEmpty()) {
                int comma = display.indexOf(',');
                name = comma > 0 ? display.substring(0, comma) : display;
            }
            String detail = display.startsWith(name) ? display.substring(name.length()) : display;
            detail = detail.replaceFirst("^[,\\s]+", "");
            out.add(new Place(name, detail, o.optDouble("lat"), o.optDouble("lon")));
        }
        return out;
    }
}
