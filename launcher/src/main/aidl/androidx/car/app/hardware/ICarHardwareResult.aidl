// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.hardware;

import androidx.car.app.serialization.Bundleable;

interface ICarHardwareResult {
    void reserved1();
    oneway void onCarHardwareResult(int p0, boolean p1, in Bundleable p2, IBinder p3);
}
