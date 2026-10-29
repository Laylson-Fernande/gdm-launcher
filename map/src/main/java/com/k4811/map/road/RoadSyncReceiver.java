package com.k4811.map.road;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.k4811.map.MapConfig;

/**
 * A internet conectou (hotspot do celular ao ligar o carro, Wi-Fi…): confere o raio dos dados das
 * vias e do mapa offline se o período venceu. Com targetSdk 19, CONNECTIVITY_ACTION ainda chega a um receiver do
 * manifesto no Android 10.
 */
public class RoadSyncReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!MapConfig.online(context)) return;
        RoadSync.get(context).areaFromLastKnown();
        RoadSync.maps(context).areaFromLastKnown();
    }
}
