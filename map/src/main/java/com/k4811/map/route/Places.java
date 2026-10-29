package com.k4811.map.route;

import android.content.Context;

import com.k4811.map.MapConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Busca de endereços e lugares pelo Nominatim ({@link MapConfig#search}). A política do serviço
 * pede no máximo 1 busca por segundo e nada de autocompletar: busca só quando o motorista confirma.
 * Bloqueia: chamar fora da thread principal.
 *
 * <b>Número da casa.</b> O Nominatim só acha o número se ele estiver no OpenStreetMap (no Brasil,
 * muitas ruas não têm). Por isso: os resultados com o número digitado vêm primeiro; sem nenhum, tenta
 * de novo com o número antes da rua ("123 Rua X", que o Nominatim entende melhor); e, se mesmo assim a
 * rua vier sem o número, o resultado sai marcado como aproximado ({@link Place#approximate}), para a
 * tela avisar (o ponto é o meio da rua, e o motorista pode marcar o lugar certo tocando no mapa).
 */
public final class Places {
    private Places() {
    }

    public static final class Place {
        public final String name;
        public final String detail;
        public final double lat;
        public final double lon;
        /** Número digitado que não está no mapa: o ponto é o da rua, não o da casa. */
        public final boolean approximate;
        /** O número digitado (para o aviso), ou null. */
        public final String number;

        public Place(String name, String detail, double lat, double lon) {
            this(name, detail, lat, lon, false, null);
        }

        public Place(String name, String detail, double lat, double lon, boolean approximate, String number) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
            this.approximate = approximate;
            this.number = number;
        }
    }

    /** Número de casa: até 5 dígitos, talvez com letra (123, 45A). CEP e "BR-232" não passam. */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,5}[A-Za-z]?");
    /** Tipos de via (highway=*) que são ruas: o ponto do resultado é o meio dela. */
    private static final Pattern STREET = Pattern.compile(
        "(motorway|trunk|primary|secondary|tertiary)(_link)?|unclassified|residential|living_street|service|pedestrian|road|track");
    /** "Rua 15 de Novembro", "Av. 7 de Setembro": o número faz parte do nome. */
    private static final List<String> NAME_LINKS = Arrays.asList("de", "do", "da", "dos", "das");

    private static long lastSearch;

    /**
     * @param nearLat posição atual (NaN se não houver), para preferir resultados por perto
     * @param language idioma das respostas (ex.: "pt")
     */
    public static List<Place> search(Context c, String query, double nearLat, double nearLon, String language)
        throws Exception {
        String number = houseNumber(query);
        List<Place> out = query(c, query, nearLat, nearLon, language, number);
        if (number == null || hasExact(out)) return out;
        // Sem o número nos resultados: "123 Rua X, cidade" costuma achar quando o número existe no mapa.
        List<Place> again = query(c, numberFirst(query, number), nearLat, nearLon, language, number);
        List<Place> merged = new ArrayList<>();
        for (Place p : again) if (!p.approximate && p.number == null) merged.add(p);
        for (Place p : out) if (!near(merged, p)) merged.add(p);
        for (Place p : again) if (!near(merged, p)) merged.add(p);
        return merged;
    }

    /**
     * Endereço de um ponto (toque longo no mapa): "Rua X, 123" e o resto do endereço; null se o serviço
     * não respondeu nada útil ou o endereço de busca não é o do Nominatim.
     */
    public static Place reverse(Context c, double lat, double lon, String language) throws Exception {
        MapConfig cfg = MapConfig.get(c);
        if (!cfg.search.endsWith("/search")) return null;
        throttle();
        StringBuilder url = new StringBuilder(cfg.search.substring(0, cfg.search.length() - "search".length()));
        url.append("reverse?format=jsonv2&addressdetails=1&zoom=18");
        url.append(String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lon));
        if (language != null) url.append("&accept-language=").append(language);
        JSONObject o = new JSONObject(Net.get(url.toString(), cfg.userAgent));
        if (o.has("error")) return null;
        Place p = place(o, null);
        // O ponto é onde o motorista tocou, não o do endereço achado.
        return p == null ? null : new Place(p.name, p.detail, lat, lon);
    }

    private static List<Place> query(Context c, String query, double nearLat, double nearLon, String language,
                                     String number) throws Exception {
        MapConfig cfg = MapConfig.get(c);
        throttle();
        StringBuilder url = new StringBuilder(cfg.search);
        url.append("?format=jsonv2&addressdetails=1&limit=8&q=").append(URLEncoder.encode(query, "UTF-8"));
        if (language != null) url.append("&accept-language=").append(language);
        if (!Double.isNaN(nearLat)) {
            // Caixa de ~100 km em volta: prefere o que está perto, sem excluir o resto.
            url.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                nearLon - 0.5, nearLat + 0.5, nearLon + 0.5, nearLat - 0.5));
        }
        JSONArray arr = new JSONArray(Net.get(url.toString(), cfg.userAgent));
        List<Place> exact = new ArrayList<>();
        List<Place> rest = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Place p = place(arr.getJSONObject(i), number);
            if (p == null) continue;
            if (number != null && !p.approximate && p.number == null) exact.add(p);
            else rest.add(p);
        }
        exact.addAll(rest); // o número digitado primeiro
        return exact;
    }

    /**
     * Um resultado do Nominatim. Com {@code number} (digitado): sem ele no endereço, uma rua sai
     * aproximada; um lugar com nome (loja, praça) fica como está, com {@code number} anotado.
     */
    private static Place place(JSONObject o, String number) {
        String display = o.optString("display_name", "");
        if (display.isEmpty()) return null;
        JSONObject address = o.optJSONObject("address");
        String road = address == null ? "" : address.optString("road", "");
        String house = address == null ? "" : address.optString("house_number", "");
        String name = o.optString("name", "");
        List<String> parts = new ArrayList<>(Arrays.asList(display.split(",\\s*")));
        if (!road.isEmpty() && !house.isEmpty() && name.isEmpty()) {
            name = road + ", " + house;
            parts.remove(house);
            parts.remove(road);
        } else if (name.isEmpty()) {
            name = parts.isEmpty() ? display : parts.remove(0);
        } else if (!parts.isEmpty() && parts.get(0).equals(name)) {
            parts.remove(0);
        }
        String detail = join(parts);
        double lat = o.optDouble("lat");
        double lon = o.optDouble("lon");
        if (number == null || sameNumber(house, number)) return new Place(name, detail, lat, lon);
        // Só a rua (um ponto de ônibus também é "highway", mas é um lugar com nome).
        boolean street = "highway".equals(o.optString("category")) && STREET.matcher(o.optString("type")).matches();
        return new Place(name, detail, lat, lon, street, number);
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }

    /** Último número de casa da busca ("Rua X, 123, Cidade" → 123); null se não há. */
    static String houseNumber(String query) {
        String[] tokens = query.trim().split("[\\s,]+");
        for (int i = tokens.length - 1; i >= 1; i--) {
            if (!NUMBER.matcher(tokens[i]).matches()) continue;
            boolean partOfName = i + 1 < tokens.length && NAME_LINKS.contains(tokens[i + 1].toLowerCase(Locale.ROOT));
            return partOfName ? null : tokens[i];
        }
        return null;
    }

    /** "Rua X, 123, Cidade" → "123 Rua X, Cidade". */
    static String numberFirst(String query, String number) {
        String without = query.replaceFirst("(?i)(,\\s*)?\\b(n[º°o.]\\s*)?" + Pattern.quote(number) + "\\b", "")
            .replaceAll("\\s+", " ").replaceAll(",\\s*,", ",").trim();
        return number + " " + without;
    }

    private static boolean sameNumber(String house, String number) {
        if (house.isEmpty()) return false;
        for (String h : house.split("[;,\\s]+")) if (h.equalsIgnoreCase(number)) return true;
        return false;
    }

    /** Algum resultado com o número digitado. */
    private static boolean hasExact(List<Place> list) {
        for (Place p : list) if (!p.approximate && p.number == null) return true;
        return false;
    }

    private static boolean near(List<Place> list, Place p) {
        for (Place o : list) if (Math.abs(o.lat - p.lat) < 1e-4 && Math.abs(o.lon - p.lon) < 1e-4) return true;
        return false;
    }

    /** No máximo 1 pedido por segundo ao Nominatim. */
    private static void throttle() throws InterruptedException {
        synchronized (Places.class) {
            long wait = 1100 - (System.currentTimeMillis() - lastSearch);
            if (wait > 0) Thread.sleep(wait);
            lastSearch = System.currentTimeMillis();
        }
    }
}
