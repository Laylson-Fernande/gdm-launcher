package com.bt;

import android.os.Parcel;
import android.os.Parcelable;

/** Parcelable required by the interface. The card doesn't call the method that uses it. */
public class HIDKeyboardStatus implements Parcelable {
    public static final Creator<HIDKeyboardStatus> CREATOR = new Creator<HIDKeyboardStatus>() {
        @Override
        public HIDKeyboardStatus createFromParcel(Parcel source) {
            return new HIDKeyboardStatus();
        }

        @Override
        public HIDKeyboardStatus[] newArray(int size) {
            return new HIDKeyboardStatus[size];
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
