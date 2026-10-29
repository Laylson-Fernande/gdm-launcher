package com.k4811.common.system;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * Brilho da tela.
 * <ul>
 * <li><b>Central NWD</b> (SettingService instalado e {@code mcu_back_light_max} gravado): o brilho é da
 * MCU, de 0 a {@code mcu_back_light_max} (10 por padrão). Lê {@code mcu_back_light_day} ou
 * {@code mcu_back_light_night} (noite = "Escurecer com o farol" ligado e farol aceso) e grava com
 * {@code setDayBackLight}, que vale para o modo atual, como no Config. Veículo de fábrica.</li>
 * <li><b>Outros aparelhos</b> (e o emulador): o brilho do Android, {@code Settings.System.SCREEN_BRIGHTNESS}
 * de 1 a 255 em modo manual. Com targetSdk 19, {@code WRITE_SETTINGS} é concedida na instalação.</li>
 * </ul>
 */
public final class Brightness {

    private static final String TAG = "K4811Brightness";
    private static final String KEY_DAY = "mcu_back_light_day";
    private static final String KEY_NIGHT = "mcu_back_light_night";
    private static final String KEY_MAX = "mcu_back_light_max";
    private static final int ANDROID_MAX = 255;
    /** Mínimo da barra, em % do máximo. */
    private static final int MIN_PERCENT = 20;

    private Brightness() {
    }

    /** Brilho pela MCU da NWD (senão, o do Android). */
    public static boolean nwd(Context c) {
        return NwdSettings.isInstalled(c) && NwdSettings.has(c, KEY_MAX);
    }

    /** Valor máximo da escala ({@link #get} vai de 0 ou 1 até ele). */
    public static int max(Context c) {
        if (nwd(c)) return Math.max(1, NwdSettings.getInt(c, KEY_MAX, 10));
        return ANDROID_MAX;
    }

    /**
     * Menor valor da barra: {@value #MIN_PERCENT}% do máximo (2 de 10 na MCU). Em 0 a tela da central fica
     * praticamente apagada e não dá para ver a barra para subir de novo.
     */
    public static int min(Context c) {
        return Math.max(1, Math.round(max(c) * MIN_PERCENT / 100f));
    }

    /** Brilho noturno guardado pela MCU (Config. Veículo); fora da NWD, um terço do máximo. */
    public static int nightDefault(Context c) {
        int v = nwd(c) ? NwdSettings.getInt(c, KEY_NIGHT, max(c) * 2 / 5) : max(c) / 3;
        return Math.max(min(c), Math.min(max(c), v));
    }

    /** O brilho noturno está valendo agora (escurecer com o farol ligado e farol aceso). */
    public static boolean night(Context c) {
        Boolean dimmer = NwdSettings.systemBit(c, NwdSettings.BIT_ILL_DIMMER);
        return dimmer != null && dimmer && NwdSettings.headlightsOn(c);
    }

    public static int get(Context c) {
        if (nwd(c)) return NwdSettings.getInt(c, night(c) ? KEY_NIGHT : KEY_DAY, max(c) / 2);
        try {
            return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            return ANDROID_MAX / 2;
        }
    }

    public static void set(Context c, int value) {
        int v = Math.max(min(c), Math.min(max(c), value));
        if (nwd(c)) {
            NwdSettings.setDayBackLight(v);
            return;
        }
        try {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
        } catch (Exception e) {
            Log.w(TAG, "brilho do Android: " + e);
        }
    }
}
