package com.k4811.common.system;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import com.k4811.common.Apps;
import com.k4811.common.config.LauncherConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Grava os logs do app em {@code /sdcard/K4811/logs/<app>.log} quando ligado nas Configurações
 * ({@code save_logs}), para diagnosticar na central sem adb.
 *
 * Roda um {@code logcat} do próprio app (um app comum só enxerga os logs do seu UID) numa thread
 * de fundo. Ao começar, retoma da hora da última linha gravada: pega o que o processo anterior
 * logou depois disso (inclusive o fim de um crash) sem repetir nada. Cada arquivo vai até
 * {@value #MAX_BYTES} bytes; aí vira {@code <app>.1.log} (um só anterior).
 *
 * {@link #sync} liga/desliga conforme a configuração; o SuiteApp chama ao abrir e a cada tela que volta.
 */
public final class LogFile {

    private static final String TAG = "K4811Log";
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static java.lang.Process logcat;
    private static Thread reader;
    private static Writer out;
    private static File file;
    private static long written;

    private LogFile() {
    }

    public static File dir() {
        return new File(Environment.getExternalStorageDirectory(), "K4811/logs");
    }

    /** Liga ou desliga conforme {@code save_logs}. Barato quando nada muda. */
    public static synchronized void sync(Context c) {
        boolean on = LauncherConfig.saveLogs(c);
        if (on && logcat == null) start(c.getApplicationContext());
        else if (!on && logcat != null) stop();
    }

    /**
     * Grava um texto direto no arquivo (o crash: o processo morre antes de o logcat entregar as
     * últimas linhas). Não faz nada se o registro estiver desligado.
     */
    public static synchronized void append(String text) {
        if (out == null) return;
        try {
            out.write(text);
            if (!text.endsWith("\n")) out.write('\n');
            out.flush();
        } catch (IOException ignored) {
            // Sem cartão/armazenamento: o crash continua no CrashLog interno.
        }
    }

    private static void start(Context app) {
        File d = dir();
        if (!d.isDirectory() && !d.mkdirs()) {
            Log.w(TAG, "sem acesso a " + d);
            return;
        }
        String name = shortName(app.getPackageName());
        file = new File(d, name + ".log");
        // Retoma de onde parou (o arquivo é tocado a cada gravação); sem arquivo, todo o buffer.
        long since = file.isFile() ? file.lastModified() + 1 : 0;
        try {
            out = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            written = file.length();
            out.write(header(app));
            out.flush();
            String[] cmd = since > 0
                ? new String[]{"logcat", "-v", "threadtime", "-T",
                    new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(since))}
                : new String[]{"logcat", "-v", "threadtime"};
            logcat = Runtime.getRuntime().exec(cmd);
        } catch (IOException e) {
            Log.w(TAG, "logcat: " + e);
            closeQuietly();
            return;
        }
        final java.lang.Process p = logcat;
        reader = new Thread(() -> read(p, name), "K4811Log");
        reader.setDaemon(true);
        reader.setPriority(Thread.MIN_PRIORITY);
        reader.start();
    }

    private static void read(java.lang.Process p, String name) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
            String line;
            while ((line = in.readLine()) != null) {
                synchronized (LogFile.class) {
                    if (out == null || logcat != p) return;
                    out.write(line);
                    out.write('\n');
                    written += line.length() + 1;
                    // Grava no disco quando o logcat para de mandar (não a cada linha).
                    if (!in.ready()) out.flush();
                    if (written > MAX_BYTES) rotate(name);
                }
            }
        } catch (IOException ignored) {
            // logcat encerrado (stop) ou processo morrendo.
        }
    }

    /** Arquivo cheio: o atual vira o anterior e começa um novo. */
    private static void rotate(String name) throws IOException {
        out.close();
        File old = new File(file.getParentFile(), name + ".1.log");
        //noinspection ResultOfMethodCallIgnored
        old.delete();
        //noinspection ResultOfMethodCallIgnored
        file.renameTo(old);
        out = new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8");
        written = 0;
    }

    private static void stop() {
        java.lang.Process p = logcat;
        logcat = null;
        if (p != null) p.destroy();
        closeQuietly();
    }

    private static void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // nada a fazer
            }
        }
        out = null;
        logcat = null;
    }

    private static String header(Context app) {
        String version = "?";
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
            // versão fica "?"
        }
        return "\n===== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
            + "  " + app.getPackageName() + " " + version + "  pid " + Process.myPid()
            + "  Android " + Build.VERSION.RELEASE + " (" + Build.DISPLAY + ", " + Build.MODEL + ")\n";
    }

    /** Nome do arquivo: o do módulo (launcher, radio, bt-music…); outro package, o package inteiro. */
    private static String shortName(String pkg) {
        String[][] names = {
            {Apps.LAUNCHER, "launcher"}, {Apps.SETTINGS, "car-setting"}, {Apps.RADIO, "radio"},
            {Apps.MUSIC, "music"}, {Apps.BT_MUSIC, "bt-music"}, {Apps.PHONE, "phone"},
            {Apps.EQ, "eq"}, {Apps.FAN, "fan"},
        };
        for (String[] n : names) if (n[0].equals(pkg)) return n[1];
        return pkg;
    }
}
