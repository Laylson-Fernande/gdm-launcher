package com.k4811.map.route;

import android.util.Log;

import com.k4811.map.geo.Geo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rota calculada no aparelho, sem internet, sobre o grafo de ruas baixado ({@link GraphStore}): A* pelo
 * tempo (comprimento ÷ velocidade da via, mais um custo pelas conversões), respeitando mão única e as
 * proibições de conversão do OpenStreetMap. As manobras saem do ângulo e da troca de nome em cada
 * cruzamento, no formato das do OSRM ({@link Route.Step}), e o resto (voz, faixa, recálculo) não muda.
 *
 * Só onde o grafo foi baixado (raio de 100 km do carro e as rotas já calculadas com internet). Rotas
 * longas usam as ruas pequenas (residenciais, de serviço) só perto da partida e do destino, para o
 * cálculo caber na central. Bloqueia: chamar fora da thread principal.
 *
 * <b>Rotas preferidas:</b> os trechos do perfil ({@link RouteHistory}) custam até 25% menos, então a rota
 * de costume ganha de uma pouco mais rápida. <b>Alternativas</b> ({@link #routes}): a melhor rota e, de
 * novo, com os trechos já achados 60% mais caros (e mais, a cada tentativa que repete um caminho); fica
 * a que for diferente (menos de 75% em comum) e não demorar mais que 40% além da primeira. Sem o perfil, a mais rápida; com ele, a mais rápida também
 * entra, se for diferente da de costume.
 */
public final class OfflineRouter {
    private OfflineRouter() {
    }

    private static final String TAG = "K4811Route";

    /** Sem dados do grafo onde a rota começa ou termina. */
    public static final class NoData extends IOException {
        NoData(String what) {
            super(what);
        }
    }

    // Velocidade (km/h) por classe, sem limite conhecido: 1 motorway … 9 service.
    private static final int[] SPEED = {30, 100, 80, 65, 55, 45, 35, 25, 10, 12};
    private static final int LINK_SPEED = 45;
    /** Velocidade da estimativa (A*): pouco acima da de quase todas as vias. */
    private static final double H_MPS = 100 / 3.6;
    /** Ruas pequenas só até essa distância (m) da partida ou do destino. */
    private static final double LOCAL_M = 6000;
    private static final double SNAP_M = 300;
    private static final int MAX_SETTLED = 1500000;
    private static final int MAX_SLOTS = 500;
    private static final int SLOT_SHIFT = 22;
    private static final int IDX_MASK = (1 << SLOT_SHIFT) - 1;

    /**
     * @param heading rumo atual do carro (graus) ou NaN; evita começar no sentido contrário
     * @throws NoData sem o grafo baixado na partida ou no destino
     * @throws IOException sem caminho entre os dois (ou grande demais)
     */
    public static Route route(double fromLat, double fromLon, double heading, double toLat, double toLon,
                              String destName) throws IOException {
        return routes(fromLat, fromLon, heading, toLat, toLon, destName, 1).get(0);
    }

    /** Diferença máxima de tempo de uma alternativa para a primeira. */
    private static final double ALT_SLOWER = 1.4;
    /** Parte em comum (pela distância) acima da qual duas rotas são "a mesma". */
    private static final double ALT_OVERLAP = 0.75;
    private static final float ALT_PENALTY = 1.6f;

    /**
     * A melhor rota (com as preferidas) e até {@code max - 1} alternativas, a melhor primeiro.
     * @throws NoData sem o grafo baixado na partida ou no destino
     * @throws IOException sem caminho entre os dois
     */
    public static List<Route> routes(double fromLat, double fromLon, double heading, double toLat, double toLon,
                                     String destName, int max) throws IOException {
        long t0 = System.currentTimeMillis();
        RouteHistory history = RouteHistory.get();
        boolean usual = !history.isEmpty();
        List<Search> found = new ArrayList<>();
        Search best = search(fromLat, fromLon, heading, toLat, toLon, destName, usual ? history : null, null);
        found.add(best);
        if (max > 1) {
            // Sem as preferências: a mais rápida (se não for a mesma).
            if (usual) add(found, search(fromLat, fromLon, heading, toLat, toLon, destName, null, null), best);
            // Os trechos já escolhidos ficam mais caros: aparece outro caminho.
            // Cada tentativa que repete um caminho já achado deixa os trechos dele ainda mais caros.
            Map<Long, Float> penalty = new HashMap<>();
            float p = ALT_PENALTY;
            for (int k = 0; k < 4 && found.size() < max; k++) {
                for (Search f : found) for (long key : f.keys) {
                    Float old = penalty.get(key);
                    if (old == null || old < p) penalty.put(key, p);
                }
                Search alt = search(fromLat, fromLon, heading, toLat, toLon, destName, usual ? history : null, penalty);
                if (!add(found, alt, best)) p *= 1.6f;
            }
        }
        List<Route> out = new ArrayList<>();
        for (Search f : found) if (out.size() < max) out.add(f.result);
        Log.i(TAG, String.format(java.util.Locale.US, "rota offline: %.1f km, %d nós, %d quadrados, %d rotas, %d ms",
            best.result.distance / 1000, best.settled, best.slots, out.size(), System.currentTimeMillis() - t0));
        return out;
    }

    private static Search search(double fromLat, double fromLon, double heading, double toLat, double toLon,
                                 String destName, RouteHistory history, Map<Long, Float> penalty) throws IOException {
        Search s = new Search();
        s.history = history;
        s.penalty = penalty;
        Snap a = s.snap(fromLat, fromLon, heading);
        if (a == null) throw new NoData("partida");
        Snap b = s.snap(toLat, toLon, Double.NaN);
        if (b == null) throw new NoData("destino");
        s.result = s.run(a, b, heading, toLat, toLon, destName);
        return s;
    }

    /** Põe {@code alt} na lista se for outro caminho e não muito mais lento que {@code best}. */
    private static boolean add(List<Search> found, Search alt, Search best) {
        if (alt.result.duration > best.result.duration * ALT_SLOWER) return false;
        for (Search f : found) if (overlap(alt, f) > ALT_OVERLAP) return false;
        found.add(alt);
        return true;
    }

    /** Parte de {@code a} (pela distância) que também está em {@code b}. */
    private static double overlap(Search a, Search b) {
        java.util.Set<Long> other = new java.util.HashSet<>();
        for (long k : b.keys) other.add(k);
        double shared = 0, total = 0;
        for (int i = 0; i < a.keys.length; i++) {
            total += a.lengths[i];
            if (other.contains(a.keys[i])) shared += a.lengths[i];
        }
        return total <= 0 ? 1 : shared / total;
    }

    /** Ponto na rua mais perto: aresta, metros desde A, e o ponto. */
    static final class Snap {
        int slot;
        int edge;
        double along;
        double lat;
        double lon;
        double distance;
    }

    /** Um trecho percorrido: aresta (quadrado, índice) e o sentido. */
    private static final class Leg {
        GraphCell c;
        int slot;
        int e;
        boolean fwd;
        /** Índice do primeiro ponto do trecho na linha da rota. */
        int start;
        double seconds;
        double meters;
    }

    private static final class Search {
        GraphCell[] cells = new GraphCell[16];
        float[][] cost = new float[16][];
        float[][] arrive = new float[16][]; // rumo de chegada em cada nó
        int[][] prevEdge = new int[16][];   // slot << 22 | aresta (no quadrado do nó anterior)
        int[][] prevNode = new int[16][];
        boolean[][] done = new boolean[16][];
        final Map<Long, Integer> slotOf = new HashMap<>();
        final GraphStore store = GraphStore.get();
        int slots;
        int settled;
        // fila de prioridade (heap binário, com repetidos)
        float[] hf = new float[1024];
        int[] hn = new int[1024];
        int hs;
        int startLat, startLon, goalLat, goalLon;
        double cosLat;
        /** Perfil de rotas preferidas (null = sem preferências). */
        RouteHistory history;
        /** Multiplicador do custo por trecho (alternativas), ou null. */
        Map<Long, Float> penalty;
        Route result;
        /** Trechos da rota encontrada e o comprimento de cada um (para comparar rotas). */
        long[] keys = new long[0];
        double[] lengths = new double[0];

        /** Desconto do perfil e penalidade das alternativas no custo da aresta. */
        float factor(GraphCell c, int e) {
            if (history == null && penalty == null) return 1;
            long k = c.key(e);
            float f = 1;
            if (history != null) f *= (float) (1 - history.discount(k));
            if (penalty != null) {
                Float p = penalty.get(k);
                if (p != null) f *= p;
            }
            return f;
        }

        /** Slot do quadrado (lê se preciso); -1 se não foi baixado. */
        int slot(int cx, int cy) {
            long k = GraphStore.key(cx, cy);
            Integer s = slotOf.get(k);
            if (s != null) return s;
            if (slots >= MAX_SLOTS) return -1;
            GraphCell c = store.cell(cx, cy);
            if (c == null) {
                slotOf.put(k, -1);
                return -1;
            }
            if (slots == cells.length) {
                int n = slots * 2;
                cells = Arrays.copyOf(cells, n);
                cost = Arrays.copyOf(cost, n);
                arrive = Arrays.copyOf(arrive, n);
                prevEdge = Arrays.copyOf(prevEdge, n);
                prevNode = Arrays.copyOf(prevNode, n);
                done = Arrays.copyOf(done, n);
            }
            int i = slots++;
            cells[i] = c;
            cost[i] = new float[c.nodes];
            Arrays.fill(cost[i], Float.POSITIVE_INFINITY);
            arrive[i] = new float[c.nodes];
            prevEdge[i] = new int[c.nodes];
            prevNode[i] = new int[c.nodes];
            done[i] = new boolean[c.nodes];
            slotOf.put(k, i);
            return i;
        }

        /** Nó de uma ponta da aresta (A ou B), como slot << 22 | índice; -1 se o quadrado dele falta. */
        int node(int slot, int e, boolean endB) {
            GraphCell c = cells[slot];
            int ref = endB ? c.edgeB[e] : c.edgeA[e];
            if (ref >= 0) return slot << SLOT_SHIFT | ref;
            int lat = c.extLat[-1 - ref], lon = c.extLon[-1 - ref];
            int s = slot(GraphStore.cellX(lon), GraphStore.cellY(lat));
            if (s < 0) return -1;
            int i = cells[s].find(lat, lon);
            return i < 0 ? -1 : s << SLOT_SHIFT | i;
        }

        double meters(int lat1, int lon1, int lat2, int lon2) {
            double dy = (lat2 - lat1) * 0.111195;
            double dx = (lon2 - lon1) * 0.111195 * cosLat;
            return Math.sqrt(dx * dx + dy * dy);
        }

        /** Rua mais perto do ponto (com o rumo, a que vai para o mesmo lado), até {@value #SNAP_M} m. */
        Snap snap(double lat, double lon, double heading) {
            int la = (int) Math.round(lat * 1e6), lo = (int) Math.round(lon * 1e6);
            double k = Math.cos(Math.toRadians(lat));
            cosLat = k;
            Snap best = null;
            double bestScore = Double.MAX_VALUE;
            int cx = GraphStore.cellX(lo), cy = GraphStore.cellY(la);
            // O quadrado do ponto e os vizinhos se o ponto está perto da borda (~3 km).
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx != 0 && Math.abs(lo - ((cx + (dx > 0 ? 1 : 0)) * 250000 - 180000000)) > 30000) continue;
                    if (dy != 0 && Math.abs(la - ((cy + (dy > 0 ? 1 : 0)) * 250000 - 90000000)) > 30000) continue;
                    int s = slot(cx + dx, cy + dy);
                    if (s < 0) continue;
                    GraphCell c = cells[s];
                    for (int e = 0; e < c.edges; e++) {
                        int n = c.points(e);
                        // Caixa da aresta longe demais: pula sem medir os trechos.
                        int minLa = Integer.MAX_VALUE, maxLa = Integer.MIN_VALUE, minLo = Integer.MAX_VALUE, maxLo = Integer.MIN_VALUE;
                        for (int i = 0; i < n; i++) {
                            int pa = c.pointLat(e, i), po = c.pointLon(e, i);
                            if (pa < minLa) minLa = pa;
                            if (pa > maxLa) maxLa = pa;
                            if (po < minLo) minLo = po;
                            if (po > maxLo) maxLo = po;
                        }
                        if (la < minLa - 3000 || la > maxLa + 3000 || lo < minLo - 3000 || lo > maxLo + 3000) continue;
                        double along = 0;
                        for (int i = 1; i < n; i++) {
                            double ax = (c.pointLon(e, i - 1) - lo) * 0.111195 * k, ay = (c.pointLat(e, i - 1) - la) * 0.111195;
                            double bx = (c.pointLon(e, i) - lo) * 0.111195 * k, by = (c.pointLat(e, i) - la) * 0.111195;
                            double t = Geo.project(0, 0, ax, ay, bx, by);
                            double qx = ax + (bx - ax) * t, qy = ay + (by - ay) * t;
                            double d = Math.hypot(qx, qy);
                            double seg = Math.hypot(bx - ax, by - ay);
                            if (d <= SNAP_M) {
                                double score = d;
                                if (!Double.isNaN(heading)) {
                                    double brg = Math.toDegrees(Math.atan2(bx - ax, by - ay));
                                    double diff = Math.abs(Geo.angleDiff(heading, brg));
                                    boolean fwdOk = (c.flags[e] & GraphCell.BWD_ONLY) == 0;
                                    boolean bwdOk = (c.flags[e] & GraphCell.FWD_ONLY) == 0;
                                    boolean aligned = (fwdOk && diff < 60) || (bwdOk && diff > 120);
                                    if (!aligned) score += 15;
                                }
                                if (score < bestScore) {
                                    bestScore = score;
                                    if (best == null) best = new Snap();
                                    best.slot = s;
                                    best.edge = e;
                                    best.along = (along + seg * t) * c.length[e] / Math.max(1e-6, measured(c, e, k));
                                    best.lat = la / 1e6 + qy / 111195.0;
                                    best.lon = lo / 1e6 + qx / (111195.0 * k);
                                    best.distance = d;
                                }
                            }
                            along += seg;
                        }
                    }
                }
            }
            return best;
        }

        double measured(GraphCell c, int e, double k) {
            double d = 0;
            for (int i = 1; i < c.points(e); i++) {
                double dy = (c.pointLat(e, i) - c.pointLat(e, i - 1)) * 0.111195;
                double dx = (c.pointLon(e, i) - c.pointLon(e, i - 1)) * 0.111195 * k;
                d += Math.sqrt(dx * dx + dy * dy);
            }
            return d;
        }

        static double mps(GraphCell c, int e) {
            int cls = c.cls[e] & 15;
            boolean link = (c.cls[e] & 16) != 0;
            int limit = c.speed[e] & 0xff;
            double kmh = link ? LINK_SPEED : SPEED[Math.min(cls, SPEED.length - 1)];
            if (limit > 0) kmh = Math.max(10, Math.min(kmh * 1.4, limit * 0.85));
            return kmh / 3.6;
        }

        static boolean canFwd(GraphCell c, int e) {
            return (c.flags[e] & GraphCell.BWD_ONLY) == 0;
        }

        static boolean canBwd(GraphCell c, int e) {
            return (c.flags[e] & GraphCell.FWD_ONLY) == 0;
        }

        /** Rumo do primeiro trecho (≥ ~5 m) saindo da ponta (A se fwd, B se não). */
        static float leaving(GraphCell c, int e, boolean fwd) {
            int n = c.points(e);
            int i0 = fwd ? 0 : n - 1, step = fwd ? 1 : -1;
            int la = c.pointLat(e, i0), lo = c.pointLon(e, i0);
            int j = i0 + step;
            while (j + step >= 0 && j + step < n && Math.abs(c.pointLat(e, j) - la) + Math.abs(c.pointLon(e, j) - lo) < 45) j += step;
            return (float) Geo.bearing(la / 1e6, lo / 1e6, c.pointLat(e, j) / 1e6, c.pointLon(e, j) / 1e6);
        }

        /** Rumo do último trecho chegando na outra ponta (B se fwd, A se não). */
        static float arriving(GraphCell c, int e, boolean fwd) {
            int n = c.points(e);
            int i1 = fwd ? n - 1 : 0, step = fwd ? -1 : 1;
            int la = c.pointLat(e, i1), lo = c.pointLon(e, i1);
            int j = i1 + step;
            while (j + step >= 0 && j + step < n && Math.abs(c.pointLat(e, j) - la) + Math.abs(c.pointLon(e, j) - lo) < 45) j += step;
            return (float) Geo.bearing(c.pointLat(e, j) / 1e6, c.pointLon(e, j) / 1e6, la / 1e6, lo / 1e6);
        }

        void push(float f, int node) {
            if (hs == hf.length) {
                hf = Arrays.copyOf(hf, hs * 2);
                hn = Arrays.copyOf(hn, hs * 2);
            }
            int i = hs++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (hf[p] <= f) break;
                hf[i] = hf[p];
                hn[i] = hn[p];
                i = p;
            }
            hf[i] = f;
            hn[i] = node;
        }

        int pop() {
            int top = hn[0];
            hs--;
            if (hs > 0) {
                float f = hf[hs];
                int n = hn[hs];
                int i = 0;
                while (true) {
                    int l = 2 * i + 1;
                    if (l >= hs) break;
                    int m = l + 1 < hs && hf[l + 1] < hf[l] ? l + 1 : l;
                    if (hf[m] >= f) break;
                    hf[i] = hf[m];
                    hn[i] = hn[m];
                    i = m;
                }
                hf[i] = f;
                hn[i] = n;
            }
            return top;
        }

        float h(int node) {
            GraphCell c = cells[node >>> SLOT_SHIFT];
            int i = node & IDX_MASK;
            return (float) (meters(c.nodeLat[i], c.nodeLon[i], goalLat, goalLon) / H_MPS);
        }

        /** Custo da conversão (s): à esquerda cruza o fluxo, retorno só se não houver jeito. */
        static float turn(float in, float out) {
            double d = Geo.angleDiff(in, out);
            double a = Math.abs(d);
            if (a > 165) return 90;
            if (a < 30) return 0;
            if (d < 0) return a > 60 ? 8 : 3;
            return a > 60 ? 4 : 1;
        }

        void relax(int node, float g, float brg, int fromEdgeRef, int fromNode) {
            int s = node >>> SLOT_SHIFT, i = node & IDX_MASK;
            if (done[s][i] || g >= cost[s][i]) return;
            cost[s][i] = g;
            arrive[s][i] = brg;
            prevEdge[s][i] = fromEdgeRef;
            prevNode[s][i] = fromNode;
            push(g + h(node), node);
        }

        Route run(Snap a, Snap b, double heading, double toLat, double toLon, String destName) throws IOException {
            startLat = (int) Math.round(a.lat * 1e6);
            startLon = (int) Math.round(a.lon * 1e6);
            goalLat = (int) Math.round(b.lat * 1e6);
            goalLon = (int) Math.round(b.lon * 1e6);
            cosLat = Math.cos(Math.toRadians(a.lat));
            GraphCell ca = cells[a.slot], cb = cells[b.slot];
            double va = mps(ca, a.edge), vb = mps(cb, b.edge);
            float la = ca.length[a.edge], lb = cb.length[b.edge];

            // Começo: pelos dois lados da rua da partida (o contrário ao rumo do carro custa um retorno).
            float penFwd = 0, penBwd = 0;
            if (!Double.isNaN(heading)) {
                double fwdBrg = leaving(ca, a.edge, true);
                if (Math.abs(Geo.angleDiff(heading, fwdBrg)) > 100) penFwd = 45;
                else penBwd = 45;
            }
            if (canFwd(ca, a.edge)) {
                int n = node(a.slot, a.edge, true);
                if (n >= 0) relax(n, (float) ((la - a.along) / va) + penFwd, arriving(ca, a.edge, true), -2, -1);
            }
            if (canBwd(ca, a.edge)) {
                int n = node(a.slot, a.edge, false);
                if (n >= 0) relax(n, (float) (a.along / va) + penBwd, arriving(ca, a.edge, false), -3, -1);
            }
            // Fim: chegar ao destino de cada ponta da rua dele.
            int goalA = canFwd(cb, b.edge) ? node(b.slot, b.edge, false) : -1;
            int goalB = canBwd(cb, b.edge) ? node(b.slot, b.edge, true) : -1;
            float extraA = (float) (b.along / vb), extraB = (float) ((lb - b.along) / vb);

            float best = Float.POSITIVE_INFINITY;
            int bestEnd = 0; // 0 nada, 1 direto, 2 por A, 3 por B
            boolean sameEdge = a.slot == b.slot && a.edge == b.edge;
            if (sameEdge) {
                if (b.along >= a.along && canFwd(ca, a.edge)) {
                    best = (float) ((b.along - a.along) / va) + penFwd;
                    bestEnd = 1;
                } else if (b.along < a.along && canBwd(ca, a.edge)) {
                    best = (float) ((a.along - b.along) / va) + penBwd;
                    bestEnd = 1;
                }
            }

            while (hs > 0) {
                float f = hf[0];
                if (f >= best) break;
                int u = pop();
                int s = u >>> SLOT_SHIFT, i = u & IDX_MASK;
                if (done[s][i]) continue;
                done[s][i] = true;
                if (++settled > MAX_SETTLED) throw new IOException("rota grande demais");
                float g = cost[s][i];
                if (u == goalA && g + extraA < best) {
                    best = g + extraA;
                    bestEnd = 2;
                }
                if (u == goalB && g + extraB < best) {
                    best = g + extraB;
                    bestEnd = 3;
                }
                GraphCell c = cells[s];
                boolean local = meters(c.nodeLat[i], c.nodeLon[i], startLat, startLon) < LOCAL_M
                    || meters(c.nodeLat[i], c.nodeLon[i], goalLat, goalLon) < LOCAL_M;
                // Proibições de conversão para a aresta de chegada (quando ela está neste quadrado).
                int inEdge = prevEdge[s][i] >= 0 && prevEdge[s][i] >>> SLOT_SHIFT == s ? prevEdge[s][i] & IDX_MASK : -1;
                int only = -1;
                int r0 = inEdge >= 0 ? c.firstRestriction(i) : -1;
                if (r0 >= 0) {
                    for (int r = r0; r < c.resVia.length && c.resVia[r] == i; r++) {
                        if (c.resFrom[r] == inEdge && c.resType[r] == 1) only = c.resTo[r];
                    }
                }
                float in = arrive[s][i];
                boolean hasIn = prevNode[s][i] != -1 || prevEdge[s][i] < -1;
                for (int k = c.adjStart[i]; k < c.adjStart[i + 1]; k++) {
                    int e = c.adj[k];
                    if (!local && (c.cls[e] & 15) >= 7) continue;
                    if (only >= 0 && e != only) continue;
                    if (r0 >= 0 && forbidden(c, r0, i, inEdge, e)) continue;
                    for (int dir = 0; dir < 2; dir++) {
                        boolean fwd = dir == 0;
                        if (fwd ? c.edgeA[e] != i : c.edgeB[e] != i) continue;
                        if (fwd ? !canFwd(c, e) : !canBwd(c, e)) continue;
                        int v = node(s, e, fwd);
                        if (v < 0 || v == u) continue;
                        float t = (float) (c.length[e] / mps(c, e)) * factor(c, e);
                        if (hasIn) t += turn(in, leaving(c, e, fwd));
                        relax(v, g + t, arriving(c, e, fwd), s << SLOT_SHIFT | e, u);
                    }
                }
            }
            if (bestEnd == 0) throw new IOException("sem caminho");
            return build(a, b, bestEnd, goalA, goalB, va, vb, toLat, toLon, destName);
        }

        private boolean forbidden(GraphCell c, int r0, int via, int inEdge, int out) {
            for (int r = r0; r < c.resVia.length && c.resVia[r] == via; r++) {
                if (c.resType[r] == 0 && c.resFrom[r] == inEdge && c.resTo[r] == out) return true;
            }
            return false;
        }

        // ------------------------------------------------------------------ linha e manobras

        Route build(Snap a, Snap b, int end, int goalA, int goalB, double va, double vb,
                    double toLat, double toLon, String destName) {
            // Trechos do meio, do fim para o começo.
            List<int[]> mid = new ArrayList<>(); // {slot, aresta, fwd}
            int startDir = 0; // 1: saiu pela B da rua da partida (para frente), -1: pela A
            if (end != 1) {
                int n = end == 2 ? goalA : goalB;
                while (true) {
                    int s = n >>> SLOT_SHIFT, i = n & IDX_MASK;
                    int pe = prevEdge[s][i];
                    if (pe == -2) {
                        startDir = 1;
                        break;
                    }
                    if (pe == -3) {
                        startDir = -1;
                        break;
                    }
                    int ps = pe >>> SLOT_SHIFT, e = pe & IDX_MASK;
                    GraphCell c = cells[ps];
                    int from = prevNode[s][i];
                    // Sentido: a aresta sai do nó anterior pela A (fwd) ou pela B.
                    boolean fwd = c.edgeA[e] == (from & IDX_MASK) && from >>> SLOT_SHIFT == ps;
                    if (c.edgeA[e] == c.edgeB[e]) fwd = true;
                    mid.add(new int[]{ps, e, fwd ? 1 : 0});
                    n = from;
                }
            }
            List<Leg> legs = new ArrayList<>();
            List<int[]> pts = new ArrayList<>(); // lat, lon (1e-6)
            GraphCell ca = cells[a.slot], cb = cells[b.slot];
            if (end == 1) {
                boolean fwd = b.along >= a.along;
                addLeg(legs, pts, a.slot, a.edge, fwd, a.along, b.along, va);
            } else {
                boolean fwd = startDir == 1;
                addLeg(legs, pts, a.slot, a.edge, fwd, a.along, fwd ? ca.length[a.edge] : 0, va);
                for (int k = mid.size() - 1; k >= 0; k--) {
                    int[] m = mid.get(k);
                    GraphCell c = cells[m[0]];
                    boolean f = m[2] == 1;
                    addLeg(legs, pts, m[0], m[1], f, f ? 0 : c.length[m[1]], f ? c.length[m[1]] : 0, mps(c, m[1]));
                }
                boolean fwdEnd = end == 2;
                addLeg(legs, pts, b.slot, b.edge, fwdEnd, fwdEnd ? 0 : cb.length[b.edge], b.along, vb);
            }
            int n = pts.size();
            double[] lat = new double[n], lon = new double[n];
            for (int i = 0; i < n; i++) {
                lat[i] = pts.get(i)[0] / 1e6;
                lon[i] = pts.get(i)[1] / 1e6;
            }
            List<Route.Step> steps = steps(legs, lat, lon);
            keys = new long[legs.size()];
            lengths = new double[legs.size()];
            for (int i = 0; i < legs.size(); i++) {
                keys[i] = legs.get(i).c.key(legs.get(i).e);
                lengths[i] = legs.get(i).meters;
            }
            double dist = 0;
            for (int i = 1; i < n; i++) dist += Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            // Tempo: os trechos e as conversões (o mesmo custo do cálculo, sem preferências nem penalidades).
            double secs = 0;
            for (int i = 0; i < legs.size(); i++) {
                Leg l = legs.get(i);
                secs += l.seconds;
                if (i > 0) {
                    Leg prev = legs.get(i - 1);
                    secs += turn(arriving(prev.c, prev.e, prev.fwd), leaving(l.c, l.e, l.fwd));
                }
            }
            return new Route(lat, lon, steps, dist, secs, toLat, toLon, destName);
        }

        /** Põe os pontos da aresta entre {@code from} e {@code to} metros (desde A) na linha. */
        void addLeg(List<Leg> legs, List<int[]> pts, int slot, int e, boolean fwd, double from, double to, double v) {
            GraphCell c = cells[slot];
            Leg l = new Leg();
            l.c = c;
            l.slot = slot;
            l.e = e;
            l.fwd = fwd;
            l.start = Math.max(0, pts.size() - 1);
            l.seconds = Math.abs(to - from) / v;
            l.meters = Math.abs(to - from);
            int n = c.points(e);
            double total = c.length[e];
            // Posições (m) dos pontos ao longo da aresta, na escala do comprimento guardado.
            double[] at = new double[n];
            double k = Math.cos(Math.toRadians(c.latA(e) / 1e6));
            for (int i = 1; i < n; i++) {
                double dy = (c.pointLat(e, i) - c.pointLat(e, i - 1)) * 0.111195;
                double dx = (c.pointLon(e, i) - c.pointLon(e, i - 1)) * 0.111195 * k;
                at[i] = at[i - 1] + Math.sqrt(dx * dx + dy * dy);
            }
            double scale = at[n - 1] > 0 ? total / at[n - 1] : 1;
            for (int i = 0; i < n; i++) at[i] *= scale;
            List<int[]> out = new ArrayList<>();
            out.add(pointAt(c, e, at, from));
            if (fwd) {
                for (int i = 0; i < n; i++) if (at[i] > from && at[i] < to) out.add(new int[]{c.pointLat(e, i), c.pointLon(e, i)});
            } else {
                for (int i = n - 1; i >= 0; i--) if (at[i] < from && at[i] > to) out.add(new int[]{c.pointLat(e, i), c.pointLon(e, i)});
            }
            out.add(pointAt(c, e, at, to));
            for (int[] p : out) {
                int[] last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
                if (last != null && last[0] == p[0] && last[1] == p[1]) continue;
                pts.add(p);
            }
            if (pts.size() == 1) pts.add(pts.get(0).clone()); // rota de um ponto só: a Route quer dois
            legs.add(l);
        }

        static int[] pointAt(GraphCell c, int e, double[] at, double m) {
            int n = at.length;
            if (m <= 0) return new int[]{c.pointLat(e, 0), c.pointLon(e, 0)};
            if (m >= at[n - 1]) return new int[]{c.pointLat(e, n - 1), c.pointLon(e, n - 1)};
            int i = 1;
            while (i < n - 1 && at[i] < m) i++;
            double t = at[i] == at[i - 1] ? 0 : (m - at[i - 1]) / (at[i] - at[i - 1]);
            return new int[]{(int) Math.round(c.pointLat(e, i - 1) + (c.pointLat(e, i) - c.pointLat(e, i - 1)) * t),
                (int) Math.round(c.pointLon(e, i - 1) + (c.pointLon(e, i) - c.pointLon(e, i - 1)) * t)};
        }

        /**
         * Manobras: em cada cruzamento entre dois trechos, uma conversão (ângulo de 35° ou mais, com outra
         * saída possível), a troca de nome da rua, bifurcação, entrada e saída de pista (ligações) e a
         * rotatória (com o número da saída). Mais a partida e a chegada.
         */
        List<Route.Step> steps(List<Leg> legs, double[] lat, double[] lon) {
            List<Route.Step> out = new ArrayList<>();
            int n = lat.length;
            double[] along = new double[n];
            for (int i = 1; i < n; i++) along[i] = along[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            List<Object[]> raw = new ArrayList<>(); // {type, modifier, exit, name, index}
            raw.add(new Object[]{"depart", null, 0, name(legs.get(0)), 0});
            for (int k = 1; k < legs.size(); k++) {
                Leg prev = legs.get(k - 1), next = legs.get(k);
                int at = next.start;
                if (at <= 0 || at >= n - 1) continue;
                boolean inRound = (prev.c.flags[prev.e] & GraphCell.ROUNDABOUT) != 0;
                boolean outRound = (next.c.flags[next.e] & GraphCell.ROUNDABOUT) != 0;
                if (outRound && !inRound) {
                    // Rotatória: conta as saídas até sair dela.
                    int exits = 0;
                    int j = k;
                    while (j < legs.size() && (legs.get(j).c.flags[legs.get(j).e] & GraphCell.ROUNDABOUT) != 0) {
                        if (j > k && exitsAt(legs.get(j - 1), legs.get(j), true) > 0) exits++;
                        j++;
                    }
                    String name = j < legs.size() ? name(legs.get(j)) : name(next);
                    raw.add(new Object[]{"roundabout", "straight", exits + 1, name, at});
                    k = Math.max(k, j);
                    continue;
                }
                if (inRound) continue;
                float in = arriving(prev.c, prev.e, prev.fwd);
                float o = leaving(next.c, next.e, next.fwd);
                double d = Geo.angleDiff(in, o);
                double ad = Math.abs(d);
                List<Float> others = new ArrayList<>();
                otherClasses.clear();
                int alternatives = exitsAt(prev, next, false, others);
                String pn = name(prev), nn = name(next);
                // Troca de nome que conta: "BR 423 - Rodovia X" e "Rodovia X" são a mesma via.
                boolean renamed = !nn.isEmpty() && !nn.equals(pn) && !nn.contains(pn.isEmpty() ? " " : pn)
                    && !pn.contains(nn);
                boolean prevLink = (prev.c.cls[prev.e] & 16) != 0, nextLink = (next.c.cls[next.e] & 16) != 0;
                int prevCls = prev.c.cls[prev.e] & 15, nextCls = next.c.cls[next.e] & 15;
                String modifier = modifier(d);
                if (alternatives == 0) {
                    // Sem outra saída: só uma curva (ou a rua muda de nome no meio do caminho, sem aviso).
                    continue;
                }
                if (nextLink && !prevLink) {
                    String side = sideOf(d, others);
                    // Ligação sem nome: o nome da via aonde ela leva.
                    String to = nn;
                    for (int j = k; to.isEmpty() && j < legs.size(); j++) to = name(legs.get(j));
                    raw.add(new Object[]{prevCls <= 2 ? "off ramp" : "on ramp", side, 0, to, at});
                } else if (prevLink && !nextLink && nextCls <= 2 && ad < 45) {
                    raw.add(new Object[]{"merge", modifier, 0, nn, at});
                } else if (ad < 35) {
                    // Bifurcação: outra saída quase na mesma direção e do mesmo porte (uma rua lateral
                    // saindo da rodovia não é bifurcação de quem segue nela).
                    boolean fork = false;
                    for (int x = 0; x < others.size(); x++) {
                        if (Math.abs(others.get(x)) < 50 && otherClasses.get(x) <= nextCls) fork = true;
                    }
                    if (fork && renamed) {
                        raw.add(new Object[]{"fork", sideOf(d, others), 0, nn, at});
                    } else if (renamed) {
                        raw.add(new Object[]{"new name", "straight", 0, nn, at});
                    }
                } else {
                    raw.add(new Object[]{"turn", modifier, 0, nn, at});
                }
            }
            raw.add(new Object[]{"arrive", null, 0, "", n - 1});
            // Duração de cada trecho entre manobras: proporcional à distância, pela velocidade de cada trecho.
            double[] secAt = new double[n];
            for (int li = 0; li < legs.size(); li++) {
                Leg l = legs.get(li);
                int end = li + 1 < legs.size() ? legs.get(li + 1).start : n - 1;
                double len = along[end] - along[l.start];
                for (int i = l.start + 1; i <= end; i++) {
                    secAt[i] = secAt[i - 1] + (len > 0 ? l.seconds * (along[i] - along[i - 1]) / len : 0);
                }
            }
            for (int i = 0; i < raw.size(); i++) {
                Object[] r = raw.get(i);
                int idx = (Integer) r[4];
                int nextIdx = i + 1 < raw.size() ? (Integer) raw.get(i + 1)[4] : idx;
                out.add(new Route.Step((String) r[0], (String) r[1], (Integer) r[2], (String) r[3], "",
                    along[nextIdx] - along[idx], secAt[nextIdx] - secAt[idx], idx));
            }
            return out;
        }

        /** Classes das outras saídas do último {@link #exitsAt} com rumos (na mesma ordem). */
        final List<Integer> otherClasses = new ArrayList<>();

        int exitsAt(Leg prev, Leg next, boolean nonRoundabout) {
            return exitsAt(prev, next, nonRoundabout, null);
        }

        /**
         * Saídas possíveis no nó entre dois trechos, além da tomada e da volta pela mesma rua; com
         * {@code nonRoundabout}, só as que saem da rotatória. Os rumos das outras saídas vão em {@code bearings}.
         */
        int exitsAt(Leg prev, Leg next, boolean nonRoundabout, List<Float> bearings) {
            GraphCell c = next.c;
            int node = next.fwd ? c.edgeA[next.e] : c.edgeB[next.e];
            if (node < 0) return 1; // cruzamento na borda do quadrado: conta como cruzamento
            int count = 0;
            int nodeLat = c.nodeLat[node], nodeLon = c.nodeLon[node];
            // Ponta de onde veio o trecho anterior (para não contar a volta por ele).
            int backLat = prev.fwd ? prev.c.latA(prev.e) : prev.c.latB(prev.e);
            int backLon = prev.fwd ? prev.c.lonA(prev.e) : prev.c.lonB(prev.e);
            int backPts = prev.c.points(prev.e);
            for (int k = c.adjStart[node]; k < c.adjStart[node + 1]; k++) {
                int e = c.adj[k];
                if (e == next.e) continue;
                for (int dir = 0; dir < 2; dir++) {
                    boolean fwd = dir == 0;
                    if (fwd ? c.edgeA[e] != node : c.edgeB[e] != node) continue;
                    if (fwd ? !canFwd(c, e) : !canBwd(c, e)) continue;
                    int oLat = fwd ? c.latB(e) : c.latA(e), oLon = fwd ? c.lonB(e) : c.lonA(e);
                    if (oLat == backLat && oLon == backLon && c.points(e) == backPts) continue; // a mesma rua de volta
                    if (nonRoundabout && (c.flags[e] & GraphCell.ROUNDABOUT) != 0) continue;
                    if ((c.cls[e] & 15) == 9 && (next.c.cls[next.e] & 15) != 9) continue; // vias de serviço não contam
                    count++;
                    if (bearings != null) {
                        bearings.add((float) Geo.angleDiff(arriving(prev.c, prev.e, prev.fwd), leaving(c, e, fwd)));
                        otherClasses.add(c.cls[e] & 15);
                    }
                }
            }
            return count;
        }

        static String name(Leg l) {
            int i = l.c.name[l.e];
            return i < 0 ? "" : l.c.names[i];
        }

        static String modifier(double d) {
            double a = Math.abs(d);
            String side = d < 0 ? "left" : "right";
            if (a < 20) return "straight";
            if (a < 50) return "slight " + side;
            if (a < 130) return side;
            if (a < 170) return "sharp " + side;
            return "uturn";
        }

        /** Para bifurcação e pista: à esquerda ou à direita da saída mais parecida (ângulos relativos à chegada). */
        static String sideOf(double d, List<Float> others) {
            if (others.isEmpty()) return d < 0 ? "slight left" : "slight right";
            float near = others.get(0);
            for (float x : others) if (Math.abs(x - d) < Math.abs(near - d)) near = x;
            return d < near ? "slight left" : "slight right";
        }
    }
}
