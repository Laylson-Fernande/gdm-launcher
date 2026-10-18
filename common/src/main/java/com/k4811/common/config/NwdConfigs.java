package com.k4811.common.config;

import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Pasta /sdcard/nwd-configs, compartilhada pelo launcher e pelos apps do suíte.
 * Um arquivo JSON por app dono: só o dono grava o seu, todos podem ler.
 */
public final class NwdConfigs {
    private NwdConfigs() {
    }

    private static final String TAG = "NwdConfigs";
    public static final String DIR = "nwd-configs";

    public static File dir() {
        return new File(Environment.getExternalStorageDirectory(), DIR);
    }

    public static File file(String name) {
        return new File(dir(), name);
    }

    /** Conteúdo do arquivo, ou null se não existir ou não der para ler. */
    public static JSONObject read(File f) {
        if (!f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível ler " + f + ": " + e);
            return null;
        } finally {
            close(in);
        }
    }

    /** Grava em .tmp e renomeia: quem lê nunca pega o arquivo pela metade. */
    public static boolean write(File f, JSONObject json) {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "Não foi possível criar " + parent);
            return false;
        }
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(json.toString(2).getBytes("UTF-8"));
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível gravar " + tmp + ": " + e);
            close(out);
            tmp.delete();
            return false;
        }
        close(out);
        if (!tmp.renameTo(f)) {
            Log.w(TAG, "Não foi possível renomear " + tmp + " para " + f);
            tmp.delete();
            return false;
        }
        return true;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
