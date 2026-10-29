package com.k4811.launcher.waze;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.car.app.ISurfaceCallback;

/**
 * O Waze dentro da tela de navegação, no lugar do mapa integrado: o launcher faz o papel do
 * Android Auto. Liga no modo carro do Waze modificado (WazeCarAppService, que aceita qualquer
 * host), entrega a superfície desta view para o Waze desenhar o mapa e desenha os templates que
 * ele manda (Overlay). A manobra e a chegada vão para o cartão do painel (RouteListener).
 *
 * Só com o Waze modificado do projeto recompile-optimized (validador de host liberado, tela
 * com.k4811.wazeui e o receiver de comandos). Android 8+ (o Waze pede 10+).
 */
@TargetApi(26)
public final class WazeScreen extends FrameLayout implements CarSession.Listener, Overlay.Host {
    private static final String CAR_SERVICE = "com.waze.car_lib.WazeCarAppService";
    /** Tela própria injetada no Waze: marca o Waze modificado. */
    private static final String MODIFIED_UI = "com.k4811.wazeui.MainActivity";
    /** Receiver injetado no Waze que executa comandos (encerrar a navegação). */
    private static final String COMMANDS = "com.k4811.wazeui.CommandReceiver";
    public static final String ACTION_COMMAND = "com.k4811.action.NAV_COMMAND";
    private static final int ICON_PX = 96;

    /** Rota no formato do broadcast Apps.ACTION_NAV_STATE (os mesmos extras). */
    public interface RouteListener {
        void onRoute(Intent state);
    }

    /** O Waze modificado está instalado (e o Android aguenta o modo carro dele). */
    public static boolean available(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            c.getPackageManager().getActivityInfo(new ComponentName(WazeUi.WAZE, MODIFIED_UI), 0);
            c.getPackageManager().getServiceInfo(new ComponentName(WazeUi.WAZE, CAR_SERVICE), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private final CarSession session;
    private final Overlay overlay;
    private final SurfaceView map;
    private final FrameLayout layer;
    private RouteListener routeListener;
    private boolean connected;
    private boolean navigating;
    private String lastRouteKey;

    private ISurfaceCallback surfaceCallback;
    private SurfaceHolder holder;
    private int surfaceW, surfaceH;
    private boolean surfaceSent;
    private Rect mapArea;
    private boolean mapShown = true;
    private int stackDepth = 1;

    public WazeScreen(Context c) {
        super(c);
        WazeUi.setScale(getResources().getDisplayMetrics().density);
        Texts.init(c);
        WazeUi.dark = com.k4811.common.config.LauncherConfig.darkMode(c);
        setBackgroundColor(WazeUi.screen());
        map = new SurfaceView(c);
        // O Waze desenha o mapa com EGL RGBA_8888 (a superfície do Android Auto); 565 dá EGL_BAD_MATCH.
        map.getHolder().setFormat(PixelFormat.RGBA_8888);
        map.getHolder().addCallback(surfaceHolderCallback);
        map.setOnTouchListener(this::onMapTouch);
        addView(map, new LayoutParams(-1, -1));
        layer = new FrameLayout(c);
        addView(layer, new LayoutParams(-1, -1));
        overlay = new Overlay(c, layer, this);
        // A manobra e a chegada ficam no cartão do painel: o mapa fica livre.
        overlay.setRouteElsewhere(true);
        overlay.status(Texts.connecting());
        session = new CarSession(c, new ComponentName(WazeUi.WAZE, CAR_SERVICE), this);
    }

    public void setRouteListener(RouteListener l) {
        routeListener = l;
    }

    // ---- ciclo de vida (chamado pela tela de navegação) ----

    public void onStart() {
        if (!connected && getWidth() > 0) connect();
        session.resume();
    }

    public void onStop() {
        session.pause();
    }

    public void onDestroy() {
        session.disconnect();
        connected = false;
    }

    /** Voltar: fecha a tela de cima do Waze; false na primeira (a tela de navegação decide). */
    public boolean onBackPressed() {
        if (stackDepth <= 1) return false;
        session.back();
        return true;
    }

    public boolean isNavigating() {
        return navigating;
    }

    /** Tema da suíte mudou (dia/noite): o mapa do Waze e os cartões acompanham. */
    public void setDark(boolean dark) {
        if (dark == WazeUi.dark) return;
        WazeUi.dark = dark;
        setBackgroundColor(WazeUi.screen());
        if (connected) session.configurationChanged(carConfig());
        overlay.redraw();
    }

    private void connect() {
        connected = true;
        session.connect(carConfig());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        if (!connected) post(this::connect);
        else session.configurationChanged(carConfig());
    }

    /**
     * Configuração do "carro": o Waze cria o display do mapa com screenWidthDp × screenHeightDp
     * e densityDpi. Usa a área desta view e a densidade real da tela (a da suíte é a do desenho).
     */
    private Configuration carConfig() {
        Configuration c = new Configuration(getResources().getConfiguration());
        int dpi = Resources.getSystem().getDisplayMetrics().densityDpi;
        c.densityDpi = dpi;
        c.screenWidthDp = Math.round(getWidth() * 160f / dpi);
        c.screenHeightDp = Math.round(getHeight() * 160f / dpi);
        c.smallestScreenWidthDp = Math.min(c.screenWidthDp, c.screenHeightDp);
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (WazeUi.dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return c;
    }

    // ---- CarSession.Listener ----

    @Override
    public void onStatus(String text) {
        if (text == null) {
            overlay.status(null);
            return;
        }
        Log.i(CarSession.TAG, text);
        overlay.status(Texts.connecting());
    }

    @Override
    public void onTemplate(CarBundle.Obj wrapper) {
        stackDepth = Math.max(1, wrapper.list("mTemplateInfoForScreenStack").size());
        CarBundle.Obj t = wrapper.obj("mTemplate");
        if (t != null) Log.i(CarSession.TAG, "template " + t.simpleName() + " pilha=" + stackDepth);
        overlay.status(null);
        overlay.render(wrapper);
    }

    @Override
    public void onSurfaceCallback(ISurfaceCallback callback) {
        if (surfaceCallback != null && surfaceSent) sendSurfaceDestroyed();
        surfaceCallback = callback;
        sendSurface();
    }

    @Override
    public void onToast(CharSequence text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAlert(CarBundle.Obj alert) {
        if (alert == null) overlay.dismissAlert();
        else overlay.showAlert(alert);
    }

    @Override
    public void onNavigation(boolean active) {
        navigating = active;
        if (!active) {
            lastRouteKey = null;
            if (routeListener != null) {
                routeListener.onRoute(new Intent().putExtra("app", WazeUi.WAZE).putExtra("active", false));
            }
        }
    }

    @Override
    public void onTrip(CarBundle.Obj trip) {
    }

    @Override
    public void onFinish() {
        // O Waze pediu para fechar a sessão: reconecta na próxima vez que a tela aparecer.
        session.disconnect();
        connected = false;
    }

    // ---- Overlay.Host ----

    @Override
    public void onMapArea(Rect visible, Rect stable) {
        mapArea = visible;
        sendAreas();
    }

    @Override
    public void onMapShown(boolean shown) {
        mapShown = shown;
    }

    @Override
    public void back() {
        if (stackDepth > 1) session.back();
    }

    @Override
    public boolean canStopNavigation() {
        try {
            getContext().getPackageManager().getReceiverInfo(new ComponentName(WazeUi.WAZE, COMMANDS), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void stopNavigation() {
        getContext().sendBroadcast(new Intent(ACTION_COMMAND)
                .setComponent(new ComponentName(WazeUi.WAZE, COMMANDS)).putExtra("cmd", "stop"));
    }

    /** Manobra e chegada para o cartão do painel, com os extras do Apps.ACTION_NAV_STATE. */
    @Override
    public void onRoute(CarBundle.Obj routing, CarBundle.Obj estimate) {
        navigating = true;
        if (routeListener == null) return;
        CarBundle.Obj step = routing.obj("mCurrentStep");
        CarBundle.Obj maneuver = step == null ? null : step.obj("mManeuver");
        String distance = Texts.distance(routing.obj("mCurrentDistance"));
        String cue = step == null ? null : Texts.plain(step.get("mCue"));
        if (cue == null && step != null) cue = Texts.plain(step.get("mRoad"));
        String remaining = null, remainingDistance = null, arrival = null;
        if (estimate != null) {
            long secs = estimate.l("mRemainingTimeSeconds", -1);
            if (secs >= 0) remaining = Texts.duration(secs);
            remainingDistance = Texts.distance(estimate.obj("mRemainingDistance"));
            arrival = Texts.clock(estimate.obj("mArrivalTimeAtDestination"));
        }
        int type = maneuver == null ? 0 : maneuver.i("mType", 0);
        String key = distance + "|" + cue + "|" + type + "|" + remaining + "|" + remainingDistance + "|" + arrival;
        if (key.equals(lastRouteKey)) return;
        lastRouteKey = key;
        Intent i = new Intent().putExtra("app", WazeUi.WAZE).putExtra("active", true)
                .putExtra("distance", distance).putExtra("cue", clean(cue))
                .putExtra("remaining", remaining).putExtra("remaining_distance", remainingDistance)
                .putExtra("arrival", arrival);
        Bitmap icon = icon(maneuver == null ? null : maneuver.obj("mIcon"));
        if (icon != null) i.putExtra("icon", icon);
        routeListener.onRoute(i);
    }

    /** Tira as marcas de direção de texto (U+200E, U+2066–2069) que o Waze põe nos nomes. */
    private static String clean(String s) {
        return s == null ? null : s.replaceAll("[\\u200e\\u200f\\u2066-\\u2069]", "").trim();
    }

    private Bitmap icon(CarBundle.Obj carIcon) {
        Drawable d = Icons.drawable(getContext(), carIcon, false);
        if (d == null) return null;
        d = d.mutate();
        d.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        Bitmap b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, ICON_PX, ICON_PX);
        d.draw(new Canvas(b));
        return b;
    }

    // ---- superfície do mapa ----

    private final SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder h) {
            holder = h;
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            holder = h;
            if (surfaceSent && (width != surfaceW || height != surfaceH)) sendSurfaceDestroyed();
            surfaceW = width;
            surfaceH = height;
            sendSurface();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            sendSurfaceDestroyed();
            holder = null;
        }
    };

    private static int dpi() {
        return Resources.getSystem().getDisplayMetrics().densityDpi;
    }

    private void sendSurface() {
        if (surfaceCallback == null || holder == null || surfaceSent || surfaceW == 0) return;
        if (!holder.getSurface().isValid()) return;
        CarSession.surfaceAvailable(surfaceCallback, holder.getSurface(), surfaceW, surfaceH, dpi());
        surfaceSent = true;
        sendAreas();
    }

    private void sendSurfaceDestroyed() {
        if (!surfaceSent || surfaceCallback == null || holder == null) {
            surfaceSent = false;
            return;
        }
        CarSession.surfaceDestroyed(surfaceCallback, holder.getSurface(), surfaceW, surfaceH, dpi());
        surfaceSent = false;
    }

    private void sendAreas() {
        if (!surfaceSent || surfaceCallback == null) return;
        Rect area = mapArea != null ? new Rect(mapArea) : new Rect(0, 0, surfaceW, surfaceH);
        area.intersect(0, 0, surfaceW, surfaceH);
        CarSession.areas(surfaceCallback, area, area);
    }

    // ---- gestos no mapa ----

    private GestureDetector gestures;
    private ScaleGestureDetector scale;
    private boolean scaling;

    private final GestureDetector.SimpleOnGestureListener gestureListener = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            try {
                surfaceCallback.onClick(e.getX(), e.getY());
            } catch (RemoteException | RuntimeException ignored) {
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            try {
                surfaceCallback.onScale(e.getX(), e.getY(), 2f);
            } catch (RemoteException | RuntimeException ignored) {
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (scaling) return false;
            try {
                surfaceCallback.onScroll(dx, dy);
            } catch (RemoteException | RuntimeException ignored) {
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (scaling) return false;
            try {
                surfaceCallback.onFling(vx, vy);
            } catch (RemoteException | RuntimeException ignored) {
            }
            return true;
        }
    };

    private boolean onMapTouch(View v, MotionEvent e) {
        if (surfaceCallback == null || !mapShown) return false;
        if (gestures == null) gestures = new GestureDetector(getContext(), gestureListener);
        if (scale == null) {
            scale = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    scaling = true;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    try {
                        surfaceCallback.onScale(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                    } catch (RemoteException | RuntimeException ignored) {
                    }
                    return true;
                }
            });
        }
        scale.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) scaling = false;
        gestures.onTouchEvent(e);
        return true;
    }
}
