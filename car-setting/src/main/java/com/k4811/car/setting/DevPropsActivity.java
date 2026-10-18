package com.k4811.car.setting;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.k4811.common.system.SystemProps;
import com.k4811.common.ui.IconView;
import com.k4811.car.setting.ui.InputDialog;
import com.k4811.common.ui.Touch;
import com.k4811.common.ui.Ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Developer menu (password-protected in Settings): lists all system props
 * ({@code getprop} output), lets you filter, change a prop via the NWD channel and export everything
 * to a text file.
 */
public class DevPropsActivity extends Activity {

    /** getprop line: "[name]: [value]". */
    private static final Pattern LINE = Pattern.compile("^\\[(.*)\\]: \\[(.*)\\]$");
    /** Time for the NWD service to apply the prop before checking. */
    private static final long SET_CHECK_MS = 1500;

    private static final class Prop {
        final String name;
        final String value;

        Prop(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Prop> all = new ArrayList<>();
    private final List<Prop> shown = new ArrayList<>();
    private BaseAdapter adapter;
    private EditText filter;
    private TextView count;
    private TextView status;
    private Dialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        setContentView(R.layout.activity_dev_props);
        Ui.applyFont(findViewById(R.id.dev_root));
        ((TextView) findViewById(R.id.dev_title)).setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.dev_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        count = (TextView) findViewById(R.id.dev_count);
        status = (TextView) findViewById(R.id.dev_status);
        filter = (EditText) findViewById(R.id.dev_filter);
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int b, int c) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        findViewById(R.id.dev_refresh).setOnClickListener(v -> load(null));
        findViewById(R.id.dev_export).setOnClickListener(v -> export());

        ListView list = (ListView) findViewById(R.id.dev_list);
        adapter = new PropAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> edit(shown.get(position)));

        Touch.install(findViewById(android.R.id.content));
        load(null);
    }

    // ------------------------------------------------------------------ reading

    /** Reads all props off the main thread. {@code then} runs after the list is shown. */
    private void load(Runnable then) {
        io.execute(() -> {
            List<Prop> props = readAll();
            handler.post(() -> {
                if (isFinishing()) return;
                all.clear();
                all.addAll(props);
                applyFilter();
                if (props.isEmpty()) showStatus(getString(R.string.adv_props_read_failed));
                if (then != null) then.run();
            });
        });
    }

    private static List<Prop> readAll() {
        List<Prop> props = new ArrayList<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = LINE.matcher(line);
                if (m.matches()) props.add(new Prop(m.group(1), m.group(2)));
            }
            r.close();
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return props;
    }

    private void applyFilter() {
        String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Prop p : all) {
            if (q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q)
                || p.value.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(p);
            }
        }
        count.setText(q.isEmpty() ? getString(R.string.adv_props_count, all.size())
            : getString(R.string.adv_props_count_filtered, shown.size(), all.size()));
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ change

    private void edit(Prop prop) {
        if (dialog != null) dialog.dismiss();
        dialog = InputDialog.show(this, prop.name,
            getString(R.string.adv_prop_dialog_msg,
                prop.value.isEmpty() ? getString(R.string.adv_prop_empty_paren) : prop.value),
            prop.value, false, getString(R.string.adv_prop_save), value -> {
                setProp(prop.name, value.trim());
                hideKeyboard();
                return null;
            });
    }

    private void setProp(String name, String value) {
        SystemProps.requestSet(this, name, value);
        showStatus(getString(R.string.adv_prop_applying, name, value));
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            String now = SystemProps.get(name, "");
            if (value.equals(now)) {
                showStatus("✓ " + name + " = " + value);
            } else {
                showStatus(getString(R.string.adv_prop_not_applied, name,
                    now.isEmpty() ? getString(R.string.adv_prop_empty) : now, value));
            }
            load(null);
        }, SET_CHECK_MS);
    }

    // ------------------------------------------------------------------ export

    /** Writes all props (getprop format) to Download/; without access, to the app's folder. */
    private void export() {
        showStatus(getString(R.string.adv_exporting));
        io.execute(() -> {
            List<Prop> props = readAll();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            String name = "k4811-props-" + stamp + ".txt";
            File file = write(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name, props);
            if (file == null) file = write(getExternalFilesDir(null), name, props);
            if (file == null) file = write(getFilesDir(), name, props);
            File done = file;
            handler.post(() -> showStatus(done == null ? getString(R.string.adv_export_failed)
                : getString(R.string.adv_export_done, props.size(), done.getAbsolutePath())));
        });
    }

    private static File write(File dir, String name, List<Prop> props) {
        if (dir == null) return null;
        try {
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, name);
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write("# Head unit props - K4811 Launcher\n");
                w.write("# " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "\n");
                w.write("# " + Build.MANUFACTURER + " " + Build.MODEL + " - " + Build.FINGERPRINT + "\n");
                w.write("# " + props.size() + " props\n\n");
                for (Prop p : props) w.write("[" + p.name + "]: [" + p.value + "]\n");
            } finally {
                w.close();
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(filter.getWindowToken(), 0);
        filter.clearFocus();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ list

    private final class PropAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View row, ViewGroup parent) {
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.dev_prop_row, parent, false);
                Ui.applyFont(row);
                Touch.hapticOnly(row);
            }
            Prop p = shown.get(position);
            ((TextView) row.findViewById(R.id.prop_name)).setText(p.name);
            ((TextView) row.findViewById(R.id.prop_value)).setText(p.value.isEmpty() ? "—" : p.value);
            return row;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
