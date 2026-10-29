package com.k4811.map.search;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Texto de busca normalizado, igual ao do gerador ({@code tools/road-data/PlaceDataBuilder.normalize}):
 * minúsculas, sem acento, sem pontuação e com as abreviações por extenso ("R. Dr. José" → "rua doutor jose").
 * Assim "av getulio vargas" acha "Avenida Getúlio Vargas".
 */
public final class Text {
    private Text() {
    }

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Map<String, String> ABBR = new HashMap<>();

    static {
        String[] a = {"r", "rua", "av", "avenida", "avda", "avenida", "pc", "praca", "pca", "praca", "tv", "travessa",
            "trav", "travessa", "al", "alameda", "rod", "rodovia", "est", "estrada", "estr", "estrada", "lgo", "largo",
            "lad", "ladeira", "vl", "vila", "jd", "jardim", "jrd", "jardim", "pq", "parque", "cj", "conjunto",
            "conj", "conjunto", "res", "residencial", "dr", "doutor", "dra", "doutora", "prof", "professor",
            "profa", "professora", "pe", "padre", "sto", "santo", "sta", "santa", "gov", "governador",
            "pres", "presidente", "cel", "coronel", "cap", "capitao", "gal", "general", "gen", "general",
            "ten", "tenente", "mal", "marechal", "eng", "engenheiro", "dep", "deputado", "sen", "senador",
            "ver", "vereador", "des", "desembargador", "min", "ministro", "maj", "major", "sgt", "sargento",
            "mons", "monsenhor", "com", "comendador", "cmte", "comandante", "bel", "bacharel", "arq", "arquiteto",
            "pref", "prefeito", "vig", "vigario", "fr", "frei", "ir", "irma", "nsa", "nossa", "sra", "senhora",
            "sr", "senhor", "bl", "bloco"};
        for (int i = 0; i < a.length; i += 2) ABBR.put(a[i], a[i + 1]);
    }

    /** Ver a classe. Palavras separadas por um espaço, sem espaço nas pontas. */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = MARKS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        StringBuilder b = new StringBuilder(t.length());
        StringBuilder w = new StringBuilder();
        for (int i = 0; i <= t.length(); i++) {
            char c = i < t.length() ? t.charAt(i) : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                w.append(c);
                continue;
            }
            if (w.length() == 0) continue;
            String word = w.toString();
            String full = ABBR.get(word);
            if (b.length() > 0) b.append(' ');
            b.append(full == null ? word : full);
            w.setLength(0);
        }
        return b.toString();
    }

    /** Só dígitos (1 a 5): um número de casa. */
    static boolean isNumber(String word) {
        if (word.isEmpty() || word.length() > 5) return false;
        for (int i = 0; i < word.length(); i++) if (!Character.isDigit(word.charAt(i))) return false;
        return true;
    }
}
