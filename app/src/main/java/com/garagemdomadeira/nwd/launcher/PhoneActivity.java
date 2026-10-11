package com.garagemdomadeira.nwd.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.garagemdomadeira.nwd.launcher.bt.BtClient;
import com.garagemdomadeira.nwd.launcher.radio.NwdConstants;
import com.garagemdomadeira.nwd.launcher.ui.DockView;
import com.garagemdomadeira.nwd.launcher.ui.IconView;
import com.garagemdomadeira.nwd.launcher.ui.Screens;
import com.garagemdomadeira.nwd.launcher.ui.Touch;
import com.garagemdomadeira.nwd.launcher.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Bluetooth phone: dialing, contacts, history, calls and minimal settings.
 * The chip lives in the system service ({@link BtClient}).
 */
public class PhoneActivity extends Activity {

    private static final int TAB_DIAL = 0;
    private static final int TAB_CONTACTS = 1;
    private static final int TAB_LOGS = 2;
    private static final int TAB_SETTINGS = 3;

    private static final int LOG_RECEIVED = 0;
    private static final int LOG_DIALED = 1;
    private static final int LOG_MISSED = 2;

    private static final int CALL_NONE = 0;
    private static final int CALL_IN = 1;
    private static final int CALL_OUT = 2;
    private static final int CALL_ONLINE = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> contacts = new ArrayList<>();
    private final List<LogRow> allLogs = new ArrayList<>();
    private final List<LogRow> shownLogs = new ArrayList<>();

    private BtClient bt;
    private boolean receiverRegistered;
    private int tab = TAB_DIAL;
    private int logFilter = LOG_MISSED;
    private int callState = CALL_NONE;
    private String dial = "";
    private String callName = "";
    private String callNumber = "";
    private boolean micMuted;
    private boolean autoAnswer;
    private boolean autoConnect;
    private boolean hfp;
    private String remoteName = "";

    private TextView statusView;
    private TextView dialView;
    private View panelDial;
    private View panelContacts;
    private View panelLogs;
    private View panelSettings;
    private View callOverlay;
    private TextView callStateView;
    private TextView callNameView;
    private TextView callNumberView;
    private TextView callAnswer;
    private TextView callMute;
    private TextView tabDial;
    private TextView tabContacts;
    private TextView tabLogs;
    private TextView tabSettings;
    private TextView logsMissed;
    private TextView logsReceived;
    private TextView logsDialed;
    private TextView autoAnswerBtn;
    private TextView autoConnectBtn;
    private EditText nameEdit;
    private EditText pinEdit;
    private RowAdapter contactAdapter;
    private LogAdapter logAdapter;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshInfo();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyDesignDensity(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_phone);
        Ui.applyFont(findViewById(R.id.phone_root));
        Screens.attachDock(this, DockView.Item.PHONE);
        bindViews();

        bt = new BtClient(this);
        bt.addContactListener(contactListener);
        bt.connect();
        registerReceiver();
        showTab(TAB_DIAL);
        renderDial();
        renderCall();
        refreshInfo();
        Touch.install(findViewById(android.R.id.content));
    }

    private void bindViews() {
        IconView back = (IconView) findViewById(R.id.phone_back);
        back.set(IconView.Icon.CHEVRON_LEFT, IconView.Style.NONE);
        back.setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.phone_title)).setTypeface(Ui.semiBold(this));
        statusView = (TextView) findViewById(R.id.phone_status);

        tabDial = (TextView) findViewById(R.id.tab_dial);
        tabContacts = (TextView) findViewById(R.id.tab_contacts);
        tabLogs = (TextView) findViewById(R.id.tab_logs);
        tabSettings = (TextView) findViewById(R.id.tab_settings);
        tabDial.setOnClickListener(v -> showTab(TAB_DIAL));
        tabContacts.setOnClickListener(v -> showTab(TAB_CONTACTS));
        tabLogs.setOnClickListener(v -> showTab(TAB_LOGS));
        tabSettings.setOnClickListener(v -> showTab(TAB_SETTINGS));

        panelDial = findViewById(R.id.panel_dial);
        panelContacts = findViewById(R.id.panel_contacts);
        panelLogs = findViewById(R.id.panel_logs);
        panelSettings = findViewById(R.id.panel_settings);
        dialView = (TextView) findViewById(R.id.dial_number);
        dialView.setTypeface(Ui.semiBold(this));

        bindKey(R.id.key_1, '1');
        bindKey(R.id.key_2, '2');
        bindKey(R.id.key_3, '3');
        bindKey(R.id.key_4, '4');
        bindKey(R.id.key_5, '5');
        bindKey(R.id.key_6, '6');
        bindKey(R.id.key_7, '7');
        bindKey(R.id.key_8, '8');
        bindKey(R.id.key_9, '9');
        bindKey(R.id.key_star, '*');
        bindKey(R.id.key_hash, '#');
        TextView zero = bindKey(R.id.key_0, '0');
        zero.setOnLongClickListener(v -> {
            append('+');
            return true;
        });

        findViewById(R.id.dial_backspace).setOnClickListener(v -> {
            if (dial.isEmpty()) return;
            dial = dial.substring(0, dial.length() - 1);
            renderDial();
        });
        // Main actions highlighted (white outline).
        findViewById(R.id.dial_call).setActivated(true);
        findViewById(R.id.settings_save).setActivated(true);
        findViewById(R.id.call_answer).setActivated(true);
        findViewById(R.id.dial_call).setOnClickListener(v -> placeCall(dial));

        findViewById(R.id.contacts_sync).setOnClickListener(v -> {
            contacts.clear();
            contactAdapter.notifyDataSetChanged();
            bt.loadContactsFromPhone();
            bt.requestContacts();
            Toast.makeText(this, R.string.media_phone_syncing_contacts, Toast.LENGTH_SHORT).show();
        });
        ListView contactsList = (ListView) findViewById(R.id.contacts_list);
        contactAdapter = new RowAdapter(contacts);
        contactsList.setAdapter(contactAdapter);
        contactsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= contacts.size()) return;
            placeCall(contacts.get(position).number);
        });

        logsMissed = (TextView) findViewById(R.id.logs_missed);
        logsReceived = (TextView) findViewById(R.id.logs_received);
        logsDialed = (TextView) findViewById(R.id.logs_dialed);
        logsMissed.setOnClickListener(v -> setLogFilter(LOG_MISSED));
        logsReceived.setOnClickListener(v -> setLogFilter(LOG_RECEIVED));
        logsDialed.setOnClickListener(v -> setLogFilter(LOG_DIALED));
        ListView logsList = (ListView) findViewById(R.id.logs_list);
        logAdapter = new LogAdapter(shownLogs);
        logsList.setAdapter(logAdapter);
        logsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownLogs.size()) return;
            placeCall(shownLogs.get(position).number);
        });

        nameEdit = (EditText) findViewById(R.id.settings_name);
        pinEdit = (EditText) findViewById(R.id.settings_pin);
        autoAnswerBtn = (TextView) findViewById(R.id.settings_auto_answer);
        autoConnectBtn = (TextView) findViewById(R.id.settings_auto_connect);
        autoAnswerBtn.setOnClickListener(v -> {
            autoAnswer = !autoAnswer;
            bt.setAutoFeature(BtClient.AUTO_ANSWER, autoAnswer);
            renderSettingsFlags();
        });
        autoConnectBtn.setOnClickListener(v -> {
            autoConnect = !autoConnect;
            bt.setAutoFeature(BtClient.AUTO_CONNECT, autoConnect);
            renderSettingsFlags();
        });
        findViewById(R.id.settings_save).setOnClickListener(v -> {
            bt.setLocalName(nameEdit.getText().toString().trim());
            bt.setPin(pinEdit.getText().toString().trim());
            Toast.makeText(this, R.string.media_phone_saved, Toast.LENGTH_SHORT).show();
            refreshInfo();
        });

        callOverlay = findViewById(R.id.call_overlay);
        callStateView = (TextView) findViewById(R.id.call_state);
        callNameView = (TextView) findViewById(R.id.call_name);
        callNumberView = (TextView) findViewById(R.id.call_number);
        callAnswer = (TextView) findViewById(R.id.call_answer);
        callMute = (TextView) findViewById(R.id.call_mute);
        callNameView.setTypeface(Ui.semiBold(this));
        callAnswer.setOnClickListener(v -> bt.answer());
        findViewById(R.id.call_hangup).setOnClickListener(v -> bt.hangup());
        callMute.setOnClickListener(v -> {
            micMuted = !micMuted;
            bt.setMicMute(micMuted);
            renderCall();
        });
        findViewById(R.id.call_channel).setOnClickListener(v -> bt.changeSoundChannel());
    }

    private TextView bindKey(int id, char digit) {
        TextView key = (TextView) findViewById(id);
        key.setText(String.valueOf(digit));
        key.setOnClickListener(v -> append(digit));
        return key;
    }

    private void append(char digit) {
        if (callState == CALL_ONLINE || callState == CALL_OUT) {
            bt.sendDtmf(digit);
        }
        if (dial.length() >= 32) return;
        dial = dial + digit;
        renderDial();
    }

    private void placeCall(String number) {
        if (number == null) return;
        String n = number.trim();
        if (n.isEmpty()) {
            Toast.makeText(this, R.string.media_phone_enter_number, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hfp) {
            Toast.makeText(this, R.string.media_phone_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        claimSource();
        callNumber = n;
        callName = "";
        callState = CALL_OUT;
        renderCall();
        bt.dial(n);
    }

    private void showTab(int next) {
        tab = next;
        panelDial.setVisibility(tab == TAB_DIAL ? View.VISIBLE : View.GONE);
        panelContacts.setVisibility(tab == TAB_CONTACTS ? View.VISIBLE : View.GONE);
        panelLogs.setVisibility(tab == TAB_LOGS ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        Touch.fadeIn(tab == TAB_DIAL ? panelDial : tab == TAB_CONTACTS ? panelContacts
            : tab == TAB_LOGS ? panelLogs : panelSettings);
        tabDial.setActivated(tab == TAB_DIAL);
        tabContacts.setActivated(tab == TAB_CONTACTS);
        tabLogs.setActivated(tab == TAB_LOGS);
        tabSettings.setActivated(tab == TAB_SETTINGS);
        if (tab == TAB_CONTACTS && contacts.isEmpty()) {
            bt.requestContacts();
        }
        if (tab == TAB_LOGS) {
            if (allLogs.isEmpty()) bt.requestCallLog();
            applyLogFilter();
        }
        if (tab == TAB_SETTINGS) refreshInfo();
    }

    private void setLogFilter(int filter) {
        logFilter = filter;
        logsMissed.setActivated(filter == LOG_MISSED);
        logsReceived.setActivated(filter == LOG_RECEIVED);
        logsDialed.setActivated(filter == LOG_DIALED);
        applyLogFilter();
    }

    private void applyLogFilter() {
        shownLogs.clear();
        for (LogRow row : allLogs) {
            if (row.type == logFilter) shownLogs.add(row);
        }
        logAdapter.notifyDataSetChanged();
    }

    private void renderDial() {
        dialView.setText(dial);
    }

    private void renderStatus() {
        if (!hfp) {
            statusView.setText(remoteName.isEmpty() ? getString(R.string.media_phone_disconnected)
                    : getString(R.string.media_phone_device_disconnected, remoteName));
            return;
        }
        statusView.setText(remoteName.isEmpty() ? getString(R.string.media_phone_connected) : remoteName);
    }

    private void renderSettingsFlags() {
        autoAnswerBtn.setText(getString(autoAnswer ? R.string.media_phone_auto_answer_on : R.string.media_phone_auto_answer_off));
        autoAnswerBtn.setActivated(autoAnswer);
        autoConnectBtn.setText(getString(autoConnect ? R.string.media_phone_auto_connect_on : R.string.media_phone_auto_connect_off));
        autoConnectBtn.setActivated(autoConnect);
    }

    private void renderCall() {
        boolean active = callState != CALL_NONE;
        callOverlay.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) return;
        if (callState == CALL_IN) callStateView.setText(R.string.media_phone_call_incoming);
        else if (callState == CALL_OUT) callStateView.setText(R.string.media_phone_call_outgoing);
        else callStateView.setText(R.string.media_phone_call_active);
        callAnswer.setVisibility(callState == CALL_IN ? View.VISIBLE : View.GONE);
        boolean hasName = callName != null && !callName.isEmpty();
        boolean hasNumber = callNumber != null && !callNumber.isEmpty();
        if (hasName) {
            callNameView.setText(callName);
            callNumberView.setText(hasNumber ? callNumber : "");
        } else if (hasNumber) {
            callNameView.setText(callNumber);
            callNumberView.setText("");
        } else {
            callNameView.setText(R.string.media_phone_unknown_number);
            callNumberView.setText("");
        }
        callMute.setText(micMuted ? R.string.media_phone_mute_on : R.string.media_phone_mute);
        callMute.setActivated(micMuted);
    }

    private void refreshInfo() {
        bt.queryPhoneInfo((hfpConnected, powered, localName, pin, remote, autoFeatures, state) -> {
            hfp = hfpConnected;
            remoteName = remote == null ? "" : remote;
            autoAnswer = (autoFeatures & BtClient.AUTO_ANSWER) != 0;
            autoConnect = (autoFeatures & BtClient.AUTO_CONNECT) != 0;
            if (tab == TAB_SETTINGS) {
                if (!nameEdit.isFocused()) nameEdit.setText(localName);
                if (!pinEdit.isFocused()) pinEdit.setText(pin);
                renderSettingsFlags();
            }
            if (state >= CALL_NONE && state <= CALL_ONLINE) {
                callState = state;
                if (callState == CALL_NONE) {
                    micMuted = false;
                }
            }
            renderStatus();
            renderCall();
            if (!powered) {
                statusView.setText(R.string.media_phone_bt_off);
            }
        });
    }

    private void claimSource() {
        Intent in = new Intent(NwdConstants.ACTION_APP_IN_OUT);
        in.putExtra(NwdConstants.EXTRA_APP_ID, NwdConstants.APP_ID_PHONE);
        in.putExtra(NwdConstants.EXTRA_APP_OPERATION, 1);
        in.putExtra(NwdConstants.EXTRA_APP_EVENT, 0);
        sendBroadcast(in);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(getWindow());
        claimSource();
        setLogFilter(logFilter);
        refreshInfo();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 2000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
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
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        if (bt != null) {
            bt.removeContactListener(contactListener);
            bt.release();
        }
        super.onDestroy();
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.bt.ACTION_BT_INCOMING_CALL");
        filter.addAction("com.bt.ACTION_BT_OUTGOING_CALL");
        filter.addAction("com.bt.ACTION_BT_BEGIN_CALL_ONLINE");
        filter.addAction("com.bt.ACTION_BT_END_CALL");
        filter.addAction("com.bt.ACTION_BT_INCOMING_NUMBER");
        filter.addAction("com.bt.ACTION_BT_OUTGOING_NUMBER");
        filter.addAction("com.bt.ACTION_BT_INCOMING_NAME");
        filter.addAction("com.bt.ACTION_BT_OUTGOING_NAME");
        filter.addAction("com.bt.ACTION_HFP_ESTABLISHED");
        filter.addAction("com.bt.ACTION_HFP_RELEASE");
        registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private final BtClient.ContactListener contactListener = new BtClient.ContactListener() {
        @Override
        public void onContact(String name, String number) {
            if (number == null || number.trim().isEmpty()) return;
            String n = number.trim();
            for (Row row : contacts) {
                if (row.number.equals(n)) return;
            }
            contacts.add(new Row(name == null || name.isEmpty() ? n : name, n));
            contactAdapter.notifyDataSetChanged();
        }

        @Override
        public void onCallLog(int type, String name, String number, String extra) {
            String num = number == null || number.isEmpty() ? extra : number;
            if (num == null || num.trim().isEmpty()) return;
            allLogs.add(0, new LogRow(type, name, num.trim()));
            if (tab == TAB_LOGS) applyLogFilter();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if ("com.bt.ACTION_BT_INCOMING_CALL".equals(action)) {
                callState = CALL_IN;
                renderCall();
                return;
            }
            if ("com.bt.ACTION_BT_OUTGOING_CALL".equals(action)) {
                callState = CALL_OUT;
                renderCall();
                return;
            }
            if ("com.bt.ACTION_BT_BEGIN_CALL_ONLINE".equals(action)) {
                callState = CALL_ONLINE;
                renderCall();
                return;
            }
            if ("com.bt.ACTION_BT_END_CALL".equals(action)) {
                callState = CALL_NONE;
                micMuted = false;
                callName = "";
                callNumber = "";
                renderCall();
                return;
            }
            if ("com.bt.ACTION_BT_INCOMING_NUMBER".equals(action)
                    || "com.bt.ACTION_BT_OUTGOING_NUMBER".equals(action)) {
                String n = intent.getStringExtra("extra_phone_number");
                if (n != null) callNumber = n.trim();
                renderCall();
                return;
            }
            if ("com.bt.ACTION_BT_INCOMING_NAME".equals(action)
                    || "com.bt.ACTION_BT_OUTGOING_NAME".equals(action)) {
                String n = intent.getStringExtra("extra_phone_name");
                if (n != null) callName = n.trim();
                renderCall();
                return;
            }
            if ("com.bt.ACTION_HFP_ESTABLISHED".equals(action)
                    || "com.bt.ACTION_HFP_RELEASE".equals(action)) {
                refreshInfo();
            }
        }
    };

    private static final class Row {
        final String title;
        final String number;

        Row(String title, String number) {
            this.title = title;
            this.number = number;
        }
    }

    private static final class LogRow {
        final int type;
        final String name;
        final String number;

        LogRow(int type, String name, String number) {
            this.type = type;
            this.name = name == null ? "" : name.trim();
            this.number = number;
        }
    }

    private final class RowAdapter extends BaseAdapter {
        private final List<Row> data;

        RowAdapter(List<Row> data) {
            this.data = data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.phone_row, parent, false);
                Touch.hapticOnly(row);
            }
            Row item = data.get(position);
            ((TextView) row.findViewById(R.id.row_title)).setText(item.title);
            ((TextView) row.findViewById(R.id.row_sub)).setText(item.number);
            return row;
        }
    }

    private final class LogAdapter extends BaseAdapter {
        private final List<LogRow> data;

        LogAdapter(List<LogRow> data) {
            this.data = data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.phone_row, parent, false);
                Touch.hapticOnly(row);
            }
            LogRow item = data.get(position);
            String title = item.name.isEmpty() ? item.number : item.name;
            ((TextView) row.findViewById(R.id.row_title)).setText(title);
            ((TextView) row.findViewById(R.id.row_sub)).setText(
                    item.name.isEmpty() ? typeLabel(item.type) : item.number + "  ·  " + typeLabel(item.type));
            return row;
        }

        private String typeLabel(int type) {
            if (type == LOG_RECEIVED) return getString(R.string.media_phone_log_received);
            if (type == LOG_DIALED) return getString(R.string.media_phone_log_dialed);
            return getString(R.string.media_phone_log_missed);
        }
    }
}
