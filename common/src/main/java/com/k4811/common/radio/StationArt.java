package com.k4811.common.radio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import com.k4811.common.system.SystemProps;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 1:1 station art, read from internal storage at {@code K4811/radio/<FM|AM>_<frequency>.jpg}
 * (e.g. {@code FM_104.5.jpg}, {@code AM_1040.jpg}). Never from a USB drive or SD card. The same
 * folder holds the station memories ({@link PresetStore}).
 * The app only reads; the operator copies the files. Does I/O: call off the main thread.
 */
public final class StationArt {
    private StationArt() {
    }

    private static final String PROP_INTERNAL_PATH = "ro.nwd.internal.path";
    private static final String DEFAULT_INTERNAL_PATH = "/mnt/media_rw/sdcard";
    private static final String FOLDER = "K4811/radio";
    private static final String[] EXTENSIONS = {".jpg", ".png"};

    /** File name without extension, same text as the display: {@code FM_104.5}, {@code AM_1040}. */
    public static String baseName(int band, int frequency) {
        boolean fm = Band.isFm(band);
        return (fm ? "FM_" : "AM_") + Band.formatValue(frequency, fm);
    }

    /** @return the image downscaled to about {@code sizePx}, or null if there is no valid file. */
    public static Bitmap load(int band, int frequency, int sizePx) {
        if (frequency <= 0) return null;
        String base = baseName(band, frequency);
        for (File dir : dirs()) {
            for (String ext : EXTENSIONS) {
                File file = new File(dir, base + ext);
                if (!file.isFile()) continue;
                Bitmap bmp = decode(file, sizePx);
                if (bmp != null) return bmp;
            }
        }
        return null;
    }

    /** NWD internal storage and, as a fallback, Android internal storage (also not USB). */
    static Set<File> dirs() {
        Set<File> dirs = new LinkedHashSet<>();
        String internal = SystemProps.get(PROP_INTERNAL_PATH, "");
        dirs.add(new File(internal.isEmpty() ? DEFAULT_INTERNAL_PATH : internal, FOLDER));
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) dirs.add(new File(ext, FOLDER));
        return dirs;
    }

    private static Bitmap decode(File file, int sizePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int smallest = Math.min(bounds.outWidth, bounds.outHeight);
            while (smallest / (opts.inSampleSize * 2) >= sizePx) opts.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
