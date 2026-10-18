package com.nwd.radio.service;

import com.nwd.radio.service.RadioCallback;
import com.nwd.radio.service.data.Frequency;
import com.nwd.radio.service.data.RadioPoint;

// NWD radio service interface (com.nwd.radio.service.ACTION_RADIO_SERVICE).
// The method ORDER defines the Binder transaction codes (1..30) and must
// match the system service. Do not reorder.
interface RadioFeature {
    void setCurrentFrequency(int frequency, byte band, int presetIndex);
    Frequency getCurrentFrequency();
    void seek(boolean up);
    void search(boolean up);
    void changeBand();
    void AMS();
    void INTRO();
    void setNearOn(boolean on);
    boolean isNearOn();
    boolean isHasStrero();
    void setStreroOn(boolean on);
    boolean isStreroOn();
    void setRadioBackServiceOn(boolean on);
    boolean isRadioBackServiceOn();
    void setRDSState(byte flag, boolean on);
    boolean getRDSState(int flag);
    void setPTYType(byte pty);
    byte getPTYType();
    byte getPrefabPTYType();
    void saveCurrentFrequency(byte presetIndex);
    Frequency[] getPrefabFrequency();
    RadioPoint[] getRadioPoint();
    byte getRadioState();
    void registCallback(RadioCallback callback);
    void unRegistCallback(RadioCallback callback);
    void prefeb(boolean next);
    void sendRadioCommand(byte action, byte value);
    String getRtMessage();
    int getRadioType();
    int getCurrentScanState();
}
