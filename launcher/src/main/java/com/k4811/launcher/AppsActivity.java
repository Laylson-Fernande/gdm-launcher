package com.k4811.launcher;

import android.content.Context;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.k4811.launcher.home.AppCatalog;
import com.k4811.launcher.start.StartGuard;
import com.k4811.common.ui.IconView;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every installed app in a 5-column grid that scrolls down until the last one (no pages). The
 * icons come in with a short cascade the first time, sink a little under the finger and the app
 * opens zooming out of its icon. Back returns to the home screen.
 *
 * The suite's apps come first, then the others, each group alphabetical ({@link AppCatalog}). The
 * list shows at once from the cache and is refreshed in the background (new or updated apps).
 * Holding an app hides it; the "Hidden" button lists the hidden ones, and tapping one shows it again.
 */
public class AppsActivity extends Activity {

    /** Suite language in the screen context (survives window changes, see Ui.withLanguage). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.withLanguage(base));
    }

    /** Rows visible at once: two and a half, so the cut row shows there is more below. */
    private static final float VISIBLE_ROWS = 2.5f;
    private static final float PRESSED_SCALE = 0.93f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    /** Every app (hidden ones included), in drawer order. */
    private List<AppCatalog.Entry> all = new ArrayList<>();
    /** What the grid shows: the visible apps or, in the hidden list, the hidden ones. */
    private final List<AppCatalog.Entry> apps = new ArrayList<>();
    private Set<String> hidden;
    /** Showing the hidden apps (tap = show again). */
    private boolean showingHidden;
    private TextView title;
    private TextView hiddenButton;
    private Dialog dialog;

    private GridView grid;
    private TextView countView;
    private TextView empty;
    private AppsAdapter adapter;
    private int rowHeight;
    private int loadGen;
    /** The cascade only plays when the drawer opens, not when coming back from an app. */
    private boolean animatedIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        Ui.applyFont(findViewById(R.id.apps_root));

        title = (TextView) findViewById(R.id.apps_title);
        title.setTypeface(Ui.semiBold(this));
        countView = (TextView) findViewById(R.id.apps_count);
        hiddenButton = (TextView) findViewById(R.id.apps_hidden);
        hiddenButton.setOnClickListener(v -> {
            showingHidden = !showingHidden;
            show();
        });
        hidden = AppCatalog.hidden(this);
        empty = (TextView) findViewById(R.id.apps_empty);

        IconView back = (IconView) findViewById(R.id.apps_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> onBackPressed());

        grid = (GridView) findViewById(R.id.apps_grid);
        // A longer, softer fling than the default: the list glides to a stop.
        grid.setFriction(ViewConfiguration.getScrollFriction() * 0.7f);
        grid.setVelocityScale(1.15f);
        grid.setLayoutAnimation(cascade());
        adapter = new AppsAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (showingHidden) unhide(position);
            else open(view, position);
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (showingHidden) return false;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            // The dialog takes the touch: the finger never lifts on the cell, so it springs back here.
            view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            askHide(position);
            return true;
        });
        // Row height follows the area the grid really got.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h <= 0 || h == ob - ot) return;
            int usable = h - grid.getPaddingTop() - Math.round(Ui.dp(this, 8)) * 2;
            int next = Math.round(usable / VISIBLE_ROWS);
            if (next != rowHeight) {
                rowHeight = next;
                v.post(adapter::notifyDataSetChanged);
            }
        });
        Touch.install(findViewById(R.id.apps_back));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        reload();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        StartGuard.userActed();
    }

    @Override
    public void onBackPressed() {
        if (showingHidden) {
            showingHidden = false;
            show();
            return;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        loadGen++;
        loader.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (dialog != null) dialog.dismiss();
        grid.setAdapter(null);
        // The icons stay in AppCatalog's cache for the next opening: nothing to recycle here.
        apps.clear();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    /**
     * Cache first (memory now; disk, the first time after the launcher starts), then the installed
     * apps in the background: the grid only changes if something did.
     */
    private void reload() {
        final int gen = ++loadGen;
        final int iconPx = Math.round(Ui.dp(this, 88));
        List<AppCatalog.Entry> ready = AppCatalog.inMemory(iconPx);
        if (ready != null && all.isEmpty()) setAll(ready);
        loader.execute(() -> {
            if (ready == null) {
                List<AppCatalog.Entry> disk = AppCatalog.cached(getApplicationContext(), iconPx);
                if (disk != null) post(gen, disk);
            }
            post(gen, AppCatalog.refresh(getApplicationContext(), iconPx));
        });
    }

    private void post(int gen, List<AppCatalog.Entry> list) {
        handler.post(() -> {
            if (isFinishing() || gen != loadGen || AppCatalog.same(all, list)) return;
            setAll(list);
        });
    }

    private void setAll(List<AppCatalog.Entry> list) {
        all = list;
        show();
    }

    /** Fills the grid for the current mode (visible apps or hidden ones). */
    private void show() {
        apps.clear();
        int hiddenCount = 0;
        for (AppCatalog.Entry e : all) {
            boolean isHidden = hidden.contains(e.key());
            if (isHidden) hiddenCount++;
            if (isHidden == showingHidden) apps.add(e);
        }
        title.setText(showingHidden ? R.string.home_apps_hidden_title : R.string.home_apps_title);
        hiddenButton.setVisibility(showingHidden || hiddenCount > 0 ? View.VISIBLE : View.GONE);
        hiddenButton.setText(showingHidden ? getString(R.string.home_apps_done)
            : getString(R.string.home_apps_hidden_button, hiddenCount));
        hiddenButton.setActivated(showingHidden);
        boolean none = apps.isEmpty();
        grid.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        empty.setText(showingHidden ? R.string.home_apps_hidden_none : R.string.home_apps_empty);
        countView.setText(none ? "" : showingHidden ? getString(R.string.home_apps_hidden_hint)
            : getResources().getQuantityString(R.plurals.home_apps_count, apps.size(), apps.size()));
        adapter.notifyDataSetChanged();
        if (!none && !animatedIn) {
            animatedIn = true;
            grid.scheduleLayoutAnimation();
        }
    }

    /** Each icon rises a little, grows from 92% and fades in, one shortly after the other. */
    private LayoutAnimationController cascade() {
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        set.addAnimation(new TranslateAnimation(0, 0, Ui.dp(this, 36), 0));
        set.addAnimation(new ScaleAnimation(0.92f, 1f, 0.92f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f));
        set.setDuration(320);
        set.setInterpolator(new DecelerateInterpolator(1.6f));
        LayoutAnimationController c = new LayoutAnimationController(set, 0.12f);
        c.setOrder(LayoutAnimationController.ORDER_NORMAL);
        return c;
    }

    // ------------------------------------------------------------------ hidden apps

    private void askHide(int position) {
        if (position < 0 || position >= apps.size()) return;
        AppCatalog.Entry entry = apps.get(position);
        if (dialog != null) dialog.dismiss();
        Dialog d = new Dialog(this, R.style.OverlayDialog);
        View root = getLayoutInflater().inflate(R.layout.dialog_confirm, null);
        Ui.applyFont(root);
        d.setContentView(root);
        ((TextView) root.findViewById(R.id.confirm_title)).setText(getString(R.string.home_apps_hide_title, entry.label));
        ((TextView) root.findViewById(R.id.confirm_message)).setText(R.string.home_apps_hide_message);
        TextView cancel = (TextView) root.findViewById(R.id.confirm_negative);
        cancel.setText(R.string.home_cancel);
        cancel.setOnClickListener(v -> d.dismiss());
        TextView ok = (TextView) root.findViewById(R.id.confirm_positive);
        ok.setText(R.string.home_apps_hide);
        ok.setActivated(true);
        ok.setOnClickListener(v -> {
            d.dismiss();
            AppCatalog.hide(this, entry, true);
            hidden = AppCatalog.hidden(this);
            show();
        });
        root.setOnClickListener(v -> d.dismiss());
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Ui.hideSystemBars(d.getWindow());
        d.show();
        Touch.install(root);
        dialog = d;
    }

    private void unhide(int position) {
        if (position < 0 || position >= apps.size()) return;
        AppCatalog.Entry entry = apps.get(position);
        AppCatalog.hide(this, entry, false);
        hidden = AppCatalog.hidden(this);
        Toast.makeText(this, getString(R.string.home_apps_unhidden, entry.label), Toast.LENGTH_SHORT).show();
        if (hidden.isEmpty()) showingHidden = false;
        show();
    }

    private void open(View cell, int position) {
        if (position < 0 || position >= apps.size()) return;
        AppCatalog.Entry entry = apps.get(position);
        cell.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(entry.packageName, entry.className));
            // Our own screens (e.g. Ventoinha) have no LAUNCHER category; external apps do.
            if (!getPackageName().equals(entry.packageName)) {
                i.setAction(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // The app grows out of its icon.
            Bundle zoom = ActivityOptions.makeScaleUpAnimation(cell, 0, 0, cell.getWidth(), cell.getHeight()).toBundle();
            startActivity(i, zoom);
        } catch (Exception e) {
            Toast.makeText(this, R.string.home_apps_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private final class AppsAdapter extends BaseAdapter {
        private final OvershootInterpolator spring = new OvershootInterpolator(3f);
        private final DecelerateInterpolator press = new DecelerateInterpolator();

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View cell = convertView;
            if (cell == null) {
                cell = getLayoutInflater().inflate(R.layout.app_cell, parent, false);
                Ui.applyFont(cell);
                ((TextView) cell.findViewById(R.id.app_label)).setTypeface(Ui.medium(AppsActivity.this));
                // Sinks under the finger and springs back; the grid still gets the tap and the scroll.
                cell.setOnTouchListener((v, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                                .setDuration(90).setInterpolator(press).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(spring).start();
                            break;
                        default:
                            break;
                    }
                    return false;
                });
            }
            if (rowHeight > 0) {
                ViewGroup.LayoutParams lp = cell.getLayoutParams();
                if (lp == null || lp.height != rowHeight) {
                    cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
                }
            }
            AppCatalog.Entry entry = apps.get(position);
            cell.setAlpha(showingHidden ? 0.55f : 1f);
            // A recycled cell may still be pressed in (after a long press).
            cell.animate().cancel();
            cell.setScaleX(1f);
            cell.setScaleY(1f);
            cell.setContentDescription(entry.label);
            ((TextView) cell.findViewById(R.id.app_label)).setText(entry.label);
            ImageView icon = (ImageView) cell.findViewById(R.id.app_icon);
            if (entry.icon != null) icon.setImageBitmap(entry.icon);
            else icon.setImageDrawable(null);
            return cell;
        }
    }
}
