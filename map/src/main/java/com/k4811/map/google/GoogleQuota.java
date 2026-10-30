package com.k4811.map.google;

import android.content.Context;
import android.util.Log;

import com.k4811.common.config.NwdConfigs;
import com.k4811.map.MapConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Uso do Google no mês, por serviço (SKU da cobrança), para nunca passar das cotas gratuitas. Antes
 * de cada chamada paga, {@link #take} confere e conta; acima do limite, quem chamou cai para o
 * próximo da cadeia (OpenStreetMap, OSRM…). Os limites ficam em ~90% da cota grátis e podem ser
 * mudados no nav.config.json ({@code google_limits}).
 *
 * O mês é o da cobrança do Google (fuso de Los Angeles). Estado em {@code K4811/nav/google/usage.json}
 * (o car-setting lê para mostrar o uso do mês). Um erro de chave ou de cota do Google (400, 401, 403,
 * 429) suspende tudo por {@link #BLOCK_MS}.
 */
public final class GoogleQuota {

    /** Serviços cobrados separadamente, com a cota grátis mensal de outubro de 2026. */
    public enum Sku {
        /** Map Tiles API, 2D Map Tiles: 100 mil grátis. */
        TILES("tiles", 90000),
        /** Routes API, Compute Routes Pro (trânsito, rumo do carro na partida): 5 mil grátis. */
        ROUTES_PRO("routes_pro", 4500),
        /** Routes API, Compute Routes Essentials (sem trânsito): 10 mil grátis. */
        ROUTES("routes", 9000),
        /** Places API (New), Autocomplete Requests: 10 mil grátis. */
        AUTOCOMPLETE("autocomplete", 9000),
        /** Places API (New), Place Details Essentials: 10 mil grátis. */
        DETAILS("details", 9000);

        public final String id;
        final int defaultLimit;

        Sku(String id, int defaultLimit) {
            this.id = id;
            this.defaultLimit = defaultLimit;
        }
    }

    private static final String TAG = "K4811Google";
    static final long BLOCK_MS = 10 * 60 * 1000L;
    /** Grava no disco a cada tantas chamadas ou depois deste tempo (o contador também vive em memória). */
    private static final int SAVE_EVERY = 20;
    private static final long SAVE_MS = 60 * 1000L;

    private static GoogleQuota instance;

    private String month;
    private final int[] used = new int[Sku.values().length];
    private long blockedUntil;
    private String lastError = "";
    private long lastErrorTime;
    private boolean loaded;
    private int unsaved;
    private long savedAt;

    private GoogleQuota() {
    }

    public static synchronized GoogleQuota get() {
        if (instance == null) instance = new GoogleQuota();
        return instance;
    }

    public static File file() {
        return new File(MapConfig.dir(), "google/usage.json");
    }

    /** Limite do mês para {@code s}: o do nav.config.json ou o padrão. */
    public static int limit(Context c, Sku s) {
        return Math.max(0, MapConfig.get(c).googleLimits.optInt(s.id, s.defaultLimit));
    }

    /** Ainda há cota de {@code s} neste mês e o Google não está suspenso. Não conta nada. */
    public synchronized boolean has(Context c, Sku s) {
        load();
        roll();
        return !blocked() && used[s.ordinal()] < limit(c, s);
    }

    /**
     * Conta uma chamada de {@code s}, se ainda houver cota. Chame logo antes de pedir ao Google (e só
     * depois de olhar o cache).
     * @return false = sem cota ou suspenso: não chame o Google
     */
    public synchronized boolean take(Context c, Sku s) {
        load();
        roll();
        if (blocked() || used[s.ordinal()] >= limit(c, s)) return false;
        used[s.ordinal()]++;
        unsaved++;
        long now = System.currentTimeMillis();
        if (unsaved >= SAVE_EVERY || now - savedAt > SAVE_MS) save(c);
        return true;
    }

    public synchronized int used(Sku s) {
        load();
        roll();
        return used[s.ordinal()];
    }

    /** Suspenso depois de um erro de chave ou de cota. */
    public synchronized boolean blocked() {
        return System.currentTimeMillis() < blockedUntil;
    }

    /** Uma chamada falhou. Erro de chave ou de cota suspende o Google por 10 min e fica gravado. */
    public synchronized void failed(Context c, Exception e) {
        load();
        boolean blocking = e instanceof GoogleHttp.HttpError && ((GoogleHttp.HttpError) e).blocking();
        Log.w(TAG, (blocking ? "suspenso por 10 min: " : "falhou: ") + e.getMessage());
        if (!blocking) return;
        blockedUntil = System.currentTimeMillis() + BLOCK_MS;
        lastError = String.valueOf(e.getMessage());
        lastErrorTime = System.currentTimeMillis();
        save(c);
    }

    /** Grava o que ainda não foi gravado (tela do mapa fechando). */
    public synchronized void flush(Context c) {
        if (unsaved > 0) save(c);
    }

    // ------------------------------------------------------------------ disco

    /** Mês da cobrança do Google: "2026-10", no fuso de Los Angeles. */
    static String billingMonth(long now) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"), Locale.US);
        cal.setTimeInMillis(now);
        return String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
    }

    private void roll() {
        String now = billingMonth(System.currentTimeMillis());
        if (now.equals(month)) return;
        month = now;
        for (int i = 0; i < used.length; i++) used[i] = 0;
        unsaved++;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        JSONObject j = NwdConfigs.read(file());
        if (j == null) return;
        month = j.optString("month", null);
        JSONObject u = j.optJSONObject("used");
        if (u != null) for (Sku s : Sku.values()) used[s.ordinal()] = Math.max(0, u.optInt(s.id, 0));
        blockedUntil = j.optLong("blocked_until", 0);
        lastError = j.optString("last_error", "");
        lastErrorTime = j.optLong("last_error_time", 0);
    }

    private void save(Context c) {
        JSONObject j = new JSONObject();
        try {
            JSONObject u = new JSONObject();
            JSONObject l = new JSONObject();
            for (Sku s : Sku.values()) {
                u.put(s.id, used[s.ordinal()]);
                l.put(s.id, limit(c, s));
            }
            j.put("month", month);
            j.put("used", u);
            // Os limites em uso, para o car-setting mostrar "usado / limite" sem ler o nav.config.json.
            j.put("limits", l);
            j.put("blocked_until", blockedUntil);
            j.put("last_error", lastError);
            j.put("last_error_time", lastErrorTime);
        } catch (JSONException e) {
            return;
        }
        File f = file();
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        if (NwdConfigs.write(f, j)) {
            unsaved = 0;
            savedAt = System.currentTimeMillis();
        }
    }
}
