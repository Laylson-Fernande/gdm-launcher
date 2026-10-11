package com.garagemdomadeira.nwd.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.R;
import com.garagemdomadeira.nwd.launcher.home.LauncherPrefs;
import com.garagemdomadeira.nwd.launcher.system.ExternalApps;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bottom bar (dock) shared by the launcher screens: rounded dark panel with the shortcuts.
 * Active item: white icon and text, bold text and a white underline.
 * Home and Apps are fixed; the middle ones come from Settings › Dock icons (up to 5), re-read
 * whenever the screen becomes visible again.
 */
public class DockView extends LinearLayout {

    public enum Item {
        // The order here is the order in the dock and in the Settings list.
        HOME("home", R.string.home_dock_home, R.string.home_dock_home_title, IconView.Icon.HOME, false),
        RADIO("radio", R.string.home_dock_radio, R.string.home_dock_radio_title, IconView.Icon.RADIO, true),
        MUSIC("music", R.string.home_dock_music, R.string.home_dock_music_title, IconView.Icon.MUSIC, true),
        BT_MUSIC("bt_music", R.string.home_dock_bt_music, R.string.home_dock_bt_music_title,
            IconView.Icon.BLUETOOTH, true),
        PHONE("phone", R.string.home_dock_phone, R.string.home_dock_phone_title, IconView.Icon.PHONE, true),
        EQ("eq", R.string.home_dock_eq, R.string.home_dock_eq_title, IconView.Icon.EQUALIZER, true),
        AUX("aux", R.string.home_dock_aux, R.string.home_dock_aux_title, IconView.Icon.AUX, true),
        CAMERA_360("cam360", R.string.home_dock_cam360, R.string.home_dock_cam360_title,
            IconView.Icon.CAMERA_360, true),
        VIDEO("video", R.string.home_dock_video, R.string.home_dock_video_title, IconView.Icon.VIDEO, true),
        APPS("apps", R.string.home_dock_apps, R.string.home_dock_apps_title, IconView.Icon.APPS, false);

        /** Id stored in launcher_prefs (dock_toggles). */
        public final String id;
        /** Short label shown in the dock (string resource). */
        public final int labelRes;
        /** Full name, for the Settings list (string resource). */
        public final int titleRes;
        public final IconView.Icon icon;
        /** false = fixed in the dock (Home and Apps). */
        public final boolean toggleable;

        Item(String id, int labelRes, int titleRes, IconView.Icon icon, boolean toggleable) {
            this.id = id;
            this.labelRes = labelRes;
            this.titleRes = titleRes;
            this.icon = icon;
            this.toggleable = toggleable;
        }

        /** Short label shown in the dock. */
        public String label(Context c) {
            return c.getString(labelRes);
        }

        /** Full name, for the Settings list. */
        public String title(Context c) {
            return c.getString(titleRes);
        }

        /** Item that opens a head-unit app instead of one of our screens. */
        public boolean isExternal() {
            return this == AUX || this == CAMERA_360 || this == VIDEO;
        }

        /** false if the item's app or hardware doesn't exist on this head unit (hidden from dock and Settings). */
        public boolean isAvailable(Context c) {
            switch (this) {
                case AUX:
                    return ExternalApps.hasAux(c);
                case CAMERA_360:
                    return ExternalApps.has360(c);
                case VIDEO:
                    return ExternalApps.hasVideo(c);
                default:
                    return true;
            }
        }
    }

    public interface Listener {
        void onDockItem(Item item);
    }

    /** Total height reserved by the dock (panel + margins), in design dp. */
    public static final int HEIGHT_DP = 160;

    private static final int IDLE = 0xFF8E949C;
    private static final int PANEL = 0xFF0C0E11;
    private static final int PANEL_BORDER = 0xFF1C1F24;

    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint underline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final View[] slots = new View[Item.values().length];
    private final IconView[] icons = new IconView[Item.values().length];
    private final TextView[] labels = new TextView[Item.values().length];
    private Item active;
    private Listener listener;
    /** Signature of the shown items, so we only rebuild when the selection changes. */
    private String shownKey;

    public DockView(Context c) {
        this(c, null);
    }

    public DockView(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setWillNotDraw(false);
        int side = Math.round(Ui.dp(c, 44));
        setPadding(side, Math.round(Ui.dp(c, 14)), side, Math.round(Ui.dp(c, 26)));
        panel.setColor(PANEL);
        border.setColor(PANEL_BORDER);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(c, 1.5f));
        underline.setColor(Ui.TEXT);

        refresh();
    }

    /** Re-reads the dock selection and rebuilds the items if it changed. */
    public void refresh() {
        Context c = getContext();
        Set<Item> toggles = LauncherPrefs.dockToggles(c);
        List<Item> shown = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (Item item : Item.values()) {
            if (item.toggleable && !(toggles.contains(item) && item.isAvailable(c))) continue;
            shown.add(item);
            key.append(item.id).append(',');
        }
        if (key.toString().equals(shownKey)) return;
        shownKey = key.toString();

        removeAllViews();
        for (int i = 0; i < slots.length; i++) {
            slots[i] = null;
            icons[i] = null;
            labels[i] = null;
        }
        for (Item item : shown) {
            LinearLayout slot = new LinearLayout(c);
            slot.setOrientation(VERTICAL);
            slot.setGravity(Gravity.CENTER);
            slot.setClickable(true);
            slot.setContentDescription(item.label(c));
            slot.setBackgroundResource(com.garagemdomadeira.nwd.launcher.R.drawable.dock_item);

            IconView icon = new IconView(c);
            icon.set(item.icon, IconView.Style.NONE);
            icon.setClickable(false);
            icon.setDuplicateParentStateEnabled(true);
            icon.color(IDLE);
            int iconSize = Math.round(Ui.dp(c, 62));
            slot.addView(icon, new LayoutParams(iconSize, iconSize));

            TextView label = new TextView(c);
            label.setText(item.label(c));
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(c, 22));
            label.setTextColor(IDLE);
            label.setSingleLine(true);
            label.setTypeface(Ui.medium(c));
            label.setIncludeFontPadding(false);
            slot.addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            slot.setOnClickListener(v -> {
                if (listener != null) listener.onDockItem(item);
            });
            addView(slot, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            slots[item.ordinal()] = slot;
            icons[item.ordinal()] = icon;
            labels[item.ordinal()] = label;
        }
        setActive(active);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // When returning from Settings (or any screen), apply the new dock selection.
        if (visibility == VISIBLE) refresh();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Highlighted item (the current screen, or on the home screen the playing source). null = none. */
    public void setActive(Item item) {
        active = item;
        for (Item i : Item.values()) {
            if (icons[i.ordinal()] == null) continue;
            boolean on = i == item;
            icons[i.ordinal()].color(on ? Ui.TEXT : IDLE);
            labels[i.ordinal()].setTextColor(on ? Ui.TEXT : IDLE);
            labels[i.ordinal()].setTypeface(on ? Ui.semiBold(getContext()) : Ui.medium(getContext()));
        }
        invalidate();
    }

    public Item getActive() {
        return active;
    }

    @Override
    protected void onDraw(Canvas c) {
        // Rounded panel with a thin border, inside the side/bottom margins.
        float inset = Ui.dp(getContext(), 26);
        float top = Ui.dp(getContext(), 4);
        float bottom = getHeight() - Ui.dp(getContext(), 14);
        float r = Ui.dp(getContext(), 26);
        rect.set(inset, top, getWidth() - inset, bottom);
        c.drawRoundRect(rect, r, r, panel);
        c.drawRoundRect(rect, r, r, border);
        super.onDraw(c);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (active == null || slots[active.ordinal()] == null) return;
        View slot = slots[active.ordinal()];
        float w = Ui.dp(getContext(), 88);
        float cx = slot.getLeft() + slot.getWidth() / 2f;
        float y = getHeight() - Ui.dp(getContext(), 26);
        float h = Ui.dp(getContext(), 4);
        rect.set(cx - w / 2f, y - h, cx + w / 2f, y);
        c.drawRoundRect(rect, h / 2f, h / 2f, underline);
    }
}
