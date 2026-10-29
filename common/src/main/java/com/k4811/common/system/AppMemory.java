package com.k4811.common.system;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.util.Log;

import com.k4811.common.Apps;

import java.util.List;

/**
 * Memória de cada app da suíte, para a aba Memória das Configurações. O Android 10+ não deixa um app
 * comum ver a memória dos outros, então cada app da suíte responde por si:
 * <ul>
 * <li>{@link Apps#ACTION_MEMORY_QUERY} (ordenado): põe nos extras do resultado o próprio uso, em KB
 * (PSS: a parte da RAM que é deste app), com o package como chave.</li>
 * <li>{@link Apps#ACTION_QUIT} (com {@code setPackage}): para os próprios serviços e encerra o processo.</li>
 * </ul>
 * Os receivers são registrados no {@code SuiteApp} (dinâmicos): só respondem os apps que estão
 * rodando, e a pergunta não abre os que estão fechados.
 */
public final class AppMemory {
    private static final String TAG = "K4811Memory";

    private AppMemory() {
    }

    public static void install(Application app) {
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Apps.ACTION_MEMORY_QUERY.equals(intent.getAction())) {
                    Bundle result = getResultExtras(true);
                    result.putLong(c.getPackageName(), ownPssKb());
                } else if (Apps.ACTION_QUIT.equals(intent.getAction())) {
                    quit(c);
                }
            }
        }, filter());
    }

    private static IntentFilter filter() {
        IntentFilter f = new IntentFilter(Apps.ACTION_MEMORY_QUERY);
        f.addAction(Apps.ACTION_QUIT);
        return f;
    }

    /** Uso de RAM deste processo (PSS), em KB. */
    public static long ownPssKb() {
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        return mi.getTotalPss();
    }

    /** Para os serviços deste app (senão o Android os recria) e encerra o processo. */
    @SuppressWarnings("deprecation")
    private static void quit(Context c) {
        Log.d(TAG, "encerrado pelas Configurações");
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(50);
            if (services != null) {
                for (ActivityManager.RunningServiceInfo s : services) {
                    ComponentName cn = s.service;
                    if (cn != null && c.getPackageName().equals(cn.getPackageName())) {
                        c.stopService(new Intent().setComponent(cn));
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "serviços", e);
        }
        Process.killProcess(Process.myPid());
    }
}
