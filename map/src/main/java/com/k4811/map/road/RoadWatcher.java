package com.k4811.map.road;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.k4811.map.geo.Geo;
import com.k4811.map.route.Route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Limite de velocidade da via e próximo alerta (radar, lombada, pedágio, passagem de nível), a
 * partir dos dados baixados por {@link RoadSync}.
 * <ul>
 * <li><b>Limite:</b> a via mais perto do carro (até {@value #MATCH_M} m) no sentido em que ele anda;
 * respeita mão única. Parado, mantém o último.</li>
 * <li><b>Navegando:</b> os alertas em cima da rota (a menos de 15–35 m da linha e, se o ponto tem
 * sentido, no sentido da rota), pela distância ao longo dela.</li>
 * <li><b>Sem rota:</b> os pontos à frente do carro, num cone em volta da direção em que ele anda.</li>
 * </ul>
 * As contas rodam numa thread própria; o resultado chega na thread principal a cada posição.
 */
public final class RoadWatcher {

    public interface Listener {
        void onRoadState(State s);
    }

    /** Ponto de alerta. */
    public static final class Alert {
        public final int type;
        /** Limite do radar em km/h; 0 = desconhecido. */
        public final int speed;
        final double lat;
        final double lon;
        final int dir;
        final long key;
        /** Distância ao longo da rota (só nos alertas da rota). */
        double along;

        Alert(RoadCell c, int p) {
            type = c.pointType[p];
            speed = c.pointSpeed[p];
            lat = c.pointLat[p] / 1e6;
            lon = c.pointLon[p] / 1e6;
            dir = c.pointDir[p];
            key = ((long) c.pointLat[p] << 32) ^ (c.pointLon[p] & 0xffffffffL);
        }

        public boolean isCamera() {
            return type == RoadCell.SPEED_CAMERA || type == RoadCell.RED_LIGHT_CAMERA;
        }
    }

    public static final class State {
        /** Limite da via em km/h; 0 = desconhecido (ou desligado). */
        public final int limit;
        /** Próximo alerta perto o bastante para mostrar; null se nenhum. */
        public final Alert alert;
        public final double distance;
        /** Radar para anunciar por voz agora (uma vez por radar). */
        public final Alert announce;
        /** Acima do limite chegando num radar (avisar uma vez por radar). */
        public final boolean slowDown;

        State(int limit, Alert alert, double distance, Alert announce, boolean slowDown) {
            this.limit = limit;
            this.alert = alert;
            this.distance = distance;
            this.announce = announce;
            this.slowDown = slowDown;
        }
    }

    private static final String TAG = "K4811Road";
    private static final double MATCH_M = 25;
    private static final double ROUTE_GRID_DEG = 0.005;
    /** Os alertas da rota são preparados só para os próximos metros (rota longa = poucos quadrados por vez). */
    private static final double ROUTE_WINDOW_M = 20000;
    /** Quadrados novos baixados durante a viagem: refaz os alertas no máximo com essa frequência. */
    private static final long ROUTE_REFRESH_MS = 30000;
    /** Um ponto anunciado não é anunciado de novo antes disso (passar de novo no mesmo lugar). */
    private static final long ANNOUNCE_AGAIN_MS = 10 * 60 * 1000;
    private static final int OVER_KMH = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread;
    private final Handler work;
    private final Listener listener;
    private final RoadStore store = RoadStore.get();

    private volatile boolean cameras = true;
    private volatile boolean limits = true;
    private volatile boolean others = true;

    // Última posição pedida (thread principal → worker).
    private final Object lock = new Object();
    private boolean pending;
    private double fixLat, fixLon, fixTraveled;
    private float fixHeading, fixMps;
    private Route pendingRoute;
    private boolean routeChanged;

    // Só no worker.
    private Route route;
    private List<Alert> routeAlerts;
    private int routeGeneration = -1;
    private double routeWindowEnd;
    private long routePreparedAt;
    private RoadCell wayCell;
    private int way = -1;
    private int limit;
    private final int[] near = new int[512];
    private final Map<Long, Long> announced = new HashMap<>();
    private final Map<Long, Long> warned = new HashMap<>();
    /** Alerta na tela (sem rota): fica até o carro passar, mesmo com o rumo oscilando. */
    private long shownKey;
    private double lastAnnounceLat = Double.NaN;
    private double lastAnnounceLon = Double.NaN;
    private long lastAnnounceTime;

    public RoadWatcher(Listener listener) {
        this.listener = listener;
        thread = new HandlerThread("K4811Road", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        work = new Handler(thread.getLooper());
    }

    public void setOptions(boolean cameras, boolean limits, boolean others) {
        this.cameras = cameras;
        this.limits = limits;
        this.others = others;
    }

    /** Rota da navegação (null = sem rota). Os alertas dela são preparados no worker. */
    public void setRoute(Route r) {
        synchronized (lock) {
            pendingRoute = r;
            routeChanged = true;
        }
        work.post(this::compute);
    }

    /**
     * Nova posição. {@code heading} NaN = parado; {@code traveled} = metros percorridos na rota
     * (NaN fora dela).
     */
    public void update(double lat, double lon, float heading, float mps, double traveled) {
        synchronized (lock) {
            fixLat = lat;
            fixLon = lon;
            fixHeading = heading;
            fixMps = mps;
            fixTraveled = traveled;
            if (pending) return;
            pending = true;
        }
        work.post(this::compute);
    }

    /** Encerra a thread; {@code trim} também devolve a memória dos quadrados lidos. */
    public void quit(boolean trim) {
        work.removeCallbacksAndMessages(null);
        work.post(() -> {
            if (trim) store.trimMemory();
            Looper l = Looper.myLooper();
            if (l != null) l.quit();
        });
    }

    // ------------------------------------------------------------------ worker

    private void compute() {
        double lat, lon, traveled;
        float heading, mps;
        boolean hasFix;
        synchronized (lock) {
            if (routeChanged) {
                route = pendingRoute;
                routeChanged = false;
                routeAlerts = null;
                routeGeneration = -1;
            }
            hasFix = pending;
            pending = false;
            lat = fixLat;
            lon = fixLon;
            heading = fixHeading;
            mps = fixMps;
            traveled = fixTraveled;
        }
        if (route != null) {
            long t = System.currentTimeMillis();
            double pos = Double.isNaN(traveled) ? 0 : traveled;
            double total = route.along[route.size() - 1];
            boolean newData = routeGeneration != store.generation() && t - routePreparedAt >= ROUTE_REFRESH_MS;
            boolean ahead = !Double.isNaN(traveled) && routeWindowEnd < total && traveled > routeWindowEnd - ROUTE_WINDOW_M / 4;
            if (routeAlerts == null || newData || ahead) prepareRoute(route, pos);
        }
        if (!hasFix) return;

        RoadCell cell = store.cell(RoadCell.cellX(lon), RoadCell.cellY(lat));
        int lim = limits ? matchLimit(cell, lat, lon, heading) : 0;
        double kmh = mps * 3.6;

        Alert alert = null;
        double dist = 0;
        if (cameras || others) {
            if (routeAlerts != null && !Double.isNaN(traveled)) {
                for (Alert a : routeAlerts) {
                    if (a.along < traveled - 5 || !enabled(a.type)) continue;
                    double d = a.along - traveled;
                    if (d <= showDistance(a, mps)) {
                        alert = a;
                        dist = Math.max(0, d);
                    }
                    break;
                }
            } else if (!Float.isNaN(heading)) {
                double[] out = new double[1];
                alert = ahead(lat, lon, heading, mps, out);
                dist = out[0];
            }
        }

        long now = System.currentTimeMillis();
        expire(announced, now);
        expire(warned, now);
        Alert announce = null;
        boolean slowDown = false;
        shownKey = alert == null ? 0 : alert.key;
        if (alert != null && alert.isCamera()) {
            if (!announced.containsKey(alert.key)) {
                announced.put(alert.key, now);
                // Dois radares juntos (um por faixa ou por pista) contam como um só aviso.
                boolean twin = now - lastAnnounceTime < 60000 && !Double.isNaN(lastAnnounceLat)
                    && Geo.distance(alert.lat, alert.lon, lastAnnounceLat, lastAnnounceLon) < 150;
                if (!twin) announce = alert;
                lastAnnounceLat = alert.lat;
                lastAnnounceLon = alert.lon;
                lastAnnounceTime = now;
            }
            int camLimit = alert.speed > 0 ? alert.speed : lim;
            if (camLimit > 0 && kmh > camLimit + OVER_KMH && dist < 300 && !warned.containsKey(alert.key)) {
                warned.put(alert.key, now);
                slowDown = true;
            }
            if (announce != null || slowDown) {
                Log.i(TAG, (announce != null ? "radar a " : "acima do limite, radar a ") + Math.round(dist)
                    + " m, limite " + camLimit + ", " + Math.round(kmh) + " km/h");
            }
        }
        State s = new State(lim, alert, dist, announce, slowDown);
        main.post(() -> listener.onRoadState(s));
    }

    private boolean enabled(int type) {
        return type == RoadCell.SPEED_CAMERA || type == RoadCell.RED_LIGHT_CAMERA ? cameras : others;
    }

    /** A partir de quantos metros o alerta aparece: radar ~20 s antes, os outros ~12 s. */
    private static double showDistance(Alert a, float mps) {
        if (a.isCamera()) return Math.max(300, Math.min(800, mps * 20));
        return Math.max(150, Math.min(400, mps * 12));
    }

    private static void expire(Map<Long, Long> m, long now) {
        Iterator<Map.Entry<Long, Long>> it = m.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue() > ANNOUNCE_AGAIN_MS) it.remove();
    }

    // ------------------------------------------------------------------ limite da via

    private int matchLimit(RoadCell cell, double lat, double lon, float heading) {
        if (cell == null) {
            way = -1;
            wayCell = null;
            limit = 0;
            return 0;
        }
        // Parado: sem direção não dá para escolher a via; fica com a última.
        if (Float.isNaN(heading)) return wayCell == cell ? limit : 0;
        int la = (int) Math.round(lat * 1e6);
        int lo = (int) Math.round(lon * 1e6);
        double kx = 0.11132 * Math.cos(Math.toRadians(lat));
        double ky = 0.110574;
        int n = cell.waysNear(la, lo, near);
        int best = -1;
        boolean bestFwd = true;
        double bestScore = Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            int w = near[k];
            int end = cell.wayStart[w + 1] - 1;
            for (int i = cell.wayStart[w]; i < end; i++) {
                double ax = (cell.lon[i] - lo) * kx, ay = (cell.lat[i] - la) * ky;
                double bx = (cell.lon[i + 1] - lo) * kx, by = (cell.lat[i + 1] - la) * ky;
                double t = Geo.project(0, 0, ax, ay, bx, by);
                double d = Math.hypot(ax + (bx - ax) * t, ay + (by - ay) * t);
                if (d > MATCH_M) continue;
                double seg = Math.toDegrees(Math.atan2(bx - ax, by - ay));
                double diff = Math.abs(Geo.angleDiff(heading, seg));
                boolean fwd;
                double off;
                int oneway = cell.wayOneway[w];
                if (oneway == 1) {
                    fwd = true;
                    off = diff;
                } else if (oneway == 2) {
                    fwd = false;
                    off = 180 - diff;
                } else {
                    fwd = diff <= 90;
                    off = fwd ? diff : 180 - diff;
                }
                if (off > 60) continue;
                double score = d + off * 0.3 - (cell == wayCell && w == way ? 8 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = w;
                    bestFwd = fwd;
                }
            }
        }
        wayCell = cell;
        way = best;
        limit = best < 0 ? 0 : bestFwd ? cell.waySpeedFwd[best] : cell.waySpeedBwd[best];
        return limit;
    }

    // ------------------------------------------------------------------ alertas sem rota

    /** O ponto mais perto à frente do carro, dentro do cone; null se nenhum. */
    private Alert ahead(double lat, double lon, float heading, float mps, double[] distOut) {
        double kx = 0.11132 * Math.cos(Math.toRadians(lat));
        double ky = 0.110574;
        int la = (int) Math.round(lat * 1e6);
        int lo = (int) Math.round(lon * 1e6);
        double h = Math.toRadians(heading);
        double hx = Math.sin(h), hy = Math.cos(h);
        RoadCell bestCell = null;
        int bestPoint = -1;
        double bestAhead = Double.MAX_VALUE;
        // O quadrado do carro e os vizinhos quando ele está a menos de ~1 km da borda.
        double m = 0.01;
        int x0 = RoadCell.cellX(lon - m), x1 = RoadCell.cellX(lon + m);
        int y0 = RoadCell.cellY(lat - m), y1 = RoadCell.cellY(lat + m);
        for (int cy = y0; cy <= y1; cy++) {
            for (int cx = x0; cx <= x1; cx++) {
                RoadCell c = store.cell(cx, cy);
                if (c == null) continue;
                for (int p = 0; p < c.points; p++) {
                    int type = c.pointType[p];
                    if (!enabled(type)) continue;
                    double dx = (c.pointLon[p] - lo) * kx, dy = (c.pointLat[p] - la) * ky;
                    double along = dx * hx + dy * hy;
                    if (along <= 0 || along > 800) continue;
                    boolean camera = type == RoadCell.SPEED_CAMERA || type == RoadCell.RED_LIGHT_CAMERA;
                    boolean shown = shownKey != 0
                        && shownKey == (((long) c.pointLat[p] << 32) ^ (c.pointLon[p] & 0xffffffffL));
                    double maxShow = camera ? Math.max(300, Math.min(800, mps * 20)) : Math.max(150, Math.min(400, mps * 12));
                    if (along > maxShow && !shown) continue;
                    double lateral = Math.abs(dx * hy - dy * hx);
                    // Longe, o rumo do GPS erra alguns graus: o cone abre com a distância. O alerta
                    // que já está na tela tem folga maior, para não piscar.
                    double maxLateral = Math.max(camera ? 30 : 12, along * 0.12) * (shown ? 1.6 : 1);
                    if (lateral > maxLateral) continue;
                    int dir = c.pointDir[p];
                    if (dir >= 0 && Math.abs(Geo.angleDiff(heading, dir)) > 60) continue;
                    if (along < bestAhead) {
                        bestAhead = along;
                        bestCell = c;
                        bestPoint = p;
                    }
                }
            }
        }
        if (bestCell == null) return null;
        distOut[0] = bestAhead;
        return new Alert(bestCell, bestPoint);
    }

    // ------------------------------------------------------------------ alertas da rota

    /** Pontos em cima da rota, dos próximos {@link #ROUTE_WINDOW_M} metros, pela distância ao longo dela. */
    private void prepareRoute(Route r, double from) {
        routeGeneration = store.generation();
        routePreparedAt = System.currentTimeMillis();
        double start = Math.max(0, from - 200);
        double end = from + ROUTE_WINDOW_M;
        routeWindowEnd = end;
        int n = r.size();
        // Grade dos trechos da rota, para cada ponto olhar só os trechos perto dele.
        HashMap<Long, List<Integer>> grid = new HashMap<>();
        double g = ROUTE_GRID_DEG;
        for (int i = 0; i < n - 1; i++) {
            if (r.along[i + 1] < start || r.along[i] > end) continue;
            int x0 = (int) Math.floor((Math.min(r.lon[i], r.lon[i + 1]) - 0.0005) / g);
            int x1 = (int) Math.floor((Math.max(r.lon[i], r.lon[i + 1]) + 0.0005) / g);
            int y0 = (int) Math.floor((Math.min(r.lat[i], r.lat[i + 1]) - 0.0005) / g);
            int y1 = (int) Math.floor((Math.max(r.lat[i], r.lat[i + 1]) + 0.0005) / g);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    long k = ((long) x << 32) ^ (y & 0xffffffffL);
                    List<Integer> l = grid.get(k);
                    if (l == null) grid.put(k, l = new ArrayList<>(4));
                    l.add(i);
                }
            }
        }
        List<Alert> out = new ArrayList<>();
        for (String name : RoadSync.cellsAlong(r, start, end)) {
            int[] xy = RoadSync.xy(name);
            RoadCell c = store.cell(xy[0], xy[1]);
            if (c == null) continue;
            for (int p = 0; p < c.points; p++) {
                double plat = c.pointLat[p] / 1e6, plon = c.pointLon[p] / 1e6;
                long k = ((long) (int) Math.floor(plon / g) << 32) ^ ((int) Math.floor(plat / g) & 0xffffffffL);
                List<Integer> segs = grid.get(k);
                if (segs == null) continue;
                int type = c.pointType[p];
                double max = type == RoadCell.SPEED_CAMERA || type == RoadCell.RED_LIGHT_CAMERA ? 35 : 15;
                double kx = 111320 * Math.cos(Math.toRadians(plat)), ky = 110574;
                double bestD = Double.MAX_VALUE, bestAlong = 0;
                int bestSeg = -1;
                for (int i : segs) {
                    double ax = (r.lon[i] - plon) * kx, ay = (r.lat[i] - plat) * ky;
                    double bx = (r.lon[i + 1] - plon) * kx, by = (r.lat[i + 1] - plat) * ky;
                    double t = Geo.project(0, 0, ax, ay, bx, by);
                    double d = Math.hypot(ax + (bx - ax) * t, ay + (by - ay) * t);
                    if (d < bestD) {
                        bestD = d;
                        bestSeg = i;
                        bestAlong = r.along[i] + (r.along[i + 1] - r.along[i]) * t;
                    }
                }
                if (bestSeg < 0 || bestD > max) continue;
                int dir = c.pointDir[p];
                if (dir >= 0) {
                    double seg = Geo.bearing(r.lat[bestSeg], r.lon[bestSeg], r.lat[bestSeg + 1], r.lon[bestSeg + 1]);
                    if (Math.abs(Geo.angleDiff(seg, dir)) > 60) continue;
                }
                Alert a = new Alert(c, p);
                a.along = bestAlong;
                out.add(a);
            }
        }
        Collections.sort(out, (a, b) -> Double.compare(a.along, b.along));
        routeAlerts = out;
    }
}
