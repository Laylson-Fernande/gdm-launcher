package com.k4811.map;

import com.k4811.common.config.NwdConfigs;
import com.k4811.map.route.Places;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Endereços favoritos ({@code K4811/nav/favorites.json}), na ordem em que foram salvos (o mais novo
 * primeiro). Aparecem antes dos recentes ao abrir a busca; a estrela de cada linha salva ou tira.
 * Leitura e gravação no disco: chamar fora da thread principal.
 */
final class Favorites {
    private Favorites() {
    }

    private static File file() {
        return new File(MapConfig.dir(), "favorites.json");
    }

    static synchronized List<Places.Place> load() {
        List<Places.Place> out = new ArrayList<>();
        JSONObject j = NwdConfigs.read(file());
        JSONArray arr = j == null ? null : j.optJSONArray("favorites");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(Places.Place.fromJson(o));
        }
        return out;
    }

    static boolean contains(List<Places.Place> list, Places.Place p) {
        return indexOf(list, p) >= 0;
    }

    /** Salva (no topo) ou tira {@code p}; devolve true se ficou salvo. */
    static synchronized boolean toggle(Places.Place p) {
        List<Places.Place> list = load();
        int i = indexOf(list, p);
        boolean saved = i < 0;
        if (saved) list.add(0, p);
        else list.remove(i);
        save(list);
        return saved;
    }

    private static int indexOf(List<Places.Place> list, Places.Place p) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).same(p)) return i;
        }
        return -1;
    }

    private static void save(List<Places.Place> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Places.Place o : list) {
                arr.put(o.toJson());
            }
            NwdConfigs.write(file(), new JSONObject().put("favorites", arr));
        } catch (JSONException ignored) {
            // valores do próprio app: não acontece
        }
    }
}
