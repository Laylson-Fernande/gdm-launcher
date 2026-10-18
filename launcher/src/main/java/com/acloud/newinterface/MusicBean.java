package com.acloud.newinterface;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Track the NWD player sends in the {@code id} extra of metachanged / playstatechanged.
 * Read order matches the sender: all String, except TYPE (int).
 */
public class MusicBean implements Parcelable {
    public String ID;
    public String DATA;
    public String SIZE;
    public String ARTIST;
    public String ALBUM_ID;
    public String DISPLAY_NAME;
    public String TITLE;
    public String DURATION;
    public int TYPE;
    public String ALBUM;
    public String PARENT_PATH;
    public String TEMP_ARTIST;
    public String TEMP_ALBUM;

    public MusicBean() {
    }

    private MusicBean(Parcel in) {
        ID = in.readString();
        DATA = in.readString();
        SIZE = in.readString();
        ARTIST = in.readString();
        ALBUM_ID = in.readString();
        DISPLAY_NAME = in.readString();
        TITLE = in.readString();
        DURATION = in.readString();
        TYPE = in.readInt();
        ALBUM = in.readString();
        PARENT_PATH = in.readString();
        TEMP_ARTIST = in.readString();
        TEMP_ALBUM = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(ID);
        dest.writeString(DATA);
        dest.writeString(SIZE);
        dest.writeString(ARTIST);
        dest.writeString(ALBUM_ID);
        dest.writeString(DISPLAY_NAME);
        dest.writeString(TITLE);
        dest.writeString(DURATION);
        dest.writeInt(TYPE);
        dest.writeString(ALBUM);
        dest.writeString(PARENT_PATH);
        dest.writeString(TEMP_ARTIST);
        dest.writeString(TEMP_ALBUM);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<MusicBean> CREATOR = new Creator<MusicBean>() {
        @Override
        public MusicBean createFromParcel(Parcel source) {
            return new MusicBean(source);
        }

        @Override
        public MusicBean[] newArray(int size) {
            return new MusicBean[size];
        }
    };
}
