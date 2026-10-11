package com.garagemdomadeira.nwd.launcher.system;

import android.content.Context;
import android.content.Intent;

import com.garagemdomadeira.nwd.launcher.R;

import java.util.Arrays;
import java.util.List;

/**
 * Tells whether the head unit is an NWD on which Garagem has already been tested. Uses
 * {@code ro.nwd.*} props and the NWD services; never {@code Build.*}, which on this ROM still
 * report Allwinner T3 / Android 7. The result only produces a warning in Settings; it blocks nothing.
 */
public final class PlatformCompat {
    private PlatformCompat() {
    }

    public enum Verdict { OK, UNTESTED, INCOMPATIBLE }

    /** Boards already run with Garagem. Extend when another one is tested. */
    private static final List<String> ALLOWLIST = Arrays.asList("K4811");

    public static final String PROP_PLATFORM = "ro.nwd.platform.name";
    public static final String PROP_FAMILY = "ro.product.nwd.platform";
    public static final String PROP_FIRMWARE = "ro.build.display.id";
    public static final String PROP_SOC = "ro.board.platform";

    /** Action of the NWD Bluetooth service (AIDL BTFeature), the same one BtClient uses. */
    private static final String ACTION_BT_SERVICE = "com.bt.ACTION_START_SERVICE";

    public static boolean hasBtService(Context c) {
        try {
            return !c.getPackageManager().queryIntentServices(new Intent(ACTION_BT_SERVICE), 0).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static String platform() {
        return SystemProps.get(PROP_PLATFORM, "").trim();
    }

    public static String family() {
        return SystemProps.get(PROP_FAMILY, "").trim();
    }

    public static Verdict verdict(Context c) {
        String name = platform();
        boolean nwdProps = !name.isEmpty() || !family().isEmpty();
        if (!nwdProps || !NwdSettings.isInstalled(c)) return Verdict.INCOMPATIBLE;
        return ALLOWLIST.contains(name) ? Verdict.OK : Verdict.UNTESTED;
    }

    /** Text of the alert at the top of Settings, or null when everything is fine. */
    public static String alert(Context c, Verdict v) {
        switch (v) {
            case UNTESTED:
                String name = platform().isEmpty() ? family() : platform();
                return c.getString(R.string.adv_compat_untested, name);
            case INCOMPATIBLE:
                return c.getString(R.string.adv_compat_incompatible);
            default:
                return null;
        }
    }

    public static String label(Context c, Verdict v) {
        switch (v) {
            case OK:
                return c.getString(R.string.adv_compat_ok);
            case UNTESTED:
                return c.getString(R.string.adv_compat_untested_label);
            default:
                return c.getString(R.string.adv_compat_incompatible_label);
        }
    }
}
