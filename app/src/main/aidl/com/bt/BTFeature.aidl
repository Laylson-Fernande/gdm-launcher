package com.bt;

import com.bt.BTDevice;
import com.bt.BTPIMCallback;
import com.bt.SppDataCallBack;
import com.bt.HIDKeyboardStatus;
import com.bt.HIDMouseStatus;

// Head unit Bluetooth service (com.bt.ACTION_START_SERVICE).
// The method ORDER defines the Binder transaction codes (1..62)
// and must match the system service. Do not reorder.
interface BTFeature {
    boolean startBlueTooth_BC();
    boolean stopBlueTooth_BC();
    boolean isBlueToothPowerOn();
    boolean connectDevice_BC(in BTDevice device);
    boolean disConnectDevice_BC();
    boolean isConnectDevice();
    boolean startSearchDevice_BC();
    boolean stopSearchDevice_BC();
    boolean setLocalDeviceName(String name);
    String getLocalDeviceName();
    void setPinCode(String pin);
    String getPinCode();
    boolean dial_BC(String number);
    boolean answerCall();
    boolean cancelCall();
    String getRemoteDeviceName(String address);
    int getRemoteDeviceClass(String address);
    boolean setDiscoverable(boolean on);
    boolean setPairable(boolean on);
    String getLocalDeviceAddress();
    int getLocalDeviceFeature();
    boolean sendDTMF(char digit);
    boolean playControl(int cmd);
    boolean isBTMusicPlaying();
    boolean getContact_CB();
    boolean getCallLog_CB();
    boolean getSMS_CB();
    int changeSoundChannel();
    boolean setAutoFeature_BC(int feature, boolean on);
    int getAutoFeature();
    boolean getPairDeviceRecord_BC();
    boolean deletePairDeviceRecord(int index);
    void registPIMCallback(BTPIMCallback cb);
    void unregistPIMCallback(BTPIMCallback cb);
    boolean isConnectHFP();
    boolean isConnectA2DP();
    boolean isConnectAVRCP();
    BTDevice getConnectDevice();
    void setBtMusicMute(boolean mute);
    void registSppDataCallback(SppDataCallBack cb);
    void unregistSppDataCallback(SppDataCallBack cb);
    void HIDConnect(String address);
    void HIDDisConnect();
    boolean isHIDConnect();
    void HIDKeyboardInput(in HIDKeyboardStatus status);
    void HIDMouseInput(in HIDMouseStatus status);
    boolean SppConnect(String address);
    void SppDisConnect();
    void installMhlSoft();
    boolean isSppConnect();
    void SppSendData(in byte[] data);
    int getCallState();
    void connectA2dp(String address);
    void disconnectA2dp();
    void setSpeakerVolume(int volume);
    int getMaxSpeakerVolume();
    int getSpeakerVolume();
    void setMute(boolean mute);
    boolean isBtMusicMute();
    void queryA2dpId3Info();
    void btReset();
    void loadContactFromPhone();
}
