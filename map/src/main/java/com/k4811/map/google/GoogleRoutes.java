package com.k4811.map.google;

import android.content.Context;

import com.k4811.map.geo.Geo;
import com.k4811.map.route.Route;
import com.k4811.map.route.Router;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rota do mapa integrado no modo Google (Routes API, {@code computeRoutes}), convertida para a
 * {@link Route} da suíte: os tipos de manobra viram os do OSRM, então faixa, ícones, voz e
 * {@link com.k4811.map.route.Navigator} não mudam.
 *
 * Duas cotas: com trânsito ({@code TRAFFIC_AWARE} e o rumo do carro na partida, cobrado como Pro) e
 * sem trânsito ({@code TRAFFIC_UNAWARE}, sem rumo, Essentials). Sem nenhuma das duas, devolve null e
 * quem chamou usa o OSRM ou o grafo baixado.
 *
 * Os passos levam o texto da instrução do Google ({@link Route.Step#instruction}) só para a tela; o
 * nome da rua fica vazio para a voz não usar conteúdo do Google (termos: nada de texto para fala).
 *
 * Cache em memória: mesma origem (~100 m), destino, rumo (30°) e tipo, por 5 min com trânsito e 30 min
 * sem. Bloqueia: chamar fora da thread principal.
 */
public final class GoogleRoutes {
    private GoogleRoutes() {
    }

    private static final String URL = "https://routes.googleapis.com/directions/v2:computeRoutes";
    private static final String FIELDS = "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,"
        + "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,routes.legs.steps.startLocation,"
        + "routes.legs.steps.navigationInstruction";
    private static final long TRAFFIC_TTL_MS = 5 * 60 * 1000L;
    private static final long STATIC_TTL_MS = 30 * 60 * 1000L;
    private static final int CACHE = 16;

    private static final class Cached {
        final long at;
        final long ttl;
        final List<Route> routes;

        Cached(long at, long ttl, List<Route> routes) {
            this.at = at;
            this.ttl = ttl;
            this.routes = routes;
        }
    }

    private static final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(CACHE, 0.75f, true);

    /**
     * Até {@code max} rotas (a primeira é a melhor do Google), ou null se não há cota (nem com nem sem
     * trânsito) ou o Google falhou: quem chamou segue para o OSRM.
     * @param heading rumo do carro (graus) ou NaN; só vai com trânsito (é um recurso Pro)
     * @param traffic usar trânsito em tempo real se ainda houver cota Pro
     */
    public static List<Route> routes(Context c, double fromLat, double fromLon, double heading,
                                     double toLat, double toLon, String destName, int max, boolean traffic,
                                     String lang) {
        GoogleQuota quota = GoogleQuota.get();
        boolean pro = traffic && quota.has(c, GoogleQuota.Sku.ROUTES_PRO);
        if (!pro && !quota.has(c, GoogleQuota.Sku.ROUTES)) return null;

        String key = String.format(Locale.US, "%.3f,%.3f>%.5f,%.5f|%s|%b|%d", fromLat, fromLon, toLat, toLon,
            Double.isNaN(heading) || !pro ? "-" : String.valueOf(Math.round(heading / 30) % 12), pro, max);
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && now - hit.at < hit.ttl) return rename(hit.routes, destName);
        }
        if (!quota.take(c, pro ? GoogleQuota.Sku.ROUTES_PRO : GoogleQuota.Sku.ROUTES)) return null;
        List<Route> out = new ArrayList<>();
        try {
            JSONObject origin = new JSONObject().put("location", location(fromLat, fromLon,
                pro && !Double.isNaN(heading) ? (int) Math.round((heading + 360) % 360) % 360 : -1));
            JSONObject body = new JSONObject();
            body.put("origin", origin);
            body.put("destination", new JSONObject().put("location", location(toLat, toLon, -1)));
            body.put("travelMode", "DRIVE");
            body.put("routingPreference", pro ? "TRAFFIC_AWARE" : "TRAFFIC_UNAWARE");
            body.put("computeAlternativeRoutes", max > 1);
            body.put("languageCode", lang);
            body.put("units", "METRIC");
            body.put("polylineQuality", "HIGH_QUALITY");
            body.put("polylineEncoding", "ENCODED_POLYLINE");
            JSONObject r = new JSONObject(GoogleHttp.request(c, URL, FIELDS, body.toString()).text());
            JSONArray all = r.optJSONArray("routes");
            for (int i = 0; all != null && i < all.length() && out.size() < max; i++) {
                Route route = parse(all.getJSONObject(i), toLat, toLon, destName);
                if (route != null) out.add(route);
            }
        } catch (Exception e) {
            quota.failed(c, e);
            return null;
        }
        if (out.isEmpty()) return null;
        synchronized (cache) {
            cache.put(key, new Cached(now, pro ? TRAFFIC_TTL_MS : STATIC_TTL_MS, out));
            Iterator<Map.Entry<String, Cached>> it = cache.entrySet().iterator();
            while (cache.size() > CACHE && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return out;
    }

    /** {@code {"latLng": {...}, "heading": h}}; heading &lt; 0 = sem rumo. */
    private static JSONObject location(double lat, double lon, int heading) throws org.json.JSONException {
        JSONObject loc = new JSONObject().put("latLng", new JSONObject().put("latitude", lat).put("longitude", lon));
        if (heading >= 0) loc.put("heading", heading);
        return loc;
    }

    /** As rotas do cache com o nome do destino pedido agora (o mesmo ponto pode ter outro nome). */
    private static List<Route> rename(List<Route> list, String destName) {
        List<Route> out = new ArrayList<>(list.size());
        for (Route r : list) {
            out.add(destName != null && destName.equals(r.destName) ? r
                : new Route(r.lat, r.lon, r.steps, r.distance, r.duration, r.destLat, r.destLon, destName));
        }
        return out;
    }

    private static Route parse(JSONObject r, double toLat, double toLon, String destName) throws Exception {
        JSONObject poly = r.optJSONObject("polyline");
        if (poly == null) return null;
        double[][] pts = Router.decode(poly.optString("encodedPolyline", ""), 1e5);
        double[] lat = pts[0];
        double[] lon = pts[1];
        if (lat.length < 2) return null;

        List<Route.Step> steps = new ArrayList<>();
        JSONArray legs = r.optJSONArray("legs");
        int from = 0;
        for (int l = 0; legs != null && l < legs.length(); l++) {
            JSONArray js = legs.getJSONObject(l).optJSONArray("steps");
            for (int i = 0; js != null && i < js.length(); i++) {
                JSONObject s = js.getJSONObject(i);
                JSONObject start = s.optJSONObject("startLocation");
                JSONObject ll = start == null ? null : start.optJSONObject("latLng");
                int index = ll == null ? from
                    : nearest(lat, lon, ll.optDouble("latitude"), ll.optDouble("longitude"), from);
                from = index;
                JSONObject ni = s.optJSONObject("navigationInstruction");
                String maneuver = ni == null ? "" : ni.optString("maneuver", "");
                String text = ni == null ? "" : firstLine(ni.optString("instructions", ""));
                String[] tm = steps.isEmpty() ? new String[]{"depart", null} : osrm(maneuver);
                steps.add(new Route.Step(tm[0], tm[1], 0, "", "", s.optDouble("distanceMeters", 0),
                    seconds(s.optString("staticDuration", "0s")), index, text.isEmpty() ? null : text));
            }
        }
        steps.add(new Route.Step("arrive", null, 0, "", "", 0, 0, lat.length - 1));
        return new Route(lat, lon, steps, r.optDouble("distanceMeters", 0), seconds(r.optString("duration", "0s")),
            toLat, toLon, destName);
    }

    /** Manobra do Google → {tipo, modificador} do OSRM (o que {@link com.k4811.map.route.Phrases} entende). */
    static String[] osrm(String m) {
        switch (m) {
            case "DEPART":
                return new String[]{"depart", null};
            case "TURN_SLIGHT_LEFT":
                return new String[]{"turn", "slight left"};
            case "TURN_SHARP_LEFT":
                return new String[]{"turn", "sharp left"};
            case "TURN_LEFT":
                return new String[]{"turn", "left"};
            case "TURN_SLIGHT_RIGHT":
                return new String[]{"turn", "slight right"};
            case "TURN_SHARP_RIGHT":
                return new String[]{"turn", "sharp right"};
            case "TURN_RIGHT":
                return new String[]{"turn", "right"};
            case "UTURN_LEFT":
            case "UTURN_RIGHT":
                return new String[]{"turn", "uturn"};
            case "RAMP_LEFT":
                return new String[]{"on ramp", "slight left"};
            case "RAMP_RIGHT":
                return new String[]{"on ramp", "slight right"};
            case "FORK_LEFT":
                return new String[]{"fork", "slight left"};
            case "FORK_RIGHT":
                return new String[]{"fork", "slight right"};
            case "MERGE":
                return new String[]{"merge", "straight"};
            case "ROUNDABOUT_LEFT":
                return new String[]{"roundabout", "left"};
            case "ROUNDABOUT_RIGHT":
                return new String[]{"roundabout", "right"};
            case "NAME_CHANGE":
                return new String[]{"new name", "straight"};
            case "FERRY":
            case "FERRY_TRAIN":
                return new String[]{"notification", "straight"};
            default: // STRAIGHT, MANEUVER_UNSPECIFIED
                return new String[]{"continue", "straight"};
        }
    }

    /** "123s" ou "123.4s" → 123.4. */
    static double seconds(String d) {
        if (d == null || d.isEmpty()) return 0;
        String v = d.endsWith("s") ? d.substring(0, d.length() - 1) : d;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i >= 0 ? s.substring(0, i) : s).trim();
    }

    private static int nearest(double[] lat, double[] lon, double la, double lo, int from) {
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int i = from; i < lat.length; i++) {
            double d = Geo.distance(lat[i], lon[i], la, lo);
            if (d < bestD) {
                bestD = d;
                best = i;
                if (d < 0.5) break;
            }
        }
        return best;
    }
}
