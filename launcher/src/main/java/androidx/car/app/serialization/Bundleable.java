package androidx.car.app.serialization;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Mesmo formato do Bundleable da biblioteca androidx.car.app: um Bundle no Parcel.
 * O conteúdo do Bundle segue o Bundler (ver com.k4811.carhost.CarBundle).
 */
public final class Bundleable implements Parcelable {
    public final Bundle bundle;

    public Bundleable(Bundle bundle) {
        this.bundle = bundle;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(bundle);
    }

    public static final Creator<Bundleable> CREATOR = new Creator<Bundleable>() {
        @Override
        public Bundleable createFromParcel(Parcel in) {
            return new Bundleable(in.readBundle(Bundleable.class.getClassLoader()));
        }

        @Override
        public Bundleable[] newArray(int size) {
            return new Bundleable[size];
        }
    };
}
