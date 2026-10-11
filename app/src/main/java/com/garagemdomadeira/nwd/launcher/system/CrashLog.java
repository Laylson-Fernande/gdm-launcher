package com.garagemdomadeira.nwd.launcher.system;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes the cause of the app's last crash (stack trace) to an internal file, so it can be viewed
 * on the Settings screen without adb. Also goes to logcat with the tag GaragemCrash.
 */
public final class CrashLog {
    private CrashLog() {
    }

    private static final String TAG = "GaragemCrash";
    private static final String FILE = "last_crash.txt";

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(app, thread, error);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void save(Context context, Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date()));
        pw.println("Thread: " + thread.getName());
        pw.println("Android " + Build.VERSION.RELEASE + " · " + Build.DISPLAY);
        pw.println();
        error.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();
        Log.e(TAG, text);
        FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE));
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /** Text of the last crash, or null if there is none. */
    public static String read(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] data = new byte[(int) Math.min(f.length(), 64 * 1024)];
                int n = in.read(data);
                return n > 0 ? new String(data, 0, n, "UTF-8") : null;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        new File(context.getFilesDir(), FILE).delete();
    }
}
