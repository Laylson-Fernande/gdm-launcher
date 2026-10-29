package com.k4811.common;

/**
 * Packages dos apps da suíte (tabela no build.gradle.kts da raiz, entregue pelo BuildConfig): os
 * originais da NWD, menos o launcher (com.garagemdomadeira.nwd.launcher) e o car-setting
 * (com.nwd.car.setting). O código Java fica sempre em com.k4811.*.
 */
public final class Apps {
    private Apps() {
    }

    public static final String LAUNCHER = BuildConfig.PKG_LAUNCHER;
    public static final String RADIO = BuildConfig.PKG_RADIO;
    public static final String MUSIC = BuildConfig.PKG_MUSIC;
    public static final String BT_MUSIC = BuildConfig.PKG_BT_MUSIC;
    public static final String PHONE = BuildConfig.PKG_PHONE;
    public static final String EQ = BuildConfig.PKG_EQ;
    /** Ventoinha. Aberta pelo bloco da ventoinha na tela inicial (fora do dock). */
    public static final String FAN = BuildConfig.PKG_FAN;
    /** Configurações (launcher, dock, idioma e central). Aberto pela engrenagem da tela inicial. */
    public static final String SETTINGS = BuildConfig.PKG_CAR_SETTING;

    /** true se o package é de um app da suíte. */
    public static boolean isSuite(String pkg) {
        if (pkg == null) return false;
        for (String p : new String[]{LAUNCHER, RADIO, MUSIC, BT_MUSIC, PHONE, EQ, FAN, SETTINGS}) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    /** Gaveta de apps do launcher. */
    public static final String ACTION_APPS = "com.k4811.launcher.action.APPS";
    /** Tela de navegação do launcher (mapa em tela dividida, música e informação). */
    public static final String ACTION_NAV_HOME = "com.k4811.launcher.action.NAV_HOME";

    /**
     * Enviado por um app antes de virar a fonte de áudio. Os players do suíte pausam ao receber,
     * a não ser que sejam o remetente (extra {@link #EXTRA_FROM}).
     */
    public static final String ACTION_PAUSE_PLAYERS = "com.k4811.action.PAUSE_PLAYERS";
    public static final String EXTRA_FROM = "from";

    /**
     * Pede ao car-setting (o único que grava o launcher.json) para ligar ou desligar o modo escuro:
     * a barra de brilho do launcher usa. Extra {@link #EXTRA_ON} (boolean).
     */
    public static final String ACTION_SET_DARK_MODE = "com.k4811.action.SET_DARK_MODE";
    public static final String EXTRA_ON = "on";

    /**
     * Uma tela de um app da suíte veio para a frente (enviado pelo {@code SuiteApp} ao launcher, que
     * guarda a última para reabrir quando o carro liga). Extra {@link #EXTRA_COMPONENT}.
     */
    public static final String ACTION_SCREEN_SHOWN = "com.k4811.action.SCREEN_SHOWN";
    public static final String EXTRA_COMPONENT = "component";

    /**
     * Aba Memória das Configurações: pergunta (ordenado) quanto cada app da suíte que está rodando usa
     * de RAM; cada um põe nos extras do resultado o package com o uso em KB ({@code system/AppMemory}).
     */
    public static final String ACTION_MEMORY_QUERY = "com.k4811.action.MEMORY_QUERY";
    /** Encerra o app da suíte do {@code setPackage} (para os serviços e o processo; {@code system/AppMemory}). */
    public static final String ACTION_QUIT = "com.k4811.action.QUIT";

    /**
     * Rota do app de navegação (o Waze modificado do projeto recompile-optimized publica; a tela de
     * navegação do launcher mostra ao lado). Extras: {@code app}, {@code active}; navegando,
     * {@code distance}, {@code cue}, {@code icon} (Bitmap branco), {@code remaining},
     * {@code remaining_distance}, {@code arrival}. Textos já formatados pelo app.
     */
    public static final String ACTION_NAV_STATE = "com.k4811.action.NAV_STATE";
    /** Pede ao app de navegação o último {@link #ACTION_NAV_STATE}. */
    public static final String ACTION_NAV_QUERY = "com.k4811.action.NAV_QUERY";
    /**
     * O painel da tela de navegação (que mostra a rota) está visível ao lado do app de navegação:
     * extra {@link #EXTRA_SHOWN}. O Waze modificado esconde os próprios cartões de rota enquanto
     * ele aparece. Mandado no onStart/onStop do painel e em resposta a {@link #ACTION_NAV_PANEL_QUERY}.
     */
    public static final String ACTION_NAV_PANEL = "com.k4811.action.NAV_PANEL";
    public static final String EXTRA_SHOWN = "shown";
    /**
     * Instância do painel que mandou o {@link #ACTION_NAV_PANEL} (elapsedRealtime da criação):
     * ao entrar na tela dividida o painel fecha e reabre, e o "saí" da instância velha pode chegar
     * depois do "apareci" da nova; o app ignora o de instância mais antiga.
     */
    public static final String EXTRA_INSTANCE = "instance";
    /** O app de navegação pergunta se o painel está visível (responde com {@link #ACTION_NAV_PANEL}). */
    public static final String ACTION_NAV_PANEL_QUERY = "com.k4811.action.NAV_PANEL_QUERY";
    /** Extra do launcher ao abrir o app de navegação: o modo escuro em uso na suíte (boolean). */
    public static final String EXTRA_DARK_MODE = "com.k4811.extra.DARK_MODE";
}
