package com.k4811.map.route;

import android.content.Context;

import com.k4811.map.R;

import java.util.Locale;

/** Textos das manobras e das distâncias, no idioma do app (strings map_* do common). */
public final class Phrases {

    /** Desenho da manobra (ver {@code TurnIcon}). */
    public enum Shape {
        STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE
    }

    private final Context c;

    public Phrases(Context c) {
        this.c = c;
    }

    public static Shape shape(Route.Step s) {
        if ("arrive".equals(s.type)) return Shape.ARRIVE;
        if (s.type.startsWith("roundabout") || s.type.startsWith("rotary")) return Shape.ROUNDABOUT;
        String m = s.modifier == null ? "straight" : s.modifier;
        switch (m) {
            case "uturn":
                return Shape.UTURN;
            case "sharp left":
                return Shape.SHARP_LEFT;
            case "left":
                return Shape.LEFT;
            case "slight left":
                return Shape.SLIGHT_LEFT;
            case "sharp right":
                return Shape.SHARP_RIGHT;
            case "right":
                return Shape.RIGHT;
            case "slight right":
                return Shape.SLIGHT_RIGHT;
            default:
                return Shape.STRAIGHT;
        }
    }

    /** A ação, sem a rua: "Vire à esquerda", "Na rotatória, pegue a 2ª saída"… */
    public String action(Route.Step s) {
        boolean left = s.modifier != null && s.modifier.contains("left");
        boolean right = s.modifier != null && s.modifier.contains("right");
        switch (s.type) {
            case "arrive":
                return c.getString(R.string.map_act_arrive);
            case "depart":
                return c.getString(R.string.map_act_depart);
            case "roundabout":
            case "rotary":
            case "roundabout turn":
                return s.exit > 0 ? c.getString(R.string.map_act_roundabout_exit, s.exit)
                    : c.getString(R.string.map_act_roundabout);
            case "fork":
                if (left) return c.getString(R.string.map_act_keep_left);
                if (right) return c.getString(R.string.map_act_keep_right);
                break;
            case "on ramp":
                if (left) return c.getString(R.string.map_act_ramp_left);
                if (right) return c.getString(R.string.map_act_ramp_right);
                break;
            case "off ramp":
                if (left) return c.getString(R.string.map_act_exit_left);
                if (right) return c.getString(R.string.map_act_exit_right);
                break;
            case "merge":
                return c.getString(R.string.map_act_merge);
            default:
                break;
        }
        switch (shape(s)) {
            case UTURN:
                return c.getString(R.string.map_act_uturn);
            case SHARP_LEFT:
                return c.getString(R.string.map_act_sharp_left);
            case LEFT:
                return c.getString(R.string.map_act_left);
            case SLIGHT_LEFT:
                return c.getString(R.string.map_act_slight_left);
            case SHARP_RIGHT:
                return c.getString(R.string.map_act_sharp_right);
            case RIGHT:
                return c.getString(R.string.map_act_right);
            case SLIGHT_RIGHT:
                return c.getString(R.string.map_act_slight_right);
            default:
                return c.getString(R.string.map_act_straight);
        }
    }

    /** Rua do trecho depois da manobra ("" se não tiver nome nem número). */
    public static String street(Route.Step s) {
        if (s.name != null && !s.name.isEmpty()) return s.name;
        return s.ref == null ? "" : s.ref;
    }

    /** Ação com a rua: "Vire à esquerda para Rua Augusta". */
    public String full(Route.Step s) {
        String street = street(s);
        if (street.isEmpty() || "arrive".equals(s.type)) return action(s);
        return c.getString(R.string.map_onto, action(s), street);
    }

    /** Frase falada: "Em 300 metros, vire à esquerda para Rua X" ou só a manobra (meters &lt; 0). */
    public String spoken(Route.Step s, int meters) {
        String what = full(s);
        if (meters < 0) return what;
        return c.getString(R.string.map_voice_in, spokenDistance(meters), lowerFirst(what));
    }

    /** Distância falada: "300 metros", "1,5 quilômetros". */
    public String spokenDistance(int meters) {
        return meters >= 1000
            ? c.getString(R.string.map_voice_km, decimal(meters / 1000.0))
            : c.getString(R.string.map_voice_meters, meters);
    }

    /** Distância na tela: "80 m", "350 m", "1,2 km", "15 km". */
    public String distance(double m) {
        if (m < 1000) {
            int r = m < 100 ? (int) (Math.round(m / 10.0) * 10) : (int) (Math.round(m / 50.0) * 50);
            if (r >= 1000) return c.getString(R.string.map_km, decimal(1.0));
            return c.getString(R.string.map_meters, r);
        }
        return c.getString(R.string.map_km, m < 10000 ? decimal(m / 1000.0) : String.valueOf(Math.round(m / 1000.0)));
    }

    /** Duração: "12 min", "1 h 05". */
    public String duration(double seconds) {
        int min = (int) Math.max(1, Math.round(seconds / 60.0));
        if (min < 60) return c.getString(R.string.map_minutes, min);
        return c.getString(R.string.map_hours, min / 60, min % 60);
    }

    private static String decimal(double v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toLowerCase(Locale.getDefault()) + s.substring(1);
    }
}
