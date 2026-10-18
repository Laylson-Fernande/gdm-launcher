package com.bt;

interface BTPIMCallback {
    void onGetContact(String name, String number);
    void onGetCallLog(int type, String name, String number, String extra);
    void onGetSMS(String address, String body, int type);
    void onGetA2dpProgress(int position, int duration);
}
