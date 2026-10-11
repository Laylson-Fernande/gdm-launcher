package com.bt;

import android.os.Parcel;
import android.os.Parcelable;

/** Parcelable required by the interface. The card doesn't call the method that uses it. */
public class HIDMouseStatus implements Parcelable {
    public static final Creator<HIDMouseStatus> CREATOR = new Creator<HIDMouseStatus>() {
        @Override
        public HIDMouseStatus createFromParcel(Parcel source) {
            return new HIDMouseStatus();
        }

        @Override
        public HIDMouseStatus[] newArray(int size) {
            return new HIDMouseStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}
