package com.k4811.launcher.waze;

import android.content.Context;

import com.k4811.launcher.R;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** CarText, Distance, durações e horários do Waze no formato da tela ("5,9 km", "9 min"). */
final class Texts {
    private Texts() {
    }

    private static Context app;

    /** Contexto para os textos (strings_map/strings_home da suíte). */
    static void init(Context c) {
        app = c.getApplicationContext();
    }

    static String whereTo() {
        return app.getString(R.string.map_search_hint);
    }

    static String arrival() {
        return app.getString(R.string.map_arrival, "").trim();
    }

    static String cancel() {
        return app.getString(R.string.map_cancel);
    }

    static String then() {
        return app.getString(R.string.map_then);
    }

    static String end() {
        return app.getString(R.string.map_end);
    }

    static String connecting() {
        return app.getString(R.string.home_nav_waze_starting);
    }

    /** Texto de um CarText (com os spans de distância, duração, cor e ícone aplicados). */
    static CharSequence of(Context c, Object carText, float textPx) {
        if (carText == null) return null;
        if (!(carText instanceof CarBundle.Obj)) return carText.toString();
        CarBundle.Obj t = (CarBundle.Obj) carText;
        String s = t.str("mText");
        if (s == null) return null;
        List<Object> spans = t.list("mSpans");
        if (spans.isEmpty()) return s;
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        // Primeiro os spans que não trocam o texto; depois as trocas, do fim para o começo
        // (o SpannableStringBuilder ajusta as posições dos spans já colocados).
        List<CarBundle.Obj> replace = new ArrayList<>();
        for (Object o : spans) {
            if (!(o instanceof CarBundle.Obj)) continue;
            CarBundle.Obj w = (CarBundle.Obj) o;
            CarBundle.Obj span = w.obj("mCarSpan");
            int start = w.i("mStart", 0), end = w.i("mEnd", 0);
            if (span == null || start < 0 || end > s.length() || start >= end) continue;
            if (span.is("ForegroundCarColorSpan")) {
                int color = Colors.of(span.obj("mCarColor"), WazeUi.dark, WazeUi.text());
                sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (span.is("CarIconSpan")) {
                Drawable d = Icons.drawable(c, span.obj("mIcon"), WazeUi.dark);
                if (d != null) {
                    int size = Math.round(textPx);
                    d = WazeUi.sized(d, size);
                    d.setBounds(0, 0, size, size);
                    sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (span.is("DistanceSpan") || span.is("DurationSpan")) {
                replace.add(w);
            }
        }
        Collections.sort(replace, (a, b) -> b.i("mStart", 0) - a.i("mStart", 0));
        for (CarBundle.Obj w : replace) {
            CarBundle.Obj span = w.obj("mCarSpan");
            String value = span.is("DistanceSpan") ? distance(span.obj("mDistance"))
                    : duration(span.l("mDurationSeconds", 0));
            if (value != null) sb.replace(w.i("mStart", 0), w.i("mEnd", 0), value);
        }
        return sb;
    }

    static String plain(Object carText) {
        if (carText instanceof CarBundle.Obj) return ((CarBundle.Obj) carText).str("mText");
        return carText == null ? null : carText.toString();
    }

    /** Distance: 1 m, 2 km, 3 km com 1 casa, 4 mi, 5 mi com 1 casa, 6 pés, 7 jardas. */
    static String distance(CarBundle.Obj d) {
        if (d == null) return null;
        double v = d.d("mDisplayDistance", 0);
        switch (d.i("mDisplayUnit", 1)) {
            case 2:
                return number(v, 0) + " km";
            case 3:
                return number(v, 1) + " km";
            case 4:
                return number(v, 0) + " mi";
            case 5:
                return number(v, 1) + " mi";
            case 6:
                return number(v, 0) + " ft";
            case 7:
                return number(v, 0) + " yd";
            default:
                return number(v, 0) + " m";
        }
    }

    /** Valor e unidade separados (o cartão de manobra mostra "800 m" com tamanhos iguais). */
    private static String number(double v, int decimals) {
        DecimalFormat f = new DecimalFormat(decimals == 0 ? "0" : "0.0", DecimalFormatSymbols.getInstance());
        String s = f.format(v);
        // "6,0 km" -> "6 km", como o Android Auto.
        if (decimals > 0 && s.endsWith(String.valueOf(f.getDecimalFormatSymbols().getDecimalSeparator()) + "0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    static String duration(long seconds) {
        long min = Math.max(0, Math.round(seconds / 60.0));
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return m == 0 ? h + " h" : String.format(Locale.US, "%d h %02d min", h, m);
    }

    static String clock(CarBundle.Obj dateTime) {
        if (dateTime == null) return null;
        long ms = dateTime.l("mTimeSinceEpochMillis", 0);
        if (ms <= 0) return null;
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** "9 min · 5,9 km" de um TravelEstimate. */
    static String estimate(CarBundle.Obj te) {
        if (te == null) return null;
        StringBuilder sb = new StringBuilder();
        long secs = te.l("mRemainingTimeSeconds", -1);
        if (secs >= 0) sb.append(duration(secs));
        String dist = distance(te.obj("mRemainingDistance"));
        if (dist != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(dist);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
