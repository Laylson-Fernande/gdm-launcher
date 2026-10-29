package com.k4811.common.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apps de música que o botão Música do dock pode abrir no lugar do MP3 da suíte (Spotify, YouTube
 * Music, Deezer…), para a escolha nas Configurações. Um app entra se tem tela inicial e é player:
 * declara o serviço de navegação de mídia (o mesmo que o Android Auto usa) ou a categoria de app de
 * música do Android.
 */
public final class MusicApps {
    private MusicApps() {
    }

    private static final String MEDIA_BROWSER = "android.media.browse.MediaBrowserService";

    /** Players instalados (menos os apps da suíte), pelo nome. */
    public static List<String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        try {
            for (ResolveInfo ri : pm.queryIntentServices(new Intent(MEDIA_BROWSER), 0)) {
                addIfApp(c, out, ri.serviceInfo.packageName);
            }
            Intent music = new Intent(Intent.ACTION_MAIN);
            music.addCategory(Intent.CATEGORY_APP_MUSIC);
            for (ResolveInfo ri : pm.queryIntentActivities(music, 0)) {
                addIfApp(c, out, ri.activityInfo.packageName);
            }
        } catch (RuntimeException e) {
            // PackageManager indisponível: lista vazia (fica o MP3 da suíte)
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(NavApps.label(c, a), NavApps.label(c, b)));
        return out;
    }

    private static void addIfApp(Context c, List<String> out, String pkg) {
        if (pkg == null || out.contains(pkg) || Apps.isSuite(pkg)) return;
        if (c.getPackageManager().getLaunchIntentForPackage(pkg) == null) return;
        out.add(pkg);
    }

    /** App que o botão Música abre; null = o MP3 da suíte (padrão, ou o escolhido foi desinstalado). */
    public static String chosen(Context c) {
        String pkg = LauncherConfig.musicApp(c);
        if (pkg == null || pkg.isEmpty()) return null;
        return c.getPackageManager().getLaunchIntentForPackage(pkg) != null ? pkg : null;
    }
}
