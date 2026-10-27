package com.k4811.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.k4811.map.geo.Geo;
import com.k4811.map.route.Route;
import com.k4811.map.tiles.Tiles;

/**
 * Mapa OpenStreetMap desenhado à mão: tiles (com zoom fracionado e rotação), a rota, o destino e a
 * seta do carro. Sem bibliotecas.
 *
 * Câmera: centro em coordenadas de mundo (ver {@link Geo}), zoom e rumo (o rumo que fica para cima).
 * No modo navegação o mapa gira com o carro e o carro fica no terço de baixo, como no Waze. Animações
 * a ~30 quadros por segundo, só enquanto algo se mexe (central de 1,5 GHz).
 */
public class MapView extends View implements Tiles.Listener {

    public interface Listener {
        /** O motorista arrastou o mapa (a câmera parou de seguir o carro) ou voltou a seguir. */
        void onFollowChanged(boolean following);

        /** Toque longo num ponto do mapa. */
        void onLongPress(double lat, double lon);
    }

    private static final float MIN_ZOOM = 3;
    private static final float MAX_ZOOM = 20;
    private static final long FRAME_MS = 30;
    /** Espera entre quadros animados (entre 1 e 2 vsyncs de 60 Hz). */
    private static final long FRAME_SKIP_MS = 20;
    private static final long FIX_ANIM_MS = 950;

    private final Tiles tiles;
    private final float tilePx;
    private final float dp;
    private int maxTileZoom;

    // Câmera
    private double cx = Geo.worldX(-47.9);
    private double cy = Geo.worldY(-15.8);
    private float zoom = 4;
    private float bearing;
    private float anchorY = 0.5f;
    private boolean follow = true;
    private boolean navigating;

    // Animação da câmera (zoom e rumo) e do carro.
    private float zoomFrom;
    private float zoomTo;
    private long zoomStart;
    private long zoomDur;
    private float bearingFrom;
    private float bearingTo;
    private long bearingStart;
    private long bearingDur;
    private double camFromX;
    private double camFromY;
    private double camToX;
    private double camToY;
    private long camStart;
    private long camDur;

    private double carFromX = Double.NaN;
    private double carFromY;
    private double carToX = Double.NaN;
    private double carToY;
    private long carStart;
    private float carHeading = Float.NaN;
    private double carX = Double.NaN;
    private double carY;

    // Inércia do arrasto, em pixels por quadro.
    private float flingX;
    private float flingY;

    private Route route;
    private int routeFrom;
    private double destX = Double.NaN;
    private double destY;
    private float[] lines = new float[0];

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint emptyPaint = new Paint();
    private final Paint casingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint();
    private final Path carPath = new Path();
    private final Path pinPath = new Path();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final float[] pt = new float[2];
    private final String attribution;
    private boolean dark;

    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private Listener listener;
    private boolean frameScheduled;
    /** Janela visível: com a tela do mapa atrás de outra (navegando), não anima nem desenha. */
    private boolean windowVisible = true;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            long now = SystemClock.uptimeMillis();
            boolean more = step(now);
            invalidate();
            // Mais de um vsync (16,7 ms) de espera: o callback cai no vsync depois do próximo, ~30 qps.
            if (more) scheduleIn(FRAME_SKIP_MS);
        }
    };

    public MapView(Context c) {
        this(c, null);
    }

    public MapView(Context c, AttributeSet a) {
        super(c, a);
        tiles = Tiles.get(c);
        dp = c.getResources().getDisplayMetrics().density;
        tilePx = 256 * dp;
        maxTileZoom = MapConfig.get(c).maxZoom;
        attribution = c.getString(R.string.map_attribution);

        emptyPaint.setColor(0xFFE8E4DC);
        casingPaint.setStyle(Paint.Style.STROKE);
        casingPaint.setStrokeCap(Paint.Cap.ROUND);
        casingPaint.setColor(0xFF0B3F8C);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setColor(0xFF3D8BFF);
        carPaint.setColor(0xFF3D8BFF);
        carEdge.setColor(0xFFFFFFFF);
        carEdge.setStyle(Paint.Style.STROKE);
        carEdge.setStrokeWidth(3 * dp);
        carEdge.setStrokeJoin(Paint.Join.ROUND);
        pinPaint.setColor(0xFFE53935);
        pinDot.setColor(0xFFFFFFFF);
        textPaint.setColor(0xFF333333);
        textPaint.setTextSize(11 * dp);
        textBg.setColor(0xB3FFFFFF);
        setRouteWidth();

        // Seta do carro (apontando para cima) e o alfinete do destino, desenhados em volta de (0,0).
        float u = dp;
        carPath.moveTo(0, -17 * u);
        carPath.lineTo(13 * u, 14 * u);
        carPath.lineTo(0, 7 * u);
        carPath.lineTo(-13 * u, 14 * u);
        carPath.close();
        pinPath.addCircle(0, -26 * u, 11 * u, Path.Direction.CW);
        pinPath.moveTo(-8 * u, -19 * u);
        pinPath.lineTo(0, 0);
        pinPath.lineTo(8 * u, -19 * u);
        pinPath.close();

        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                flingX = 0;
                flingY = 0;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale.isInProgress()) return true;
                setFollowInternal(false);
                panBy(dx, dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (follow) return false;
                flingX = -vx / 1000f * FRAME_MS * 0.6f;
                flingY = -vy / 1000f * FRAME_MS * 0.6f;
                schedule();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAround(follow ? getWidth() / 2f : e.getX(), follow ? getHeight() * anchorY : e.getY(), 1);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (listener == null) return;
                double[] w = fromScreen(e.getX(), e.getY());
                listener.onLongPress(Geo.lat(w[1]), Geo.lon(w[0]));
            }
        });
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float dz = (float) (Math.log(d.getScaleFactor()) / Math.log(2));
                float fx = follow ? getWidth() / 2f : d.getFocusX();
                float fy = follow ? getHeight() * anchorY : d.getFocusY();
                zoomAt(fx, fy, zoom + dz);
                zoomDur = 0;
                invalidate();
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tiles.addListener(this);
        tiles.setOnline(MapConfig.online(getContext()));
        // Animação pedida antes de a View entrar na janela (primeira posição do GPS, por exemplo).
        schedule();
    }

    @Override
    protected void onDetachedFromWindow() {
        tiles.removeListener(this);
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameScheduled = false;
        super.onDetachedFromWindow();
    }

    @Override
    public void onTileLoaded() {
        if (windowVisible) invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        if (windowVisible) {
            // Volta a mostrar: a seta e a câmera pulam para a última posição, sem animar o caminho perdido.
            if (!Double.isNaN(carToX)) {
                carStart = 0;
                schedule();
            }
            invalidate();
        }
    }

    // ------------------------------------------------------------------ API

    /** Mapa escuro (filtro de cor sobre os tiles claros do OSM). */
    public void setDark(boolean on) {
        if (dark == on) return;
        dark = on;
        tilePaint.setColorFilter(on ? darkFilter() : null);
        emptyPaint.setColor(on ? 0xFF1B1E22 : 0xFFE8E4DC);
        textPaint.setColor(on ? 0xFFCCCCCC : 0xFF333333);
        textBg.setColor(on ? 0x99000000 : 0xB3FFFFFF);
        invalidate();
    }

    /** Internet disponível para baixar tiles. */
    public void setOnline(boolean on) {
        tiles.setOnline(on);
    }

    public void setCenter(double lat, double lon, float z) {
        cx = Geo.worldX(lon);
        cy = Geo.worldY(lat);
        zoom = clampZoom(z);
        camDur = 0;
        zoomDur = 0;
        invalidate();
    }

    public float getZoom() {
        return zoom;
    }

    public double centerLat() {
        return Geo.lat(cy);
    }

    public double centerLon() {
        return Geo.lon(cx);
    }

    public boolean isFollowing() {
        return follow;
    }

    /** Volta a seguir o carro (botão "centralizar"). */
    public void setFollow(boolean on) {
        setFollowInternal(on);
        if (on && !Double.isNaN(carX)) {
            animateCamera(carX, carY, 600);
            if (navigating && !Float.isNaN(carHeading)) animateBearing(carHeading, 600);
        }
        schedule();
    }

    private void setFollowInternal(boolean on) {
        if (follow == on) return;
        follow = on;
        if (listener != null) listener.onFollowChanged(on);
    }

    /**
     * Modo navegação: mapa girando com o carro, carro no terço de baixo e zoom pela velocidade.
     * Fora dele: norte para cima e carro no meio.
     */
    public void setNavigating(boolean on) {
        navigating = on;
        anchorY = on ? 0.72f : 0.5f;
        if (!on) animateBearing(0, 500);
        else if (!Float.isNaN(carHeading)) animateBearing(carHeading, 500);
        if (on) animateZoom(17, 600);
        schedule();
    }

    public void zoomBy(float dz) {
        zoomAround(getWidth() / 2f, getHeight() * anchorY, dz);
    }

    /** Posição do GPS: a seta anda até lá em ~1 s; a câmera vai junto se estiver seguindo. */
    public void setMyLocation(double lat, double lon, float heading, float speedMps) {
        double x = Geo.worldX(lon);
        double y = Geo.worldY(lat);
        long now = SystemClock.uptimeMillis();
        boolean first = Double.isNaN(carX);
        float newHeading = !Float.isNaN(heading) && speedMps > 1.5f ? heading : carHeading;
        if (!first && Math.abs(x - carToX) < 2e-9 && Math.abs(y - carToY) < 2e-9
            && (Float.isNaN(newHeading) || newHeading == carHeading)) {
            // Parado no mesmo lugar (o GPS repete a posição): nada a animar.
            return;
        }
        if (first) {
            carX = x;
            carY = y;
        }
        carFromX = carX;
        carFromY = carY;
        carToX = x;
        carToY = y;
        carStart = now;
        if (!Float.isNaN(heading) && speedMps > 1.5f) carHeading = heading;
        if (follow) {
            if (first && !navigating) {
                cx = x;
                cy = y;
                if (zoom < 14) animateZoom(16, 700);
            }
            if (navigating) {
                if (!Float.isNaN(carHeading)) animateBearing(carHeading, FIX_ANIM_MS);
                animateZoom(zoomForSpeed(speedMps), 2500);
            }
        }
        schedule();
    }

    public boolean hasLocation() {
        return !Double.isNaN(carToX);
    }

    public double carLat() {
        return Double.isNaN(carToY) ? Double.NaN : Geo.lat(carToY);
    }

    public double carLon() {
        return Double.isNaN(carToX) ? Double.NaN : Geo.lon(carToX);
    }

    public float carHeading() {
        return carHeading;
    }

    public void setRoute(Route r) {
        route = r;
        routeFrom = 0;
        if (r != null) {
            destX = Geo.worldX(r.lon[r.size() - 1]);
            destY = Geo.worldY(r.lat[r.size() - 1]);
            if (lines.length < r.size() * 4) lines = new float[r.size() * 4];
        }
        invalidate();
    }

    /** A rota antes deste ponto já foi percorrida e some do desenho. */
    public void setRouteProgress(int fromIndex) {
        if (fromIndex == routeFrom) return;
        routeFrom = fromIndex;
        invalidate();
    }

    /** Alfinete do destino (sem rota: busca escolhida, antes de calcular). NaN apaga. */
    public void setDestination(double lat, double lon) {
        destX = Double.isNaN(lon) ? Double.NaN : Geo.worldX(lon);
        destY = Double.isNaN(lat) ? 0 : Geo.worldY(lat);
        invalidate();
    }

    /** Mostra a rota inteira (prévia), norte para cima. */
    public void showWholeRoute(Route r, int padTop, int padBottom) {
        if (r == null || getWidth() == 0) return;
        double minX = 1;
        double maxX = 0;
        double minY = 1;
        double maxY = 0;
        for (int i = 0; i < r.size(); i++) {
            minX = Math.min(minX, r.wx[i]);
            maxX = Math.max(maxX, r.wx[i]);
            minY = Math.min(minY, r.wy[i]);
            maxY = Math.max(maxY, r.wy[i]);
        }
        if (!Double.isNaN(carX)) {
            minX = Math.min(minX, carX);
            maxX = Math.max(maxX, carX);
            minY = Math.min(minY, carY);
            maxY = Math.max(maxY, carY);
        }
        float w = getWidth() - 80 * dp;
        float h = getHeight() - padTop - padBottom - 40 * dp;
        double spanX = Math.max(maxX - minX, 1e-7);
        double spanY = Math.max(maxY - minY, 1e-7);
        double scaleX = w / (spanX * 256 * dp);
        double scaleY = h / (spanY * 256 * dp);
        float z = (float) (Math.log(Math.min(scaleX, scaleY)) / Math.log(2));
        setFollowInternal(false);
        anchorY = 0.5f;
        // Centro do retângulo útil, deslocado pelas margens de cima e de baixo.
        double centerY = (minY + maxY) / 2;
        float targetZoom = clampZoom(Math.min(z, 17));
        double shiftY = (padBottom - padTop) / 2.0 / (tilePx * Math.pow(2, targetZoom));
        animateCamera((minX + maxX) / 2, centerY + shiftY, 700);
        animateZoom(targetZoom, 700);
        animateBearing(0, 700);
        schedule();
    }

    // ------------------------------------------------------------------ desenho

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        tiles.beginFrame();
        float ax = w / 2f;
        float ay = h * anchorY;
        int z = Math.max(2, Math.min(maxTileZoom, Math.round(zoom)));
        float s = (float) Math.pow(2, zoom - z);
        int n = 1 << z;
        double worldPx = (double) tilePx * n;
        double cpx = cx * worldPx;
        double cpy = cy * worldPx;
        float r = (float) (Math.hypot(Math.max(ax, w - ax), Math.max(ay, h - ay)) / s) + 1;
        int x0 = (int) Math.floor((cpx - r) / tilePx);
        int x1 = (int) Math.floor((cpx + r) / tilePx);
        int y0 = Math.max(0, (int) Math.floor((cpy - r) / tilePx));
        int y1 = Math.min(n - 1, (int) Math.floor((cpy + r) / tilePx));

        canvas.drawColor(emptyPaint.getColor());
        canvas.save();
        canvas.translate(ax, ay);
        canvas.rotate(-bearing);
        canvas.scale(s, s);
        float rr = r * r;
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                float left = (float) (tx * (double) tilePx - cpx);
                float top = (float) (ty * (double) tilePx - cpy);
                // Fora do círculo que cobre a tela girada: pula.
                float nx = Math.max(left, Math.min(0, left + tilePx));
                float ny = Math.max(top, Math.min(0, top + tilePx));
                if (nx * nx + ny * ny > rr) continue;
                int wx = ((tx % n) + n) % n;
                dst.set(left, top, left + tilePx + 0.6f, top + tilePx + 0.6f);
                Bitmap b = tiles.get(z, wx, ty);
                if (b != null) {
                    canvas.drawBitmap(b, null, dst, tilePaint);
                } else {
                    drawParent(canvas, z, wx, ty);
                }
            }
        }
        canvas.restore();

        drawRoute(canvas, ax, ay);
        if (!Double.isNaN(destX)) drawPin(canvas, ax, ay);
        if (!Double.isNaN(carX)) drawCar(canvas, ax, ay);
        drawAttribution(canvas, w, h);
    }

    /** Enquanto o tile não chega, um pedaço ampliado de um tile de zoom menor que já está em memória. */
    private void drawParent(Canvas canvas, int z, int x, int y) {
        for (int dz = 1; dz <= 5 && z - dz >= 0; dz++) {
            Bitmap p = tiles.peek(z - dz, x >> dz, y >> dz);
            if (p == null) continue;
            int size = p.getWidth() >> dz;
            if (size < 8) return;
            int sx = (x - ((x >> dz) << dz)) * size;
            int sy = (y - ((y >> dz) << dz)) * size;
            src.set(sx, sy, sx + size, sy + size);
            canvas.drawBitmap(p, src, dst, tilePaint);
            return;
        }
    }

    private void drawRoute(Canvas canvas, float ax, float ay) {
        Route rt = route;
        if (rt == null) return;
        double scale = tilePx * Math.pow(2, zoom);
        double rad = Math.toRadians(-bearing);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        int count = 0;
        float px = 0;
        float py = 0;
        boolean have = false;
        float minStep = 2.5f * dp;
        float margin = 40 * dp;
        float right = getWidth() + margin;
        float bottom = getHeight() + margin;
        int from = Math.max(0, Math.min(routeFrom, rt.size() - 1));
        for (int i = from; i < rt.size(); i++) {
            float dx = (float) ((rt.wx[i] - cx) * scale);
            float dy = (float) ((rt.wy[i] - cy) * scale);
            float sx = ax + dx * cos - dy * sin;
            float sy = ay + dx * sin + dy * cos;
            if (have) {
                // Pontos colados não mudam nada na tela: pula (menos o último).
                if (i < rt.size() - 1 && Math.abs(sx - px) < minStep && Math.abs(sy - py) < minStep) continue;
                // Trecho inteiro fora da tela (os dois pontos do mesmo lado): não manda desenhar.
                boolean out = (sx < -margin && px < -margin) || (sx > right && px > right)
                    || (sy < -margin && py < -margin) || (sy > bottom && py > bottom);
                if (out) {
                    px = sx;
                    py = sy;
                    continue;
                }
                lines[count++] = px;
                lines[count++] = py;
                lines[count++] = sx;
                lines[count++] = sy;
            }
            px = sx;
            py = sy;
            have = true;
        }
        if (count == 0) return;
        canvas.drawLines(lines, 0, count, casingPaint);
        canvas.drawLines(lines, 0, count, routePaint);
    }

    private void drawPin(Canvas canvas, float ax, float ay) {
        toScreen(destX, destY, ax, ay, pt);
        canvas.save();
        canvas.translate(pt[0], pt[1]);
        canvas.drawPath(pinPath, pinPaint);
        canvas.drawCircle(0, -26 * dp, 4.5f * dp, pinDot);
        canvas.restore();
    }

    private void drawCar(Canvas canvas, float ax, float ay) {
        toScreen(carX, carY, ax, ay, pt);
        canvas.save();
        canvas.translate(pt[0], pt[1]);
        if (Float.isNaN(carHeading)) {
            canvas.drawCircle(0, 0, 11 * dp, carPaint);
            canvas.drawCircle(0, 0, 11 * dp, carEdge);
        } else {
            canvas.rotate(carHeading - bearing);
            canvas.drawPath(carPath, carPaint);
            canvas.drawPath(carPath, carEdge);
        }
        canvas.restore();
    }

    private void drawAttribution(Canvas canvas, int w, int h) {
        float tw = textPaint.measureText(attribution);
        float pad = 4 * dp;
        float bottom = h - 2 * dp;
        float top = bottom - textPaint.getTextSize() - pad;
        canvas.drawRect(w - tw - 2 * pad, top, w, h, textBg);
        canvas.drawText(attribution, w - tw - pad, bottom - pad / 2, textPaint);
    }

    // ------------------------------------------------------------------ animação

    private void schedule() {
        scheduleIn(0);
    }

    private void scheduleIn(long delay) {
        if (frameScheduled || !isAttachedToWindow2() || !windowVisible) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delay);
    }

    private boolean isAttachedToWindow2() {
        return getWindowToken() != null;
    }

    /** Avança as animações para o instante {@code now}; true se ainda falta. */
    private boolean step(long now) {
        boolean more = false;
        if (!Double.isNaN(carToX)) {
            float t = Math.min(1f, (now - carStart) / (float) FIX_ANIM_MS);
            carX = carFromX + (carToX - carFromX) * t;
            carY = carFromY + (carToY - carFromY) * t;
            if (t < 1f) more = true;
            if (follow) {
                if (camDur > 0) {
                    // Voltando a seguir: a câmera alcança o carro, que continua andando.
                    camToX = carX;
                    camToY = carY;
                } else {
                    cx = carX;
                    cy = carY;
                }
            }
        }
        if (camDur > 0) {
            float t = ease(Math.min(1f, (now - camStart) / (float) camDur));
            cx = camFromX + (camToX - camFromX) * t;
            cy = camFromY + (camToY - camFromY) * t;
            if (t < 1f) more = true;
            else camDur = 0;
        }
        if (zoomDur > 0) {
            float t = ease(Math.min(1f, (now - zoomStart) / (float) zoomDur));
            zoom = zoomFrom + (zoomTo - zoomFrom) * t;
            setRouteWidth();
            if (t < 1f) more = true;
            else zoomDur = 0;
        }
        if (bearingDur > 0) {
            float t = Math.min(1f, (now - bearingStart) / (float) bearingDur);
            bearing = bearingFrom + (bearingTo - bearingFrom) * t;
            if (t < 1f) more = true;
            else {
                bearingDur = 0;
                bearing = (bearing % 360 + 360) % 360;
            }
        }
        if (flingX != 0 || flingY != 0) {
            panBy(flingX, flingY);
            flingX *= 0.85f;
            flingY *= 0.85f;
            if (Math.abs(flingX) < 0.5f && Math.abs(flingY) < 0.5f) {
                flingX = 0;
                flingY = 0;
            } else {
                more = true;
            }
        }
        return more;
    }

    private static float ease(float t) {
        return t < 1f ? 1f - (1f - t) * (1f - t) : 1f;
    }

    private void animateCamera(double x, double y, long ms) {
        camFromX = cx;
        camFromY = cy;
        camToX = x;
        camToY = y;
        camStart = SystemClock.uptimeMillis();
        camDur = ms;
    }

    private void animateZoom(float z, long ms) {
        z = clampZoom(z);
        if (Math.abs(z - zoom) < 0.01f) return;
        if (zoomDur > 0 && Math.abs(zoomTo - z) < 0.05f) return;
        zoomFrom = zoom;
        zoomTo = z;
        zoomStart = SystemClock.uptimeMillis();
        zoomDur = ms;
    }

    private void animateBearing(float target, long ms) {
        float from = bearing;
        float diff = (float) Geo.angleDiff(from, target);
        if (Math.abs(diff) < 0.5f) return;
        bearingFrom = from;
        bearingTo = from + diff;
        bearingStart = SystemClock.uptimeMillis();
        bearingDur = ms;
    }

    /** Zoom da navegação: mais perto devagar, mais longe na estrada. */
    private static float zoomForSpeed(float mps) {
        float kmh = mps * 3.6f;
        if (kmh < 30) return 17.3f;
        if (kmh < 60) return 16.6f;
        if (kmh < 90) return 15.9f;
        return 15.2f;
    }

    // ------------------------------------------------------------------ gestos e contas

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean a = scale.onTouchEvent(e);
        boolean b = gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return a || b || super.onTouchEvent(e);
    }

    private void panBy(float dx, float dy) {
        double scale = tilePx * Math.pow(2, zoom);
        double rad = Math.toRadians(bearing);
        double wx = (dx * Math.cos(rad) - dy * Math.sin(rad)) / scale;
        double wy = (dx * Math.sin(rad) + dy * Math.cos(rad)) / scale;
        cx = (cx + wx + 1) % 1;
        cy = Math.max(0, Math.min(1, cy + wy));
        camDur = 0;
    }

    private void zoomAround(float fx, float fy, float dz) {
        float target = clampZoom(zoom + dz);
        if (follow) {
            animateZoom(target, 300);
            schedule();
            return;
        }
        double[] before = fromScreen(fx, fy);
        double save = zoom;
        zoom = target;
        double[] after = fromScreen(fx, fy);
        zoom = (float) save;
        animateCamera(cx + before[0] - after[0], cy + before[1] - after[1], 300);
        animateZoom(target, 300);
        schedule();
    }

    private void zoomAt(float fx, float fy, float z) {
        double[] before = fromScreen(fx, fy);
        zoom = clampZoom(z);
        setRouteWidth();
        if (follow) return;
        double[] after = fromScreen(fx, fy);
        cx += before[0] - after[0];
        cy += before[1] - after[1];
    }

    private float clampZoom(float z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private void setRouteWidth() {
        float k = Math.max(0.6f, Math.min(1.2f, (zoom - 10) / 7f));
        casingPaint.setStrokeWidth(13 * dp * k);
        routePaint.setStrokeWidth(8.5f * dp * k);
    }

    private void toScreen(double wx, double wy, float ax, float ay, float[] out) {
        double scale = tilePx * Math.pow(2, zoom);
        double rad = Math.toRadians(-bearing);
        double dx = (wx - cx) * scale;
        double dy = (wy - cy) * scale;
        out[0] = (float) (ax + dx * Math.cos(rad) - dy * Math.sin(rad));
        out[1] = (float) (ay + dx * Math.sin(rad) + dy * Math.cos(rad));
    }

    private double[] fromScreen(float sx, float sy) {
        double scale = tilePx * Math.pow(2, zoom);
        double rad = Math.toRadians(bearing);
        double dx = sx - getWidth() / 2f;
        double dy = sy - getHeight() * anchorY;
        double wx = (dx * Math.cos(rad) - dy * Math.sin(rad)) / scale;
        double wy = (dx * Math.sin(rad) + dy * Math.cos(rad)) / scale;
        return new double[]{cx + wx, cy + wy};
    }

    /**
     * Mapa claro do OSM em tons escuros: inverte as cores e gira o matiz 180° (as ruas e a água
     * mantêm a cor de referência), um pouco mais apagado para a noite.
     */
    private static ColorMatrixColorFilter darkFilter() {
        ColorMatrix invert = new ColorMatrix(new float[]{
            -1, 0, 0, 0, 255,
            0, -1, 0, 0, 255,
            0, 0, -1, 0, 255,
            0, 0, 0, 1, 0});
        // Giro de matiz de 180° (matriz padrão de rotação de matiz).
        ColorMatrix hue = new ColorMatrix(new float[]{
            -0.574f, 1.430f, 0.144f, 0, 0,
            0.426f, 0.430f, 0.144f, 0, 0,
            0.426f, 1.430f, -0.856f, 0, 0,
            0, 0, 0, 1, 0});
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.45f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.8f, 0.84f, 0.92f, 1f);
        invert.postConcat(hue);
        invert.postConcat(sat);
        invert.postConcat(dim);
        return new ColorMatrixColorFilter(invert);
    }

    /** Usado pelo layout para o contraste dos controles por cima do mapa. */
    public boolean isDark() {
        return dark;
    }

    /** Cor de fundo enquanto não há tiles (combina com o tema). */
    public void setEmptyColor(int color) {
        emptyPaint.setColor(color);
        invalidate();
    }
}
