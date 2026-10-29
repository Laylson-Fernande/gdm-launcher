package com.k4811.car.setting.system;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;

import com.k4811.car.setting.R;
import com.k4811.car.setting.ui.OptionList;
import com.k4811.common.Apps;
import com.k4811.common.ui.Ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aba Memória das Configurações: quanto da RAM está em uso e quais apps estão rodando, do que mais
 * usa para o que menos usa, com um botão para encerrar cada um.
 *
 * <p>Os apps da suíte respondem por si ({@link Apps#ACTION_MEMORY_QUERY}, ver
 * {@code common/.../system/AppMemory}) e se encerram com {@link Apps#ACTION_QUIT}. Os outros apps só
 * aparecem se a central deixar ver: a lista de processos do Android (até o Android 5.0, ou numa ROM
 * que libere) ou o {@code /proc} (ROM sem {@code hidepid}). Para encerrar um app de fora, tenta
 * {@code killBackgroundProcesses} (vale até o Android 13); se ele continuar rodando, abre a tela do app
 * no Android, onde o motorista toca em "Forçar parada".
 *
 * <p>Lê só ao abrir a aba, ao tocar em Atualizar e depois de encerrar um app: nada fica medindo.
 */
public final class MemoryPanel {
    private static final String TAG = "K4811Memory";
    private static final long QUERY_TIMEOUT_MS = 3000;
    private static final long AFTER_QUIT_MS = 1200;

    /** Um app rodando e quanto usa. */
    private static final class Entry {
        final String pkg;
        final long kb;
        final boolean suite;

        Entry(String pkg, long kb, boolean suite) {
            this.pkg = pkg;
            this.kb = kb;
            this.suite = suite;
        }
    }

    private final Activity activity;
    private final LinearLayout container;
    private final ActivityManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OptionList list;
    /** Mudou a cada leitura: respostas atrasadas de uma leitura anterior são ignoradas. */
    private int generation;
    private boolean reading;
    /** App de fora que pedimos para encerrar: se continuar rodando, abre a tela dele no Android. */
    private String quitting;

    public MemoryPanel(Activity activity, LinearLayout container) {
        this.activity = activity;
        this.container = container;
        am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        generation++;
        if (list != null) list.release();
    }

    /** Lê de novo: primeiro os apps da suíte (pergunta ordenada), depois os de fora (outra thread). */
    public void refresh() {
        if (reading) return;
        reading = true;
        final int gen = ++generation;
        if (list == null) render(null, false);
        Intent q = new Intent(Apps.ACTION_MEMORY_QUERY);
        q.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final boolean[] answered = {false};
        final Runnable timeout = () -> {
            // Algum app travou na resposta: segue com os de fora.
            if (gen != generation || answered[0]) return;
            answered[0] = true;
            readOthers(gen, new Bundle());
        };
        handler.postDelayed(timeout, QUERY_TIMEOUT_MS);
        activity.sendOrderedBroadcast(q, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handler.removeCallbacks(timeout);
                if (gen != generation || answered[0]) return;
                answered[0] = true;
                Bundle r = getResultExtras(true);
                readOthers(gen, r == null ? new Bundle() : r);
            }
        }, handler, Activity.RESULT_OK, null, new Bundle());
    }

    private void readOthers(int gen, Bundle suite) {
        final String own = activity.getPackageName();
        new Thread(() -> {
            Map<String, Long> others = new HashMap<>();
            boolean visible = fromProcessList(others, own) || fromProc(others);
            List<Entry> entries = new ArrayList<>();
            for (String pkg : suite.keySet()) entries.add(new Entry(pkg, suite.getLong(pkg), true));
            for (Map.Entry<String, Long> e : others.entrySet()) {
                if (!suite.containsKey(e.getKey()) && !own.equals(e.getKey())) {
                    entries.add(new Entry(e.getKey(), e.getValue(), false));
                }
            }
            Collections.sort(entries, (a, b) -> Long.compare(b.kb, a.kb));
            handler.post(() -> {
                if (gen != generation || activity.isFinishing()) return;
                reading = false;
                render(entries, visible);
                checkQuitting(entries);
            });
        }, "memory").start();
    }

    // ------------------------------------------------------------------ fontes dos apps de fora

    /**
     * Lista de processos do Android com o PSS de cada um. Num Android 5.1+ comum só vem o próprio app
     * (e o PSS dos outros vem zerado no 10+): aí devolve false.
     */
    private boolean fromProcessList(Map<String, Long> out, String own) {
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            List<ActivityManager.RunningAppProcessInfo> foreign = new ArrayList<>();
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.uid != Process.myUid() && p.uid >= Process.FIRST_APPLICATION_UID) foreign.add(p);
            }
            if (foreign.isEmpty()) return false;
            int[] pids = new int[foreign.size()];
            for (int i = 0; i < pids.length; i++) pids[i] = foreign.get(i).pid;
            Debug.MemoryInfo[] mem = am.getProcessMemoryInfo(pids);
            boolean any = false;
            for (int i = 0; i < pids.length; i++) {
                long kb = mem != null && i < mem.length && mem[i] != null ? mem[i].getTotalPss() : 0;
                if (kb <= 0) continue;
                any = true;
                add(out, packageOf(foreign.get(i)), kb);
            }
            return any;
        } catch (RuntimeException e) {
            Log.w(TAG, "lista de processos", e);
            return false;
        }
    }

    private static String packageOf(ActivityManager.RunningAppProcessInfo p) {
        if (p.pkgList != null && p.pkgList.length > 0) return p.pkgList[0];
        return processPackage(p.processName);
    }

    /** {@code /proc} de cada processo de app (memória residente): só numa ROM sem {@code hidepid}. */
    private boolean fromProc(Map<String, Long> out) {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return false;
        int myUid = Process.myUid();
        boolean any = false;
        for (File d : dirs) {
            String name = d.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int uid = procUid(d);
            if (uid < Process.FIRST_APPLICATION_UID || uid == myUid) continue;
            String cmd = firstLine(new File(d, "cmdline"));
            String statm = firstLine(new File(d, "statm"));
            if (cmd == null || statm == null) continue;
            String[] parts = statm.trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                long kb = Long.parseLong(parts[1]) * 4;
                if (kb <= 0) continue;
                add(out, processPackage(cmd.trim()), kb);
                any = true;
            } catch (NumberFormatException ignored) {
                // linha estranha: pula
            }
        }
        return any;
    }

    private static int procUid(File dir) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir, "status")), 1024)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
            }
        } catch (Exception ignored) {
            // processo de outro usuário (hidepid) ou que acabou de sair
        }
        return -1;
    }

    private static String firstLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f), 256)) {
            String line = r.readLine();
            return line == null ? null : line.replace('\0', ' ');
        } catch (Exception e) {
            return null;
        }
    }

    /** "com.app:servico" → "com.app". */
    private static String processPackage(String process) {
        if (process == null) return "?";
        int colon = process.indexOf(':');
        String p = colon > 0 ? process.substring(0, colon) : process;
        int space = p.indexOf(' ');
        return space > 0 ? p.substring(0, space) : p;
    }

    private static void add(Map<String, Long> out, String pkg, long kb) {
        Long old = out.get(pkg);
        out.put(pkg, old == null ? kb : old + kb);
    }

    // ------------------------------------------------------------------ tela

    /** entries null = lendo pela primeira vez. */
    private void render(List<Entry> entries, boolean othersVisible) {
        if (list != null) list.release();
        container.removeAllViews();
        list = new OptionList(activity, container);

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem;
        long used = Math.max(0, total - mi.availMem);
        int percent = total > 0 ? (int) Math.round(100.0 * used / total) : 0;
        list.section(activity.getString(R.string.settings_memory_section));
        list.info(activity.getString(R.string.settings_memory_used),
            () -> activity.getString(R.string.settings_memory_of, size(used >> 10), size(total >> 10))
                + " · " + percent + "%")
            .help(activity.getString(R.string.settings_help_memory_used));
        list.info(activity.getString(R.string.settings_memory_available), () -> size(mi.availMem >> 10));
        if (entries != null) {
            long suiteKb = 0;
            for (Entry e : entries) if (e.suite) suiteKb += e.kb;
            final long sum = suiteKb;
            list.info(activity.getString(R.string.settings_memory_suite), () -> size(sum));
        }

        list.section(activity.getString(R.string.settings_memory_apps));
        list.action(activity.getString(R.string.settings_memory_apps_hint),
            entries == null ? activity.getString(R.string.settings_memory_reading) : null,
            activity.getString(R.string.settings_memory_refresh), this::refresh)
            .help(activity.getString(R.string.settings_help_memory_apps));
        if (entries == null) return;
        if (!othersVisible) list.note(activity.getString(R.string.settings_memory_limited), Ui.TEXT_MUTED);

        String own = activity.getPackageName();
        for (Entry e : entries) {
            String sub = size(e.kb) + (e.pkg.equals(own) ? " · " + activity.getString(R.string.settings_memory_this_screen) : "");
            if (e.pkg.equals(own)) {
                list.plain(label(e.pkg), sub);
            } else {
                list.action(label(e.pkg), sub, activity.getString(R.string.settings_memory_quit), () -> quit(e));
            }
        }
    }

    private String label(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** KB → "850 MB" ou "1,8 GB". */
    private static String size(long kb) {
        double mb = kb / 1024.0;
        if (mb >= 1000) return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0);
        return String.format(Locale.getDefault(), "%d MB", Math.round(mb));
    }

    // ------------------------------------------------------------------ encerrar

    @SuppressWarnings("deprecation")
    private void quit(Entry e) {
        Log.d(TAG, "encerrar " + e.pkg);
        if (e.suite) {
            Intent i = new Intent(Apps.ACTION_QUIT);
            i.setPackage(e.pkg);
            activity.sendBroadcast(i);
        } else {
            try {
                am.killBackgroundProcesses(e.pkg);
            } catch (RuntimeException ex) {
                Log.w(TAG, "killBackgroundProcesses", ex);
            }
            quitting = e.pkg;
        }
        handler.postDelayed(this::refresh, AFTER_QUIT_MS);
    }

    /** O Android não deixou encerrar o app de fora (Android 14+, ou app em primeiro plano): abre a tela dele. */
    private void checkQuitting(List<Entry> entries) {
        String pkg = quitting;
        quitting = null;
        if (pkg == null) return;
        for (Entry e : entries) {
            if (!e.pkg.equals(pkg)) continue;
            Log.d(TAG, pkg + " continua rodando: abrindo a tela do app");
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
            } catch (RuntimeException ex) {
                Log.w(TAG, "tela do app", ex);
            }
            return;
        }
    }
}
