package com.k4811.map.route;

import android.content.Context;

import com.k4811.map.MapConfig;
import com.k4811.map.geo.Geo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rota de carro pelo serviço OSRM ({@link MapConfig#route}): a linha (polyline6) e as manobras.
 * Bloqueia: chamar fora da thread principal.
 */
public final class Router {
    private Router() {
    }

    /**
     * @param heading rumo atual do carro (graus) ou NaN; evita rotas que começam com retorno
     * @return a rota, ou lança exceção (sem internet, sem rota, servidor fora)
     */
    public static Route route(Context c, double fromLat, double fromLon, double heading,
                              double toLat, double toLon, String destName) throws Exception {
        return routes(c, fromLat, fromLon, heading, toLat, toLon, destName, 1).get(0);
    }

    /** A rota e, se o servidor achar, até {@code max - 1} alternativas (a primeira é a dele, a melhor). */
    public static List<Route> routes(Context c, double fromLat, double fromLon, double heading,
                                     double toLat, double toLon, String destName, int max) throws Exception {
        MapConfig cfg = MapConfig.get(c);
        StringBuilder url = new StringBuilder(cfg.route);
        url.append(String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat));
        url.append("?overview=full&geometries=polyline6&steps=true&alternatives=")
            .append(max > 1 ? String.valueOf(max - 1) : "false");
        if (!Double.isNaN(heading)) {
            url.append(String.format(Locale.US, "&bearings=%d,60;", Math.round((heading + 360) % 360)));
        }
        JSONObject json = new JSONObject(Net.get(url.toString(), cfg.userAgent));
        if (!"Ok".equals(json.optString("code"))) throw new java.io.IOException(json.optString("code"));
        JSONArray all = json.getJSONArray("routes");
        List<Route> out = new ArrayList<>();
        for (int k = 0; k < all.length() && out.size() < max; k++) {
            Route r = parse(all.getJSONObject(k), toLat, toLon, destName);
            if (r != null) out.add(r);
        }
        if (out.isEmpty()) throw new java.io.IOException("empty route");
        return out;
    }

    private static Route parse(JSONObject r, double toLat, double toLon, String destName) throws Exception {
        double[][] pts = decode(r.getString("geometry"));
        double[] lat = pts[0];
        double[] lon = pts[1];
        if (lat.length < 2) return null;

        List<Route.Step> steps = new ArrayList<>();
        JSONArray legs = r.getJSONArray("legs");
        int from = 0;
        for (int l = 0; l < legs.length(); l++) {
            JSONArray js = legs.getJSONObject(l).getJSONArray("steps");
            for (int i = 0; i < js.length(); i++) {
                JSONObject s = js.getJSONObject(i);
                JSONObject m = s.getJSONObject("maneuver");
                JSONArray loc = m.getJSONArray("location");
                int index = nearest(lat, lon, loc.getDouble(1), loc.getDouble(0), from);
                from = index;
                steps.add(new Route.Step(
                    m.optString("type", "turn"),
                    m.has("modifier") ? m.optString("modifier") : null,
                    m.optInt("exit", 0),
                    s.optString("name", ""),
                    s.optString("ref", ""),
                    s.optDouble("distance", 0),
                    s.optDouble("duration", 0),
                    index));
            }
        }
        return new Route(lat, lon, steps, r.optDouble("distance", 0), r.optDouble("duration", 0),
            toLat, toLon, destName);
    }

    /** Ponto da linha mais próximo, procurando de {@code from} em diante (as manobras vêm em ordem). */
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

    /** Polyline com precisão de 6 casas (formato do Google, usado pelo OSRM). */
    static double[][] decode(String s) {
        return decode(s, 1e6);
    }

    /** Polyline codificada do Google com a precisão dada (1e5 na Routes API, 1e6 no OSRM). */
    public static double[][] decode(String s, double factor) {
        int n = s.length();
        double[] la = new double[n / 2 + 1];
        double[] lo = new double[n / 2 + 1];
        int count = 0;
        int i = 0;
        long lat = 0;
        long lon = 0;
        while (i < n) {
            long[] r = next(s, i);
            lat += r[0];
            i = (int) r[1];
            r = next(s, i);
            lon += r[0];
            i = (int) r[1];
            la[count] = lat / factor;
            lo[count] = lon / factor;
            count++;
        }
        double[] outLa = new double[count];
        double[] outLo = new double[count];
        System.arraycopy(la, 0, outLa, 0, count);
        System.arraycopy(lo, 0, outLo, 0, count);
        return new double[][]{outLa, outLo};
    }

    private static long[] next(String s, int i) {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = s.charAt(i++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        long value = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new long[]{value, i};
    }
}
