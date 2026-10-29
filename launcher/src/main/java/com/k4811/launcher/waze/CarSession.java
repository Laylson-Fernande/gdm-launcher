package com.k4811.launcher.waze;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.location.Location;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.car.app.IAppHost;
import androidx.car.app.IAppManager;
import androidx.car.app.ICarApp;
import androidx.car.app.ICarHost;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.ISurfaceCallback;
import androidx.car.app.constraints.IConstraintHost;
import androidx.car.app.navigation.INavigationHost;
import androidx.car.app.serialization.Bundleable;

/**
 * Lado "host" do protocolo androidx.car.app (o papel do app Android Auto): conecta no
 * CarAppService de um app, faz o handshake, cria a sessão e recebe os templates.
 *
 * Sequência (igual à do Android Auto): bind -> getAppInfo -> onHandshakeCompleted ->
 * onAppCreate -> getManager("app") -> onAppStart -> onAppResume -> getTemplate.
 * Depois disso o app chama de volta: invalidate (pedir o template de novo),
 * setSurfaceCallback (para desenhar o mapa), navigationStarted, updateTrip...
 */
final class CarSession {
    static final String TAG = "K4811Waze";
    /** Nível de API do host. O Waze aceita de 5 até o mLatestCarAppApiLevel dele. */
    private static final int HOST_API_LEVEL = 8;
    /** Código de transação de getInterfaceVersion (AIDL estável). */
    private static final int TX_INTERFACE_VERSION = 0xffffff;

    interface Listener {
        void onStatus(String text);

        void onTemplate(CarBundle.Obj wrapper);

        void onSurfaceCallback(ISurfaceCallback callback);

        void onToast(CharSequence text);

        void onAlert(CarBundle.Obj alert);

        void onNavigation(boolean active);

        void onTrip(CarBundle.Obj trip);

        void onFinish();
    }

    private final Context context;
    private final ComponentName component;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ICarApp app;
    private IAppManager appManager;
    private Configuration config;
    private boolean bound;
    private boolean started;

    CarSession(Context context, ComponentName component, Listener listener) {
        this.context = context.getApplicationContext();
        this.component = component;
        this.listener = listener;
    }

    void connect(Configuration config) {
        this.config = new Configuration(config);
        Intent i = new Intent("androidx.car.app.CarAppService").setComponent(component);
        bound = context.bindService(i, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        listener.onStatus(bound ? "connecting" : "not found");
    }

    void disconnect() {
        if (app != null && started) {
            try {
                app.onAppPause(new Done("onAppPause", null));
                app.onAppStop(new Done("onAppStop", null));
            } catch (RemoteException ignored) {
            }
        }
        started = false;
        if (bound) context.unbindService(connection);
        bound = false;
        app = null;
        appManager = null;
    }

    void resume() {
        if (app == null || !started) return;
        try {
            app.onAppStart(new Done("onAppStart", null));
            app.onAppResume(new Done("onAppResume", null));
        } catch (RemoteException e) {
            Log.w(TAG, "resume", e);
        }
    }

    void pause() {
        if (app == null || !started) return;
        try {
            app.onAppPause(new Done("onAppPause", null));
            app.onAppStop(new Done("onAppStop", null));
        } catch (RemoteException e) {
            Log.w(TAG, "pause", e);
        }
    }

    /** Dia/noite e tamanho: o app recebe a mesma Configuration que o Android Auto mandaria. */
    void configurationChanged(Configuration c) {
        config = new Configuration(c);
        if (app == null) return;
        try {
            app.onConfigurationChanged(config, new Done("onConfigurationChanged", null));
        } catch (RemoteException e) {
            Log.w(TAG, "config", e);
        }
    }

    void requestTemplate() {
        if (appManager == null) return;
        try {
            appManager.getTemplate(new Done("getTemplate", r -> {
                if (r instanceof CarBundle.Obj) listener.onTemplate((CarBundle.Obj) r);
            }));
        } catch (RemoteException e) {
            Log.w(TAG, "getTemplate", e);
        }
    }

    void back() {
        if (appManager == null) return;
        try {
            appManager.onBackPressed(new Done("onBackPressed", null));
        } catch (RemoteException e) {
            Log.w(TAG, "back", e);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            app = ICarApp.Stub.asInterface(service);
            listener.onStatus("Conectado. Handshake…");
            try {
                app.getAppInfo(new Done("getAppInfo", r -> handshake((CarBundle.Obj) r)));
            } catch (RemoteException e) {
                listener.onStatus("Falha: " + e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            app = null;
            appManager = null;
            started = false;
            listener.onStatus("O app desconectou");
        }
    };

    private void handshake(CarBundle.Obj appInfo) {
        int min = appInfo == null ? 1 : appInfo.i("mMinCarAppApiLevel", 1);
        int latest = appInfo == null ? HOST_API_LEVEL : appInfo.i("mLatestCarAppApiLevel", HOST_API_LEVEL);
        int level = Math.max(min, Math.min(latest, HOST_API_LEVEL));
        Log.i(TAG, "AppInfo " + appInfo + " -> API " + level);
        Bundleable info = CarBundle.wrap(CarBundle.object("androidx.car.app.HandshakeInfo",
                "mHostPackageName", context.getPackageName(),
                "mHostCarAppApiLevel", level));
        try {
            app.onHandshakeCompleted(info, new Done("onHandshakeCompleted", r -> create()));
        } catch (RemoteException e) {
            listener.onStatus("Falha: " + e);
        }
    }

    private void create() {
        Intent start = new Intent().setComponent(component);
        try {
            app.onAppCreate(carHost, start, config, new Done("onAppCreate", r -> {
                try {
                    app.getManager("app", new Done("getManager", m -> {
                        if (!(m instanceof CarBundle.Iface)) return;
                        appManager = IAppManager.Stub.asInterface(((CarBundle.Iface) m).binder);
                        started = true;
                        listener.onStatus(null);
                        resume();
                        requestTemplate();
                    }));
                } catch (RemoteException e) {
                    listener.onStatus("Falha: " + e);
                }
            }));
        } catch (RemoteException e) {
            listener.onStatus("Falha: " + e);
        }
    }

    // ---- o que o app chama no host ----

    /** onTransact comum: responde getInterfaceVersion (AIDL estável) com 1. */
    static boolean version(int code, Parcel reply) {
        if (code != TX_INTERFACE_VERSION) return false;
        reply.writeNoException();
        reply.writeInt(1);
        return true;
    }

    private final ICarHost.Stub carHost = new ICarHost.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return version(code, reply) || super.onTransact(code, data, reply, flags);
        }

        @Override
        public void reserved1() {
        }

        @Override
        public void startCarApp(Intent intent) {
            Log.i(TAG, "startCarApp " + intent);
        }

        @Override
        public IBinder getHost(String type) {
            switch (type) {
                case "app":
                    return appHost;
                case "navigation":
                    return navigationHost;
                case "constraints":
                    return constraintHost;
                default:
                    Log.i(TAG, "getHost sem suporte: " + type);
                    return null;
            }
        }

        @Override
        public void finish() {
            main.post(listener::onFinish);
        }
    };

    private final IAppHost.Stub appHost = new IAppHost.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return version(code, reply) || super.onTransact(code, data, reply, flags);
        }

        @Override
        public void reserved1() {
        }

        @Override
        public void invalidate() {
            main.post(CarSession.this::requestTemplate);
        }

        @Override
        public void showToast(CharSequence text, int duration) {
            main.post(() -> listener.onToast(text));
        }

        @Override
        public void setSurfaceCallback(ISurfaceCallback callback) {
            main.post(() -> listener.onSurfaceCallback(callback));
        }

        @Override
        public void sendLocation(Location location) {
        }

        @Override
        public void showAlert(Bundleable alert) {
            Object o = CarBundle.decode(alert);
            if (o instanceof CarBundle.Obj) main.post(() -> listener.onAlert((CarBundle.Obj) o));
        }

        @Override
        public void dismissAlert(int id) {
            main.post(() -> listener.onAlert(null));
        }

        @Override
        public Bundleable openMicrophone(Bundleable request) {
            return null;
        }
    };

    private final INavigationHost.Stub navigationHost = new INavigationHost.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return version(code, reply) || super.onTransact(code, data, reply, flags);
        }

        @Override
        public void reserved1() {
        }

        @Override
        public void navigationStarted() {
            main.post(() -> listener.onNavigation(true));
        }

        @Override
        public void navigationEnded() {
            main.post(() -> listener.onNavigation(false));
        }

        @Override
        public void updateTrip(Bundleable trip) {
            Object o = CarBundle.decode(trip);
            if (o instanceof CarBundle.Obj) main.post(() -> listener.onTrip((CarBundle.Obj) o));
        }

        @Override
        public void setVoiceAssistantCapabilities(Bundleable capabilities) {
        }
    };

    private final IConstraintHost.Stub constraintHost = new IConstraintHost.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return version(code, reply) || super.onTransact(code, data, reply, flags);
        }

        @Override
        public void reserved1() {
        }

        /** Limites de itens por tipo de conteúdo (0 lista, 1 grade, 2 lugares, 3 rotas, 4 painel). */
        @Override
        public int getContentLimit(int type) {
            switch (type) {
                case 1:
                    return 12;
                case 2:
                    return 12;
                case 3:
                    return 3;
                case 4:
                    return 4;
                default:
                    return 50;
            }
        }

        @Override
        public boolean isAppDrivenRefreshEnabled() {
            return true;
        }
    };

    // ---- superfície ----

    static void surfaceAvailable(ISurfaceCallback cb, Surface surface, int w, int h, int dpi) {
        try {
            cb.onSurfaceAvailable(CarBundle.wrap(surfaceContainer(surface, w, h, dpi)), new Done("onSurfaceAvailable", null));
        } catch (RemoteException e) {
            Log.w(TAG, "surface", e);
        }
    }

    static void surfaceDestroyed(ISurfaceCallback cb, Surface surface, int w, int h, int dpi) {
        try {
            cb.onSurfaceDestroyed(CarBundle.wrap(surfaceContainer(surface, w, h, dpi)), new Done("onSurfaceDestroyed", null));
        } catch (RemoteException e) {
            Log.w(TAG, "surface", e);
        }
    }

    static void areas(ISurfaceCallback cb, Rect visible, Rect stable) {
        try {
            cb.onVisibleAreaChanged(visible, new Done("onVisibleAreaChanged", null));
            cb.onStableAreaChanged(stable, new Done("onStableAreaChanged", null));
        } catch (RemoteException e) {
            Log.w(TAG, "areas", e);
        }
    }

    private static android.os.Bundle surfaceContainer(Surface surface, int w, int h, int dpi) {
        return CarBundle.object("androidx.car.app.SurfaceContainer",
                "mSurface", surface, "mWidth", w, "mHeight", h, "mDpi", dpi);
    }

    // ---- respostas ----

    interface Result {
        void accept(Object value);
    }

    /** IOnDoneCallback: entrega o resultado já decodificado na thread principal. */
    static final class Done extends IOnDoneCallback.Stub {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private final String what;
        private final Result result;

        Done(String what, Result result) {
            this.what = what;
            this.result = result;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return version(code, reply) || super.onTransact(code, data, reply, flags);
        }

        @Override
        public void reserved1() {
        }

        @Override
        public void onSuccess(Bundleable response) {
            if (result == null) return;
            Object value;
            try {
                value = CarBundle.decode(response);
            } catch (RuntimeException e) {
                Log.w(TAG, what + ": resposta ilegível", e);
                return;
            }
            MAIN.post(() -> result.accept(value));
        }

        @Override
        public void onFailure(Bundleable response) {
            Object o = null;
            try {
                o = CarBundle.decode(response);
            } catch (RuntimeException ignored) {
            }
            Log.w(TAG, what + " falhou: " + o + " (uid " + Binder.getCallingUid() + ")");
        }
    }
}
