package com.k4811.map.route;

import com.k4811.map.geo.Geo;

import java.util.Collections;
import java.util.List;

/**
 * Rota calculada: a linha (pontos) e as manobras. Distâncias em metros, tempos em segundos.
 * Imutável; o progresso fica no {@link Navigator}.
 */
public final class Route {

    /** Uma manobra e o trecho que vem depois dela (até a próxima). */
    public static final class Step {
        /** Tipo do OSRM: depart, turn, new name, continue, merge, on ramp, off ramp, fork, end of road,
         *  roundabout, rotary, roundabout turn, exit roundabout, exit rotary, notification, arrive. */
        public final String type;
        /** uturn, sharp right, right, slight right, straight, slight left, left, sharp left (ou null). */
        public final String modifier;
        /** Saída da rotatória (1, 2…), 0 se não houver. */
        public final int exit;
        /** Rua do trecho depois da manobra ("" se não tiver nome). */
        public final String name;
        public final String ref;
        public final double distance;
        public final double duration;
        /** Índice do ponto da linha onde está a manobra. */
        public final int index;
        /**
         * Texto pronto da instrução vindo do serviço da rota (Google), só para a tela; null nos outros.
         * Nunca vai para a voz (termos do Google): a voz usa {@link Phrases} com o tipo e a distância.
         */
        public final String instruction;

        public Step(String type, String modifier, int exit, String name, String ref,
                    double distance, double duration, int index) {
            this(type, modifier, exit, name, ref, distance, duration, index, null);
        }

        public Step(String type, String modifier, int exit, String name, String ref,
                    double distance, double duration, int index, String instruction) {
            this.instruction = instruction;
            this.type = type;
            this.modifier = modifier;
            this.exit = exit;
            this.name = name;
            this.ref = ref;
            this.distance = distance;
            this.duration = duration;
            this.index = index;
        }
    }

    public final double[] lat;
    public final double[] lon;
    /** Mesmos pontos em coordenadas de mundo (Web Mercator 0..1), para desenhar. */
    public final double[] wx;
    public final double[] wy;
    /** Distância acumulada do início até cada ponto. */
    public final double[] along;
    public final List<Step> steps;
    public final double distance;
    public final double duration;
    /** Destino pedido (pode ficar fora da rua; a rota termina no ponto da rua mais próximo). */
    public final double destLat;
    public final double destLon;
    public final String destName;

    public Route(double[] lat, double[] lon, List<Step> steps, double distance, double duration,
                 double destLat, double destLon, String destName) {
        this.lat = lat;
        this.lon = lon;
        int n = lat.length;
        wx = new double[n];
        wy = new double[n];
        along = new double[n];
        for (int i = 0; i < n; i++) {
            wx[i] = Geo.worldX(lon[i]);
            wy[i] = Geo.worldY(lat[i]);
            if (i > 0) along[i] = along[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        this.steps = Collections.unmodifiableList(steps);
        this.distance = distance;
        this.duration = duration;
        this.destLat = destLat;
        this.destLon = destLon;
        this.destName = destName;
    }

    public int size() {
        return lat.length;
    }
}
