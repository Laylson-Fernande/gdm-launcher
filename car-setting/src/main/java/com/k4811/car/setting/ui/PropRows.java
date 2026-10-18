package com.k4811.car.setting.ui;

import com.k4811.common.ui.Ui;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import com.k4811.car.setting.R;
import com.k4811.common.system.SystemProps;

/**
 * Option rows bound to a system prop, written through the NWD channel
 * ({@link SystemProps#requestSet}) and verified after 1.5 s, showing the adb command if it fails.
 */
public final class PropRows {
    private PropRows() {
    }

    public static String adb(String key, String value) {
        return "adb shell setprop " + key + " " + (value.isEmpty() ? "\"\"" : value);
    }

    /**
     * On/Off. On = value equal to {@code on} (case-insensitive).
     * @param missingIsOff true: a missing prop shows as off; false: the row is hidden
     */
    public static OptionList.Row toggle(Activity a, OptionList list, String title, String subtitle, String key,
                                        String on, String off, boolean missingIsOff) {
        return list.toggle(title, subtitle, () -> {
            String v = SystemProps.get(key, "").trim();
            if (v.isEmpty()) return missingIsOff ? Boolean.FALSE : null;
            return v.equalsIgnoreCase(on);
        }, want -> SystemProps.requestSet(a, key, want ? on : off))
            .hint(value -> adb(key, "1".equals(value) ? on : off));
    }

    /**
     * Free value: the button shows the current value; tapping opens a field to type in.
     * {@code validator} (optional) returns an error message to reject the text.
     */
    public static OptionList.Row edit(Activity a, OptionList list, String title, String subtitle, String key,
                                      InputDialog.OnSubmit validator) {
        OptionList.Row[] row = new OptionList.Row[1];
        TextView[] button = new TextView[1];
        row[0] = list.info(title, () -> {
            String v = SystemProps.get(key, "");
            if (button[0] != null) button[0].setText(v.isEmpty() ? a.getString(R.string.settings_prop_missing) : v);
            return "";
        });
        row[0].setSubtitle(subtitle == null ? key : subtitle + " · " + key);
        // Same size as the other option rows (the read-only row is shorter).
        row[0].titleView.setTextSize(26);
        row[0].view.setMinimumHeight(Math.round(Ui.dp(a, 96)));
        button[0] = row[0].addButton("", () -> {
            String current = SystemProps.get(key, "");
            InputDialog.show(a, title, key, current, false, a.getString(R.string.settings_apply), text -> {
                String value = text.trim();
                if (validator != null) {
                    String err = validator.submit(value);
                    if (err != null) return err;
                }
                SystemProps.requestSet(a, key, value);
                row[0].verifyLater(() -> value.equals(SystemProps.get(key, "")), value);
                return null;
            });
        });
        row[0].hint(value -> adb(key, value));
        row[0].refresh();
        return row[0];
    }

    /** Validator for an integer between {@code min} and {@code max}. */
    public static InputDialog.OnSubmit range(Context c, int min, int max) {
        return text -> {
            try {
                int v = Integer.parseInt(text);
                return v < min || v > max ? c.getString(R.string.settings_range_error, min, max) : null;
            } catch (NumberFormatException e) {
                return c.getString(R.string.settings_enter_number);
            }
        };
    }
}
