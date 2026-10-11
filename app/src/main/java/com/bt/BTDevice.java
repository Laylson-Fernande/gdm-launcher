package com.bt;

import android.os.Parcel;
import android.os.Parcelable;

/** Bluetooth service Parcelable. Field order must match the system service. */
public class BTDevice implements Parcelable {
    public String address;
    public String name;
    public int deviceClass;
    public int service;
    public int isPair;
    public int pairRecordIndex;
    public String obdPassword;

    public BTDevice() {
    }

    private BTDevice(Parcel in) {
        address = in.readString();
        name = in.readString();
        deviceClass = in.readInt();
        service = in.readInt();
        isPair = in.readInt();
        pairRecordIndex = in.readInt();
        obdPassword = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(address);
        dest.writeString(name);
        dest.writeInt(deviceClass);
        dest.writeInt(service);
        dest.writeInt(isPair);
        dest.writeInt(pairRecordIndex);
        dest.writeString(obdPassword);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BTDevice> CREATOR = new Creator<BTDevice>() {
        @Override
        public BTDevice createFromParcel(Parcel source) {
            return new BTDevice(source);
        }

        @Override
        public BTDevice[] newArray(int size) {
            return new BTDevice[size];
        }
    };
}
