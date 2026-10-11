package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.garagemdomadeira.nwd.launcher.bt.BtClient;
import com.garagemdomadeira.nwd.launcher.radio.NwdConstants;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.DockView;
import com.garagemdomadeira.nwd.launcher.ui.Screens;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

/**
 * Phone track via A2DP/AVRCP. Audio comes from the device; here we only display it and send
 * play, pause, previous and next. Pairing lives on the phone screen.
 */
public class BtMusicActivity extends Activity {

    private static final int BT_PLAY = 0;
    private static final int BT_PAUSE = 1;
    private static final int BT_NEXT = 2;
    private static final int BT_PREV = 3;
    private static final int BT_FORCE_PLAY = 4;

    private BtClient bt;
    private boolean a2dp;
    private boolean playing;
    private boolean known;
    private String title = "";
    private String artist = "";
    private String deviceName = "";
    private String pin = "";
    private boolean receiverRegistered;

    private TextView trackView;
    private TextView artistView;
    private TextView deviceView;
    private TextView statusView;
    private IconView playBtn;
    private View transport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_bt_music);
        Ui.applyFont(findViewById(R.id.bt_music_root));
        Screens.attachDock(this, DockView.Item.BT_MUSIC);
        bindViews();

        bt = new BtClient(this);
        bt.connect();
        registerReceiver();
        render();
        Touch.install(findViewById(android.R.id.content));
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.bt_music_title_bar)).setTypeface(Ui.semiBold(this));
        IconView back = (IconView) findViewById(R.id.bt_music_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());

        findViewById(R.id.bt_music_phone).setOnClickListener(v -> openPhone());

        IconView mark = (IconView) findViewById(R.id.bt_music_mark);
        mark.set(IconView.Icon.BLUETOOTH, IconView.Style.NONE).color(0xFF7D838C);
        mark.setClickable(false);

        trackView = (TextView) findViewById(R.id.bt_music_track);
        artistView = (TextView) findViewById(R.id.bt_music_artist);
        deviceView = (TextView) findViewById(R.id.bt_music_device);
        statusView = (TextView) findViewById(R.id.bt_music_status);
        trackView.setTypeface(Ui.semiBold(this));
        transport = findViewById(R.id.bt_music_transport);

        ((IconView) findViewById(R.id.bt_music_prev))
            .set(IconView.Icon.SKIP_PREV, IconView.Style.NONE)
            .setOnClickListener(v -> transport(BT_PREV));
        playBtn = (IconView) findViewById(R.id.bt_music_play);
        playBtn.set(IconView.Icon.PLAY, IconView.Style.OUTLINE_CIRCLE);
        playBtn.setOnClickListener(v -> transport(playing ? BT_PAUSE : BT_PLAY));
        ((IconView) findViewById(R.id.bt_music_next))
            .set(IconView.Icon.SKIP_NEXT, IconView.Style.NONE)
            .setOnClickListener(v -> transport(BT_NEXT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        claimSource();
        bt.setMusicMute(false);
        refresh();
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
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        if (bt != null) bt.release();
        super.onDestroy();
    }

    private void refresh() {
        bt.queryMusicInfo((connected, musicPlaying, name, pinCode) -> {
            known = true;
            boolean wasConnected = a2dp;
            a2dp = connected;
            playing = musicPlaying;
            if (name != null && !name.isEmpty()) deviceName = name;
            if (pinCode != null) pin = pinCode;
            // On A2DP connect, send a forced play once so audio starts.
            if (connected && !wasConnected && !musicPlaying) {
                bt.playControl(BT_FORCE_PLAY);
            }
            render();
        });
    }

    private void transport(int cmd) {
        claimSource();
        bt.setMusicMute(false);
        if (cmd == BT_PLAY) playing = true;
        else if (cmd == BT_PAUSE) playing = false;
        render();
        bt.playControl(cmd);
        if (cmd == BT_PLAY || cmd == BT_PAUSE || cmd == BT_NEXT || cmd == BT_PREV) {
            bt.queryId3();
        }
    }

    private void claimSource() {
        Intent in = new Intent(NwdConstants.ACTION_APP_IN_OUT);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_BT_MUSIC);
        in.putExtra(NwdConstants.EXTRA_APP_OPERATION, 1);
        in.putExtra(NwdConstants.EXTRA_APP_EVENT, 0);
        sendBroadcast(in);
    }

    private void render() {
        if (!known) {
            trackView.setText("Bluetooth");
            artistView.setText("");
            deviceView.setText("");
            statusView.setText(R.string.media_bt_connecting_service);
            transport.setAlpha(0.4f);
            playBtn.set(IconView.Icon.PLAY, IconView.Style.OUTLINE_CIRCLE);
            return;
        }
        if (!a2dp) {
            trackView.setText(R.string.media_bt_phone_disconnected);
            artistView.setText("");
            String info = deviceName.isEmpty() ? getString(R.string.media_bt_connect_hint) : deviceName;
            if (!pin.isEmpty()) info = getString(R.string.media_bt_info_pin, info, pin);
            deviceView.setText(info);
            statusView.setText(R.string.media_bt_pair_hint);
            transport.setAlpha(0.35f);
            playBtn.set(IconView.Icon.PLAY, IconView.Style.OUTLINE_CIRCLE);
            return;
        }

        transport.setAlpha(1f);
        trackView.setText(title.isEmpty() ? getString(R.string.media_bt_title) : title);
        artistView.setText(artist);
        deviceView.setText(deviceName.isEmpty() ? getString(R.string.media_bt_connected) : deviceName);
        statusView.setText(playing ? R.string.media_bt_playing : R.string.media_bt_paused);
        playBtn.set(playing ? IconView.Icon.PAUSE : IconView.Icon.PLAY, IconView.Style.OUTLINE_CIRCLE);
    }

    private void openPhone() {
        Intent i = new Intent(this, PhoneActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.bt.ACTION_A2DP_ESTABLISHED");
        filter.addAction("com.bt.ACTION_A2DP_RELEASE");
        filter.addAction("com.bt.ACTION_BT_MUSIC_PLAY");
        filter.addAction("com.bt.ACTION_BT_MUSIC_PAUSE");
        filter.addAction("com.bt.ACTION_AVRCP_MUSIC_ID3");
        filter.addAction("com.bt.ACTION_AVRCP_ESTABLISHED");
        registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if ("com.bt.ACTION_A2DP_ESTABLISHED".equals(action)) {
                a2dp = true;
                known = true;
                bt.playControl(BT_FORCE_PLAY);
                bt.queryId3();
                refresh();
                return;
            }
            if ("com.bt.ACTION_A2DP_RELEASE".equals(action)) {
                a2dp = false;
                playing = false;
                known = true;
                render();
                return;
            }
            if ("com.bt.ACTION_BT_MUSIC_PLAY".equals(action)) {
                playing = true;
                known = true;
                render();
                return;
            }
            if ("com.bt.ACTION_BT_MUSIC_PAUSE".equals(action)) {
                playing = false;
                known = true;
                render();
                return;
            }
            if ("com.bt.ACTION_AVRCP_MUSIC_ID3".equals(action)) {
                String t = intent.getStringExtra("extra_avrcp_id3_title");
                String a = intent.getStringExtra("extra_avrcp_id3_artist");
                if (t != null) title = t.trim();
                if (a != null) artist = a.trim();
                known = true;
                render();
                return;
            }
            if ("com.bt.ACTION_AVRCP_ESTABLISHED".equals(action)) {
                bt.queryId3();
            }
        }
    };
}
