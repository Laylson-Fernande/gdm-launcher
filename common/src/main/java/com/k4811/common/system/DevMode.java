package com.k4811.common.system;

import android.os.Environment;

import java.io.File;

/**
 * Developer mode: debug and factory features (radio diagnostics, EQ diagnostics tab, last-error
 * panel, technical "About" lines and the Advanced menu) only show up when the file
 * {@code LAUNCHER_DEV_MODE.txt} exists at the root of the internal storage (/sdcard).
 * Checked every time a screen is built, so creating or deleting the file takes effect on the
 * next screen open, without reinstalling.
 */
public final class DevMode {
    private DevMode() {
    }

    public static final String FILE_NAME = "LAUNCHER_DEV_MODE.txt";

    public static File file() {
        return new File(Environment.getExternalStorageDirectory(), FILE_NAME);
    }

    public static boolean enabled() {
        try {
            return file().isFile();
        } catch (SecurityException e) {
            return false;
        }
    }
}
