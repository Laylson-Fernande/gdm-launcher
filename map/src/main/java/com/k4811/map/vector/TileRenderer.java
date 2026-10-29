package com.k4811.map.vector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.k4811.common.ui.Ui;
import com.k4811.map.MapConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.k4811.map.vector.MapSection.*;

/**
 * Desenha um tile (256 dp) a partir do mapa offline ({@link MapStore}), no tema claro ou escuro.
 *
 * Nível dos dados pelo zoom: visão geral (quadrados de 2°) nos zooms 7–9, a seção simplificada dos
 * quadrados nos 10–12 e os blocos 8×8 do 13 em diante (o detalhe dos blocos, com prédios e caminhos,
 * só do 15 em diante). As feições de um bloco já vêm recortadas nele; o tile pega também os blocos
 * vizinhos numa margem, para nomes e prédios que atravessam a borda saírem inteiros.
 *
 * Os nomes ficam numa posição calculada no mundo (não no tile), então os dois tiles vizinhos
 * desenham cada um a sua metade de um nome que cruza a borda.
 */
public final class TileRenderer {

    /** Zoom mínimo com dados (abaixo, só os tiles da internet). */
    public static final int MIN_ZOOM = 7;
    public static final int FULL = 2, PARTIAL = 1, NONE = 0;

    private static final int WORLD_BITS = 26;
    private static final double WORLD = 1 << WORLD_BITS;
    /** Margem em volta do tile (dp) de onde ainda se pegam dados: nomes e prédios na borda. */
    private static final float MARGIN_DP = 64;

    private final Context context;
    private final float dp;
    private final int tilePx;
    private final Typeface regular;
    private final Typeface bold;
    private final ThreadLocal<Painter> painters = new ThreadLocal<>();

    public TileRenderer(Context c, int tilePx) {
        context = c.getApplicationContext();
        dp = c.getResources().getDisplayMetrics().density;
        this.tilePx = tilePx;
        regular = Ui.medium(c);
        bold = Ui.semiBold(c);
    }

    // ------------------------------------------------------------------ quais arquivos

    /** Uma seção que o tile precisa. */
    private static final class Need {
        final String name;
        final int section;

        Need(String name, int section) {
            this.name = name;
            this.section = section;
        }
    }

    /** Camadas ligadas, de {@link MapConfig#mapHide}. */
    private static final class Layers {
        boolean buildings, paths, casings, green, lines, roadNames, antialias;

        void read(MapConfig cfg) {
            buildings = !cfg.mapHide.contains("buildings");
            paths = !cfg.mapHide.contains("paths");
            casings = !cfg.mapHide.contains("casings");
            green = !cfg.mapHide.contains("green");
            lines = !cfg.mapHide.contains("lines");
            roadNames = !cfg.mapHide.contains("road_names");
            antialias = !cfg.mapHide.contains("antialias");
        }

        /** A seção de detalhe do bloco (prédios e vias pequenas) tem algo a desenhar. */
        boolean detail() {
            return buildings || paths;
        }
    }

    private final Layers layers = new Layers();

    private List<Need> needs(int z, int x, int y) {
        int span = 1 << (WORLD_BITS - z);
        double scale = tilePx / (double) span;
        int m = (int) Math.ceil(MARGIN_DP * dp / scale);
        double wx0 = (double) x * span - m, wx1 = (double) (x + 1) * span + m;
        double wy0 = (double) y * span - m, wy1 = (double) (y + 1) * span + m;
        double latN = lat(Math.max(0, wy0)), latS = lat(Math.min(WORLD - 1, wy1));
        List<Need> out = new ArrayList<>();
        if (z <= 9) {
            int c0 = col(wx0, 180), c1 = col(wx1, 180);
            int r0 = row(latS, 2.0), r1 = row(latN, 2.0);
            for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) out.add(new Need("o" + c + "_" + r, 0));
        } else if (z <= 12) {
            int c0 = col(wx0, 1440), c1 = col(wx1, 1440);
            int r0 = row(latS, 0.25), r1 = row(latN, 0.25);
            for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) out.add(new Need(c + "_" + r, 0));
        } else {
            int b = BLOCKS;
            int c0 = col(wx0, 1440 * b), c1 = col(wx1, 1440 * b);
            int r0 = row(latS, 0.25 / b), r1 = row(latN, 0.25 / b);
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    String name = Math.floorDiv(c, b) + "_" + Math.floorDiv(r, b);
                    int bx = Math.floorMod(c, b), by = b - 1 - Math.floorMod(r, b);
                    out.add(new Need(name, 1 + by * b + bx));
                    if (z >= 15 && layers.detail()) out.add(new Need(name, 1 + b * b + by * b + bx));
                }
            }
        }
        return out;
    }

    private static int col(double wx, int cols) {
        return (int) Math.floor(wx * cols / WORLD);
    }

    private static int row(double lat, double deg) {
        return (int) Math.floor((lat + 90) / deg);
    }

    private static double lat(double wy) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * wy / WORLD))));
    }

    /**
     * Quanto do tile há no disco: {@link #FULL} (tudo o que o servidor tem para essa área),
     * {@link #PARTIAL} (parte baixada) ou {@link #NONE}.
     */
    public int coverage(int z, int x, int y) {
        if (z < MIN_ZOOM) return NONE;
        layers.read(MapConfig.get(context));
        MapStore store = MapStore.get();
        boolean present = false, missing = false;
        String last = null;
        for (Need n : needs(z, x, y)) {
            if (n.name.equals(last)) continue;
            last = n.name;
            if (store.has(n.name)) present = true;
            else if (!Boolean.FALSE.equals(store.inIndex(n.name))) missing = true;
        }
        return !present ? NONE : missing ? PARTIAL : FULL;
    }

    // ------------------------------------------------------------------ desenho

    /** O tile desenhado (RGB_565), ou null se não há dado nenhum para ele. */
    public Bitmap render(int z, int x, int y, boolean dark) {
        long t0 = android.os.SystemClock.uptimeMillis();
        layers.read(MapConfig.get(context));
        MapStore store = MapStore.get();
        List<MapSection> secs = new ArrayList<>();
        for (Need n : needs(z, x, y)) {
            MapSection s = store.section(n.name, n.section);
            if (s != null) secs.add(s);
        }
        if (secs.isEmpty()) return null;
        Painter p = painters.get();
        if (p == null) {
            p = new Painter();
            painters.set(p);
        }
        long t1 = android.os.SystemClock.uptimeMillis();
        Bitmap b = p.draw(z, x, y, secs, dark ? MapStyle.DARK : MapStyle.LIGHT);
        long t2 = android.os.SystemClock.uptimeMillis();
        // Só os lentos (dados de um bloco muito denso ou central ocupada), para o log de diagnóstico.
        if (t2 - t0 >= Math.max(1, MapConfig.get(context).mapLogMs)) android.util.Log.i("K4811MapData", "tile " + z + "/" + x + "/" + y + ": dados " + (t1 - t0) + " ms, desenho " + (t2 - t1) + " ms");
        return b;
    }

    /** Pincéis e caminhos reaproveitados (um por thread). */
    private final class Painter {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Path path = new Path();
        final Path buildings = new Path();
        final Path[] roads = new Path[24];
        final Path[] tunnels = new Path[12];
        final Path[] bridges = new Path[24];
        final Path[] lines = new Path[8];
        final RectF r = new RectF();
        final List<RectF> placed = new ArrayList<>();
        final Map<String, List<float[]>> placedNames = new LinkedHashMap<>();
        final DashPathEffect boundaryDash = new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0);
        final DashPathEffect pathDash = new DashPathEffect(new float[]{3 * dp, 2.5f * dp}, 0);
        final DashPathEffect railDash = new DashPathEffect(new float[]{8 * dp, 8 * dp}, 0);

        int z;
        float zf;
        double x0, y0;
        double scale;
        MapStyle st;
        Canvas canvas;

        Painter() {
            for (int i = 0; i < roads.length; i++) roads[i] = new Path();
            for (int i = 0; i < tunnels.length; i++) tunnels[i] = new Path();
            for (int i = 0; i < bridges.length; i++) bridges[i] = new Path();
            for (int i = 0; i < lines.length; i++) lines[i] = new Path();
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeJoin(Paint.Join.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            halo.setTextAlign(Paint.Align.CENTER);
        }

        Bitmap draw(int z, int x, int y, List<MapSection> secs, MapStyle st) {
            this.z = z;
            this.zf = z;
            this.st = st;
            int span = 1 << (WORLD_BITS - z);
            x0 = (double) x * span;
            y0 = (double) y * span;
            scale = tilePx / (double) span;
            Bitmap bmp = Bitmap.createBitmap(tilePx, tilePx, Bitmap.Config.RGB_565);
            canvas = new Canvas(bmp);
            canvas.drawColor(st.land);
            fill.setAntiAlias(layers.antialias);
            stroke.setAntiAlias(layers.antialias);

            // Áreas, de baixo para cima.
            if (layers.green) {
                areas(secs, SAND, st.sand);
                areas(secs, PARK, st.park);
                areas(secs, FOREST, st.forest);
            }
            areas(secs, WATER, st.water);
            areas(secs, OCEAN, st.water);
            if (layers.buildings && z >= MapStyle.minZoom(BUILDING)) buildings(secs);

            for (Path l : lines) l.rewind();
            for (Path l : roads) l.rewind();
            for (Path l : tunnels) l.rewind();
            for (Path l : bridges) l.rewind();
            collectLines(secs);
            drawLines();
            labels(secs);
            canvas = null;
            return bmp;
        }

        boolean visible(MapSection s, int i, float pad) {
            double sp = tilePx / scale;
            double m = pad / scale;
            int[] b = s.box;
            return b[i * 4 + 2] >= x0 - m && b[i * 4] <= x0 + sp + m && b[i * 4 + 3] >= y0 - m && b[i * 4 + 1] <= y0 + sp + m;
        }

        float sx(int wx) {
            return (float) ((wx - x0) * scale);
        }

        float sy(int wy) {
            return (float) ((wy - y0) * scale);
        }

        /** Adiciona as partes da feição ao caminho (fechadas se for área). */
        void addTo(Path p, MapSection s, int i, boolean close) {
            for (int k = s.partStart[i]; k < s.partStart[i + 1]; k++) {
                int a = s.ptStart[k], b = s.ptStart[k + 1];
                if (b - a < 2) continue;
                p.moveTo(sx(s.xy[a * 2]), sy(s.xy[a * 2 + 1]));
                for (int j = a + 1; j < b; j++) p.lineTo(sx(s.xy[j * 2]), sy(s.xy[j * 2 + 1]));
                if (close) p.close();
            }
        }

        void areas(List<MapSection> secs, int type, int color) {
            if (z < MapStyle.minZoom(type)) return;
            fill.setColor(color);
            path.setFillType(Path.FillType.EVEN_ODD);
            for (MapSection s : secs) {
                for (int i = 0; i < s.count; i++) {
                    if (s.type[i] != type || !visible(s, i, 1)) continue;
                    path.rewind();
                    addTo(path, s, i, true);
                    canvas.drawPath(path, fill);
                }
            }
            path.setFillType(Path.FillType.WINDING);
        }

        void buildings(List<MapSection> secs) {
            buildings.rewind();
            boolean any = false;
            for (MapSection s : secs) {
                for (int i = 0; i < s.count; i++) {
                    if (s.type[i] != BUILDING || !visible(s, i, 1)) continue;
                    addTo(buildings, s, i, true);
                    any = true;
                }
            }
            if (!any) return;
            fill.setColor(st.building);
            canvas.drawPath(buildings, fill);
            if (z >= 17) {
                stroke.setPathEffect(null);
                stroke.setColor(st.buildingLine);
                stroke.setStrokeWidth(dp);
                canvas.drawPath(buildings, stroke);
            }
        }

        /** Índices em {@link #lines}. */
        static final int L_RIVER = 0, L_STREAM = 1, L_RAIL = 2, L_LIGHT_RAIL = 3, L_TRAM = 4,
            L_STATE = 5, L_CITY = 6, L_RAIL_TUNNEL = 7;

        void collectLines(List<MapSection> secs) {
            float pad = 12 * dp;
            for (MapSection s : secs) {
                for (int i = 0; i < s.count; i++) {
                    int type = s.type[i];
                    int fl = s.flags[i];
                    if ((fl & F_AREA) != 0 || type >= WATER || z < MapStyle.minZoom(type)) continue;
                    if (!layers.paths && type >= SERVICE && type <= PATH) continue;
                    if (!layers.lines && !MapStyle.isRoad(type) && type != RIVER) continue;
                    if (!visible(s, i, pad)) continue;
                    Path target;
                    boolean tunnel = (fl & F_TUNNEL) != 0, bridge = (fl & F_BRIDGE) != 0;
                    if (MapStyle.isRoad(type)) {
                        int link = (fl & F_LINK) != 0 ? 12 : 0;
                        target = tunnel ? tunnels[type] : bridge && z >= 14 ? bridges[type + link] : roads[type + link];
                    } else if (type == RIVER) target = lines[L_RIVER];
                    else if (type == STREAM) target = lines[L_STREAM];
                    else if (type == RAIL || type == LIGHT_RAIL || type == TRAM) {
                        target = tunnel ? lines[L_RAIL_TUNNEL] : lines[type == RAIL ? L_RAIL : type == LIGHT_RAIL ? L_LIGHT_RAIL : L_TRAM];
                    } else if (type == BOUNDARY_STATE) target = lines[L_STATE];
                    else if (type == BOUNDARY_CITY) target = lines[L_CITY];
                    else continue;
                    addTo(target, s, i, false);
                }
            }
        }

        void drawLines() {
            float k = (float) Math.pow(2, (zf - 16) * 0.75);
            // Rios.
            stroke.setPathEffect(null);
            stroke.setColor(st.water);
            stroke.setStrokeWidth(Math.max(1.2f, 7 * k) * dp);
            canvas.drawPath(lines[L_RIVER], stroke);
            stroke.setStrokeWidth(Math.max(0.8f, 2.5f * k) * dp);
            canvas.drawPath(lines[L_STREAM], stroke);
            // Limites.
            stroke.setStrokeCap(Paint.Cap.BUTT);
            stroke.setColor(st.boundaryCity);
            stroke.setStrokeWidth(1.2f * dp);
            stroke.setPathEffect(boundaryDash);
            canvas.drawPath(lines[L_CITY], stroke);
            stroke.setColor(st.boundaryState);
            stroke.setStrokeWidth(1.8f * dp);
            canvas.drawPath(lines[L_STATE], stroke);
            stroke.setPathEffect(null);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            // Túneis: só o preenchimento, apagado.
            for (int t = PATH; t >= MOTORWAY; t--) {
                if (tunnels[t].isEmpty()) continue;
                stroke.setColor(blend(st.roadLow[t], st.land, 0.5f));
                stroke.setStrokeWidth(MapStyle.roadWidth(t, false, zf) * dp * 0.8f);
                canvas.drawPath(tunnels[t], stroke);
            }
            // Trilhos.
            rails();
            // Vias: contornos de todas, depois os preenchimentos (das menores para as maiores).
            boolean casing = z >= 14 && layers.casings;
            if (casing) {
                for (int t = PATH; t >= MOTORWAY; t--) {
                    for (int link = 12; link >= 0; link -= 12) {
                        Path p = roads[t + link];
                        if (p.isEmpty() || st.roadCase[t] == 0) continue;
                        stroke.setColor(st.roadCase[t]);
                        stroke.setStrokeWidth((MapStyle.roadWidth(t, link > 0, zf) + 1.8f) * dp);
                        canvas.drawPath(p, stroke);
                    }
                }
            }
            for (int t = PATH; t >= MOTORWAY; t--) {
                for (int link = 12; link >= 0; link -= 12) {
                    Path p = roads[t + link];
                    if (p.isEmpty()) continue;
                    drawRoadFill(p, t, link > 0, casing);
                }
            }
            // Pontes por cima, com o contorno.
            for (int t = PATH; t >= MOTORWAY; t--) {
                for (int link = 12; link >= 0; link -= 12) {
                    Path p = bridges[t + link];
                    if (p.isEmpty()) continue;
                    if (st.roadCase[t] != 0) {
                        stroke.setColor(st.roadCase[t]);
                        stroke.setStrokeCap(Paint.Cap.BUTT);
                        stroke.setStrokeWidth((MapStyle.roadWidth(t, link > 0, zf) + 3.4f) * dp);
                        canvas.drawPath(p, stroke);
                        stroke.setStrokeCap(Paint.Cap.ROUND);
                    }
                    drawRoadFill(p, t, link > 0, true);
                }
            }
        }

        void drawRoadFill(Path p, int t, boolean link, boolean casing) {
            stroke.setColor(casing ? st.roadFill[t] : st.roadLow[t]);
            stroke.setStrokeWidth(MapStyle.roadWidth(t, link, zf) * dp);
            if (t == PATH || t == TRACK) {
                stroke.setStrokeCap(Paint.Cap.BUTT);
                stroke.setPathEffect(pathDash);
                canvas.drawPath(p, stroke);
                stroke.setPathEffect(null);
                stroke.setStrokeCap(Paint.Cap.ROUND);
            } else {
                canvas.drawPath(p, stroke);
            }
        }

        void rails() {
            stroke.setStrokeCap(Paint.Cap.BUTT);
            float w = (z >= 15 ? 2.6f : 1.6f) * dp;
            stroke.setColor(st.rail);
            stroke.setStrokeWidth(w);
            canvas.drawPath(lines[L_RAIL], stroke);
            stroke.setStrokeWidth(w * 0.8f);
            canvas.drawPath(lines[L_LIGHT_RAIL], stroke);
            stroke.setStrokeWidth(w * 0.6f);
            canvas.drawPath(lines[L_TRAM], stroke);
            if (z >= 15) {
                // Trilho tracejado (branco por dentro), como nos mapas impressos.
                stroke.setColor(st.land);
                stroke.setStrokeWidth(w * 0.45f);
                stroke.setPathEffect(railDash);
                canvas.drawPath(lines[L_RAIL], stroke);
                stroke.setPathEffect(null);
            }
            stroke.setColor(blend(st.rail, st.land, 0.55f));
            stroke.setStrokeWidth(w * 0.7f);
            stroke.setPathEffect(boundaryDash);
            canvas.drawPath(lines[L_RAIL_TUNNEL], stroke);
            stroke.setPathEffect(null);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        // -------------------------------------------------------------- nomes

        void labels(List<MapSection> secs) {
            placed.clear();
            placedNames.clear();
            places(secs);
            if (z >= 13 && layers.roadNames) roadNames(secs);
        }

        void places(List<MapSection> secs) {
            List<int[]> cand = new ArrayList<>();
            for (int si = 0; si < secs.size(); si++) {
                MapSection s = secs.get(si);
                for (int i = 0; i < s.count; i++) {
                    int type = s.type[i];
                    if (type < CITY || s.name[i] < 0) continue;
                    if (z < MapStyle.minZoom(type) || z > MapStyle.maxPlaceZoom(type)) continue;
                    cand.add(new int[]{type, si, i});
                }
            }
            // Mais importante primeiro; empate pela posição (igual em todos os tiles).
            Collections.sort(cand, (a, b) -> {
                if (a[0] != b[0]) return a[0] - b[0];
                MapSection sa = secs.get(a[1]), sb = secs.get(b[1]);
                return Integer.compare(sa.xy[sa.ptStart[sa.partStart[a[2]]] * 2], sb.xy[sb.ptStart[sb.partStart[b[2]]] * 2]);
            });
            for (int[] c : cand) {
                MapSection s = secs.get(c[1]);
                int i = c[2];
                int pt = s.ptStart[s.partStart[i]];
                float px = sx(s.xy[pt * 2]), py = sy(s.xy[pt * 2 + 1]);
                String name = s.names[s.name[i]];
                boolean big = c[0] <= TOWN;
                text.setTypeface(big ? bold : regular);
                text.setTextSize(MapStyle.placeSize(c[0]) * dp);
                float w = text.measureText(name);
                float h = text.getTextSize();
                if (px + w / 2 < -2 || px - w / 2 > tilePx + 2 || py + h < -2 || py - h > tilePx + 2) continue;
                RectF box = new RectF(px - w / 2 - 3 * dp, py - h * 0.8f, px + w / 2 + 3 * dp, py + h * 0.4f);
                if (collides(box)) continue;
                placed.add(box);
                int color = c[0] >= SUBURB ? st.suburbText : st.placeText;
                drawText(name, px, py + h * 0.35f, 0, color);
            }
        }

        void roadNames(List<MapSection> secs) {
            List<float[]> cand = new ArrayList<>();
            List<String> candName = new ArrayList<>();
            text.setTypeface(regular);
            float size = (z >= 16 ? 12.5f : 11.5f) * dp;
            text.setTextSize(size);
            for (MapSection s : secs) {
                for (int i = 0; i < s.count; i++) {
                    int type = s.type[i];
                    if (!MapStyle.isRoad(type) || s.name[i] < 0 || (s.flags[i] & F_TUNNEL) != 0) continue;
                    if (z < MapStyle.labelZoom(type) || z < MapStyle.minZoom(type) || !visible(s, i, 0)) continue;
                    String name = s.names[s.name[i]];
                    float w = text.measureText(name) + 14 * dp;
                    for (int k = s.partStart[i]; k < s.partStart[i + 1]; k++) {
                        float[] best = bestRun(s, s.ptStart[k], s.ptStart[k + 1], w);
                        if (best == null) continue;
                        best[4] = type;
                        cand.add(best);
                        candName.add(name);
                    }
                }
            }
            Integer[] order = new Integer[cand.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> {
                float[] ca = cand.get(a), cb = cand.get(b);
                if (ca[4] != cb[4]) return Float.compare(ca[4], cb[4]);
                if (ca[3] != cb[3]) return Float.compare(cb[3], ca[3]);
                return Float.compare(ca[0], cb[0]);
            });
            for (Integer oi : order) {
                float[] c = cand.get(oi);
                String name = candName.get(oi);
                float w = text.measureText(name);
                float cx = c[0], cy = c[1], ang = c[2];
                if (cx < -w || cx > tilePx + w || cy < -w || cy > tilePx + w) continue;
                List<float[]> same = placedNames.get(name);
                boolean near = false;
                if (same != null) {
                    for (float[] q : same) if (Math.hypot(q[0] - cx, q[1] - cy) < Math.max(w * 3, 160 * dp)) { near = true; break; }
                }
                if (near) continue;
                double rad = Math.toRadians(ang);
                float hw = w / 2 + 3 * dp, hh = size * 0.7f;
                float ex = (float) (Math.abs(Math.cos(rad)) * hw + Math.abs(Math.sin(rad)) * hh);
                float ey = (float) (Math.abs(Math.sin(rad)) * hw + Math.abs(Math.cos(rad)) * hh);
                RectF box = new RectF(cx - ex, cy - ey, cx + ex, cy + ey);
                if (collides(box)) continue;
                placed.add(box);
                if (same == null) placedNames.put(name, same = new ArrayList<>());
                same.add(new float[]{cx, cy});
                drawText(name, cx, cy + size * 0.35f, ang, st.text);
            }
        }

        /**
         * Trecho mais longo da linha que é quase reto (desvio até ~15°) e cabe o nome: {centro x,
         * centro y, ângulo legível, comprimento, (tipo)}; null se nenhum cabe.
         */
        float[] bestRun(MapSection s, int a, int b, float need) {
            float[] best = null;
            int i = a;
            while (i < b - 1) {
                float ax = sx(s.xy[i * 2]), ay = sy(s.xy[i * 2 + 1]);
                float bx = sx(s.xy[i * 2 + 2]), by = sy(s.xy[i * 2 + 3]);
                double dir = Math.atan2(by - ay, bx - ax);
                int j = i + 1;
                while (j < b - 1) {
                    float nx = sx(s.xy[j * 2 + 2]), ny = sy(s.xy[j * 2 + 3]);
                    double d2 = Math.atan2(ny - ay, nx - ax);
                    double diff = Math.abs(Math.atan2(Math.sin(d2 - dir), Math.cos(d2 - dir)));
                    if (diff > Math.toRadians(15)) break;
                    j++;
                }
                float ex = sx(s.xy[j * 2]), ey = sy(s.xy[j * 2 + 1]);
                float len = (float) Math.hypot(ex - ax, ey - ay);
                if (len >= need && (best == null || len > best[3])) {
                    float ang = (float) Math.toDegrees(Math.atan2(ey - ay, ex - ax));
                    if (ang > 90) ang -= 180;
                    else if (ang < -90) ang += 180;
                    best = new float[]{(ax + ex) / 2, (ay + ey) / 2, ang, len, 0};
                }
                i = j;
            }
            return best;
        }

        boolean collides(RectF box) {
            for (RectF q : placed) if (RectF.intersects(q, box)) return true;
            return false;
        }

        void drawText(String s, float x, float y, float angle, int color) {
            halo.setTypeface(text.getTypeface());
            halo.setTextSize(text.getTextSize());
            halo.setStrokeWidth(3 * dp);
            halo.setColor(st.textHalo);
            text.setColor(color);
            if (angle != 0) {
                canvas.save();
                canvas.rotate(angle, x, y - text.getTextSize() * 0.35f);
            }
            canvas.drawText(s, x, y, halo);
            canvas.drawText(s, x, y, text);
            if (angle != 0) canvas.restore();
        }
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
