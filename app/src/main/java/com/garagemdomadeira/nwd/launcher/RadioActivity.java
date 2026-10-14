package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.garagemdomadeira.nwd.launcher.home.PlayerHandoff;
import com.garagemdomadeira.nwd.launcher.radio.Band;
import com.garagemdomadeira.nwd.launcher.radio.BandRange;
import com.garagemdomadeira.nwd.launcher.radio.Preset;
import com.garagemdomadeira.nwd.launcher.radio.PresetStore;
import com.garagemdomadeira.nwd.launcher.radio.RadioClient;
import com.garagemdomadeira.nwd.launcher.radio.RadioState;
import com.garagemdomadeira.nwd.launcher.radio.StationArt;
import com.garagemdomadeira.nwd.launcher.system.DevMode;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.IconView.Icon;
import com.garagemdomadeira.nwd.launcher.ui.IconView.Style;
import com.garagemdomadeira.nwd.launcher.ui.PresetCardView;
import com.garagemdomadeira.nwd.launcher.ui.SlantedTabsView;
import com.garagemdomadeira.nwd.launcher.ui.TunerDialView;
import com.garagemdomadeira.nwd.launcher.ui.DockView;
import com.garagemdomadeira.nwd.launcher.ui.Screens;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Radio screen. Opened by the "Rádio" button on the home screen: on open, the radio is turned on
 * (becomes the audio source). The user can turn it off with Power; "Sair" turns it off and goes home.
 */
public class RadioActivity extends Activity implements RadioClient.Listener {

    private static final int PRESET_SLOTS = 12;

    private RadioClient radio;
    private RadioState state = new RadioState();
    private Integer preview;

    private SlantedTabsView tabs;
    private TextView freqValue, freqUnit, stationName, radioText;
    private View stationArtFrame;
    private ImageView stationArt;
    private IconView stationArtPlaceholder;
    /** Current station's art file (e.g. FM_104.5), to avoid reloading on every service update. */
    private String artKey;
    private int artGen;
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TunerDialView dial;
    private View toggleSt, toggleLoc;
    private View toggleStDot, toggleLocDot;
    private TextView banner;
    private final PresetCardView[] cards = new PresetCardView[PRESET_SLOTS];
    private IconView powerButton;
    /** Radio on = it's the active audio source. Off = source 0 (silence), app stays open. */
    private boolean radioOn = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        Ui.applyFont(findViewById(android.R.id.content));
        Screens.attachDock(this, DockView.Item.RADIO);
        bindViews();

        radio = new RadioClient(this);
        radio.setListener(this);
        if (getIntent().getBooleanExtra("demo", false)) radio.loadDemo();
        else radio.connect();
        Touch.install(findViewById(android.R.id.content));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tapping "Rádio" again on the home screen always turns the radio on.
        if (!radioOn) setRadioOn(true);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The system recomputes density on every configuration change; reapply ours.
        Ui.applyDesignDensity(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        // When coming to the foreground, the radio becomes the audio source (unless the user
        // turned it off with the Power button, or it already is the source: nothing to restart).
        if (radioOn && !PlayerHandoff.radioIsSource(this)) {
            PlayerHandoff.switchToRadio(this, radio::activateSource);
        }
        radioText.setSelected(true); // required for the marquee to scroll
        warnBrokenConfig();
    }

    /** radio.config.json edited by hand with a mistake: memories won't be saved until it's fixed. */
    private void warnBrokenConfig() {
        new Thread(() -> {
            if (!PresetStore.isBroken()) return;
            String path = String.valueOf(PresetStore.file());
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    Toast.makeText(this, getString(R.string.radio_config_invalid, path), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Returning from a dialog (or another app) brings the system bar back; hide it again.
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        radio.setListener(null);
        radio.release();
        artGen++;
        artLoader.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ setup

    private void bindViews() {
        tabs = (SlantedTabsView) findViewById(R.id.tabs);
        tabs.setLabels(Band.LABELS);
        tabs.setOnTabSelected(i -> {
            ensureOn();
            radio.selectBand(Band.SELECTABLE[i]);
        });

        icon(R.id.btn_exit, Icon.EXIT, Style.NONE).setOnClickListener(v -> {
            radio.deactivateSource();
            finish();
        });


        freqValue = text(R.id.freq_value);
        // Diagnostics without a button: hold the frequency for ~1 s (developer mode only).
        if (DevMode.enabled()) {
            freqValue.setOnLongClickListener(v -> {
                showDiagnostics();
                return true;
            });
        }
        freqUnit = text(R.id.freq_unit);
        stationName = text(R.id.station_name);
        stationArtFrame = findViewById(R.id.station_art_frame);
        stationArt = (ImageView) findViewById(R.id.station_art);
        stationArtPlaceholder = (IconView) findViewById(R.id.station_art_placeholder);
        stationArtPlaceholder.set(Icon.RADIO, Style.NONE).color(Ui.TEXT_DIM);
        radioText = text(R.id.radio_text);
        radioText.setTypeface(Ui.regular(this));

        dial = (TunerDialView) findViewById(R.id.dial);
        dial.setListener(new TunerDialView.Listener() {
            @Override
            public void onPreview(Integer frequency) {
                preview = frequency;
                renderFrequency();
            }

            @Override
            public void onTune(int frequency) {
                ensureOn();
                radio.tune(frequency);
            }
        });

        // ⏮ ⏭: previous/next saved station. ‹ ›: 1 frequency step (0.1 MHz on FM).
        icon(R.id.btn_seek_prev, Icon.SKIP_PREV, Style.NONE).setOnClickListener(v -> {
            ensureOn();
            radio.presetStep(false);
        });
        icon(R.id.btn_step_prev, Icon.CHEVRON_LEFT, Style.NONE).setOnClickListener(v -> {
            ensureOn();
            radio.step(false);
        });
        icon(R.id.btn_step_next, Icon.CHEVRON_RIGHT, Style.NONE).setOnClickListener(v -> {
            ensureOn();
            radio.step(true);
        });
        icon(R.id.btn_seek_next, Icon.SKIP_NEXT, Style.NONE).setOnClickListener(v -> {
            ensureOn();
            radio.presetStep(true);
        });
        // Central button (same look as the MP3 play button): turns the radio on/off.
        powerButton = icon(R.id.btn_power, Icon.POWER, Style.OUTLINE_CIRCLE);
        powerButton.setOnClickListener(v -> setRadioOn(!radioOn));

        // Reception toggles: fixed icon and label; the dot on the right lights up when the function is on.
        toggleSt = findViewById(R.id.toggle_st);
        toggleIcon(R.id.toggle_st_icon, Icon.STEREO);
        text(R.id.toggle_st_text).setText(R.string.radio_stereo);
        toggleStDot = findViewById(R.id.toggle_st_dot);
        toggleLoc = findViewById(R.id.toggle_loc);
        toggleIcon(R.id.toggle_loc_icon, Icon.TOWER_FAR);
        text(R.id.toggle_loc_text).setText(R.string.radio_distant);
        toggleLocDot = findViewById(R.id.toggle_loc_dot);
        toggleSt.setOnClickListener(v -> radio.setStereo(!state.stereoOn));
        toggleLoc.setOnClickListener(v -> radio.setLoc(!state.locOn));
        renderToggles();
        icon(R.id.btn_keypad, Icon.KEYBOARD, Style.NONE).setOnClickListener(v -> showKeypad());

        buildPresetGrid();

        banner = text(R.id.banner);
        renderPower();
    }

    /** Landscape: 2 columns × 6 rows in the side panel. Portrait (horizontal container): one row. */
    private void buildPresetGrid() {
        LinearLayout grid = (LinearLayout) findViewById(R.id.presets_grid);
        if (grid.getOrientation() == LinearLayout.HORIZONTAL) {
            buildPresetRow(grid);
            return;
        }
        int cardH = Math.round(Ui.dp(this, 128));
        int gap = Math.round(Ui.dp(this, 4));
        for (int row = 0; row < PRESET_SLOTS / 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                PresetCardView card = new PresetCardView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cardH, 1f);
                lp.setMargins(col == 0 ? 0 : gap, 0, col == 0 ? gap : 0, 0);
                line.addView(card, lp);
                card.setOnClickListener(v -> {
                    ensureOn();
                    playPreset(index);
                });
                card.setOnLongClickListener(v -> {
                    radio.savePreset(index);
                    Toast.makeText(this, getString(R.string.radio_preset_saved, index + 1), Toast.LENGTH_SHORT).show();
                    return true;
                });
                cards[index] = card;
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH));
        }
    }

    /** The 12 memories side by side; about 6 fit across, the row scrolls sideways. */
    private void buildPresetRow(LinearLayout row) {
        int cardW = Math.round(Ui.dp(this, 146));
        int cardH = Math.round(Ui.dp(this, 112));
        int gap = Math.round(Ui.dp(this, 8));
        for (int i = 0; i < PRESET_SLOTS; i++) {
            int index = i;
            PresetCardView card = new PresetCardView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.leftMargin = gap;
            row.addView(card, lp);
            card.setOnClickListener(v -> {
                ensureOn();
                playPreset(index);
            });
            card.setOnLongClickListener(v -> {
                radio.savePreset(index);
                Toast.makeText(this, getString(R.string.radio_preset_saved, index + 1), Toast.LENGTH_SHORT).show();
                return true;
            });
            cards[index] = card;
        }
    }

    private IconView icon(int id, Icon icon, Style style) {
        return ((IconView) findViewById(id)).set(icon, style);
    }

    /** Icon inside a toggle: the toggle handles the tap. */
    private void toggleIcon(int id, Icon icon) {
        IconView v = (IconView) findViewById(id);
        v.set(icon, Style.NONE).color(Ui.TEXT);
        v.setClickable(false);
        v.setFocusable(false);
    }

    private TextView text(int id) {
        return (TextView) findViewById(id);
    }

    // ------------------------------------------------------------------ state → screen

    @Override
    public void onRadioState(RadioState s) {
        state = s;
        boolean fm = s.isFm();

        tabs.setSelected(Math.max(0, Math.min(4, s.band)));
        renderFrequency();
        bindArt(s.band, s.frequency);
        dial.setFrequency(s.frequency, s.range(), fm);

        if (radioOn) onRadioStateText();
        if (!TextUtils.equals(radioText.getText(), s.radioText)) radioText.setText(s.radioText);
        int rtVis = s.radioText.isEmpty() ? View.INVISIBLE : View.VISIBLE;
        findViewById(R.id.rt_left).setVisibility(rtVis);
        findViewById(R.id.rt_right).setVisibility(rtVis);

        renderToggles();
        renderPresets(s);
        renderBanner(s.connection);
    }

    private void renderToggles() {
        toggleStDot.setActivated(state.stereoOn);
        toggleLocDot.setActivated(!state.locOn); // "Distant" = LOC off
    }

    private void renderFrequency() {
        boolean fm = state.isFm();
        int f = preview != null ? preview : state.frequency;
        freqValue.setText(Band.formatValue(f, fm));
        freqValue.setTextColor(preview != null ? Ui.ACCENT : Ui.TEXT);
        freqUnit.setText(Band.unit(fm));
    }

    /** Looks for Garagem/radio/<FM|AM>_<freq>.jpg for the tuned station; without a file, the radio placeholder. */
    private void bindArt(int band, int frequency) {
        String key = StationArt.baseName(band, frequency);
        if (key.equals(artKey)) return;
        artKey = key;
        int gen = ++artGen;
        int size = stationArtFrame.getLayoutParams().width;
        artLoader.execute(() -> {
            Bitmap bmp = StationArt.load(band, frequency, size);
            handler.post(() -> {
                if (gen != artGen) return;
                // The square always stays; without a picture, the radio icon shows through.
                stationArt.setImageBitmap(bmp);
                show(stationArtPlaceholder, bmp == null);
            });
        });
    }

    private void renderPresets(RadioState s) {
        Preset[] presets = s.currentPresets();
        boolean fm = s.isFm();
        for (int i = 0; i < PRESET_SLOTS; i++) {
            Preset p = i < presets.length ? presets[i] : null;
            boolean empty = p == null || p.isEmpty();
            cards[i].bind(i, empty ? "" : Band.formatValue(p.frequency, fm), p != null ? p.psName : null,
                empty, s.presetIndex == i + 1);
        }
    }

    private void renderBanner(RadioState.Connection c) {
        String text;
        int color;
        switch (c) {
            case CONNECTING:
                text = getString(R.string.radio_banner_connecting);
                color = Ui.ACCENT;
                break;
            case NOT_FOUND:
                text = getString(R.string.radio_banner_not_found);
                color = Ui.DANGER;
                break;
            case DISCONNECTED:
                text = getString(R.string.radio_banner_disconnected);
                color = Ui.DANGER;
                break;
            default:
                banner.setVisibility(View.GONE);
                return;
        }
        banner.setText(text);
        banner.setTextColor(color);
        banner.setVisibility(View.VISIBLE);
    }

    private static void show(View v, boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    private void playPreset(int index) {
        Preset[] presets = state.currentPresets();
        if (index < presets.length) radio.playPreset(presets[index]);
    }

    // ------------------------------------------------------------------ actions

    // ------------------------------------------------------------------ on/off

    private void setRadioOn(boolean on) {
        radioOn = on;
        if (on) PlayerHandoff.switchToRadio(this, radio::activateSource);
        else radio.deactivateSource();
        renderPower();
    }

    /** Any tuning command turns the radio back on, like a real radio. */
    private void ensureOn() {
        if (!radioOn) setRadioOn(true);
    }

    private void renderPower() {
        powerButton.color(radioOn ? Ui.ACCENT : Ui.TEXT_MUTED);
        float a = radioOn ? 1f : 0.35f;
        freqValue.setAlpha(a);
        freqUnit.setAlpha(a);
        radioText.setAlpha(a);
        dial.setAlpha(a);
        stationArtFrame.setAlpha(a);
        stationName.setAlpha(radioOn ? 1f : 0.6f);
        if (!radioOn) stationName.setText(R.string.radio_off);
        else onRadioStateText();
    }

    private void onRadioStateText() {
        stationName.setText(state.psName != null ? state.psName.toUpperCase() : Band.label(state.band));
    }

    private Dialog overlay(int layout) {
        Dialog d = new Dialog(this, R.style.OverlayDialog);
        View root = getLayoutInflater().inflate(layout, null);
        Ui.applyFont(root);
        d.setContentView(root);
        root.setOnClickListener(v -> d.dismiss()); // tapping outside the panel closes it
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Ui.hideSystemBars(d.getWindow());
        d.show();
        return d;
    }

    private void showKeypad() {
        final Dialog d = overlay(R.layout.dialog_keypad);
        final boolean fm = state.isFm();
        final BandRange range = state.range();
        final StringBuilder input = new StringBuilder();
        final TextView display = (TextView) d.findViewById(R.id.keypad_input);
        final TextView ok = (TextView) d.findViewById(R.id.keypad_ok);
        ((TextView) d.findViewById(R.id.keypad_title)).setText(
            getString(R.string.radio_keypad_title, Band.label(state.band)));
        ((TextView) d.findViewById(R.id.keypad_range)).setText(
            Band.formatValue(range.min, fm) + " – " + Band.formatValue(range.max, fm) + " " + Band.unit(fm));

        final Runnable refresh = () -> {
            Integer v = parseInput(input.toString(), fm);
            boolean valid = v != null && v >= range.min && v <= range.max;
            display.setText(input.length() == 0 ? "—" : input);
            display.setTextColor(input.length() == 0 || valid ? Ui.TEXT : Ui.DANGER);
            ok.setActivated(valid);
        };

        LinearLayout keys = (LinearLayout) d.findViewById(R.id.keypad_keys);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {fm ? "." : "", "0", "⌫"}};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            for (String key : row) {
                TextView b = (TextView) getLayoutInflater().inflate(R.layout.keypad_key, line, false);
                b.setText(key);
                b.setTypeface(Ui.medium(this));
                if (key.isEmpty()) b.setVisibility(View.INVISIBLE);
                b.setOnClickListener(v -> {
                    if ("⌫".equals(key)) {
                        if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                    } else if (".".equals(key)) {
                        if (input.indexOf(".") < 0) input.append('.');
                    } else if (input.length() < 6) {
                        input.append(key);
                    }
                    refresh.run();
                });
                line.addView(b);
            }
            keys.addView(line);
        }
        d.findViewById(R.id.keypad_cancel).setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            Integer value = parseInput(input.toString(), fm);
            if (value != null && value >= range.min && value <= range.max) {
                ensureOn();
                radio.tune(value);
                d.dismiss();
            }
        });
        refresh.run();
        Touch.install(d.getWindow().getDecorView());
    }

    private static Integer parseInput(String s, boolean fm) {
        if (s.isEmpty()) return null;
        try {
            double v = Double.parseDouble(s);
            return fm ? (int) Math.round(v * 100) : (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void showDiagnostics() {
        final Dialog d = overlay(R.layout.dialog_diag);
        final TextView info = (TextView) d.findViewById(R.id.diag_info);
        final TextView log = (TextView) d.findViewById(R.id.diag_log);
        final ScrollView scroll = (ScrollView) d.findViewById(R.id.diag_scroll);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);

        final RadioClient.Listener stateListener = s -> {
            onRadioState(s);
            info.setText("Connection: " + s.connection + " · radio type: " + s.radioType + " · state: " + s.radioState
                + " · band: " + s.band + " · freq: " + s.frequency + " · range: " + s.range());
        };
        radio.setListener(stateListener);
        radio.setLogListener(lines -> {
            log.setText(TextUtils.join("\n", lines));
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        d.setOnDismissListener(x -> {
            radio.setLogListener(null);
            radio.setListener(this);
        });

        d.findViewById(R.id.diag_close).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.diag_seek_prev).setOnClickListener(v -> radio.seek(false));
        d.findViewById(R.id.diag_seek_next).setOnClickListener(v -> radio.seek(true));
        d.findViewById(R.id.diag_search_prev).setOnClickListener(v -> radio.search(false));
        d.findViewById(R.id.diag_search_next).setOnClickListener(v -> radio.search(true));
        d.findViewById(R.id.diag_intro).setOnClickListener(v -> radio.intro());
        d.findViewById(R.id.diag_next_band).setOnClickListener(v -> radio.nextBand());
        d.findViewById(R.id.diag_state).setOnClickListener(v -> radio.requestFullState());
        d.findViewById(R.id.diag_source).setOnClickListener(v -> radio.activateSource());
        d.findViewById(R.id.diag_reconnect).setOnClickListener(v -> radio.connect());
        Touch.install(d.getWindow().getDecorView());
    }

    private void hideSystemBars() {
        Ui.hideSystemBars(getWindow());
    }

}
