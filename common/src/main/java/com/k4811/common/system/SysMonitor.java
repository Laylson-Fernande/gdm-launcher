package com.k4811.common.system;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Uso de RAM e de CPU da central, para o indicador ao lado do voltímetro.
 *
 * RAM: {@link ActivityManager.MemoryInfo} (sempre disponível). CPU, na ordem: {@code /proc/stat}
 * (uso real entre duas leituras; o Android 8+ costuma bloquear para apps), a frequência atual dos
 * núcleos em relação à máxima ({@code cpufreq}, legível na maioria das centrais ARM) ou nada.
 * Uma instância por tela, lida a cada poucos segundos na thread principal (leituras de µs).
 */
public final class SysMonitor {

    public static final class Sample {
        /** RAM em uso, 0–100. */
        public final int ramPercent;
        public final long ramUsedMb;
        public final long ramTotalMb;
        /** CPU, 0–100; -1 se a central não deixa ler. */
        public final int cpuPercent;

        Sample(int ramPercent, long ramUsedMb, long ramTotalMb, int cpuPercent) {
            this.ramPercent = ramPercent;
            this.ramUsedMb = ramUsedMb;
            this.ramTotalMb = ramTotalMb;
            this.cpuPercent = cpuPercent;
        }
    }

    private final ActivityManager am;
    private final ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
    private boolean statBlocked;
    private long lastTotal = -1;
    private long lastIdle;
    private int cores = -1;

    public SysMonitor(Context c) {
        am = (ActivityManager) c.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
    }

    public Sample sample() {
        am.getMemoryInfo(mem);
        long total = mem.totalMem;
        long used = Math.max(0, total - mem.availMem);
        int ram = total > 0 ? (int) Math.round(100.0 * used / total) : 0;
        return new Sample(ram, used >> 20, total >> 20, cpu());
    }

    private int cpu() {
        if (!statBlocked) {
            int p = fromStat();
            if (p != Integer.MIN_VALUE) return p;
        }
        return fromFrequency();
    }

    /** Uso entre esta leitura e a anterior pela 1ª linha de /proc/stat; MIN_VALUE se bloqueado. */
    private int fromStat() {
        String line;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"), 256)) {
            line = r.readLine();
        } catch (Exception e) {
            statBlocked = true;
            return Integer.MIN_VALUE;
        }
        if (line == null || !line.startsWith("cpu ")) {
            statBlocked = true;
            return Integer.MIN_VALUE;
        }
        String[] f = line.trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < f.length; i++) total += parse(f[i]);
        // idle + iowait
        long idle = parse(f[4]) + (f.length > 5 ? parse(f[5]) : 0);
        int out = -1;
        if (lastTotal >= 0 && total > lastTotal) {
            long dt = total - lastTotal;
            long di = idle - lastIdle;
            out = (int) Math.max(0, Math.min(100, Math.round(100.0 * (dt - di) / dt)));
        }
        lastTotal = total;
        lastIdle = idle;
        return out;
    }

    /** Média de frequência atual / máxima dos núcleos (aproxima a carga); -1 se não der para ler. */
    private int fromFrequency() {
        if (cores < 0) cores = countCores();
        if (cores == 0) return -1;
        double sum = 0;
        int read = 0;
        for (int i = 0; i < cores; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            long cur = readLong(base + "scaling_cur_freq");
            long max = readLong(base + "cpuinfo_max_freq");
            if (cur <= 0 || max <= 0) continue; // núcleo desligado
            sum += Math.min(1.0, cur / (double) max);
            read++;
        }
        if (read == 0) {
            if (cores > 0 && !new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").canRead()) cores = 0;
            return -1;
        }
        // Núcleos desligados contam como parados.
        return (int) Math.round(100.0 * sum / cores);
    }

    private static int countCores() {
        File[] list = new File("/sys/devices/system/cpu/").listFiles(
            (dir, name) -> name.matches("cpu[0-9]+"));
        return list == null ? 0 : list.length;
    }

    private static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path), 64)) {
            String s = r.readLine();
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
