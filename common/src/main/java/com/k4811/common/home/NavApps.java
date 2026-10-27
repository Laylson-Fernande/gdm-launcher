package com.k4811.common.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Apps de navegação da central (os que abrem um endereço {@code geo:}, como Waze e Google Maps),
 * para a tela de navegação do launcher e a escolha nas Configurações.
 */
public final class NavApps {
    private NavApps() {
    }

    /** Serviço do launcher que o usuário liga em "Acesso a notificações" para a tela de navegação ler a música. */
    public static final String MEDIA_LISTENER = "com.k4811.launcher.nav.MediaListener";

    /** Settings.System com o app de navegação escolhido no Config. Veículo da NWD. */
    private static final String UNIT_NAV_APP = "navigation_packagename";
    /** Preferidos no modo automático, nesta ordem. */
    private static final String[] PREFERRED = {"com.waze", "com.google.android.apps.maps"};

    /** Packages instalados que abrem {@code geo:} e têm tela inicial: os preferidos primeiro. */
    public static List<String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String pkg : PREFERRED) {
            if (pm.getLaunchIntentForPackage(pkg) != null) out.add(pkg);
        }
        Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0"));
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(geo, 0);
        } catch (RuntimeException e) {
            return out;
        }
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (out.contains(pkg) || pm.getLaunchIntentForPackage(pkg) == null) continue;
            out.add(pkg);
        }
        return out;
    }

    /**
     * O app escolhido nas Configurações; no automático (ou se ele foi desinstalado), o app de
     * navegação da central (Config. Veículo); senão o primeiro de {@link #installed}.
     */
    public static String chosen(Context c) {
        String pkg = LauncherConfig.navApp(c);
        if (launchable(c, pkg)) return pkg;
        String unit = Settings.System.getString(c.getContentResolver(), UNIT_NAV_APP);
        if (launchable(c, unit)) return unit;
        List<String> all = installed(c);
        return all.isEmpty() ? null : all.get(0);
    }

    private static boolean launchable(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** Nome do app como aparece na gaveta; o package se ele não existir. */
    public static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** O acesso a notificações do launcher está ligado (a tela de navegação consegue ler a música; Android 5+). */
    public static boolean mediaAccess(Context c) {
        if (Build.VERSION.SDK_INT < 21) return false;
        String enabled = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(Apps.LAUNCHER, MEDIA_LISTENER).flattenToString());
    }
}
