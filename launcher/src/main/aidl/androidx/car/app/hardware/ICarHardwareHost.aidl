// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.hardware;

import androidx.car.app.hardware.ICarHardwareResult;
import androidx.car.app.serialization.Bundleable;

interface ICarHardwareHost {
    void reserved1();
    void getCarHardwareResult(int p0, in Bundleable p1, ICarHardwareResult p2);
    void subscribeCarHardwareResult(int p0, in Bundleable p1, ICarHardwareResult p2);
    void unsubscribeCarHardwareResult(int p0, in Bundleable p1);
}
