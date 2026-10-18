package com.nwd.setting.service;

// NWD SettingService audio callback. The ORDER defines the Binder codes (1..3).
interface AudioSettingCallback {
    void notifyAudioParam(int type, int value);
    void notifySingleGainParam(int type, int value);
    void notifyDspAudioParam(int type, int value);
}
