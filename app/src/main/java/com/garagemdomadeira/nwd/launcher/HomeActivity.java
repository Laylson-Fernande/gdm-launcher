package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.garagemdomadeira.nwd.launcher.bt.BtClient;
import com.garagemdomadeira.nwd.launcher.home.HomeSession;
import com.garagemdomadeira.nwd.launcher.home.LauncherPrefs;
import com.garagemdomadeira.nwd.launcher.home.MuteClient;
import com.garagemdomadeira.nwd.launcher.home.PlayerHandoff;
import com.garagemdomadeira.nwd.launcher.home.VolumeClient;
import com.garagemdomadeira.nwd.launcher.music.AlbumArt;
import com.garagemdomadeira.nwd.launcher.radio.Band;
import com.garagemdomadeira.nwd.launcher.radio.RadioClient;
import com.garagemdomadeira.nwd.launcher.radio.RadioState;
import com.garagemdomadeira.nwd.launcher.system.SystemProps;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;
import com.garagemdomadeira.nwd.launcher.ui.DockView;
import com.garagemdomadeira.nwd.launcher.ui.Screens;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen: volume and mute, gear (settings), fan, clock, "now playing" (radio, USB/SD
 * or Bluetooth music), Bluetooth status and the dock.
 * A call covers only the "now playing" area. Opening this screen neither turns the radio on nor
 * cuts what is playing.
 */
public class HomeActivity extends Activity {

    private static final int SOURCE_ANDROID = 0;
    private static final int SOURCE_RADIO = 4;

    private static final String KEY_FAN_SWITCH = "mcu_smart_fan_switch";
    // Screen backlight ("screen off" button).
    private static final String ACTION_SET_BACKLIGHT = "com.nwd.action.ACTION_SET_BACKLIGHT_SWITCH";
    private static final String EXTRA_BACKLIGHT = "extra_backlight_switch";
    private static final String KEY_BACKLIGHT_STATE = "mcu_back_light_state";
    private static final String[] BT_ACTIONS = {
        "com.bt.ACTION_BT_HFP_ESTABLISHED", "com.bt.ACTION_BT_HFP_RELEASE",
        "com.bt.ACTION_BT_CONNECTION_CHANGE", "com.bt.ACTION_A2DP_ESTABLISHED",
        "com.bt.ACTION_A2DP_RELEASE",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    // Created in onCreate, after the app language has been applied (Locale.getDefault()).
    private SimpleDateFormat weekFormat;
    private SimpleDateFormat monthFormat;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            renderClock();
            long delay = 1000L - (System.currentTimeMillis() % 1000L);
            handler.postDelayed(this, Math.max(delay, 16L));
        }
    };

    private HomeSession session;
    private VolumeClient volume;
    private MuteClient mute;
    private BtClient bt;
    private RadioClient radio;
    private RadioState radioState = new RadioState();
    private HomeSession.Snapshot snap;
    private boolean radioReady;
    private Dialog dialog;

    private int volumeValue;
    private boolean muted;
    private boolean fanOn;
    private boolean btHfp;
    private String btDevice = "";

    private View screenOffOverlay;
    private DockView dock;
    private IconView muteButton;
    private TextView clockTime;
    private TextView clockDate;
    private View fanDot;
    private TextView fanText;
    private TextView npLabel;
    private TextView npLine1;
    private TextView npLine2;
    private View npControls;
    private View npProgressTrack;
    private View npProgress;
    private IconView npPrev;
    private IconView npPlay;
    private IconView npNext;
    private ImageView npArt;
    private IconView npArtIcon;
    /** Track whose cover is on screen (or loading); null = source icon. */
    private String artKey;
    private int artGen;
    private View voltageView;
    private TextView voltageText;
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private View cardCall;
    private TextView callState;
    private TextView callName;
    private TextView callNumber;
    private TextView callAnswer;
    private IconView btIcon;
    private TextView btText;
    private TextView btDeviceView;
    private final ContentObserver fanObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            readFan();
        }
    };

    /** If the system turns the backlight back on by itself (key, reverse, etc.), removes the black layer. */
    private final ContentObserver backlightObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            if (screenOffOverlay != null && screenOffOverlay.getVisibility() == View.VISIBLE) {
                screenOffOverlay.setVisibility(View.GONE);
            }
        }
    };

    /**
     * Battery voltage via CAN (as in the NWD themes): asks every 10 s and the head unit
     * replies with volts × 10. Without a reply (car without CAN), the indicator stays hidden.
     */
    private static final String ACTION_QUERY_VOLTAGE = "com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE";
    private static final String ACTION_VOLTAGE = "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE";
    private static final String EXTRA_VOLTAGE = "extra_voltage_value";
    private static final long VOLTAGE_PERIOD_MS = 10000;

    private final Runnable voltageTick = new Runnable() {
        @Override
        public void run() {
            try {
                sendBroadcast(new Intent(ACTION_QUERY_VOLTAGE));
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, VOLTAGE_PERIOD_MS);
        }
    };

    private final BroadcastReceiver voltageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!LauncherPrefs.showVoltmeter(HomeActivity.this)) return;
            int raw = intent.getIntExtra(EXTRA_VOLTAGE, 0);
            float volts = Math.abs(raw) / 10f;
            if (volts <= 0f) return; // reply without a value: keep the previous one
            volts = Math.max(8f, Math.min(18f, volts));
            voltageText.setText(String.format(Locale.getDefault(), "%.1f V", volts));
            if (voltageView.getVisibility() != View.VISIBLE) {
                voltageView.setVisibility(View.VISIBLE);
                Touch.fadeIn(voltageView);
            }
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Connection changed: ask the Bluetooth service for the state and the device name.
            handler.removeCallbacks(btQuery);
            handler.postDelayed(btQuery, 300);
        }
    };

    private final Runnable btQuery = this::queryBt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        createDateFormats();
        setContentView(R.layout.activity_home);
        Ui.applyFont(findViewById(R.id.home_root));
        dock = Screens.attachDock(this, null);
        bindViews();
        // The "screen off" layer must cover the dock too.
        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        ((ViewGroup) screenOffOverlay.getParent()).removeView(screenOffOverlay);
        content.addView(screenOffOverlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderClock();

        session = new HomeSession(this);
        session.setListener(this::render);
        session.start();

        volume = new VolumeClient(this);
        volume.setListener((v, max) -> {
            volumeValue = v;
            renderVolume();
        });
        volume.connect();

        mute = new MuteClient(this);
        mute.setListener(m -> {
            muted = m;
            renderVolume();
        });
        mute.start();

        bt = new BtClient(this);
        bt.connect();
        IntentFilter btFilter = new IntentFilter();
        for (String a : BT_ACTIONS) btFilter.addAction(a);
        registerReceiver(btReceiver, btFilter);
        registerReceiver(voltageReceiver, new IntentFilter(ACTION_VOLTAGE));

        getContentResolver().registerContentObserver(
            Settings.System.getUriFor(KEY_FAN_SWITCH), false, fanObserver);
        getContentResolver().registerContentObserver(
            Settings.System.getUriFor(KEY_BACKLIGHT_STATE), false, backlightObserver);
        readFan();

        // Only observes. connect(false) doesn't send ACTION_MEDIA_PLAY.
        radio = new RadioClient(this);
        radio.setListener(state -> {
            radioState = state;
            if (state.connection == RadioState.Connection.CONNECTED) radioReady = true;
            if (snap != null) renderNowPlaying();
        });
        radio.connect(false);

        handler.postDelayed(this::checkDefaultLauncher, 800);
        Touch.install(findViewById(android.R.id.content));
    }

    private void bindViews() {
        icon(R.id.volume_down, IconView.Icon.MINUS, IconView.Style.NONE).setOnClickListener(v -> stepVolume(-1));
        icon(R.id.volume_up, IconView.Icon.PLUS, IconView.Style.NONE).setOnClickListener(v -> stepVolume(1));
        muteButton = icon(R.id.volume_mute, IconView.Icon.VOLUME, IconView.Style.NONE);
        muteButton.setOnClickListener(v -> {
            if (mute != null) mute.toggle();
        });

        icon(R.id.settings_button, IconView.Icon.SETTINGS, IconView.Style.NONE)
            .setOnClickListener(v -> open(SettingsActivity.class));
        icon(R.id.screen_off_button, IconView.Icon.SCREEN_OFF, IconView.Style.NONE)
            .setOnClickListener(v -> setScreenOn(false));
        screenOffOverlay = findViewById(R.id.screen_off_overlay);
        screenOffOverlay.setOnClickListener(v -> setScreenOn(true));

        clockTime = text(R.id.clock_time);
        clockDate = text(R.id.clock_date);
        clockTime.setTypeface(Ui.semiBold(this));
        clockDate.setTypeface(Ui.regular(this));
        View clock = findViewById(R.id.clock_block);
        clock.setOnClickListener(v -> openDateSettings());

        // Emergency exit: long-pressing the black area opens Android Settings.
        findViewById(R.id.home_space).setOnLongClickListener(v -> {
            openAndroidSettings();
            return true;
        });

        fanDot = findViewById(R.id.fan_status_dot);
        fanText = text(R.id.fan_status_text);
        text(R.id.fan_status_title).setTypeface(Ui.medium(this));
        fanText.setTypeface(Ui.medium(this));
        IconView fanIcon = icon(R.id.fan_status_icon, IconView.Icon.FAN, IconView.Style.NONE);
        fanIcon.setClickable(false);
        fanIcon.color(Ui.TEXT_MUTED);
        findViewById(R.id.fan_status).setOnClickListener(v -> open(FanActivity.class));

        npLabel = text(R.id.np_label);
        npLine1 = text(R.id.np_line1);
        npLine2 = text(R.id.np_line2);
        npLabel.setTypeface(Ui.medium(this));
        npLine1.setTypeface(Ui.medium(this));
        npLine2.setTypeface(Ui.regular(this));
        npControls = findViewById(R.id.np_controls);
        npProgressTrack = findViewById(R.id.np_progress_track);
        npProgress = findViewById(R.id.np_progress);
        npPrev = icon(R.id.np_prev, IconView.Icon.SKIP_PREV, IconView.Style.NONE);
        npPlay = icon(R.id.np_play, IconView.Icon.PLAY, IconView.Style.NONE);
        npNext = icon(R.id.np_next, IconView.Icon.SKIP_NEXT, IconView.Style.NONE);
        npPrev.setOnClickListener(v -> transport(-1));
        npPlay.setOnClickListener(v -> transport(0));
        npNext.setOnClickListener(v -> transport(1));
        voltageView = findViewById(R.id.voltage);
        voltageText = text(R.id.voltage_text);
        voltageText.setTypeface(Ui.medium(this));
        icon(R.id.voltage_icon, IconView.Icon.BATTERY, IconView.Style.NONE).color(Ui.TEXT_MUTED);
        npArt = (ImageView) findViewById(R.id.np_art);
        npArtIcon = (IconView) findViewById(R.id.np_art_icon);
        npArtIcon.color(0xFF5C626B);
        findViewById(R.id.now_playing).setOnClickListener(v -> openNowPlaying());

        cardCall = findViewById(R.id.card_call);
        cardCall.setOnClickListener(v -> open(PhoneActivity.class));
        callState = text(R.id.call_state);
        callName = text(R.id.call_name);
        callNumber = text(R.id.call_number);
        callName.setTypeface(Ui.semiBold(this));
        callAnswer = text(R.id.call_answer);
        callAnswer.setActivated(true);
        callAnswer.setOnClickListener(v -> session.answerCall());
        text(R.id.call_hangup).setOnClickListener(v -> session.hangupCall());

        btIcon = icon(R.id.bt_status_icon, IconView.Icon.BLUETOOTH, IconView.Style.NONE);
        btIcon.setClickable(false);
        btText = text(R.id.bt_status_text);
        btDeviceView = text(R.id.bt_status_device);
        btText.setTypeface(Ui.medium(this));
        btDeviceView.setTypeface(Ui.regular(this));
        findViewById(R.id.bt_status).setOnClickListener(v -> open(PhoneActivity.class));

        renderVolume();
        renderFan();
        renderBt();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        renderClock();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        if (session != null) session.refresh();
        if (volume != null) volume.refresh();
        if (mute != null) mute.refresh();
        readFan();
        queryBt();
        applyPrefs();
    }

    /** Settings screen options: voltmeter and volume controls. */
    private void applyPrefs() {
        findViewById(R.id.volume_row).setVisibility(
            LauncherPrefs.showVolumeControls(this) ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(voltageTick);
        if (LauncherPrefs.showVoltmeter(this)) {
            handler.post(voltageTick);
        } else {
            voltageView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onPause() {
        // Leaving the home screen with the backlight off (another app, a call): turn it back on.
        if (screenOffOverlay != null && screenOffOverlay.getVisibility() == View.VISIBLE) setScreenOn(true);
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(voltageTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(voltageReceiver);
        } catch (Exception ignored) {
        }
        getContentResolver().unregisterContentObserver(fanObserver);
        getContentResolver().unregisterContentObserver(backlightObserver);
        if (volume != null) {
            volume.setListener(null);
            volume.release();
            volume = null;
        }
        if (mute != null) {
            mute.setListener(null);
            mute.stop();
        }
        if (bt != null) bt.release();
        if (session != null) {
            session.setListener(null);
            session.stop();
        }
        if (radio != null) {
            radio.setListener(null);
            radio.release();
        }
        if (dialog != null) dialog.dismiss();
        artLoader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.applyDesignDensity(this);
        createDateFormats();
    }

    /** Weekday and month names follow the app language (applied by Ui.applyDesignDensity). */
    private void createDateFormats() {
        weekFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
        monthFormat = new SimpleDateFormat("MMMM", Locale.getDefault());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(getWindow());
    }

    /** Home screen: Back doesn't close anything. */
    @Override
    public void onBackPressed() {
    }

    // ------------------------------------------------------------------ rendering

    private void render(HomeSession.Snapshot s) {
        snap = s;
        boolean calling = s.call != HomeSession.CallPhase.NONE;
        show(cardCall, calling);
        findViewById(R.id.now_playing).setVisibility(calling ? View.INVISIBLE : View.VISIBLE);
        renderNowPlaying();
        renderCall(s);
        renderDock();
        renderBt();
    }

    private void renderClock() {
        Date now = new Date();
        clockTime.setText(timeFormat.format(now));
        // "Friday, October 9, 2026" / "Sexta, 9 de Outubro de 2026": weekday without "-feira"
        // and capitalized month; the order comes from the home_date resource.
        String week = weekFormat.format(now);
        int dash = week.indexOf('-');
        if (dash > 0) week = week.substring(0, dash);
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        clockDate.setText(getString(R.string.home_date, capitalize(week), capitalize(monthFormat.format(now)),
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.YEAR)));
    }

    private void stepVolume(int delta) {
        if (muted && mute != null) mute.setMuted(false); // changing the volume unmutes
        if (volume != null) volume.nudge(delta);
    }

    private void renderVolume() {
        if (muteButton == null) return;
        muteButton.set(muted ? IconView.Icon.VOLUME_MUTE : IconView.Icon.VOLUME, IconView.Style.NONE);
        muteButton.color(muted ? Ui.TEXT : 0xFFB4B7BC);
        muteButton.setContentDescription(getString(
            muted ? R.string.home_unmute_volume : R.string.home_mute_volume, volumeValue));
    }

    /** "Now playing": radio, USB/SD or Bluetooth music, depending on the current source. */
    private void renderNowPlaying() {
        if (snap == null) return;
        HomeSession.Snapshot s = snap;
        boolean controls = true;
        float progress = -1f; // < 0 hides the bar
        String sub = "";
        switch (s.kind) {
            case RADIO: {
                boolean live = s.source == SOURCE_RADIO;
                npLabel.setText(R.string.home_now_playing_radio);
                if (!radioReady) {
                    npLine1.setText(radioState.connection == RadioState.Connection.NOT_FOUND
                        ? R.string.home_radio_unavailable : R.string.home_radio);
                } else {
                    boolean fm = Band.isFm(radioState.band);
                    String freq = Band.formatValue(radioState.frequency, fm) + " " + Band.unit(fm);
                    boolean named = radioState.psName != null && !radioState.psName.isEmpty();
                    npLine1.setText(named ? radioState.psName : freq);
                    sub = Band.label(radioState.band) + (named ? "  ·  " + freq : "");
                }
                // On the radio the center button toggles on/off.
                npPlay.set(IconView.Icon.POWER, IconView.Style.NONE);
                npPlay.color(live ? Ui.TEXT : Ui.TEXT_MUTED);
                npPlay.setContentDescription(getString(live ? R.string.home_radio_turn_off : R.string.home_radio_turn_on));
                break;
            }
            case USB:
                npLabel.setText(R.string.home_now_playing_mp3);
                npLine1.setText(s.title.isEmpty() ? getString(R.string.home_music) : s.title);
                sub = s.artist.isEmpty() ? "USB / SD" : s.artist;
                progress = s.durationMs > 0 ? Math.min(1f, s.positionMs / (float) s.durationMs) : 0f;
                setPlay(s.playing);
                break;
            case BT: {
                npLabel.setText(R.string.home_now_playing_bt);
                boolean named = !s.btTitle.isEmpty();
                if (s.a2dpKnown && !s.a2dpConnected && !named) {
                    npLine1.setText(R.string.home_phone_disconnected);
                } else {
                    npLine1.setText(named ? s.btTitle : getString(R.string.home_bt_music));
                    sub = s.btArtist;
                }
                setPlay(s.btPlaying);
                break;
            }
            default:
                npLabel.setText(R.string.home_now_playing);
                npLine1.setText(R.string.home_nothing_playing);
                controls = false;
                progress = 0f; // empty bar, as in the design
                break;
        }
        renderArt(s);
        npLine2.setText(sub);
        npLine2.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        npControls.setVisibility(controls ? View.VISIBLE : View.GONE);
        npProgressTrack.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
        if (progress >= 0) {
            npProgress.setPivotX(0f);
            npProgress.setScaleX(progress);
        }
    }

    /** Track cover for MP3; for other sources (or no cover), the source icon in the frame. */
    private void renderArt(HomeSession.Snapshot s) {
        IconView.Icon icon = s.kind == HomeSession.Kind.RADIO ? IconView.Icon.RADIO
            : s.kind == HomeSession.Kind.BT ? IconView.Icon.BLUETOOTH : IconView.Icon.MUSIC;
        npArtIcon.set(icon, IconView.Style.NONE);
        npArtIcon.color(0xFF5C626B);

        long id = 0;
        if (s.kind == HomeSession.Kind.USB && s.mediaId != null) {
            try {
                id = Long.parseLong(s.mediaId.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String path = s.kind == HomeSession.Kind.USB ? s.path : null;
        String key = id > 0 ? "id:" + id : path != null ? "path:" + path : null;
        if (key == null) {
            clearArt();
            return;
        }
        if (key.equals(artKey)) return;
        clearArt();
        artKey = key;
        int gen = ++artGen;
        long mediaId = id;
        try {
            artLoader.execute(() -> {
                Bitmap bmp = AlbumArt.decode(getApplicationContext(), mediaId, path);
                handler.post(() -> {
                    if (isFinishing() || gen != artGen || bmp == null) return;
                    npArt.setImageBitmap(bmp);
                    npArt.setVisibility(View.VISIBLE);
                    npArtIcon.setVisibility(View.GONE);
                    Touch.fadeIn(npArt);
                });
            });
        } catch (Exception ignored) {
            // Executor already shut down (screen leaving).
        }
    }

    private void clearArt() {
        artGen++;
        artKey = null;
        npArt.setImageDrawable(null);
        npArt.setVisibility(View.GONE);
        npArtIcon.setVisibility(View.VISIBLE);
    }

    private void setPlay(boolean playing) {
        npPlay.set(playing ? IconView.Icon.PAUSE : IconView.Icon.PLAY, IconView.Style.NONE);
        npPlay.color(Ui.TEXT);
        npPlay.setContentDescription(getString(playing ? R.string.home_pause : R.string.home_play));
    }

    private void renderCall(HomeSession.Snapshot s) {
        if (s.call == HomeSession.CallPhase.NONE) return;
        if (s.call == HomeSession.CallPhase.INCOMING) callState.setText(R.string.home_call_incoming);
        else if (s.call == HomeSession.CallPhase.OUTGOING) callState.setText(R.string.home_call_outgoing);
        else callState.setText(R.string.home_call_active);
        show(callAnswer, s.call == HomeSession.CallPhase.INCOMING);
        boolean hasName = !s.callName.isEmpty();
        boolean hasNumber = !s.callNumber.isEmpty();
        if (hasName) {
            callName.setText(s.callName);
            callNumber.setText(hasNumber ? s.callNumber : "");
        } else if (hasNumber) {
            callName.setText(s.callNumber);
            callNumber.setText("");
        } else {
            callName.setText(R.string.home_unknown_number);
            callNumber.setText("");
        }
    }

    /** Home screen dock highlight: the source that is playing (or the phone, during a call). */
    private void renderDock() {
        HomeSession.Snapshot s = snap;
        DockView.Item active = null;
        if (s != null) {
            if (s.call != HomeSession.CallPhase.NONE) active = DockView.Item.PHONE;
            else if (s.source == SOURCE_RADIO) active = DockView.Item.RADIO;
            else if (s.source == SOURCE_ANDROID && s.kind == HomeSession.Kind.USB) active = DockView.Item.MUSIC;
            else if (s.source == SOURCE_ANDROID && s.kind == HomeSession.Kind.BT) active = DockView.Item.BT_MUSIC;
        }
        if (dock.getActive() != active) dock.setActive(active);
    }

    // ------------------------------------------------------------------ fan and Bluetooth

    /** Actual fan state, kept by the head unit system in Settings.System. */
    private void readFan() {
        int v = 0;
        try {
            v = Settings.System.getInt(getContentResolver(), KEY_FAN_SWITCH, 0);
        } catch (Exception ignored) {
        }
        fanOn = v != 0;
        renderFan();
    }

    private void renderFan() {
        if (fanText == null) return;
        fanDot.setActivated(fanOn);
        fanText.setText(fanOn ? R.string.home_fan_on : R.string.home_fan_off);
        fanText.setTextColor(fanOn ? Ui.TEXT : Ui.TEXT_MUTED);
    }

    private void queryBt() {
        if (bt == null) return;
        bt.queryPhoneInfo((hfp, powered, localName, pin, remoteName, autoFeatures, callState) -> {
            btHfp = hfp;
            btDevice = remoteName == null ? "" : remoteName;
            renderBt();
        });
    }

    /** Connected = phone (HFP) or audio (A2DP) linked to the phone. */
    private void renderBt() {
        if (btText == null) return;
        boolean a2dp = snap != null && snap.a2dpKnown && snap.a2dpConnected;
        boolean connected = btHfp || a2dp;
        btText.setText(connected ? R.string.home_bt_connected : R.string.home_bt_disconnected);
        btText.setTextColor(Ui.TEXT);
        btIcon.color(connected ? Ui.TEXT : Ui.TEXT_MUTED);
        btDeviceView.setText(connected ? (btDevice.isEmpty() ? getString(R.string.home_phone) : btDevice)
            : getString(R.string.home_tap_to_connect));
    }

    // ------------------------------------------------------------------ actions

    /**
     * Turns the screen backlight off/on through the NWD system. With the backlight off, touch keeps
     * working: a black layer covers the screen and any touch on it turns the backlight back on.
     */
    private void setScreenOn(boolean on) {
        Intent i = new Intent(ACTION_SET_BACKLIGHT);
        i.putExtra(EXTRA_BACKLIGHT, on);
        sendBroadcast(i);
        android.util.Log.d("GaragemLauncher", "→ " + ACTION_SET_BACKLIGHT + " " + EXTRA_BACKLIGHT + "=" + on);
        screenOffOverlay.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) screenOffOverlay.bringToFront();
    }

    /** −1 previous, 0 play/pause (on the radio: on/off), +1 next. */
    private void transport(int what) {
        if (snap == null) return;
        switch (snap.kind) {
            case RADIO:
                if (what == 0) toggleRadio();
                else tuneRadio(what > 0);
                break;
            case USB:
                session.usbTransport(what < 0 ? HomeSession.USB_PREV
                    : what > 0 ? HomeSession.USB_NEXT : HomeSession.USB_TOGGLE);
                break;
            case BT:
                if (what < 0) session.btTransport(HomeSession.BT_PREV);
                else if (what > 0) session.btTransport(HomeSession.BT_NEXT);
                else session.btTransport(snap.btPlaying ? HomeSession.BT_PAUSE : HomeSession.BT_PLAY);
                break;
            default:
                break;
        }
    }

    private void openNowPlaying() {
        if (snap == null) return;
        switch (snap.kind) {
            case RADIO:
                open(RadioActivity.class);
                break;
            case USB:
                open(MusicActivity.class);
                break;
            case BT:
                open(BtMusicActivity.class);
                break;
            default:
                break;
        }
    }

    /** Previous/next memory. If the radio isn't the source, turns it on first — the tap is what turns it on. */
    private void tuneRadio(boolean next) {
        if (snap == null || snap.source != SOURCE_RADIO) {
            PlayerHandoff.pauseOtherPlayers(this);
            radio.activateSource();
            session.assumeSource(SOURCE_RADIO);
        }
        radio.presetStep(next);
    }

    private void toggleRadio() {
        if (snap != null && snap.source == SOURCE_RADIO) {
            radio.deactivateSource();
            session.assumeSource(SOURCE_ANDROID);
        } else {
            PlayerHandoff.pauseOtherPlayers(this);
            radio.activateSource();
            session.assumeSource(SOURCE_RADIO);
        }
    }

    // ------------------------------------------------------------------ dock e apps

    private void open(Class<? extends Activity> screen) {
        Intent i = new Intent(this, screen);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void openDateSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            openAndroidSettings();
        }
    }

    private void openAndroidSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, R.string.home_settings_not_found, Toast.LENGTH_SHORT).show();
        }
    }


    // ------------------------------------------------------------------ NWD default launcher

    /**
     * The NWD system stores the default launcher in persist.nwd.launcher.default (system
     * services read this property). If it isn't this app, asks the user whether to
     * change it.
     */
    private void checkDefaultLauncher() {
        if (isFinishing()) return;
        if (!LauncherPrefs.askDefaultLauncher(this)) return; // the user chose the stock launcher
        String current = SystemProps.get(SystemProps.PROP_DEFAULT_LAUNCHER, "");
        if (getPackageName().equals(current)) return;

        showConfirm(
            getString(R.string.home_default_launcher_title),
            getString(R.string.home_default_launcher_message,
                current.isEmpty() ? getString(R.string.home_not_set_paren) : current),
            null,
            getString(R.string.home_not_now), null,
            getString(R.string.home_set), this::setAsDefaultLauncher);
    }

    private void setAsDefaultLauncher() {
        SystemProps.requestSet(this, SystemProps.PROP_DEFAULT_LAUNCHER, getPackageName());
        handler.postDelayed(() -> {
            String now = SystemProps.get(SystemProps.PROP_DEFAULT_LAUNCHER, "");
            if (getPackageName().equals(now)) {
                Toast.makeText(this, R.string.home_default_launcher_done, Toast.LENGTH_LONG).show();
            } else {
                showConfirm(
                    getString(R.string.home_default_launcher_failed_title),
                    getString(R.string.home_default_launcher_failed_message,
                        now.isEmpty() ? getString(R.string.home_not_set) : now),
                    "adb shell setprop " + SystemProps.PROP_DEFAULT_LAUNCHER + " " + getPackageName(),
                    null, null,
                    getString(R.string.home_ok), null);
            }
        }, 1500);
    }

    /** App-styled dialog. A button with null text is hidden; a null action just dismisses. */
    private void showConfirm(String title, String message, String code,
                             String negative, Runnable onNegative,
                             String positive, Runnable onPositive) {
        if (dialog != null) dialog.dismiss();
        Dialog d = new Dialog(this, R.style.OverlayDialog);
        View root = getLayoutInflater().inflate(R.layout.dialog_confirm, null);
        Ui.applyFont(root);
        d.setContentView(root);
        ((TextView) root.findViewById(R.id.confirm_title)).setText(title);
        ((TextView) root.findViewById(R.id.confirm_message)).setText(message);
        TextView codeView = (TextView) root.findViewById(R.id.confirm_code);
        if (code != null) {
            codeView.setText(code);
            codeView.setTypeface(android.graphics.Typeface.MONOSPACE);
            codeView.setVisibility(View.VISIBLE);
        }
        bindButton((TextView) root.findViewById(R.id.confirm_negative), negative, onNegative, d);
        TextView positiveButton = (TextView) root.findViewById(R.id.confirm_positive);
        positiveButton.setActivated(true);
        bindButton(positiveButton, positive, onPositive, d);
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Ui.hideSystemBars(d.getWindow());
        d.show();
        Touch.install(root);
        dialog = d;
    }

    private static void bindButton(TextView b, String text, Runnable action, Dialog d) {
        if (text == null) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setText(text);
        b.setOnClickListener(v -> {
            d.dismiss();
            if (action != null) action.run();
        });
    }

    // ------------------------------------------------------------------ util

    private TextView text(int id) {
        return (TextView) findViewById(id);
    }

    private IconView icon(int id, IconView.Icon glyph, IconView.Style style) {
        IconView v = (IconView) findViewById(id);
        v.set(glyph, style);
        return v;
    }

    private static void show(View v, boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    private static String capitalize(String t) {
        if (t == null || t.isEmpty()) return "";
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
