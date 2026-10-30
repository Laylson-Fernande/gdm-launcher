package com.k4811.map.google;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import com.k4811.map.BuildConfig;

import java.security.MessageDigest;

/**
 * Chave da API do Google Maps Platform e a identificação do app nos pedidos.
 *
 * A chave vem só do build ({@code k4811.googleMapsKey} no local.properties, fora do git). Os pedidos levam {@code X-Android-Package} e
 * {@code X-Android-Cert} (SHA-1 da assinatura do APK), para a chave poder ser restrita a este app no
 * Google Cloud.
 */
public final class GoogleKey {
    private GoogleKey() {
    }

    private static String cert;

    /** A chave em uso, ou "" se não há nenhuma. */
    public static String key(Context c) {
        return BuildConfig.GOOGLE_MAPS_KEY == null ? "" : BuildConfig.GOOGLE_MAPS_KEY.trim();
    }

    public static boolean has(Context c) {
        return !key(c).isEmpty();
    }

    /** SHA-1 da assinatura do APK em hexadecimal maiúsculo, sem separadores ("" se não der). */
    @SuppressWarnings("deprecation")
    static synchronized String cert(Context c) {
        if (cert != null) return cert;
        String out = "";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] sigs = pi.signatures;
            if (sigs != null && sigs.length > 0) {
                byte[] d = MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
                StringBuilder b = new StringBuilder(d.length * 2);
                for (byte x : d) b.append(String.format("%02X", x & 0xFF));
                out = b.toString();
            }
        } catch (Exception ignored) {
        }
        cert = out;
        return cert;
    }
}
