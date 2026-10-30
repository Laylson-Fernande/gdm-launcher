package com.k4811.common.config;

import android.content.Context;

import com.k4811.common.home.HomeGrid;
import com.k4811.common.ui.DockView;
import com.k4811.common.ui.Palette;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configurações do suíte seguidas por todos os apps: idioma, cor do tema, fonte, ícones
 * do dock (e o nome do Z-Link) e esconder a barra da NWD; e as da tela inicial (módulos, imagem,
 * voltímetro, dia/noite, apagar a tela, perguntar pelo launcher padrão).
 *
 * Ficam em /sdcard/nwd-configs/launcher.json. Só o car-setting grava; o launcher e os apps leem.
 * Sem arquivo (ou com erro de leitura), valem os padrões.
 */
public final class LauncherConfig {
    private LauncherConfig() {
    }

    public static final String FILE = "launcher.json";
    public static final int VERSION = 2;
    private static final String KEY_VERSION = "version";
    private static final String KEY_DOCK_TOGGLES = "dock_toggles";
    private static final String KEY_DOCK_SIZE = "dock_size";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_FONT = "font";
    private static final String KEY_THEME = "theme";
    /** true = fundo escuro (padrão); false = fundo claro. */
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_VOLTMETER = "show_voltmeter";
    private static final String KEY_DAY_NIGHT = "show_day_night";
    private static final String KEY_DAY_NIGHT_BY = "day_night_by";
    private static final String KEY_DAY_NIGHT_AFFECTS = "day_night_affects";
    private static final String KEY_NIGHT_START = "night_start";
    private static final String KEY_DAY_START = "day_start";
    private static final String KEY_SCREEN_OFF_AFTER = "screen_off_after";
    /** Antigo "mostrar controles de volume"; só serve para montar a tela inicial padrão. */
    private static final String KEY_VOLUME = "show_volume_controls";
    private static final String KEY_HOME_LAYOUT = "home_layout";
    private static final String KEY_HOME_IMAGE = "home_image";
    private static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    private static final String KEY_ZLINK_NAME = "zlink_name";
    private static final String KEY_ZLINK_AUTO_CONNECT = "zlink_auto_connect";
    private static final String KEY_ZLINK_AUTO_OPEN = "zlink_auto_open";
    private static final String KEY_ASK_DEFAULT = "ask_default_launcher";
    private static final String KEY_VOLUME_OVERLAY = "show_volume_overlay";
    /** Antigo liga/desliga do menu da borda; só lido quando ainda não há {@link #KEY_EDGE_MENU_SIDE}. */
    private static final String KEY_EDGE_MENU = "edge_menu";
    private static final String KEY_EDGE_MENU_SIDE = "edge_menu_side";
    private static final String KEY_NAV_APP = "nav_app";
    private static final String KEY_MUSIC_APP = "music_app";
    private static final String KEY_NAV_INFO = "nav_info";
    private static final String KEY_NAV_MODE = "nav_mode";
    private static final String KEY_NAV_PANEL_RIGHT = "nav_panel_right";
    private static final String KEY_NAV_VOICE = "nav_voice";
    private static final String KEY_NAV_CAMERAS = "nav_cameras";
    private static final String KEY_NAV_SPEED_LIMIT = "nav_speed_limit";
    private static final String KEY_NAV_ROAD_ALERTS = "nav_road_alerts";
    private static final String KEY_NAV_DATA_DAYS = "nav_data_days";
    private static final String KEY_NAV_DATA_KM = "nav_data_km";
    private static final String KEY_NAV_AUTO_ZOOM = "nav_auto_zoom";
    private static final String KEY_NAV_PERSPECTIVE = "nav_perspective";
    private static final String KEY_NAV_OFFLINE_MAP = "nav_offline_map";
    /** Serviços do mapa integrado: {@link #NAV_PROVIDER_OSM} (padrão) ou {@link #NAV_PROVIDER_GOOGLE}. */
    private static final String KEY_NAV_MAP_PROVIDER = "nav_map_provider";
    /** Rota do Google com trânsito em tempo real (cota menor, ver map/.../google/GoogleQuota). */
    private static final String KEY_NAV_GOOGLE_TRAFFIC = "nav_google_traffic";
    /** % da mídia durante a voz da navegação (-1 = nunca configurado). Ver MixVolumes. */
    private static final String KEY_NAV_MEDIA_PERCENT = "nav_media_percent";
    /** % das notificações em relação ao volume (-1 = nunca configurado). Ver MixVolumes. */
    private static final String KEY_NOTIFY_PERCENT = "notify_percent";
    private static final String KEY_SYS_MONITOR = "show_sys_monitor";
    private static final String KEY_SAVE_LOGS = "save_logs";
    private static final String KEY_START_GUARD = "start_guard";
    private static final String KEY_BLOCK_NOTIFICATIONS = "block_notifications";

    /** Launcher original da NWD (valor de fábrica de persist.nwd.launcher.default). */
    public static final String ORIGINAL_LAUNCHER = "com.android.launcher";

    /** Nomes que o item Z-Link do dock pode ter. */
    public static final String[] ZLINK_NAMES = {"Z-Link", "CarPlay", "Auto"};

    /** Idiomas (tag), na ordem das Configurações. Para incluir um: tag aqui, nome em
     *  {@code LANGUAGE_NAMES} e {@code res/values-<tag>/} no common. */
    public static final String[] LANGUAGES = {"pt", "en", "es", "fr", "ru", "ja", "zh", "hi"};
    /** Nome de cada idioma escrito nele mesmo (com uma bandeira de referência). */
    public static final String[] LANGUAGE_NAMES = {
        "🇧🇷 Português", "🇺🇸 English", "🇪🇸 Español", "🇫🇷 Français",
        "🇷🇺 Русский", "🇯🇵 日本語", "🇨🇳 中文（普通话）", "🇮🇳 हिन्दी",
    };
    public static final String DEFAULT_LANGUAGE = "en";

    /** Fontes: prefixo dos arquivos em assets/fonts ({@code <id>-400/500/600.ttf}). */
    public static final String[] FONTS = {"Exo2", "Montserrat", "Rajdhani", "Outfit", "Inter", "PublicSans"};
    public static final String[] FONT_NAMES = {"Exo 2", "Montserrat", "Rajdhani", "Outfit", "Inter", "Public Sans"};
    public static final String DEFAULT_FONT = "Exo2";

    /** Opções de "Apagar a tela após" (segundos sem tocar na tela inicial); 0 = nunca. */
    public static final int[] SCREEN_OFF_AFTER = {0, 5, 10, 30};

    /** Máximo de itens opcionais no dock (Início, Navegação e Apps são fixos e ficam fora da conta). */
    public static final int MAX_DOCK_TOGGLES = 5;
    public static final DockView.Item[] DEFAULT_DOCK = {
        DockView.Item.RADIO, DockView.Item.MUSIC, DockView.Item.BT_MUSIC, DockView.Item.PHONE, DockView.Item.EQ,
    };

    private static final String DEFAULT_HOME_LAYOUT =
        "now_playing:left,clock:center_top,volume:center_bottom,fan:right_top,bluetooth:right_middle";

    private static JSONObject cached;
    private static long cachedModified = -1;

    /** Arquivo atual. Só relê do disco quando a data de modificação muda. */
    private static synchronized JSONObject json() {
        File f = NwdConfigs.file(FILE);
        long modified = f.lastModified(); // 0 se não existe
        if (modified != cachedModified) {
            cachedModified = modified;
            cached = NwdConfigs.read(f);
        }
        return cached;
    }

    private static boolean bool(String key, boolean def) {
        JSONObject j = json();
        return j == null ? def : j.optBoolean(key, def);
    }

    private static int integer(String key, int def) {
        JSONObject j = json();
        return j == null ? def : j.optInt(key, def);
    }

    private static String string(String key, String def) {
        JSONObject j = json();
        if (j == null || !j.has(key) || j.isNull(key)) return def;
        return j.optString(key, def);
    }

    // ------------------------------------------------------------------ todos os apps

    /** Idioma escolhido; inglês quando nada foi escolhido ou o gravado não existe mais. */
    public static String language(Context c) {
        String tag = string(KEY_LANGUAGE, DEFAULT_LANGUAGE);
        for (String known : LANGUAGES) {
            if (known.equals(tag)) return tag;
        }
        return DEFAULT_LANGUAGE;
    }

    public static boolean setLanguage(Context c, String tag) {
        return put(KEY_LANGUAGE, tag == null ? DEFAULT_LANGUAGE : tag);
    }

    /** Fonte escolhida; Exo 2 quando nada foi escolhido ou a gravada não existe mais. */
    public static String font(Context c) {
        String id = string(KEY_FONT, DEFAULT_FONT);
        for (String known : FONTS) {
            if (known.equals(id)) return id;
        }
        return DEFAULT_FONT;
    }

    public static boolean setFont(Context c, String id) {
        return put(KEY_FONT, id == null ? DEFAULT_FONT : id);
    }

    /** Cor do tema: 0..Palette.THEMES-1, ou Palette.DEFAULT (tons de cinza). */
    public static int theme(Context c) {
        int t = integer(KEY_THEME, Palette.DEFAULT);
        return t >= 0 && t < Palette.THEMES ? t : Palette.DEFAULT;
    }

    public static boolean setTheme(Context c, int theme) {
        return put(KEY_THEME, theme);
    }

    /**
     * Modo escuro (fundo escuro); ligado por padrão, desligado deixa o fundo claro. Vale a última
     * escolha: a do motorista (Configurações ou barra de brilho) ou, com o tema seguindo o dia e a noite
     * ({@link #dayNightTheme}), a da última troca (escuro ao anoitecer, claro ao amanhecer, gravada pelo
     * car-setting a pedido do {@code DayNightWatcher} do launcher).
     */
    public static boolean darkMode(Context c) {
        return bool(KEY_DARK_MODE, true);
    }

    public static boolean setDarkMode(Context c, boolean on) {
        return put(KEY_DARK_MODE, on);
    }

    /** O que decide o dia e a noite: o farol (sinal ILL da MCU) ou o horário. */
    public static final String DAY_NIGHT_BY_HEADLIGHTS = "headlights";
    public static final String DAY_NIGHT_BY_TIME = "time";

    public static String dayNightBy(Context c) {
        return DAY_NIGHT_BY_TIME.equals(string(KEY_DAY_NIGHT_BY, null)) ? DAY_NIGHT_BY_TIME : DAY_NIGHT_BY_HEADLIGHTS;
    }

    public static boolean setDayNightBy(Context c, String by) {
        return put(KEY_DAY_NIGHT_BY, by);
    }

    /** O que muda à noite: só o brilho (padrão, como a central faz com o farol), só o tema ou os dois. */
    public static final String DAY_NIGHT_BRIGHTNESS = "brightness";
    public static final String DAY_NIGHT_THEME = "theme";
    public static final String DAY_NIGHT_BOTH = "both";
    public static final String[] DAY_NIGHT_AFFECTS = {DAY_NIGHT_BRIGHTNESS, DAY_NIGHT_THEME, DAY_NIGHT_BOTH};

    public static String dayNightAffects(Context c) {
        String v = string(KEY_DAY_NIGHT_AFFECTS, DAY_NIGHT_BRIGHTNESS);
        for (String a : DAY_NIGHT_AFFECTS) if (a.equals(v)) return a;
        return DAY_NIGHT_BRIGHTNESS;
    }

    public static boolean setDayNightAffects(Context c, String affects) {
        return put(KEY_DAY_NIGHT_AFFECTS, affects);
    }

    /** O tema fica escuro à noite e claro de dia (em vez da escolha fixa). */
    public static boolean dayNightTheme(Context c) {
        return !DAY_NIGHT_BRIGHTNESS.equals(dayNightAffects(c));
    }

    /** O brilho muda entre o do dia e o da noite. */
    public static boolean dayNightBrightness(Context c) {
        return !DAY_NIGHT_THEME.equals(dayNightAffects(c));
    }

    /** Horários de "Por horário", em minutos desde a meia-noite (de 30 em 30). */
    public static final int[] DAY_NIGHT_TIMES;

    static {
        DAY_NIGHT_TIMES = new int[48];
        for (int i = 0; i < 48; i++) DAY_NIGHT_TIMES[i] = i * 30;
    }

    /** Começo da noite (padrão 18:00). */
    public static int nightStart(Context c) {
        return clampMinutes(integer(KEY_NIGHT_START, 18 * 60), 18 * 60);
    }

    public static boolean setNightStart(Context c, int minutes) {
        return put(KEY_NIGHT_START, minutes);
    }

    /** Começo do dia (padrão 06:00). */
    public static int dayStart(Context c) {
        return clampMinutes(integer(KEY_DAY_START, 6 * 60), 6 * 60);
    }

    public static boolean setDayStart(Context c, int minutes) {
        return put(KEY_DAY_START, minutes);
    }

    private static int clampMinutes(int v, int def) {
        return v >= 0 && v < 24 * 60 ? v : def;
    }

    /** Itens opcionais ligados no dock, na ordem fixa do enum. Sem nada gravado, o padrão. */
    public static Set<DockView.Item> dockToggles(Context c) {
        Set<DockView.Item> out = new LinkedHashSet<>();
        JSONObject j = json();
        JSONArray ids = j == null ? null : j.optJSONArray(KEY_DOCK_TOGGLES);
        if (ids == null) {
            Collections.addAll(out, DEFAULT_DOCK);
            return out;
        }
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < ids.length(); i++) set.add(ids.optString(i));
        for (DockView.Item item : DockView.Item.values()) {
            if (item.toggleable && set.contains(item.id) && out.size() < MAX_DOCK_TOGGLES) out.add(item);
        }
        return out;
    }

    public static boolean setDockToggles(Context c, Set<DockView.Item> items) {
        JSONArray ids = new JSONArray();
        for (DockView.Item item : DockView.Item.values()) {
            if (item.toggleable && items.contains(item)) ids.put(item.id);
        }
        return put(KEY_DOCK_TOGGLES, ids);
    }

    /** Tamanhos do dock em % do padrão (altura, ícones e textos), na ordem das Configurações. */
    public static final int[] DOCK_SIZES = {60, 75, 85, 100, 110};

    /** Tamanho do dock em % ({@link #DOCK_SIZES}); 100 por padrão. */
    public static int dockSize(Context c) {
        int v = integer(KEY_DOCK_SIZE, 100);
        for (int known : DOCK_SIZES) {
            if (known == v) return v;
        }
        return 100;
    }

    public static boolean setDockSize(Context c, int percent) {
        return put(KEY_DOCK_SIZE, percent);
    }

    public static boolean resetDockToggles(Context c) {
        return put(KEY_DOCK_TOGGLES, null);
    }

    /** Índice em {@link #ZLINK_NAMES} do nome do Z-Link no dock; Z-Link por padrão. */
    public static int zlinkName(Context c) {
        int i = integer(KEY_ZLINK_NAME, 0);
        return i >= 0 && i < ZLINK_NAMES.length ? i : 0;
    }

    public static boolean setZlinkName(Context c, int index) {
        return put(KEY_ZLINK_NAME, index);
    }

    /** false = o launcher desconecta o ZLINK quando ele conecta sem o usuário ter aberto o ZLINK. */
    public static boolean zlinkAutoConnect(Context c) {
        return bool(KEY_ZLINK_AUTO_CONNECT, true);
    }

    public static boolean setZlinkAutoConnect(Context c, boolean on) {
        return put(KEY_ZLINK_AUTO_CONNECT, on);
    }

    /** Ao conectar: não abre (esconde a tela do ZLINK), abre, ou abre se estava aberto ao desligar. */
    public static final int ZLINK_OPEN_NO = 0;
    public static final int ZLINK_OPEN_YES = 1;
    public static final int ZLINK_OPEN_LAST = 2;

    public static int zlinkAutoOpen(Context c) {
        return integer(KEY_ZLINK_AUTO_OPEN, ZLINK_OPEN_YES);
    }

    public static boolean setZlinkAutoOpen(Context c, int mode) {
        return put(KEY_ZLINK_AUTO_OPEN, mode);
    }

    /** Esconder a barra flutuante da NWD enquanto um app da suíte está na frente. Desligado por padrão. */
    public static boolean hideBottomBar(Context c) {
        return bool(KEY_HIDE_BOTTOM_BAR, false);
    }

    public static boolean setHideBottomBar(Context c, boolean on) {
        return put(KEY_HIDE_BOTTOM_BAR, on);
    }

    // ------------------------------------------------------------------ tela inicial

    public static boolean showVoltmeter(Context c) {
        return bool(KEY_VOLTMETER, true);
    }

    public static boolean setShowVoltmeter(Context c, boolean on) {
        return put(KEY_VOLTMETER, on);
    }

    /** Uso de RAM e CPU ao lado do voltímetro. */
    public static boolean showSysMonitor(Context c) {
        return bool(KEY_SYS_MONITOR, true);
    }

    public static boolean setShowSysMonitor(Context c, boolean on) {
        return put(KEY_SYS_MONITOR, on);
    }

    /** Gravar os logs dos apps em /sdcard/K4811/logs (ver LogFile). Desligado por padrão. */
    /**
     * Ao ligar o carro, se outro app abrir sozinho (ex.: o player de fábrica, pela última fonte), volta
     * para a tela inicial ou para o último app da suíte em uso (launcher {@code start/StartGuard}).
     */
    public static boolean startGuard(Context c) {
        return bool(KEY_START_GUARD, true);
    }

    public static boolean setStartGuard(Context c, boolean on) {
        return put(KEY_START_GUARD, on);
    }

    /**
     * Apaga as notificações dos apps assim que aparecem (launcher {@code nav/MediaListener}; precisa do
     * acesso a notificações do launcher). As fixas (player tocando, serviço) o Android não deixa apagar.
     */
    public static boolean blockNotifications(Context c) {
        return bool(KEY_BLOCK_NOTIFICATIONS, false);
    }

    public static boolean setBlockNotifications(Context c, boolean on) {
        return put(KEY_BLOCK_NOTIFICATIONS, on);
    }

    public static boolean saveLogs(Context c) {
        return bool(KEY_SAVE_LOGS, false);
    }

    public static boolean setSaveLogs(Context c, boolean on) {
        return put(KEY_SAVE_LOGS, on);
    }

    /** Sol/lua ao lado do voltímetro (lua = farol aceso). */
    public static boolean showDayNight(Context c) {
        return bool(KEY_DAY_NIGHT, true);
    }

    public static boolean setShowDayNight(Context c, boolean on) {
        return put(KEY_DAY_NIGHT, on);
    }

    public static int screenOffAfter(Context c) {
        return integer(KEY_SCREEN_OFF_AFTER, 0);
    }

    public static boolean setScreenOffAfter(Context c, int seconds) {
        return put(KEY_SCREEN_OFF_AFTER, seconds);
    }

    /**
     * Área de cada módulo da tela inicial; módulo fora do mapa fica escondido. Sem nada gravado,
     * a tela inicial original (o volume só se o antigo "mostrar controles de volume" estava ligado).
     */
    public static Map<HomeGrid.Module, HomeGrid.Slot> homeLayout(Context c) {
        Map<HomeGrid.Module, HomeGrid.Slot> out = new EnumMap<>(HomeGrid.Module.class);
        String raw = string(KEY_HOME_LAYOUT, null);
        if (raw == null) {
            out.put(HomeGrid.Module.NOW_PLAYING, HomeGrid.Slot.LEFT);
            out.put(HomeGrid.Module.CLOCK, HomeGrid.Slot.CENTER_TOP);
            if (bool(KEY_VOLUME, true)) out.put(HomeGrid.Module.VOLUME, HomeGrid.Slot.CENTER_BOTTOM);
            out.put(HomeGrid.Module.FAN, HomeGrid.Slot.RIGHT_TOP);
            out.put(HomeGrid.Module.BLUETOOTH, HomeGrid.Slot.RIGHT_MIDDLE);
            return out;
        }
        for (String pair : raw.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length != 2) continue;
            HomeGrid.Module m = HomeGrid.Module.byId(kv[0].trim());
            HomeGrid.Slot s = HomeGrid.Slot.byId(kv[1].trim());
            if (m != null && s != null && !out.containsValue(s)) out.put(m, s);
        }
        return out;
    }

    /** De volta à tela inicial original, com o volume. */
    public static boolean resetHomeLayout(Context c) {
        return put(KEY_HOME_LAYOUT, DEFAULT_HOME_LAYOUT);
    }

    /**
     * Põe {@code module} em {@code slot} (null = escondido). Se outro módulo já estiver lá, os dois
     * trocam de lugar (o outro fica escondido se {@code module} não tinha área).
     */
    public static boolean setHomeSlot(Context c, HomeGrid.Module module, HomeGrid.Slot slot) {
        Map<HomeGrid.Module, HomeGrid.Slot> layout = homeLayout(c);
        HomeGrid.Slot old = layout.remove(module);
        if (slot != null) {
            for (HomeGrid.Module other : HomeGrid.Module.values()) {
                if (layout.get(other) != slot) continue;
                if (old != null) layout.put(other, old);
                else layout.remove(other);
            }
            layout.put(module, slot);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<HomeGrid.Module, HomeGrid.Slot> e : layout.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey().id).append(':').append(e.getValue().id);
        }
        return put(KEY_HOME_LAYOUT, sb.toString());
    }

    /** Mostrar o volume por cima de qualquer app quando ele muda (VolumeOverlayService do launcher). */
    public static boolean showVolumeOverlay(Context c) {
        return bool(KEY_VOLUME_OVERLAY, true);
    }

    public static boolean setShowVolumeOverlay(Context c, boolean on) {
        return put(KEY_VOLUME_OVERLAY, on);
    }

    /** Borda do menu Voltar | Vol− Vol+ | Início (EdgeMenuService do launcher), na ordem das Configurações. */
    public static final int EDGE_MENU_OFF = 0;
    public static final int EDGE_MENU_BOTTOM = 1;
    public static final int EDGE_MENU_LEFT = 2;
    public static final int EDGE_MENU_RIGHT = 3;
    public static final int[] EDGE_MENU_SIDES = {EDGE_MENU_OFF, EDGE_MENU_BOTTOM, EDGE_MENU_LEFT, EDGE_MENU_RIGHT};

    /** De qual borda o menu sai (deslizando dela para dentro); {@link #EDGE_MENU_OFF} = sem menu. */
    public static int edgeMenuSide(Context c) {
        int fallback = bool(KEY_EDGE_MENU, true) ? EDGE_MENU_BOTTOM : EDGE_MENU_OFF;
        int side = integer(KEY_EDGE_MENU_SIDE, fallback);
        return side >= EDGE_MENU_OFF && side <= EDGE_MENU_RIGHT ? side : fallback;
    }

    public static boolean setEdgeMenuSide(Context c, int side) {
        return put(KEY_EDGE_MENU_SIDE, side);
    }

    // ------------------------------------------------------------------ tela de navegação

    /** O que a tela de navegação mostra acima da música, na ordem das Configurações. */
    public static final String NAV_INFO_CLOCK = "clock";
    public static final String NAV_INFO_SPEED = "speed";
    public static final String NAV_INFO_NONE = "none";
    public static final String[] NAV_INFOS = {NAV_INFO_CLOCK, NAV_INFO_SPEED, NAV_INFO_NONE};

    /** Mapa da tela de navegação, na ordem das Configurações. */
    public static final String NAV_MODE_INTEGRATED = "integrated";
    public static final String NAV_MODE_EXTERNAL = "external";
    public static final String NAV_MODE_AUTO = "auto";
    public static final String[] NAV_MODES = {NAV_MODE_INTEGRATED, NAV_MODE_EXTERNAL, NAV_MODE_AUTO};

    /** Mapa integrado (OpenStreetMap), app externo em tela dividida ou automático (integrado sem internet). */
    public static String navMode(Context c) {
        String id = string(KEY_NAV_MODE, NAV_MODE_AUTO);
        for (String known : NAV_MODES) {
            if (known.equals(id)) return id;
        }
        return NAV_MODE_AUTO;
    }

    public static boolean setNavMode(Context c, String id) {
        return put(KEY_NAV_MODE, id);
    }

    /** No mapa integrado, a música e a informação à direita (o mapa à esquerda). Padrão: à esquerda. */
    public static boolean navPanelRight(Context c) {
        return bool(KEY_NAV_PANEL_RIGHT, false);
    }

    public static boolean setNavPanelRight(Context c, boolean right) {
        return put(KEY_NAV_PANEL_RIGHT, right);
    }

    /** Instruções por voz no mapa integrado. */
    public static boolean navVoice(Context c) {
        return bool(KEY_NAV_VOICE, true);
    }

    public static boolean setNavVoice(Context c, boolean on) {
        return put(KEY_NAV_VOICE, on);
    }

    /** % da mídia enquanto a navegação fala, 0 a 100; -1 se nunca configurado (use MixVolumes). */
    public static int navMediaPercent(Context c) {
        return integer(KEY_NAV_MEDIA_PERCENT, -1);
    }

    public static boolean setNavMediaPercent(Context c, int percent) {
        return put(KEY_NAV_MEDIA_PERCENT, percent);
    }

    /** % das notificações em relação ao volume, 0 a 100; -1 se nunca configurado (use MixVolumes). */
    public static int notifyPercent(Context c) {
        return integer(KEY_NOTIFY_PERCENT, -1);
    }

    public static boolean setNotifyPercent(Context c, int percent) {
        return put(KEY_NOTIFY_PERCENT, percent);
    }

    /** Alertas de radar (tela e voz) no mapa integrado. */
    public static boolean navCameras(Context c) {
        return bool(KEY_NAV_CAMERAS, true);
    }

    public static boolean setNavCameras(Context c, boolean on) {
        return put(KEY_NAV_CAMERAS, on);
    }

    /** Placa com o limite de velocidade da via no mapa integrado. */
    public static boolean navSpeedLimit(Context c) {
        return bool(KEY_NAV_SPEED_LIMIT, true);
    }

    public static boolean setNavSpeedLimit(Context c, boolean on) {
        return put(KEY_NAV_SPEED_LIMIT, on);
    }

    /** Avisos de lombada, pedágio e passagem de nível no mapa integrado. */
    public static boolean navRoadAlerts(Context c) {
        return bool(KEY_NAV_ROAD_ALERTS, true);
    }

    public static boolean setNavRoadAlerts(Context c, boolean on) {
        return put(KEY_NAV_ROAD_ALERTS, on);
    }

    /**
     * Zoom dinâmico no mapa integrado, navegando: aproxima perto da próxima manobra ou do próximo
     * alerta (radar, lombada…) e afasta quando o próximo está longe.
     */
    public static boolean navAutoZoom(Context c) {
        return bool(KEY_NAV_AUTO_ZOOM, true);
    }

    public static boolean setNavAutoZoom(Context c, boolean on) {
        return put(KEY_NAV_AUTO_ZOOM, on);
    }

    /**
     * Mapa integrado em perspectiva com a direção do carro para cima: o chão inclinado, como uma câmera
     * atrás do carro olhando para a frente.
     */
    public static boolean navPerspective(Context c) {
        return bool(KEY_NAV_PERSPECTIVE, true);
    }

    public static boolean setNavPerspective(Context c, boolean on) {
        return put(KEY_NAV_PERSPECTIVE, on);
    }

    /**
     * Mapa offline do mapa integrado: baixa e desenha o mapa (ruas, água, prédios…) do raio de 100 km
     * e das rotas, nas mesmas regras dos dados das vias.
     */
    public static boolean navOfflineMap(Context c) {
        return bool(KEY_NAV_OFFLINE_MAP, true);
    }

    public static boolean setNavOfflineMap(Context c, boolean on) {
        return put(KEY_NAV_OFFLINE_MAP, on);
    }

    /** Mapa integrado com os dados do OpenStreetMap (funciona sem internet). */
    public static final String NAV_PROVIDER_OSM = "osm";
    /** Mapa integrado com tiles, busca e rota do Google (só com internet; sem ela, o OpenStreetMap). */
    public static final String NAV_PROVIDER_GOOGLE = "google";
    public static final String[] NAV_PROVIDERS = {NAV_PROVIDER_OSM, NAV_PROVIDER_GOOGLE};

    /** Serviços do mapa integrado ({@link #NAV_PROVIDERS}); OpenStreetMap por padrão. */
    public static String navMapProvider(Context c) {
        String id = string(KEY_NAV_MAP_PROVIDER, NAV_PROVIDER_OSM);
        for (String known : NAV_PROVIDERS) {
            if (known.equals(id)) return id;
        }
        return NAV_PROVIDER_OSM;
    }

    public static boolean setNavMapProvider(Context c, String id) {
        return put(KEY_NAV_MAP_PROVIDER, id);
    }

    /** Rota do Google com trânsito em tempo real (ligado por padrão). */
    public static boolean navGoogleTraffic(Context c) {
        return bool(KEY_NAV_GOOGLE_TRAFFIC, true);
    }

    public static boolean setNavGoogleTraffic(Context c, boolean on) {
        return put(KEY_NAV_GOOGLE_TRAFFIC, on);
    }

    /** De quantos em quantos dias os dados das vias (radares, limites) são conferidos, na ordem das Configurações. */
    public static final int[] NAV_DATA_DAYS = {1, 7, 30};

    public static int navDataDays(Context c) {
        int d = integer(KEY_NAV_DATA_DAYS, 7);
        for (int known : NAV_DATA_DAYS) {
            if (known == d) return d;
        }
        return 7;
    }

    public static boolean setNavDataDays(Context c, int days) {
        return put(KEY_NAV_DATA_DAYS, days);
    }

    /** Raio (km) em volta do carro dos dados baixados (vias, mapa, rota e endereços), na ordem das Configurações. */
    public static final int[] NAV_DATA_KM = {25, 50, 100, 200, 300, 500};

    public static int navDataKm(Context c) {
        int km = integer(KEY_NAV_DATA_KM, 100);
        for (int known : NAV_DATA_KM) {
            if (known == km) return km;
        }
        return 100;
    }

    public static boolean setNavDataKm(Context c, int km) {
        return put(KEY_NAV_DATA_KM, km);
    }

    /** Package do app de navegação escolhido; null = automático (ver NavApps). */
    public static String navApp(Context c) {
        return string(KEY_NAV_APP, null);
    }

    public static boolean setNavApp(Context c, String pkg) {
        return put(KEY_NAV_APP, pkg);
    }

    /** Package do app que o botão Música do dock abre; null = o MP3 da suíte (ver MusicApps). */
    public static String musicApp(Context c) {
        return string(KEY_MUSIC_APP, null);
    }

    public static boolean setMusicApp(Context c, String pkg) {
        return put(KEY_MUSIC_APP, pkg);
    }

    /** Informação acima da música ({@link #NAV_INFOS}); relógio por padrão. */
    public static String navInfo(Context c) {
        String id = string(KEY_NAV_INFO, NAV_INFO_CLOCK);
        for (String known : NAV_INFOS) {
            if (known.equals(id)) return id;
        }
        return NAV_INFO_CLOCK;
    }

    public static boolean setNavInfo(Context c, String id) {
        return put(KEY_NAV_INFO, id);
    }

    /** Caminho da imagem do módulo Imagem (ver HomeImage); null se nenhuma foi escolhida. */
    public static String homeImage(Context c) {
        return string(KEY_HOME_IMAGE, null);
    }

    public static boolean setHomeImage(Context c, String path) {
        return put(KEY_HOME_IMAGE, path);
    }

    /**
     * Perguntar na abertura do launcher se ele deve virar o padrão. Desligado quando o usuário
     * escolhe outro launcher (para não insistir); religado quando escolhe este.
     */
    public static boolean askDefaultLauncher(Context c) {
        return bool(KEY_ASK_DEFAULT, true);
    }

    public static boolean setAskDefaultLauncher(Context c, boolean ask) {
        return put(KEY_ASK_DEFAULT, ask);
    }

    // ------------------------------------------------------------------ gravação (só o car-setting)

    /** Cria o arquivo com os padrões se ele ainda não existir. */
    public static boolean ensureExists(Context c) {
        if (NwdConfigs.file(FILE).isFile()) return true;
        return put(KEY_LANGUAGE, language(c));
    }

    private static synchronized boolean put(String key, Object value) {
        JSONObject current = json();
        JSONObject next;
        try {
            next = new JSONObject();
            if (current != null) {
                for (Iterator<String> it = current.keys(); it.hasNext(); ) {
                    String k = it.next();
                    next.put(k, current.get(k));
                }
            }
            next.put(KEY_VERSION, VERSION);
            if (value == null) next.remove(key);
            else next.put(key, value);
        } catch (JSONException e) {
            return false;
        }
        boolean ok = NwdConfigs.write(NwdConfigs.file(FILE), next);
        cachedModified = -1; // força reler na próxima consulta
        return ok;
    }
}
