package com.garagemdomadeira.nwd.launcher.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.garagemdomadeira.nwd.launcher.FanActivity;
import com.garagemdomadeira.nwd.launcher.R;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Apps with a launcher icon, excluding keyboards and whatever the head unit hardware lacks. */
public final class AppCatalog {

    public static final class Entry {
        public final String label;
        public final String packageName;
        public final String className;
        public final Bitmap icon;

        Entry(String label, String packageName, String className, Bitmap icon) {
            this.label = label;
            this.packageName = packageName;
            this.className = className;
            this.icon = icon;
        }
    }

    private static final String FEATURE = "factory_feature";

    private AppCatalog() {
    }

    public static List<Entry> load(Context context, int iconPx) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(query, 0);
        } catch (Exception e) {
            found = null;
        }
        if (found == null) return new ArrayList<>();

        Set<String> keyboards = keyboards(context);
        String ours = context.getPackageName();
        int features = features(context);
        int size = Math.max(iconPx, 1);
        List<Entry> apps = new ArrayList<>();
        // Our own screens that are not in the default dock.
        apps.add(new Entry(context.getString(R.string.home_app_fan), ours, FanActivity.class.getName(), fanIcon(size)));
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            String cls = info.activityInfo.name;
            if (pkg == null || cls == null) continue;
            if (pkg.equals(ours) || keyboards.contains(pkg)) continue;
            // Our screens replace the system panel; the phone app
            // stays installed for the floating call window.
            if ("com.nwd.cpumonitor".equals(pkg)) continue;
            if ("com.nwd.android.phone".equals(pkg)) continue;
            if ("com.garagemdomadeira.eq".equals(pkg)) continue;
            if ("com.nwd.audioset".equals(pkg)) continue;
            if (!hardwareAllows(pkg, cls, features)) continue;
            CharSequence title = info.loadLabel(pm);
            String label = title == null ? pkg : title.toString().trim();
            if (label.isEmpty()) label = pkg;
            apps.add(new Entry(label, pkg, cls, icon(info.loadIcon(pm), size)));
        }
        Collator order = Collator.getInstance(Locale.getDefault());
        order.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> order.compare(a.label, b.label));
        return apps;
    }

    public static void recycle(List<Entry> apps) {
        if (apps == null) return;
        for (Entry e : apps) {
            if (e.icon != null && !e.icon.isRecycled()) e.icon.recycle();
        }
    }

    /** -1 when the head unit didn't report its hardware: in that case we don't hide by bit. */
    private static int features(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), FEATURE);
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    /**
     * Factory feature bits: DVD 4, 360 camera 16, AUX 4096, TV 8192,
     * rear AUX 16384, DVR 32768.
     */
    private static boolean hardwareAllows(String pkg, String cls, int features) {
        if (features < 0) return true;
        int bit = -1;
        if ("com.nwd.auxin.rear".equals(pkg)) bit = 16384;
        else if ("com.nwd.auxin".equals(pkg) || "com.nwd.camera.app.AuxActivity".equals(cls)) bit = 4096;
        else if ("com.nwd.tv".equals(pkg) || "com.nwd.camera.app.TvActivity".equals(cls)) bit = 8192;
        else if ("com.nwd.carkit360camera".equals(pkg) || "com.ivicar.avm".equals(pkg)) bit = 16;
        else if ("com.nwd.dvd".equals(pkg)) bit = 4;
        else if ("com.nwd.dvr.nomal".equals(pkg)) bit = 32768;
        if (bit < 0) return true;
        return (features & bit) == bit;
    }

    private static Set<String> keyboards(Context context) {
        Set<String> names = new HashSet<>();
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return names;
            List<InputMethodInfo> list = imm.getInputMethodList();
            if (list == null) return names;
            for (InputMethodInfo info : list) {
                if (info.getPackageName() != null) names.add(info.getPackageName());
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    private static Bitmap icon(Drawable drawable, int size) {
        if (drawable == null) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fanIcon(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Ui.TEXT);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Ui.TEXT);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, size / 28f));
        float cx = size / 2f;
        float cy = size / 2f;
        float r = size * 0.38f;
        Path blade = new Path();
        for (int i = 0; i < 3; i++) {
            c.save();
            c.translate(cx, cy);
            c.rotate(i * 120f);
            blade.reset();
            blade.moveTo(0, 0);
            blade.quadTo(r * 0.45f, -r * 0.55f, 0, -r);
            blade.quadTo(-r * 0.35f, -r * 0.45f, 0, 0);
            c.drawPath(blade, fill);
            c.restore();
        }
        c.drawCircle(cx, cy, r * 0.16f, fill);
        c.drawCircle(cx, cy, r, stroke);
        return bitmap;
    }
}
