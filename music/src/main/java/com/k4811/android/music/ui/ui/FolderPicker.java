package com.k4811.android.music.ui.ui;

import com.k4811.common.ui.Touch;

import com.k4811.common.ui.ThemeDrawables;

import com.k4811.common.ui.Ui;

import android.app.Activity;
import android.app.Dialog;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k4811.android.music.ui.R;
import com.k4811.android.music.ui.music.MusicLibrary;
import com.k4811.common.system.SystemProps;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Folder browser for the MP3 player: starts at the storages (internal memory, USB drives, SD
 * cards), goes into subfolders and back, and "Use this folder" picks the one being shown. Shows
 * how many music files sit directly in it, so the driver knows they're in the right place.
 */
public final class FolderPicker {

    public interface OnPick {
        void pick(File folder);
    }

    private static final String PROP_INTERNAL_PATH = "ro.nwd.internal.path";
    /** Where NWD units and plain Android mount USB drives and SD cards. */
    private static final String[] MOUNT_PARENTS = {"/storage", "/mnt/media_rw"};
    private static final String[] MOUNTS = {
        "/mnt/usb_storage", "/mnt/usb_storage1", "/mnt/usb_storage2", "/mnt/external_sd",
        "/mnt/extsd", "/mnt/ext_sdcard",
    };

    private final Activity activity;
    private final Dialog dialog;
    private final OnPick onPick;
    private final List<File> roots;
    private final TextView path;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final TextView use;
    /** Folder being shown; null = the list of storages. */
    private File current;

    public static Dialog show(Activity activity, File start, OnPick onPick) {
        return new FolderPicker(activity, start, onPick).dialog;
    }

    private FolderPicker(Activity activity, File start, OnPick onPick) {
        this.activity = activity;
        this.onPick = onPick;
        this.roots = roots();
        dialog = new Dialog(activity, R.style.OverlayDialog);
        FrameLayout root = new FrameLayout(activity);
        root.setOnClickListener(v -> dialog.dismiss());
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        ThemeDrawables.setBackground(panel, R.drawable.panel);
        panel.setClickable(true);
        panel.setPadding(px(28), px(20), px(28), px(20));
        root.addView(panel, new FrameLayout.LayoutParams(px(700), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView title = new TextView(activity);
        title.setText(R.string.media_folder_title);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(26);
        title.setTypeface(Ui.semiBold(activity));
        panel.addView(title);
        path = new TextView(activity);
        path.setTextColor(Ui.TEXT_MUTED);
        path.setTextSize(17);
        path.setTypeface(Ui.regular(activity));
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.START);
        path.setPadding(0, px(4), 0, px(12));
        panel.addView(path);

        scroll = new ScrollView(activity);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(380)));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.RIGHT);
        buttons.setPadding(0, px(16), 0, 0);
        panel.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        use = button(buttons, activity.getString(R.string.media_folder_use), () -> {
            if (current == null) return;
            dialog.dismiss();
            onPick.pick(current);
        });
        use.setActivated(true);
        button(buttons, activity.getString(R.string.media_folder_cancel), dialog::dismiss);

        dialog.setContentView(root);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Ui.hideSystemBars(dialog.getWindow());
        open(start != null && start.isDirectory() ? start : null);
        dialog.show();
        Touch.install(root);
    }

    // ------------------------------------------------------------------ navigation

    /** Shows {@code folder}'s subfolders, or the storages when it's null. */
    private void open(File folder) {
        current = folder;
        rows.removeAllViews();
        use.setEnabled(folder != null);
        use.setAlpha(folder != null ? 1f : 0.4f);
        if (folder == null) {
            path.setText(R.string.media_folder_storages);
            for (File r : roots) addRow(label(r), r.getAbsolutePath(), () -> open(r));
            if (roots.isEmpty()) addNote(activity.getString(R.string.media_folder_no_storage));
        } else {
            int music = 0;
            List<File> folders = new ArrayList<>();
            File[] children = folder.listFiles();
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : children) {
                    if (f.getName().startsWith(".")) continue;
                    if (f.isDirectory()) folders.add(f);
                    else if (MusicLibrary.isAudio(f.getName())) music++;
                }
            }
            path.setText(activity.getResources().getQuantityString(R.plurals.media_folder_path,
                music, folder.getAbsolutePath(), music));
            addRow("‹  " + activity.getString(R.string.media_folder_up), null, () -> open(parentOf(folder)));
            for (File f : folders) addRow(f.getName() + "  ›", null, () -> open(f));
            if (folders.isEmpty()) addNote(activity.getString(R.string.media_folder_no_subfolders));
        }
        scroll.scrollTo(0, 0);
        Touch.install(rows);
    }

    /** One level up; above a storage, back to the list of storages. */
    private File parentOf(File folder) {
        for (File r : roots) {
            if (r.getAbsolutePath().equals(folder.getAbsolutePath())) return null;
        }
        File parent = folder.getParentFile();
        return parent != null && parent.canRead() ? parent : null;
    }

    private String label(File root) {
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.getAbsolutePath().equals(root.getAbsolutePath())) {
            return activity.getString(R.string.media_folder_internal);
        }
        return root.getName();
    }

    // ------------------------------------------------------------------ storages

    /** Readable storages: Android internal storage, NWD internal storage and mounted USB/SD. */
    private static List<File> roots() {
        Set<String> seen = new LinkedHashSet<>();
        List<File> out = new ArrayList<>();
        List<File> candidates = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) candidates.add(ext);
        String internal = SystemProps.get(PROP_INTERNAL_PATH, "");
        if (!internal.isEmpty()) candidates.add(new File(internal));
        for (String parent : MOUNT_PARENTS) {
            File[] mounts = new File(parent).listFiles();
            if (mounts == null) continue;
            for (File m : mounts) {
                String name = m.getName().toLowerCase(Locale.US);
                if (name.equals("emulated") || name.equals("self")) continue;
                candidates.add(m);
            }
        }
        for (String m : MOUNTS) candidates.add(new File(m));
        for (File f : candidates) {
            if (!f.isDirectory() || f.listFiles() == null) continue;
            if (seen.add(canonical(f))) out.add(f);
        }
        return out;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    // ------------------------------------------------------------------ views

    private void addRow(String text, String subtitle, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ThemeDrawables.setBackground(row, R.drawable.list_row);
        row.setPadding(px(14), 0, px(14), 0);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(22);
        t.setTypeface(Ui.medium(activity));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(activity);
            s.setText(subtitle);
            s.setTextColor(Ui.TEXT_DIM);
            s.setTextSize(15);
            s.setSingleLine(true);
            row.addView(s);
        }
        rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(68)));
    }

    private void addNote(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(Ui.TEXT_MUTED);
        t.setTextSize(18);
        t.setPadding(px(14), px(16), px(14), px(16));
        rows.addView(t);
    }

    private TextView button(LinearLayout parent, String text, Runnable onClick) {
        TextView b = (TextView) activity.getLayoutInflater().inflate(R.layout.option_button, parent, false);
        b.setText(text);
        b.setTypeface(Ui.medium(activity));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.leftMargin = px(14);
        parent.addView(b, lp);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private int px(float dp) {
        return Math.round(Ui.dp(activity, dp));
    }
}
