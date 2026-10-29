package com.k4811.launcher.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.k4811.common.Apps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apps with a launcher icon, excluding keyboards and whatever the head unit hardware lacks.
 *
 * Order: the suite's apps first (radio, MP3, Bluetooth…), then the others, each group in
 * alphabetical order. Apps the user hid ({@link #hide}) stay out of the drawer list.
 *
 * Cache: reading every label and icon from the PackageManager is what made the drawer slow on the
 * head unit. The list stays in memory for the launcher process and on disk ({@code cache/apps/}:
 * list + one PNG per icon), so the drawer shows at once; {@link #refresh} then compares with the
 * installed apps and only reads the new or updated ones (by {@code lastUpdateTime}).
 */
public final class AppCatalog {

    public static final class Entry {
        public final String label;
        public final String packageName;
        public final String className;
        public final Bitmap icon;
        /** One of the suite's apps: listed first. */
        public final boolean suite;
        /** Package install/update time when label and icon were read. */
        final long updated;
        /** Language of the label. */
        final String locale;

        Entry(String label, String packageName, String className, Bitmap icon, boolean suite, long updated,
              String locale) {
            this.label = label;
            this.packageName = packageName;
            this.className = className;
            this.icon = icon;
            this.suite = suite;
            this.updated = updated;
            this.locale = locale;
        }

        /** Identifies the entry (a package can have more than one launcher activity). */
        public String key() {
            return packageName + "/" + className;
        }
    }

    private static final String TAG = "K4811Apps";
    private static final String FEATURE = "factory_feature";
    private static final String PREFS = "apps";
    private static final String KEY_HIDDEN = "hidden";
    private static final int CACHE_VERSION = 1;

    /** Last list (all apps, hidden ones included), shared by every drawer opening in this process. */
    private static List<Entry> memory;
    private static int memoryIconPx;

    private AppCatalog() {
    }

    // ------------------------------------------------------------------ reading

    /**
     * What is already known, without asking the PackageManager: the memory list or, the first time
     * in this process, the disk cache. null if there is none (first run). Disk: off the main thread.
     */
    public static synchronized List<Entry> cached(Context context, int iconPx) {
        if (memory != null && memoryIconPx == iconPx) return memory;
        List<Entry> disk = readDisk(context, iconPx);
        if (disk != null) {
            memory = disk;
            memoryIconPx = iconPx;
        }
        return disk;
    }

    /** Memory list only (main thread, instant); null if the drawer was never loaded in this process. */
    public static synchronized List<Entry> inMemory(int iconPx) {
        return memory != null && memoryIconPx == iconPx ? memory : null;
    }

    /**
     * Installed apps now. Reuses label and icon of apps that didn't change since the cache; reads the
     * rest. Saves the result to the cache. Off the main thread.
     */
    public static List<Entry> refresh(Context context, int iconPx) {
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

        List<Entry> before = cached(context, iconPx);
        Map<String, Entry> known = new HashMap<>();
        if (before != null) for (Entry e : before) known.put(e.key(), e);

        Set<String> keyboards = keyboards(context);
        Set<String> suite = suitePackages();
        String ours = context.getPackageName();
        String locale = Locale.getDefault().toString();
        int features = features(context);
        int size = Math.max(iconPx, 1);
        int read = 0;
        List<Entry> apps = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            String cls = info.activityInfo.name;
            if (pkg == null || cls == null) continue;
            if (pkg.equals(ours) || keyboards.contains(pkg)) continue;
            if (!hardwareAllows(pkg, cls, features)) continue;
            long updated = updated(pm, pkg);
            Entry old = known.get(pkg + "/" + cls);
            if (old != null && old.updated == updated && old.locale.equals(locale) && old.icon != null) {
                apps.add(old);
                continue;
            }
            CharSequence title = info.loadLabel(pm);
            String label = title == null ? pkg : title.toString().trim();
            if (label.isEmpty()) label = pkg;
            apps.add(new Entry(label, pkg, cls, icon(info.loadIcon(pm), size), suite.contains(pkg), updated, locale));
            read++;
        }
        sort(apps);
        boolean changed = before == null || read > 0 || before.size() != apps.size();
        synchronized (AppCatalog.class) {
            memory = apps;
            memoryIconPx = iconPx;
        }
        if (changed) writeDisk(context, apps, iconPx);
        Log.d(TAG, apps.size() + " apps, " + read + " lidos do sistema");
        return apps;
    }

    /** Suite first, then the others; alphabetical in each group (accents and case ignored). */
    private static void sort(List<Entry> apps) {
        Collator order = Collator.getInstance(Locale.getDefault());
        order.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> {
            if (a.suite != b.suite) return a.suite ? -1 : 1;
            return order.compare(a.label, b.label);
        });
    }

    /** The suite's apps (Apps.*), whatever their packages are in this build. */
    private static Set<String> suitePackages() {
        return new HashSet<>(Arrays.asList(Apps.RADIO, Apps.MUSIC, Apps.BT_MUSIC, Apps.PHONE, Apps.EQ,
            Apps.FAN, Apps.SETTINGS));
    }

    private static long updated(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Same apps, labels and icons (the drawer only redraws when something changed). */
    public static boolean same(List<Entry> a, List<Entry> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    // ------------------------------------------------------------------ hidden apps

    /** Keys ({@link Entry#key}) of the apps the user hid (launcher preferences: only this app uses them). */
    public static Set<String> hidden(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN, Collections.<String>emptySet()));
    }

    public static void hide(Context context, Entry e, boolean hide) {
        Set<String> set = hidden(context);
        if (hide) set.add(e.key());
        else set.remove(e.key());
        prefs(context).edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ disk cache

    private static File dir(Context context) {
        return new File(context.getCacheDir(), "apps");
    }

    private static List<Entry> readDisk(Context context, int iconPx) {
        File list = new File(dir(context), "apps.json");
        if (!list.isFile()) return null;
        try {
            JSONObject j = new JSONObject(read(list));
            if (j.optInt("version") != CACHE_VERSION || j.optInt("icon_px") != iconPx) return null;
            JSONArray arr = j.getJSONArray("apps");
            Set<String> suite = suitePackages();
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String pkg = o.getString("pkg");
                Bitmap icon = BitmapFactory.decodeFile(new File(dir(context), o.getString("icon")).getPath());
                out.add(new Entry(o.getString("label"), pkg, o.getString("cls"), icon, suite.contains(pkg),
                    o.optLong("updated"), o.optString("locale")));
            }
            sort(out);
            return out;
        } catch (Exception e) {
            Log.d(TAG, "cache: " + e);
            return null;
        }
    }

    private static void writeDisk(Context context, List<Entry> apps, int iconPx) {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try {
            Set<String> keep = new HashSet<>();
            JSONArray arr = new JSONArray();
            for (Entry e : apps) {
                String name = iconName(e);
                keep.add(name);
                File f = new File(dir, name);
                if (e.icon != null && !e.icon.isRecycled()) {
                    try (OutputStream out = new FileOutputStream(f)) {
                        e.icon.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
                arr.put(new JSONObject().put("label", e.label).put("pkg", e.packageName).put("cls", e.className)
                    .put("updated", e.updated).put("locale", e.locale).put("icon", name));
            }
            JSONObject j = new JSONObject().put("version", CACHE_VERSION).put("icon_px", iconPx).put("apps", arr);
            File tmp = new File(dir, "apps.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(j.toString().getBytes("UTF-8"));
            }
            if (!tmp.renameTo(new File(dir, "apps.json"))) Log.d(TAG, "cache não gravado");
            // Ícones de apps que saíram.
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".png") && !keep.contains(f.getName()) && !f.delete()) {
                        Log.d(TAG, "não apagou " + f.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "cache: " + e);
        }
    }

    /** Icon file: the entry key with only safe characters; also changes when the app is updated. */
    private static String iconName(Entry e) {
        return (e.key() + "_" + e.updated).replaceAll("[^A-Za-z0-9._-]", "_") + ".png";
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        }
    }

    // ------------------------------------------------------------------ filters

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
}
