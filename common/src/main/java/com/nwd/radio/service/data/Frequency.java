package com.nwd.radio.service.data;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mirror of the NWD service Parcelable. The write order (band, psName, frequency)
 * must match the system service exactly.
 */
public class Frequency implements Parcelable {
    public byte bandType;
    public int frequency;
    public String psName;

    public Frequency(byte bandType, int frequency, String psName) {
        this.bandType = bandType;
        this.frequency = frequency;
        this.psName = psName;
    }

    private Frequency(Parcel in) {
        bandType = in.readByte();
        psName = in.readString();
        frequency = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(bandType);
        dest.writeString(psName);
        dest.writeInt(frequency);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "Frequency(band=" + bandType + ", freq=" + frequency + ", ps=" + psName + ")";
    }

    public static final Creator<Frequency> CREATOR = new Creator<Frequency>() {
        @Override
        public Frequency createFromParcel(Parcel source) {
            return new Frequency(source);
        }

        @Override
        public Frequency[] newArray(int size) {
            return new Frequency[size];
        }
    };
}
