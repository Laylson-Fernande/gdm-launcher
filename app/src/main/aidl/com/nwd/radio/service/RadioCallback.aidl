package com.nwd.radio.service;

import com.nwd.radio.service.data.Frequency;
import com.nwd.radio.service.data.RadioPoint;

// NWD radio service callback. The method ORDER defines the Binder transaction
// codes and must match the system service (1..14).
interface RadioCallback {
    void notifyState(byte state);
    void notifyCurrentFrequency(byte band, int frequency, String psName, int presetIndex);
    void notifyNearOn(boolean on);
    void notifyStereo(boolean isStereo);
    void notifyStereoOn(boolean on);
    void notifyRDSStateChange();
    void notifyCurrentPTYType(byte pty);
    void notifyPrefabFrequency(in Frequency[] list);
    void notifyPrefabPTYType(byte pty);
    void notifyRadioPoint(in RadioPoint[] points);
    void notifyCurrentIsTA(boolean ta);
    void notifyRdsShowState(boolean show);
    void notifyRtMessage(String rt);
    void notifyRadioScanState(int state);
}
