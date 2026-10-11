package com.bt;

// The card doesn't register this callback. The method exists to complete the AIDL interface.
interface SppDataCallBack {
    void onSppData(in byte[] data);
}
