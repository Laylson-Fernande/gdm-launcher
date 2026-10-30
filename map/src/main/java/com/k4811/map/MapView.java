package com.k4811.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mapa OpenStreetMap desenhado à mão: tiles (com zoom fracionado e rotação), a rota, o destino e a
 * seta do carro. Sem bibliotecas.
 *
 * Câmera: centro em coordenadas de mundo (ver {@link Geo}), zoom e rumo (o rumo que fica para cima).
 * Seguindo o carro, o mapa gira com ele (direção do carro para cima) ou fica com o norte para cima,
 * conforme a bússola ({@link #setHeadingUp}). No modo navegação com a direção para cima, o carro fica
 * no terço de baixo, como no Waze, e o zoom acompanha a velocidade e, com o zoom dinâmico, a distância
 * até a próxima manobra ou alerta. Animações a ~30 quadros por segundo, só enquanto algo se mexe
 * (central de 1,5 GHz).
 *
 * Perspectiva ({@link #setPerspective}): com a direção para cima, o chão inclina como numa câmera atrás
 * do carro olhando para a frente (como no Waze). É uma projeção sobre o desenho plano: "plano" =
 * pixels do mapa já girados, relativos à âncora (x para a direita, y para baixo); na tela,
 * {@code (x / w, y·cos θ / w)} com {@code w = 1 − y·sen θ / D}. Perto do carro a escala é a mesma do
 * mapa plano; à frente as coisas diminuem. Os tiles longe (escala < {@link #FAR_SCALE}) vêm do zoom de
 * cima, para não carregar o dobro de tiles por um detalhe que não se vê.
 */
public class MapView extends View implements Tiles.Listener {

    public interface Listener {
        /** O motorista arrastou o mapa (a câmera parou de seguir o carro) ou voltou a seguir. */
        void onFollowChanged(boolean following);

        /** Toque longo num ponto do mapa. */
        void onLongPress(double lat, double lon);

        /** O rumo do mapa (graus, o que fica para cima) mudou: a bússola gira junto. */
        void onBearingChanged(float bearing);

        /** Toque perto de uma das rotas alternativas da prévia ({@link #setAlternatives}). */
        void onRouteTap(Route r);
    }

    private static final float MIN_ZOOM = 3;
    private static final float MAX_ZOOM = 20;
    private static final long FRAME_MS = 30;
    /** Espera entre quadros animados (entre 1 e 2 vsyncs de 60 Hz). */
    private static final long FRAME_SKIP_MS = 20;
    private static final long FIX_ANIM_MS = 950;
    /** Zoom dinâmico: o mais perto que chega numa manobra ou alerta. */
    private static final float NAV_NEAR_ZOOM = 17.8f;
    /** Zoom dinâmico: quanto afasta além do zoom da velocidade quando o próximo evento está longe. */
    private static final float NAV_FAR_EXTRA = 0.7f;
    /** Zoom mudado à mão navegando: o automático espera este tempo para voltar. */
    private static final long MANUAL_ZOOM_MS = 15000;
    /** Inclinação da perspectiva (graus a partir da vista de cima). */
    private static final float TILT_DEG = 42;
    /** Distância da câmera, em alturas da View: menor = perspectiva mais forte. */
    private static final float TILT_DISTANCE = 1.5f;
    /** Tile que aparece com menos que esta escala vem do zoom de cima (metade da resolução). */
    private static final float FAR_SCALE = 0.62f;
    private static final long TILT_ANIM_MS = 600;

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
    /** Seguindo o carro: true = direção do carro para cima; false = norte para cima. */
    private boolean headingUp = true;
    private boolean autoZoom = true;
    /** Metros até a próxima manobra ou alerta (o que vier antes); NaN = nada à frente. */
    private double focusAhead = Double.NaN;
    private long manualZoomUntil;
    private float notifiedBearing;
    /** Perspectiva ligada (opção); vale com a direção para cima e fora da prévia da rota. */
    private boolean perspective = true;
    /** Mostrando a rota inteira (prévia): sem perspectiva até voltar a seguir o carro. */
    private boolean overview;
    /** Inclinação atual, 0 (de cima) a 1 ({@link #TILT_DEG}), e a projeção dela (ver {@link #setTiltValues}). */
    private float tilt;
    private float tiltFrom;
    private float tiltTo;
    private long tiltStart;
    private float tiltCos = 1;
    /** sen θ / D: quanto {@code w} cresce por pixel à frente. */
    private float tiltK;
    private final Matrix tiltMatrix = new Matrix();
    private final float[] tiltValues = new float[9];
    /** Retângulo da tela no plano (trapézio), para escolher os tiles: x0,y0 … x3,y3. */
    private final float[] ground = new float[8];
    private long[] farKeys = new long[32];
    private Shader haze;
    private int hazeColor;
    private int hazeHeight;
    private final Paint hazePaint = new Paint();

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
    /** Prévia com mais de uma rota: as outras, desenhadas em cinza por baixo da escolhida. */
    private List<Route> alternatives = Collections.emptyList();
    private int routeFrom;
    /** Navegando: a linha é dividida na posição do carro (percorrido mais claro, o resto em azul). */
    private boolean routeProgress;
    private double destX = Double.NaN;
    private double destY;
    private float[] lines = new float[0];

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Tiles do mapa próprio: já vêm no tema, sem o filtro escuro. */
    private final Paint vectorPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint emptyPaint = new Paint();
    private final Paint casingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Trecho já percorrido da rota: mais claro, como no Waze. */
    private final Paint passedCasing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint passedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altCasing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    /** Atribuição na tela: a do OpenStreetMap ou, no modo Google, a do viewport do Google. */
    private String attribution;
    private final String osmAttribution;
    private boolean google;
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
        osmAttribution = c.getString(R.string.map_attribution);
        attribution = osmAttribution;

        emptyPaint.setColor(0xFFE8E4DC);
        casingPaint.setStyle(Paint.Style.STROKE);
        casingPaint.setStrokeCap(Paint.Cap.ROUND);
        casingPaint.setColor(0xFF0B3F8C);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setColor(0xFF3D8BFF);
        passedCasing.setStyle(Paint.Style.STROKE);
        passedCasing.setStrokeCap(Paint.Cap.ROUND);
        passedPaint.setStyle(Paint.Style.STROKE);
        passedPaint.setStrokeCap(Paint.Cap.ROUND);
        setPassedColors();
        altCasing.setStyle(Paint.Style.STROKE);
        altCasing.setStrokeCap(Paint.Cap.ROUND);
        altCasing.setColor(0xFF5F6B7A);
        altPaint.setStyle(Paint.Style.STROKE);
        altPaint.setStrokeCap(Paint.Cap.ROUND);
        altPaint.setColor(0xFFA9B7C9);
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
                panBy(e2.getX(), e2.getY(), dx, dy);
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
                markManualZoom();
                zoomAround(follow ? getWidth() / 2f : e.getX(), follow ? getHeight() * anchorY : e.getY(), 1);
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                Route hit = alternativeAt(e.getX(), e.getY());
                if (hit == null || listener == null) return false;
                listener.onRouteTap(hit);
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
                markManualZoom();
                float fx = follow ? getWidth() / 2f : d.getFocusX();
                float fy = follow ? getHeight() * anchorY : d.getFocusY();
                zoomAt(fx, fy, zoom + dz);
                zoomDur = 0;
                invalidate();
                return true;
            }
        });
        tilt = tiltTo = perspective && headingUp ? 1f : 0f;
        anchorY = navAnchor();
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
        tiles.setDark(on);
        emptyPaint.setColor(on ? 0xFF1B1E22 : 0xFFE8E4DC);
        textPaint.setColor(on ? 0xFFCCCCCC : 0xFF333333);
        textBg.setColor(on ? 0x99000000 : 0xB3FFFFFF);
        setPassedColors();
        invalidate();
    }

    /**
     * Modo Google (tiles do Google no lugar do OpenStreetMap e do mapa offline) ou OpenStreetMap.
     * Ver {@link Tiles#setGoogle}.
     */
    public void setGoogle(boolean on) {
        // O Tiles é um só no processo: confere também o estado dele (outra tela pode ter mudado).
        if (google == on && tiles.isGoogle() == on) return;
        google = on;
        tiles.setGoogle(on);
        attribution = on ? "Google" : osmAttribution;
        invalidate();
    }

    public boolean isGoogle() {
        return google;
    }

    /** Texto de atribuição do Google para a área na tela (do viewport); null mantém o atual. */
    public void setGoogleCopyright(String text) {
        if (!google || text == null || text.isEmpty()) return;
        attribution = text.contains("Google") ? text : "Google · " + text;
        invalidate();
    }

    /**
     * Retângulo aproximado do que está na tela {norte, sul, leste, oeste} em graus (o círculo que
     * contém a tela, então vale com o mapa girado). Para o viewport do Google.
     */
    public double[] visibleBounds() {
        double scale = tilePx * Math.pow(2, zoom);
        double r = Math.hypot(getWidth(), getHeight()) / 2 / scale;
        double north = Geo.lat(Math.max(0, cy - r));
        double south = Geo.lat(Math.min(1, cy + r));
        double west = (cx - r) * 360 - 180;
        double east = (cx + r) * 360 - 180;
        return new double[]{north, south, Math.min(180, east), Math.max(-180, west)};
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
        if (on) setOverview(false);
        if (on && !Double.isNaN(carX)) {
            animateCamera(carX, carY, 600);
            animateBearing(followBearing(), 600);
        }
        schedule();
    }

    /** Rumo da câmera seguindo o carro: o do carro (direção para cima) ou 0 (norte para cima). */
    private float followBearing() {
        if (!headingUp) return 0;
        return Float.isNaN(carHeading) ? bearing : carHeading;
    }

    private void setFollowInternal(boolean on) {
        if (follow == on) return;
        follow = on;
        if (listener != null) listener.onFollowChanged(on);
    }

    /**
     * Modo navegação: zoom pela velocidade (e pela próxima manobra, com o zoom dinâmico) e, com a
     * direção para cima, o carro no terço de baixo. Fora dele: carro no meio (ou embaixo, com perspectiva).
     */
    public void setNavigating(boolean on) {
        navigating = on;
        manualZoomUntil = 0;
        overview = false;
        updateTilt();
        anchorY = navAnchor();
        animateBearing(followBearing(), 500);
        if (on) animateZoom(17, 600);
        schedule();
    }

    /** Altura do carro na tela: no terço de baixo navegando ou com perspectiva (direção para cima). */
    private float navAnchor() {
        return headingUp && (navigating || tiltTo > 0) ? 0.72f : 0.5f;
    }

    /** Bússola: direção do carro para cima (true) ou norte para cima (false). */
    public void setHeadingUp(boolean on) {
        if (headingUp == on) return;
        headingUp = on;
        updateTilt();
        // Norte para cima vale também com o mapa solto; direção para cima só seguindo o carro.
        if (follow || !on) animateBearing(followBearing(), 500);
        schedule();
    }

    public boolean isHeadingUp() {
        return headingUp;
    }

    /** Visão em perspectiva (câmera atrás do carro) com a direção para cima. */
    public void setPerspective(boolean on) {
        if (perspective == on) return;
        perspective = on;
        updateTilt();
        schedule();
    }

    private void setOverview(boolean on) {
        if (overview == on) return;
        overview = on;
        updateTilt();
    }

    /**
     * Anima a inclinação até a que vale agora (perspectiva, direção para cima, fora da prévia) e, seguindo
     * o carro, põe ele na altura certa.
     */
    private void updateTilt() {
        float target = perspective && headingUp && !overview ? 1f : 0f;
        if (target != tiltTo) {
            tiltFrom = tilt;
            tiltTo = target;
            tiltStart = SystemClock.uptimeMillis();
            schedule();
        }
        if (follow) anchorY = navAnchor();
    }

    /** Projeção da inclinação {@code t} (0..1) para a altura atual da View. */
    private void setTiltValues(float t) {
        tilt = t;
        double th = Math.toRadians(TILT_DEG * t);
        tiltCos = (float) Math.cos(th);
        float d = Math.max(1, getHeight()) * TILT_DISTANCE;
        tiltK = (float) (Math.sin(th) / d);
        // (x, y, 1) → (x, y·cos, 1 − y·k)
        tiltValues[Matrix.MSCALE_X] = 1;
        tiltValues[Matrix.MSKEW_X] = 0;
        tiltValues[Matrix.MTRANS_X] = 0;
        tiltValues[Matrix.MSKEW_Y] = 0;
        tiltValues[Matrix.MSCALE_Y] = tiltCos;
        tiltValues[Matrix.MTRANS_Y] = 0;
        tiltValues[Matrix.MPERSP_0] = 0;
        tiltValues[Matrix.MPERSP_1] = -tiltK;
        tiltValues[Matrix.MPERSP_2] = 1;
        tiltMatrix.setValues(tiltValues);
    }

    /** y no plano de um y da tela (relativos à âncora); acima do horizonte, o ponto mais longe possível. */
    private float flatY(float dy) {
        float den = Math.max(0.05f, tiltCos + dy * tiltK);
        return dy / den;
    }

    /** Zoom dinâmico navegando (aproxima perto da manobra/alerta, afasta quando estão longe). */
    public void setAutoZoom(boolean on) {
        autoZoom = on;
    }

    /** Metros até a próxima manobra ou alerta, o que vier antes; NaN se não há nada à frente. */
    public void setFocusAhead(double meters) {
        focusAhead = meters;
    }

    public void zoomBy(float dz) {
        markManualZoom();
        zoomAround(getWidth() / 2f, getHeight() * anchorY, dz);
    }

    /** Navegando, o zoom à mão vale por um tempo antes de o automático voltar. */
    private void markManualZoom() {
        if (navigating) manualZoomUntil = SystemClock.uptimeMillis() + MANUAL_ZOOM_MS;
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
            if (headingUp && !Float.isNaN(carHeading)) animateBearing(carHeading, FIX_ANIM_MS);
            if (navigating && now >= manualZoomUntil) animateZoom(navZoom(speedMps), 2500);
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
        routeProgress = false;
        if (r != null) {
            destX = Geo.worldX(r.lon[r.size() - 1]);
            destY = Geo.worldY(r.lat[r.size() - 1]);
            ensureLines(r);
        }
        invalidate();
    }

    /** As outras rotas da prévia (vazia ou null: nenhuma). */
    public void setAlternatives(List<Route> list) {
        alternatives = list == null ? Collections.<Route>emptyList() : list;
        for (Route r : alternatives) ensureLines(r);
        invalidate();
    }

    /** Espaço para os segmentos da rota e mais dois pontos (a divisão na posição do carro). */
    private void ensureLines(Route r) {
        int need = (r.size() + 2) * 4;
        if (lines.length < need) lines = new float[need];
    }

    /** A alternativa a até ~28 dp do toque (a mais perto), ou null. */
    private Route alternativeAt(float x, float y) {
        if (alternatives.isEmpty()) return null;
        float ax = getWidth() / 2f, ay = getHeight() * anchorY;
        Route best = null;
        double bestD = 28 * dp;
        for (Route r : alternatives) {
            float px = Float.NaN, py = 0;
            for (int i = 0; i < r.size(); i++) {
                toScreen(r.wx[i], r.wy[i], ax, ay, pt);
                if (!Float.isNaN(px)) {
                    double d = segmentDistance(x, y, px, py, pt[0], pt[1]);
                    if (d < bestD) {
                        bestD = d;
                        best = r;
                    }
                }
                px = pt[0];
                py = pt[1];
            }
        }
        return best;
    }

    private static double segmentDistance(float x, float y, float ax, float ay, float bx, float by) {
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2));
        return Math.hypot(x - (ax + dx * t), y - (ay + dy * t));
    }

    /**
     * Navegando: o carro está no trecho que começa no ponto {@code fromIndex} da rota. A linha é
     * dividida na posição do carro a cada quadro (a seta anima entre as posições do GPS e a divisão vai
     * junto): o que ficou para trás sai mais claro, como no Waze, e o resto continua azul.
     */
    public void setRouteProgress(int fromIndex) {
        if (fromIndex == routeFrom && routeProgress) return;
        routeFrom = fromIndex;
        routeProgress = true;
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
        List<Route> all = new ArrayList<>(alternatives);
        all.add(r);
        for (Route q : all) {
            for (int i = 0; i < q.size(); i++) {
                minX = Math.min(minX, q.wx[i]);
                maxX = Math.max(maxX, q.wx[i]);
                minY = Math.min(minY, q.wy[i]);
                maxY = Math.max(maxY, q.wy[i]);
            }
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
        setOverview(true);
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
        setTiltValues(tilt);
        boolean tilted = tilt > 0.001f;
        float ax = w / 2f;
        float ay = h * anchorY;
        int z = Math.max(2, Math.min(maxTileZoom, Math.round(zoom)));
        float s = (float) Math.pow(2, zoom - z);
        int n = 1 << z;
        double worldPx = (double) tilePx * n;
        double cpx = cx * worldPx;
        double cpy = cy * worldPx;
        // A tela no plano: um retângulo (sem inclinação) ou um trapézio que abre para a frente.
        groundQuad(w, h, ax, ay);
        float r = 0;
        for (int i = 0; i < 8; i += 2) r = Math.max(r, (float) Math.hypot(ground[i], ground[i + 1]));
        r = r / s + 1;
        int x0 = (int) Math.floor((cpx - r) / tilePx);
        int x1 = (int) Math.floor((cpx + r) / tilePx);
        int y0 = Math.max(0, (int) Math.floor((cpy - r) / tilePx));
        int y1 = Math.min(n - 1, (int) Math.floor((cpy + r) / tilePx));
        double rad = Math.toRadians(-bearing);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float half = tilePx * s / 2f;
        float halfDiag = half * 1.415f;

        canvas.drawColor(emptyPaint.getColor());
        canvas.save();
        canvas.translate(ax, ay);
        if (tilted) canvas.concat(tiltMatrix);
        canvas.rotate(-bearing);
        canvas.scale(s, s);
        // Primeiro os tiles longe, do zoom de cima (por baixo); depois os de perto, por cima deles.
        int far = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int ty = y0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++) {
                    float left = (float) (tx * (double) tilePx - cpx);
                    float top = (float) (ty * (double) tilePx - cpy);
                    // Centro do tile no plano (girado e na escala da tela).
                    float lx = (left + tilePx / 2f) * s;
                    float ly = (top + tilePx / 2f) * s;
                    float fx = lx * cos - ly * sin;
                    float fy = lx * sin + ly * cos;
                    if (!nearGround(fx, fy, halfDiag)) continue;
                    // Tile longe (pequeno na tela): o pai, uma vez só, na primeira passada.
                    boolean isFar = tilted && z > 3 && (1 - (fy + half) * tiltK) * FAR_SCALE > 1;
                    if (pass == 0) {
                        if (!isFar) continue;
                        long k = Tiles.key(z - 1, tx >> 1, ty >> 1);
                        boolean seen = false;
                        for (int i = 0; i < far && !seen; i++) seen = farKeys[i] == k;
                        if (seen) continue;
                        if (far == farKeys.length) farKeys = java.util.Arrays.copyOf(farKeys, far * 2);
                        farKeys[far++] = k;
                        float pl = (float) ((tx >> 1) * 2.0 * tilePx - cpx);
                        float pt0 = (float) ((ty >> 1) * 2.0 * tilePx - cpy);
                        drawTile(canvas, z - 1, tx >> 1, ty >> 1, pl, pt0, tilePx * 2);
                    } else {
                        if (isFar) continue;
                        drawTile(canvas, z, tx, ty, left, top, tilePx);
                    }
                }
            }
        }
        canvas.restore();

        if (tilted) drawHaze(canvas, w, h);
        drawRoute(canvas, ax, ay);
        if (!Double.isNaN(destX)) drawPin(canvas, ax, ay);
        if (!Double.isNaN(carX)) drawCar(canvas, ax, ay);
        drawAttribution(canvas, w, h);
    }

    /** Um tile (ou, sem ele, o pedaço de um pai em memória) em {@code size} pixels do plano do zoom. */
    private void drawTile(Canvas canvas, int z, int tx, int ty, float left, float top, float size) {
        int n = 1 << z;
        int wx = ((tx % n) + n) % n;
        dst.set(left, top, left + size + 0.6f, top + size + 0.6f);
        Bitmap b = tiles.get(z, wx, ty);
        if (b != null) {
            canvas.drawBitmap(b, null, dst, tiles.isVector(b) ? vectorPaint : tilePaint);
        } else {
            drawParent(canvas, z, wx, ty);
        }
    }

    /** Os 4 cantos da tela (com uma margem) no plano, relativos à âncora, em {@link #ground}. */
    private void groundQuad(int w, int h, float ax, float ay) {
        float m = 8 * dp;
        float top = flatY(-ay - m);
        float bottom = flatY(h - ay + m);
        float wt = 1 - top * tiltK;
        float wb = 1 - bottom * tiltK;
        float l = -ax - m;
        float rr = w - ax + m;
        ground[0] = l * wt;
        ground[1] = top;
        ground[2] = rr * wt;
        ground[3] = top;
        ground[4] = rr * wb;
        ground[5] = bottom;
        ground[6] = l * wb;
        ground[7] = bottom;
    }

    /** O ponto (fx, fy) do plano está a até {@code pad} do trapézio da tela. */
    private boolean nearGround(float fx, float fy, float pad) {
        for (int i = 0; i < 8; i += 2) {
            float ex = ground[(i + 2) % 8] - ground[i];
            float ey = ground[(i + 3) % 8] - ground[i + 1];
            float len = (float) Math.hypot(ex, ey);
            if (len == 0) continue;
            // Cantos no sentido horário na tela (y para baixo): dentro = produto vetorial positivo.
            float cross = (ex * (fy - ground[i + 1]) - ey * (fx - ground[i])) / len;
            if (cross < -pad) return false;
        }
        return true;
    }

    /** Névoa no alto da tela, onde o chão vai longe: some com o detalhe pequeno demais. */
    private void drawHaze(Canvas canvas, int w, int h) {
        int color = emptyPaint.getColor();
        int height = Math.round(h * 0.28f);
        if (haze == null || hazeColor != color || hazeHeight != height) {
            hazeColor = color;
            hazeHeight = height;
            haze = new LinearGradient(0, 0, 0, height, (color & 0xFFFFFF) | 0xE6000000, color & 0xFFFFFF,
                Shader.TileMode.CLAMP);
            hazePaint.setShader(haze);
        }
        hazePaint.setAlpha(Math.round(255 * tilt));
        canvas.drawRect(0, 0, w, height, hazePaint);
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
            canvas.drawBitmap(p, src, dst, tiles.isVector(p) ? vectorPaint : tilePaint);
            return;
        }
    }

    private void drawRoute(Canvas canvas, float ax, float ay) {
        // As alternativas por baixo, a escolhida por cima.
        for (Route alt : alternatives) {
            drawLine(canvas, alt, 0, alt.size() - 1, Double.NaN, 0, Double.NaN, 0, ax, ay, altCasing, altPaint);
        }
        Route rt = route;
        if (rt == null) return;
        int last = rt.size() - 1;
        if (!routeProgress) {
            drawLine(canvas, rt, 0, last, Double.NaN, 0, Double.NaN, 0, ax, ay, casingPaint, routePaint);
            return;
        }
        // Divide no ponto da linha mais perto da seta (na posição animada, não só na do último GPS).
        double[] at = splitPoint(rt);
        int seg = (int) at[0];
        double sx = at[1];
        double sy = at[2];
        // Percorrido (do começo até o carro), por baixo; o que falta (do carro ao destino), por cima.
        drawLine(canvas, rt, 0, seg, Double.NaN, 0, sx, sy, ax, ay, passedCasing, passedPaint);
        drawLine(canvas, rt, seg + 1, last, sx, sy, Double.NaN, 0, ax, ay, casingPaint, routePaint);
    }

    /**
     * Onde a linha da rota é dividida: {trecho, x, y do mundo}. A seta do carro projetada nos trechos em
     * volta de {@link #routeFrom}; com a seta longe da linha (fora da rota, sem posição), o começo do
     * trecho atual, como antes.
     */
    private double[] splitPoint(Route rt) {
        int n = rt.size();
        int base = Math.max(0, Math.min(routeFrom, n - 2));
        double[] out = {base, rt.wx[base], rt.wy[base]};
        if (Double.isNaN(carX) || n < 2) return out;
        double best = Double.MAX_VALUE;
        for (int i = Math.max(0, base - 4); i <= Math.min(n - 2, base + 4); i++) {
            double ax = rt.wx[i], ay = rt.wy[i], bx = rt.wx[i + 1], by = rt.wy[i + 1];
            double t = Geo.project(carX, carY, ax, ay, bx, by);
            double qx = ax + (bx - ax) * t;
            double qy = ay + (by - ay) * t;
            double d = Math.hypot(carX - qx, carY - qy);
            // Empate: fica com o trecho mais à frente (a seta passou do ponto).
            if (d < best || (d == best && i > out[0])) {
                best = d;
                out[0] = i;
                out[1] = qx;
                out[2] = qy;
            }
        }
        // Mais de ~30 m da linha: fora da rota (o recálculo vem); divide no começo do trecho atual.
        if (best * Geo.metersPerWorld(Geo.lat(carY)) > 30) {
            out[0] = base;
            out[1] = rt.wx[base];
            out[2] = rt.wy[base];
        }
        return out;
    }

    /**
     * Linha da rota do ponto {@code start} ao {@code end} (inclusive). {@code headX/headY} (mundo), se não
     * for NaN, entra antes de {@code start}; {@code tailX/tailY}, depois de {@code end}: as pontas da
     * divisão na posição do carro.
     */
    private void drawLine(Canvas canvas, Route rt, int start, int end, double headX, double headY,
                          double tailX, double tailY, float ax, float ay, Paint casing, Paint paint) {
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
        // Com perspectiva, o trecho que passa do alto da tela (rumo ao horizonte) é cortado no plano.
        float farY = tiltK > 0 ? flatY(-ay - margin) : Float.NEGATIVE_INFINITY;
        // Último ponto usado, no plano e na tela (a tela só vale se ele não foi cortado).
        float pfx = 0;
        float pfy = 0;
        boolean head = !Double.isNaN(headX);
        boolean tail = !Double.isNaN(tailX);
        int from = Math.max(0, start);
        int to = Math.min(end, rt.size() - 1);
        // Índices "virtuais": from - 1 é a ponta de cima (head), to + 1 a de baixo (tail).
        int first = head ? from - 1 : from;
        int last = tail ? to + 1 : to;
        for (int i = first; i <= last; i++) {
            double wx;
            double wy;
            if (head && i == from - 1) {
                wx = headX;
                wy = headY;
            } else if (tail && i == to + 1) {
                wx = tailX;
                wy = tailY;
            } else {
                wx = rt.wx[i];
                wy = rt.wy[i];
            }
            float dx = (float) ((wx - cx) * scale);
            float dy = (float) ((wy - cy) * scale);
            float fx = dx * cos - dy * sin;
            float fy = dx * sin + dy * cos;
            if (!have) {
                pfx = fx;
                pfy = fy;
                pt[0] = fx;
                pt[1] = fy;
                project(pt);
                px = ax + pt[0];
                py = ay + pt[1];
                have = true;
                continue;
            }
            if (fy < farY && pfy < farY) {
                // Os dois lá na frente, depois do alto da tela.
                pfx = fx;
                pfy = fy;
                continue;
            }
            float sx0 = px;
            float sy0 = py;
            if (pfy < farY) {
                pt[0] = pfx + (fx - pfx) * (farY - pfy) / (fy - pfy);
                pt[1] = farY;
                project(pt);
                sx0 = ax + pt[0];
                sy0 = ay + pt[1];
            }
            if (fy < farY) {
                pt[0] = pfx + (fx - pfx) * (farY - pfy) / (fy - pfy);
                pt[1] = farY;
            } else {
                pt[0] = fx;
                pt[1] = fy;
            }
            project(pt);
            float sx = ax + pt[0];
            float sy = ay + pt[1];
            // Pontos colados não mudam nada na tela: pula (menos o último).
            if (pfy >= farY && i < last && Math.abs(sx - sx0) < minStep && Math.abs(sy - sy0) < minStep) {
                continue;
            }
            pfx = fx;
            pfy = fy;
            px = sx;
            py = sy;
            // Trecho inteiro fora da tela (os dois pontos do mesmo lado): não manda desenhar.
            boolean out = (sx < -margin && sx0 < -margin) || (sx > right && sx0 > right)
                || (sy < -margin && sy0 < -margin) || (sy > bottom && sy0 > bottom);
            if (out) continue;
            lines[count++] = sx0;
            lines[count++] = sy0;
            lines[count++] = sx;
            lines[count++] = sy;
        }
        if (count == 0) return;
        canvas.drawLines(lines, 0, count, casing);
        canvas.drawLines(lines, 0, count, paint);
    }

    /** Ponto do plano (relativo à âncora) → tela (relativo à âncora), em {@code p}. */
    private void project(float[] p) {
        float w = Math.max(0.05f, 1 - p[1] * tiltK);
        p[0] = p[0] / w;
        p[1] = p[1] * tiltCos / w;
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
            canvas.rotate(screenAngle(carHeading - bearing, pt[0] - ax, pt[1] - ay));
            canvas.drawPath(carPath, carPaint);
            canvas.drawPath(carPath, carEdge);
        }
        canvas.restore();
    }

    /**
     * Ângulo na tela de uma direção {@code angle} (graus, já relativa ao rumo do mapa) que sai do ponto
     * (sx, sy) da tela (relativo à âncora): com perspectiva, as direções "abrem" para os lados.
     */
    private float screenAngle(float angle, float sx, float sy) {
        if (tiltK <= 0) return angle;
        float fy = flatY(sy);
        float fx = sx * (1 - fy * tiltK);
        double a = Math.toRadians(angle);
        float step = 20 * dp;
        pt[0] = fx + (float) Math.sin(a) * step;
        pt[1] = fy - (float) Math.cos(a) * step;
        project(pt);
        return (float) Math.toDegrees(Math.atan2(pt[0] - sx, sy - pt[1]));
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
            if (listener != null && Math.abs(Geo.angleDiff(notifiedBearing, bearing)) >= 1) {
                notifiedBearing = bearing;
                listener.onBearingChanged(bearing);
            }
        }
        if (tilt != tiltTo) {
            float t = ease(Math.min(1f, (now - tiltStart) / (float) TILT_ANIM_MS));
            tilt = tiltFrom + (tiltTo - tiltFrom) * t;
            if (t < 1f) more = true;
            else tilt = tiltTo;
        }
        if (flingX != 0 || flingY != 0) {
            panBy(getWidth() / 2f, getHeight() * anchorY, flingX, flingY);
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

    /**
     * Zoom da navegação. Sem o zoom dinâmico: só pela velocidade. Com ele: um pouco mais longe que o
     * da velocidade quando a próxima manobra (ou alerta) está longe e, chegando perto, o zoom em que
     * ela cabe na parte da tela à frente do carro, até {@link #NAV_NEAR_ZOOM}. Em passos de 0,1 para
     * não refazer a animação por diferenças que não se veem.
     */
    private float navZoom(float mps) {
        float base = zoomForSpeed(mps);
        if (!autoZoom) return base;
        float far = base - NAV_FAR_EXTRA;
        double d = focusAhead;
        if (Double.isNaN(d) || getHeight() == 0 || Double.isNaN(carToY)) return far;
        // Espaço à frente do carro: com a direção para cima, do carro até embaixo da faixa da manobra;
        // com o norte para cima a manobra pode estar para qualquer lado, então o menor meio da tela.
        float room = headingUp ? getHeight() * anchorY - 140 * dp : Math.min(getWidth(), getHeight()) / 2f - 40 * dp;
        room = Math.max(80 * dp, room);
        // Com perspectiva cabe mais chão à frente na mesma altura de tela (na inclinação de destino).
        if (headingUp && tiltTo > 0) {
            float save = tilt;
            setTiltValues(tiltTo);
            room = -flatY(-room);
            setTiltValues(save);
        }
        room *= 0.8f;
        double perWorld = Geo.metersPerWorld(Geo.lat(carToY));
        double fit = Math.log(room * perWorld / (tilePx * Math.max(60, d))) / Math.log(2);
        float z = (float) Math.max(far, Math.min(NAV_NEAR_ZOOM, fit));
        return Math.round(z * 10) / 10f;
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

    /**
     * Arrasto de (sx + dx, sy + dy) para (sx, sy) na tela: o ponto do mapa que estava sob o dedo vai
     * junto (com perspectiva, o mesmo arrasto anda mais lá na frente).
     */
    private void panBy(float sx, float sy, float dx, float dy) {
        double[] a = fromScreen(sx + dx, sy + dy);
        double[] b = fromScreen(sx, sy);
        cx = (cx + a[0] - b[0] + 1) % 1;
        cy = Math.max(0, Math.min(1, cy + a[1] - b[1]));
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
        passedCasing.setStrokeWidth(12 * dp * k);
        passedPaint.setStrokeWidth(8 * dp * k);
        altCasing.setStrokeWidth(11 * dp * k);
        altPaint.setStrokeWidth(7 * dp * k);
    }

    /** Percorrido: azul claro acinzentado (no escuro, mais apagado, para não brilhar mais que a rota). */
    private void setPassedColors() {
        passedCasing.setColor(dark ? 0xFF3A4A60 : 0xFF8FA9CC);
        passedPaint.setColor(dark ? 0xFF6F86A6 : 0xFFC4D7F2);
    }

    private void toScreen(double wx, double wy, float ax, float ay, float[] out) {
        double scale = tilePx * Math.pow(2, zoom);
        double rad = Math.toRadians(-bearing);
        double dx = (wx - cx) * scale;
        double dy = (wy - cy) * scale;
        out[0] = (float) (dx * Math.cos(rad) - dy * Math.sin(rad));
        out[1] = (float) (dx * Math.sin(rad) + dy * Math.cos(rad));
        project(out);
        out[0] += ax;
        out[1] += ay;
    }

    private double[] fromScreen(float sx, float sy) {
        double scale = tilePx * Math.pow(2, zoom);
        double rad = Math.toRadians(bearing);
        double dy = flatY(sy - getHeight() * anchorY);
        double dx = (sx - getWidth() / 2f) * (1 - dy * tiltK);
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
