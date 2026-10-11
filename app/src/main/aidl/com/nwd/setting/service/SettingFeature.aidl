package com.nwd.setting.service;

import com.nwd.setting.service.AudioSettingCallback;

// NWD SettingService interface (package com.nwd.setting.service).
// The method ORDER defines the Binder transaction codes (1..39) and must
// match the system service. Do not reorder.
// SettingCallback and CanCallback are declared as IBinder: the wire format is the
// same (strong binder) and this app doesn't use these callbacks.
interface SettingFeature {
    void setWatchDogSwtich(boolean on);                 // 1
    void responseWatchDog(int value);                   // 2
    void setLanguageIndex(int index);                   // 3
    void setBackStageManageFunction(int value);         // 4
    void setTirePressureFunctionManage(int value);      // 5
    void setAudioParam(int type, int value);            // 6
    int getAudioParam(int type);                        // 7
    void setGainParam(int type, int value);             // 8
    int getGainParam(int type);                         // 9
    void setMute(boolean mute);                         // 10
    void setSystemFeature(int type, int value);         // 11
    void setTriColor(int r, int g, int b);              // 12
    void setDefaultVolume(int volume);                  // 13
    void setGPSPromptVolumeWay(int way);                // 14
    void setATVStandard(int standard);                  // 15
    void setRadioRegion(int region);                    // 16
    void setMixedRatio(int ratio);                      // 17
    void setDayBackLight(int value);                    // 18
    void setBackLightSwitch(boolean on);                // 19
    void setVideoParam(int type, int value);            // 20
    void setVideoMode(int mode);                        // 21
    void registCallback(IBinder settingCallback);       // 22
    void unRegistCallback(IBinder settingCallback);     // 23
    void registAudioCallback(AudioSettingCallback cb);  // 24
    void unRegistAudioCallback(AudioSettingCallback cb);// 25
    boolean[] getSteerWheelLearningState();             // 26
    void setRadarSwitcher(int value);                   // 27
    void requestOSFactoryReset(byte type);              // 28
    void registCanCallback(IBinder canCallback);        // 29
    void unRegistCanCallback(IBinder canCallback);      // 30
    void setDspAudio(byte type, byte value);            // 31
    int getDspAudio(byte type);                         // 32
    void setAmplifierSwitch(boolean on);                // 33
    void setSmartFanSwitch(boolean on);                 // 34
    int[] getTriColor();                                // 35
    int getGSensorSensitivity();                        // 36
    void setGSensorSensitivity(int value);              // 37
    void setAutoWakeup(boolean on);                     // 38
    void shortMute(int millis);                         // 39
}
