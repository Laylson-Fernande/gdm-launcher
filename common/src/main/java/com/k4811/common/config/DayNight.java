package com.k4811.common.config;

import android.content.Context;

import com.k4811.common.system.NwdSettings;

import java.util.Calendar;

/**
 * Dia ou noite agora, pela opção "Mudar para noite" das Configurações ({@link LauncherConfig#dayNightBy}):
 * <ul>
 * <li><b>Com o farol</b> (padrão): noite enquanto o farol está aceso (sinal ILL da MCU).</li>
 * <li><b>Por horário</b>: noite entre {@link LauncherConfig#nightStart} e {@link LauncherConfig#dayStart}
 * (pode passar da meia-noite). O farol não muda nada.</li>
 * </ul>
 * O que a noite muda (tema, brilho ou os dois) é {@link LauncherConfig#dayNightAffects}: o tema segue
 * {@link LauncherConfig#darkMode}; o brilho por horário é aplicado pelo launcher.
 */
public final class DayNight {
    private DayNight() {
    }

    public static boolean byTime(Context c) {
        return LauncherConfig.DAY_NIGHT_BY_TIME.equals(LauncherConfig.dayNightBy(c));
    }

    public static boolean isNight(Context c) {
        if (!byTime(c)) return NwdSettings.headlightsOn(c);
        return isNightAt(minutesNow(), LauncherConfig.nightStart(c), LauncherConfig.dayStart(c));
    }

    /** Noite em {@code now} (minutos do dia) com a noite começando em {@code night} e o dia em {@code day}. */
    static boolean isNightAt(int now, int night, int day) {
        if (night == day) return false;
        if (night < day) return now >= night && now < day;
        return now >= night || now < day; // a noite passa da meia-noite
    }

    /** Milissegundos até a próxima troca por horário (pelo menos 1 s). */
    public static long millisToNextChange(Context c) {
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int next = Integer.MAX_VALUE;
        for (int at : new int[]{LauncherConfig.nightStart(c), LauncherConfig.dayStart(c)}) {
            int wait = (at - now + 24 * 60) % (24 * 60);
            if (wait == 0) wait = 24 * 60;
            next = Math.min(next, wait);
        }
        long ms = next * 60000L - cal.get(Calendar.SECOND) * 1000L - cal.get(Calendar.MILLISECOND);
        return Math.max(1000L, ms);
    }

    private static int minutesNow() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /** "18:30". */
    public static String format(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
