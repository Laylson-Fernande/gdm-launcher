package com.k4811.map.road;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Confere os dados baixados (vias, mapa offline, grafo da rota, endereços) sem o mapa aberto: boot,
 * app instalado por cima (carga inicial depois da atualização), internet conectou (hotspot do
 * celular ao ligar o carro, Wi-Fi…) e o alarme de hora em hora de {@link RoadSync#start}. Só baixa
 * se o período venceu, o carro se afastou, o raio mudou ou a carga inicial ainda não terminou. Com
 * targetSdk 19, CONNECTIVITY_ACTION ainda chega a um receiver do manifesto no Android 10.
 */
public class RoadSyncReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        RoadSync.start(context);
    }
}
