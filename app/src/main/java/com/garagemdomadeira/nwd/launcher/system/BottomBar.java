package com.garagemdomadeira.nwd.launcher.system;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.garagemdomadeira.nwd.launcher.home.LauncherPrefs;

/**
 * NWD floating bottom bar ({@code com.nwd.statusbarbottom}: Home, Back, volume and, on some cars,
 * A/C), drawn over every app. Garagem has its own dock, so with Settings › System › "Hide the NWD
 * bottom bar" on (off by default) the bar is hidden while any Garagem screen is in front and shown
 * again when another app takes over. It follows the whole app, not each screen: going from the
 * home screen to the radio doesn't flash the bar.
 *
 * The bar's receivers take {@code removeFloat} / {@code createFloat} with
 * {@code pagename=home_horizontal} from any app; on units without it the broadcasts go nowhere.
 */
public final class BottomBar implements Application.ActivityLifecycleCallbacks {

    private static final String ACTION_REMOVE = "com.nwd.statusbarbottom.removeFloat";
    private static final String ACTION_CREATE = "com.nwd.statusbarbottom.createFloat";
    private static final String EXTRA_PAGE = "pagename";
    private static final String PAGE = "home_horizontal";
    private static final String TAG = "GaragemLauncher";
    private static final String PACKAGE = "com.nwd.statusbarbottom";

    private static BottomBar instance;
    private final Context app;
    /** Garagem screens started and not stopped; 0 = another app is in front. */
    private int started;
    /** We asked the bar to go away and haven't brought it back yet. */
    private boolean hidden;

    private BottomBar(Context app) {
        this.app = app;
    }

    public static void install(Application app) {
        instance = new BottomBar(app);
        app.registerActivityLifecycleCallbacks(instance);
    }

    /** The head unit has the bar (Settings only offers the switch then). */
    public static boolean available(Context c) {
        return ExternalApps.installed(c, PACKAGE);
    }

    /** Settings switch. Applies at once: Garagem is in front while it's toggled. */
    public static void setEnabled(Context c, boolean on) {
        LauncherPrefs.setHideBottomBar(c, on);
        BottomBar b = instance;
        if (b == null) return;
        if (on && b.started > 0 && !b.hidden) b.hide();
        else if (!on && b.hidden) b.show();
    }

    @Override
    public void onActivityStarted(Activity activity) {
        // The next screen starts before the previous one stops, so moving between our screens
        // never goes through 0.
        if (started++ == 0 && LauncherPrefs.hideBottomBar(app)) hide();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (started > 0 && --started == 0 && hidden) show();
    }

    private void hide() {
        hidden = true;
        send(ACTION_REMOVE);
    }

    private void show() {
        hidden = false;
        send(ACTION_CREATE);
    }

    private void send(String action) {
        try {
            app.sendBroadcast(new Intent(action).putExtra(EXTRA_PAGE, PAGE));
            Log.d(TAG, "→ " + action);
        } catch (Exception e) {
            Log.w(TAG, "Bottom bar: " + e.getMessage());
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
