package com.k4811.common.system;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fake MCU keys: the same path as the steering wheel keys, used by the NWD bottom bar
 * ({@code com.nwd.statusbarbottom}, VScreenKeyUtil.emuButtonProtocal). The NWD kernel service takes
 * the frame as if it came from the MCU, so Back and Home work over any app without the platform key.
 *
 * Frame (9 bytes): {@code F0 06 01 01 00 00 00 KK CS}, CS = sum of bytes 1..7 = (8 + KK) & 0xFF.
 * On units without the NWD kernel the broadcast goes nowhere.
 */
public final class McuKeys {
    private McuKeys() {
    }

    public static final byte VOL_UP = 14;
    public static final byte VOL_DOWN = 15;
    public static final byte BACK = 18;
    public static final byte HOME = 20;

    private static final String ACTION = "com.nwd.action.EMU_RECEIVER_MCU_PROTOCAL";
    private static final String EXTRA = "protocal";
    private static final String TAG = "K4811McuKeys";

    public static void press(Context c, byte key) {
        byte[] frame = {(byte) 0xF0, 6, 1, 1, 0, 0, 0, key, 0};
        byte sum = 0;
        for (int i = 1; i < frame.length - 1; i++) sum += frame[i];
        frame[frame.length - 1] = sum;
        Intent intent = new Intent(ACTION);
        intent.putExtra(EXTRA, frame);
        try {
            c.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "key " + key + ": " + e.getMessage());
        }
    }
}
