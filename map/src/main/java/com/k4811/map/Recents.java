package com.k4811.map;

import com.k4811.common.config.NwdConfigs;
import com.k4811.map.route.Places;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Últimos destinos ({@code K4811/nav/recent.json}), o mais recente primeiro. */
final class Recents {
    private Recents() {
    }

    private static final int MAX = 8;

    private static File file() {
        return new File(MapConfig.dir(), "recent.json");
    }

    static List<Places.Place> load() {
        List<Places.Place> out = new ArrayList<>();
        JSONObject j = NwdConfigs.read(file());
        JSONArray arr = j == null ? null : j.optJSONArray("recent");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(Places.Place.fromJson(o));
        }
        return out;
    }

    /** Põe {@code p} no topo (sem repetir o mesmo lugar). Grava no disco: chamar fora da thread principal. */
    static void add(Places.Place p) {
        List<Places.Place> list = load();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).same(p)) list.remove(i);
        }
        list.add(0, p);
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < Math.min(MAX, list.size()); i++) {
                arr.put(list.get(i).toJson());
            }
            NwdConfigs.write(file(), new JSONObject().put("recent", arr));
        } catch (JSONException ignored) {
        }
    }
}
