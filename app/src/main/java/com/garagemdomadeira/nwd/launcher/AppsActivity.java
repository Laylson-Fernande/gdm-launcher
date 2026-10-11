package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.garagemdomadeira.nwd.launcher.home.AppCatalog;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Grid of installed apps, 5×2 per page. Back returns to the home screen. */
public class AppsActivity extends Activity {

    private static final int COLUMNS = 5;
    private static final int ROWS = 2;
    private static final int PAGE_SIZE = COLUMNS * ROWS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<AppCatalog.Entry> apps = new ArrayList<>();

    private LinearLayout grid;
    private LinearLayout dots;
    private TextView pageLabel;
    private TextView empty;
    private View prev;
    private View next;
    private final View[] cells = new View[PAGE_SIZE];
    private GestureDetector gesture;
    private boolean swiped;
    private int page;
    private int loadGen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        Ui.applyFont(findViewById(R.id.apps_root));

        ((TextView) findViewById(R.id.apps_title)).setTypeface(Ui.semiBold(this));
        pageLabel = (TextView) findViewById(R.id.apps_page);
        empty = (TextView) findViewById(R.id.apps_empty);
        grid = (LinearLayout) findViewById(R.id.apps_grid);
        dots = (LinearLayout) findViewById(R.id.apps_dots);

        IconView back = (IconView) findViewById(R.id.apps_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        prev = findViewById(R.id.apps_prev);
        next = findViewById(R.id.apps_next);
        ((IconView) prev).set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        ((IconView) next).set(IconView.Icon.CHEVRON_RIGHT, IconView.Style.NONE);
        prev.setOnClickListener(v -> showPage(page - 1));
        next.setOnClickListener(v -> showPage(page + 1));

        LinearLayout row0 = (LinearLayout) findViewById(R.id.apps_row_0);
        LinearLayout row1 = (LinearLayout) findViewById(R.id.apps_row_1);
        for (int i = 0; i < PAGE_SIZE; i++) {
            View cell = getLayoutInflater().inflate(R.layout.app_cell, i < COLUMNS ? row0 : row1, false);
            Ui.applyFont(cell);
            ((TextView) cell.findViewById(R.id.app_label)).setTypeface(Ui.medium(this));
            cell.setOnClickListener(this::openCell);
            (i < COLUMNS ? row0 : row1).addView(cell);
            cells[i] = cell;
        }

        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                swiped = false;
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(vx) < Math.abs(vy) || Math.abs(dx) < 80) return false;
                swiped = true;
                showPage(page + (dx < 0 ? 1 : -1));
                return true;
            }
        });
        Touch.install(findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        reload();
    }

    @Override
    protected void onDestroy() {
        loadGen++;
        loader.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        for (View cell : cells) {
            if (cell == null) continue;
            ImageView icon = (ImageView) cell.findViewById(R.id.app_icon);
            if (icon != null) icon.setImageDrawable(null);
        }
        AppCatalog.recycle(new ArrayList<>(apps));
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gesture.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void reload() {
        final int gen = ++loadGen;
        final int iconPx = Math.round(Ui.dp(this, 88));
        loader.execute(() -> {
            List<AppCatalog.Entry> loaded = AppCatalog.load(getApplicationContext(), iconPx);
            handler.post(() -> {
                if (isFinishing() || gen != loadGen) {
                    AppCatalog.recycle(loaded);
                    return;
                }
                List<AppCatalog.Entry> previous = new ArrayList<>(apps);
                apps.clear();
                apps.addAll(loaded);
                int last = Math.max(0, pageCount() - 1);
                if (page > last) page = last;
                showPage(page);
                AppCatalog.recycle(previous);
            });
        });
    }

    private void showPage(int index) {
        int count = pageCount();
        if (count == 0) {
            page = 0;
            grid.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            pageLabel.setText("");
            prev.setVisibility(View.INVISIBLE);
            next.setVisibility(View.INVISIBLE);
            dots.removeAllViews();
            return;
        }
        page = Math.max(0, Math.min(index, count - 1));
        grid.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        pageLabel.setText((page + 1) + " / " + count);
        prev.setVisibility(page > 0 ? View.VISIBLE : View.INVISIBLE);
        next.setVisibility(page < count - 1 ? View.VISIBLE : View.INVISIBLE);

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            View cell = cells[i];
            int at = start + i;
            if (at >= apps.size()) {
                cell.setVisibility(View.INVISIBLE);
                cell.setTag(null);
                continue;
            }
            AppCatalog.Entry entry = apps.get(at);
            cell.setVisibility(View.VISIBLE);
            cell.setTag(entry);
            cell.setContentDescription(entry.label);
            TextView label = (TextView) cell.findViewById(R.id.app_label);
            ImageView icon = (ImageView) cell.findViewById(R.id.app_icon);
            label.setText(entry.label);
            if (entry.icon != null) icon.setImageBitmap(entry.icon);
            else icon.setImageDrawable(null);
        }
        drawDots(count);
    }

    private void drawDots(int count) {
        dots.removeAllViews();
        if (count < 2) return;
        for (int i = 0; i < count; i++) {
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setTextSize(16);
            dot.setTextColor(i == page ? Ui.ACCENT : Ui.TEXT_DIM);
            dot.setPadding(8, 0, 8, 0);
            dots.addView(dot);
        }
    }

    private int pageCount() {
        if (apps.isEmpty()) return 0;
        return (apps.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private void openCell(View cell) {
        if (swiped) return;
        Object tag = cell.getTag();
        if (!(tag instanceof AppCatalog.Entry)) return;
        AppCatalog.Entry entry = (AppCatalog.Entry) tag;
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(entry.packageName, entry.className));
            // Our own screens (e.g. Ventoinha) have no LAUNCHER category; external apps do.
            if (!getPackageName().equals(entry.packageName)) {
                i.setAction(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.home_apps_not_found, Toast.LENGTH_SHORT).show();
        }
    }
}
