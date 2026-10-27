package com.k4811.map.route;

import com.k4811.map.geo.Geo;

/**
 * Progresso numa {@link Route}: onde o carro está na linha, a próxima manobra, quanto falta e
 * quando avisar por voz. Recebe uma posição por vez ({@link #update}); tudo na thread principal.
 */
public final class Navigator {

    public interface Listener {
        /** Avisar a manobra {@code step}: a {@code meters} dela, ou agora (meters &lt; 0). */
        void onAnnounce(Route.Step step, int meters);

        /** O carro saiu da rota (algumas posições seguidas longe dela): hora de recalcular. */
        void onOffRoute();

        void onArrived();
    }

    /** Distância da linha que conta como fora da rota (somada à imprecisão do GPS). */
    private static final double OFF_ROUTE_M = 40;
    private static final int OFF_ROUTE_FIXES = 3;
    private static final double ARRIVE_M = 25;

    private final Route route;
    private final Listener listener;
    private int segment;
    private double traveled;
    private double offRoute;
    private int offCount;
    private boolean offReported;
    private boolean arrived;
    private int next = 1;
    /** Avisos já feitos para a manobra {@link #next}: 0 nenhum, 1 de longe, 2 perto, 3 agora. */
    private int announced;
    /** Posição do carro grudada na linha (para desenhar a seta em cima da rua). */
    private double snappedLat = Double.NaN;
    private double snappedLon = Double.NaN;

    public Navigator(Route route, Listener listener) {
        this.route = route;
        this.listener = listener;
    }

    public Route route() {
        return route;
    }

    /** Próxima manobra (null depois da chegada). */
    public Route.Step nextStep() {
        return next < route.steps.size() ? route.steps.get(next) : null;
    }

    /** A manobra depois da próxima, para "e depois…" (null se não houver). */
    public Route.Step stepAfterNext() {
        return next + 1 < route.steps.size() ? route.steps.get(next + 1) : null;
    }

    public double distanceToNext() {
        Route.Step s = nextStep();
        return s == null ? 0 : Math.max(0, route.along[s.index] - traveled);
    }

    public double remainingDistance() {
        return Math.max(0, route.along[route.size() - 1] - traveled);
    }

    /** Tempo restante: o da rota, proporcional ao que falta. */
    public double remainingTime() {
        double total = route.along[route.size() - 1];
        return total <= 0 ? 0 : route.duration * remainingDistance() / total;
    }

    /** Índice do ponto onde o carro está (a linha antes dele já foi percorrida). */
    public int segment() {
        return segment;
    }

    public double traveled() {
        return traveled;
    }

    public double snappedLat() {
        return snappedLat;
    }

    public double snappedLon() {
        return snappedLon;
    }

    /**
     * Rumo da rua da rota onde o carro está (graus), para a seta e o giro do mapa: mais estável que o
     * do GPS. NaN antes da primeira posição.
     */
    public float routeBearing() {
        if (Double.isNaN(snappedLat)) return Float.NaN;
        int n = route.size();
        int i = Math.min(segment, n - 2);
        // Um trecho curto demais (pontos colados) não dá um rumo bom: usa o próximo.
        int j = i + 1;
        while (j < n - 1 && route.along[j] - route.along[i] < 5) j++;
        return (float) Geo.bearing(route.lat[i], route.lon[i], route.lat[j], route.lon[j]);
    }

    public boolean onRoute() {
        return offCount == 0;
    }

    public boolean arrived() {
        return arrived;
    }

    /** Primeiro aviso, ao começar ("Siga pela Rua X"). */
    public void start() {
        if (route.steps.isEmpty()) return;
        listener.onAnnounce(route.steps.get(0), -1);
        Route.Step s = nextStep();
        if (s != null && distanceToNext() < 250) announced = 2;
    }

    /**
     * Nova posição do GPS.
     * @param speed velocidade em m/s (0 se não souber)
     * @param accuracy precisão em metros (0 se não souber)
     */
    public void update(double lat, double lon, double speed, double accuracy) {
        if (arrived) return;
        locate(lat, lon);
        double tolerance = OFF_ROUTE_M + Math.min(60, accuracy);
        if (offRoute > tolerance) {
            offCount++;
            if (offCount >= OFF_ROUTE_FIXES && !offReported) {
                offReported = true;
                listener.onOffRoute();
            }
            return;
        }
        offCount = 0;

        if (remainingDistance() < ARRIVE_M
            || Geo.distance(lat, lon, route.lat[route.size() - 1], route.lon[route.size() - 1]) < ARRIVE_M) {
            arrived = true;
            listener.onArrived();
            return;
        }
        // Passou da manobra: a próxima é a seguinte.
        while (next < route.steps.size() && route.along[route.steps.get(next).index] <= traveled + 3) {
            next++;
            announced = 0;
        }
        announce(speed);
    }

    /** Avisos por voz: de longe (estrada), perto e na hora, conforme a velocidade. */
    private void announce(double speed) {
        Route.Step s = nextStep();
        if (s == null) return;
        double d = distanceToNext();
        double now = Math.max(35, speed * 3.5);
        double near = Math.max(180, speed * 12);
        double far = speed > 20 ? 1500 : speed > 14 ? 800 : 0;
        if (d <= now) {
            if (announced < 3) {
                announced = 3;
                listener.onAnnounce(s, -1);
            }
        } else if (d <= near) {
            if (announced < 2) {
                announced = 2;
                listener.onAnnounce(s, roundForVoice(d));
            }
        } else if (far > 0 && d <= far && d > near * 1.5) {
            if (announced < 1) {
                announced = 1;
                listener.onAnnounce(s, roundForVoice(d));
            }
        }
    }

    private static int roundForVoice(double m) {
        if (m >= 1000) return (int) (Math.round(m / 100.0) * 100);
        if (m >= 200) return (int) (Math.round(m / 50.0) * 50);
        return (int) (Math.round(m / 10.0) * 10);
    }

    /** Projeta a posição na linha, procurando perto do último ponto conhecido (o carro anda para frente). */
    private void locate(double lat, double lon) {
        int n = route.size();
        int from = Math.max(0, segment - 3);
        int to = Math.min(n - 1, segment + 60);
        // O Mercator é conforme: perto do carro, x e y têm a mesma escala em metros.
        double mx = Geo.metersPerWorld(lat);
        double my = mx;
        double px = Geo.worldX(lon) * mx;
        double py = Geo.worldY(lat) * my;
        double best = Double.MAX_VALUE;
        int bestSeg = segment;
        double bestT = 0;
        for (int i = from; i < to; i++) {
            double ax = route.wx[i] * mx;
            double ay = route.wy[i] * my;
            double bx = route.wx[i + 1] * mx;
            double by = route.wy[i + 1] * my;
            double t = Geo.project(px, py, ax, ay, bx, by);
            double qx = ax + (bx - ax) * t;
            double qy = ay + (by - ay) * t;
            double d = Math.hypot(px - qx, py - qy);
            if (d < best) {
                best = d;
                bestSeg = i;
                bestT = t;
            }
        }
        // Não volta para trás por ruído do GPS (só se estiver claramente mais perto).
        double candidate = route.along[bestSeg] + (route.along[Math.min(n - 1, bestSeg + 1)] - route.along[bestSeg]) * bestT;
        if (candidate >= traveled - 15 || best < offRoute - 10) {
            segment = bestSeg;
            traveled = candidate;
            int j = Math.min(n - 1, bestSeg + 1);
            snappedLat = route.lat[bestSeg] + (route.lat[j] - route.lat[bestSeg]) * bestT;
            snappedLon = route.lon[bestSeg] + (route.lon[j] - route.lon[bestSeg]) * bestT;
        }
        offRoute = best;
    }
}
