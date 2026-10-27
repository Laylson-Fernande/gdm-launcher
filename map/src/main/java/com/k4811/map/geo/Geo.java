package com.k4811.map.geo;

/**
 * Contas do mapa. Posições em "mundo": Web Mercator normalizado, x e y de 0 a 1 (0,0 no canto
 * noroeste), o mesmo sistema dos tiles do OpenStreetMap: no zoom z o mundo tem 256·2^z pixels.
 */
public final class Geo {
    private Geo() {
    }

    public static final double EARTH_RADIUS_M = 6371008.8;
    /** Limite de latitude do Web Mercator. */
    private static final double MAX_LAT = 85.05112878;

    public static double worldX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    public static double worldY(double lat) {
        double l = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        double s = Math.sin(Math.toRadians(l));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    public static double lon(double worldX) {
        return worldX * 360.0 - 180.0;
    }

    public static double lat(double worldY) {
        double n = Math.PI - 2.0 * Math.PI * worldY;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Distância em metros (haversine). */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Rumo de 1 para 2, em graus (0 = norte, sentido horário). */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Metros por unidade de "mundo" na latitude {@code lat}. */
    public static double metersPerWorld(double lat) {
        return 2 * Math.PI * EARTH_RADIUS_M * Math.cos(Math.toRadians(lat));
    }

    /** Diferença entre dois rumos, de -180 a 180. */
    public static double angleDiff(double from, double to) {
        double d = (to - from) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /**
     * Ponto de um segmento (a→b, em metros num plano local) mais próximo de p: devolve a fração
     * 0..1 ao longo do segmento. Os parâmetros são x/y em metros.
     */
    public static double project(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 <= 0) return 0;
        double t = ((px - ax) * dx + (py - ay) * dy) / len2;
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }
}
