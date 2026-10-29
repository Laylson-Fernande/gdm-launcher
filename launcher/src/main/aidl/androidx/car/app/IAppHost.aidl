// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app;

import android.location.Location;
import androidx.car.app.ISurfaceCallback;
import androidx.car.app.serialization.Bundleable;

interface IAppHost {
    void reserved1();
    void invalidate();
    void showToast(CharSequence p0, int p1);
    void setSurfaceCallback(ISurfaceCallback p0);
    void sendLocation(in Location p0);
    void showAlert(in Bundleable p0);
    void dismissAlert(int p0);
    Bundleable openMicrophone(in Bundleable p0);
}
