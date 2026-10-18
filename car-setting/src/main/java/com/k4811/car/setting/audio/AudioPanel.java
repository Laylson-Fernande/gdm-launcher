package com.k4811.car.setting.audio;

import com.k4811.common.audio.AudioGains;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.k4811.car.setting.R;
import com.k4811.common.eq.EqModel;
import com.k4811.common.eq.SettingClient;
import com.k4811.car.setting.ui.OptionList;
import com.k4811.common.ui.ThemeDrawables;
import com.k4811.common.ui.Ui;
import com.nwd.setting.service.SettingFeature;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings › Audio: one horizontal slider per source (radio, Bluetooth, media, navigation, AUX)
 * and the Android music ceiling. Each change is applied when the finger lifts.
 */
public final class AudioPanel {

    private final Activity activity;
    private final OptionList list;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SeekBar[] sliders = new SeekBar[AudioGains.SOURCES.length];
    private final TextView[] values = new TextView[AudioGains.SOURCES.length];
    private SettingClient client;
    private TextView status;
    private int maxGain;

    public AudioPanel(Activity activity, OptionList list) {
        this.activity = activity;
        this.list = list;
    }

    /** Adds the rows to the list's current panel and connects to the head unit's audio service. */
    public void build() {
        maxGain = AudioGains.maxGain(activity);
        list.section(activity.getString(R.string.audio_sources_section));
        // Always visible (OptionList re-shows section notes on every render): only its text changes.
        status = list.note(activity.getString(R.string.audio_connecting), Ui.TEXT_MUTED);
        for (int i = 0; i < AudioGains.SOURCES.length; i++) {
            final int index = i;
            final AudioGains.Source source = AudioGains.SOURCES[i];
            values[i] = valueText();
            sliders[i] = slider(maxGain, values[i], 0, v -> applyGain(index, source.id, v));
            setEnabled(sliders[i], false); // until the current value is read
            values[i].setText("—");
            list.custom(activity.getString(source.labelRes), null, row(sliders[i], values[i])).local();
        }

        list.section(activity.getString(R.string.audio_music_section));
        TextView musicValue = valueText();
        SeekBar music = slider(AudioGains.MUSIC_MAX - AudioGains.MUSIC_MIN, musicValue, AudioGains.MUSIC_MIN,
            v -> AudioGains.setMusicMax(activity, v));
        music.setProgress(AudioGains.musicMax() - AudioGains.MUSIC_MIN);
        musicValue.setText(String.valueOf(AudioGains.musicMax()));
        list.custom(activity.getString(R.string.audio_music_max),
            activity.getString(R.string.audio_music_max_hint), row(music, musicValue)).local();

        client = new SettingClient(activity, new AudioGains.Callbacks() {
            @Override
            public void onConnection(EqModel.Connection connection) {
                main.post(() -> renderConnection(connection));
            }

            @Override
            public void onConnected(SettingFeature f) {
                worker.execute(() -> readGains(f));
            }
        });
        client.connect();
    }

    public void release() {
        if (client != null) client.release();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    private void renderConnection(EqModel.Connection c) {
        if (c == EqModel.Connection.NOT_FOUND || c == EqModel.Connection.DISCONNECTED) {
            status.setText(R.string.audio_unavailable);
            status.setTextColor(Ui.DANGER);
            for (SeekBar s : sliders) setEnabled(s, false);
        }
    }

    private void readGains(SettingFeature f) {
        final int[] current = new int[AudioGains.SOURCES.length];
        for (int i = 0; i < current.length; i++) {
            try {
                current[i] = f.getGainParam(AudioGains.SOURCES[i].id);
            } catch (Exception e) {
                current[i] = -1;
            }
        }
        main.post(() -> {
            status.setText(R.string.audio_sources_hint);
            status.setTextColor(Ui.TEXT_MUTED);
            for (int i = 0; i < current.length; i++) {
                if (current[i] < 0) continue;
                sliders[i].setProgress(Math.min(maxGain, current[i]));
                values[i].setText(String.valueOf(current[i]));
                setEnabled(sliders[i], true);
            }
        });
    }

    private void applyGain(int index, int source, int value) {
        SettingFeature f = client == null ? null : client.feature();
        if (f == null) return;
        worker.execute(() -> {
            try {
                AudioGains.apply(activity, f, source, value);
            } catch (Exception e) {
                main.post(() -> {
                    status.setText(activity.getString(R.string.audio_apply_failed, e.getMessage()));
                    status.setTextColor(Ui.DANGER);
                });
            }
        });
    }

    // ------------------------------------------------------------------ views

    private static void setEnabled(SeekBar s, boolean on) {
        s.setEnabled(on);
        s.setAlpha(on ? 1f : 0.35f);
    }

    private interface OnValue {
        void set(int value);
    }

    /** Slider 0..range shown as offset..offset+range; applies on release. */
    private SeekBar slider(int range, TextView value, int offset, OnValue onValue) {
        SeekBar s = new SeekBar(activity);
        s.setMax(range);
        s.setProgressDrawable(ThemeDrawables.drawable(activity, R.drawable.seek_line));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Ui.ACCENT);
        int t = px(28);
        thumb.setSize(t, t);
        s.setThumb(thumb);
        s.setThumbOffset(t / 2);
        s.setPadding(t / 2, 0, t / 2, 0);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(String.valueOf(progress + offset));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                onValue.set(bar.getProgress() + offset);
            }
        });
        return s;
    }

    private TextView valueText() {
        TextView v = new TextView(activity);
        v.setTextColor(Ui.TEXT);
        v.setTextSize(24);
        v.setTypeface(Ui.medium(activity));
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        return v;
    }

    private View row(SeekBar slider, TextView value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(slider, new LinearLayout.LayoutParams(px(520), px(64)));
        row.addView(value, new LinearLayout.LayoutParams(px(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private int px(float dp) {
        return Math.round(Ui.dp(activity, dp));
    }
}
