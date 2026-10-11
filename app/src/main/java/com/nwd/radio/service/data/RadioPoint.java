package com.nwd.radio.service.data;

import android.os.Parcel;
import android.os.Parcelable;

/** Band limits (min, max and step), in the order the system service writes them. */
public class RadioPoint implements Parcelable {
    public final int frequencyMin;
    public final int frequencyMax;
    public final int frequencyStep;

    public RadioPoint(int min, int max, int step) {
        frequencyMin = min;
        frequencyMax = max;
        frequencyStep = step;
    }

    private RadioPoint(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(frequencyMin);
        dest.writeInt(frequencyMax);
        dest.writeInt(frequencyStep);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "RadioPoint(" + frequencyMin + ".." + frequencyMax + " step " + frequencyStep + ")";
    }

    public static final Creator<RadioPoint> CREATOR = new Creator<RadioPoint>() {
        @Override
        public RadioPoint createFromParcel(Parcel source) {
            return new RadioPoint(source);
        }

        @Override
        public RadioPoint[] newArray(int size) {
            return new RadioPoint[size];
        }
    };
}
