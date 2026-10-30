package com.k4811.map.google;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pedidos HTTP às APIs do Google (Map Tiles, Routes, Places), com a chave e a identificação do app
 * nos cabeçalhos. Bloqueia: chamar fora da thread principal.
 */
final class GoogleHttp {
    private GoogleHttp() {
    }

    /** Resposta que não foi 200: o código e o corpo (a mensagem de erro do Google). */
    static final class HttpError extends java.io.IOException {
        final int code;

        HttpError(int code, String body) {
            super("HTTP " + code + (body == null || body.isEmpty() ? "" : ": " + shorten(body)));
            this.code = code;
        }

        /** Chave recusada, API não ativada ou cota do Google estourada: não adianta insistir agora. */
        boolean blocking() {
            return code == 400 || code == 401 || code == 403 || code == 429;
        }
    }

    /** Resposta: o corpo e quantos segundos ela pode ficar em cache ({@code Cache-Control: max-age}). */
    static final class Response {
        final byte[] body;
        final long maxAgeSeconds;

        Response(byte[] body, long maxAgeSeconds) {
            this.body = body;
            this.maxAgeSeconds = maxAgeSeconds;
        }

        String text() {
            try {
                return new String(body, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                return new String(body);
            }
        }
    }

    /**
     * @param fieldMask {@code X-Goog-FieldMask} (null = sem)
     * @param json corpo de um POST (null = GET)
     */
    static Response request(Context c, String url, String fieldMask, String json) throws java.io.IOException {
        String key = GoogleKey.key(c);
        if (key.isEmpty()) throw new java.io.IOException("sem chave do Google");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(15000);
            h.setRequestProperty("X-Goog-Api-Key", key);
            h.setRequestProperty("X-Android-Package", c.getPackageName());
            String cert = GoogleKey.cert(c);
            if (!cert.isEmpty()) h.setRequestProperty("X-Android-Cert", cert);
            if (fieldMask != null) h.setRequestProperty("X-Goog-FieldMask", fieldMask);
            if (json != null) {
                byte[] out = json.getBytes("UTF-8");
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(out.length);
                OutputStream os = h.getOutputStream();
                os.write(out);
                os.close();
            }
            int code = h.getResponseCode();
            if (code != 200) {
                String body = "";
                try {
                    InputStream err = h.getErrorStream();
                    if (err != null) body = new String(readAll(err), "UTF-8");
                } catch (Exception ignored) {
                }
                throw new HttpError(code, body);
            }
            return new Response(readAll(h.getInputStream()), maxAge(h.getHeaderField("Cache-Control")));
        } finally {
            h.disconnect();
        }
    }

    /** {@code max-age} do Cache-Control em segundos; 0 se não há ou se pede para não guardar. */
    static long maxAge(String cacheControl) {
        if (cacheControl == null) return 0;
        String v = cacheControl.toLowerCase(java.util.Locale.ROOT);
        if (v.contains("no-store") || v.contains("no-cache")) return 0;
        int i = v.indexOf("max-age=");
        if (i < 0) return 0;
        int j = i + 8;
        int k = j;
        while (k < v.length() && Character.isDigit(v.charAt(k))) k++;
        try {
            return k > j ? Long.parseLong(v.substring(j, k)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String shorten(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
