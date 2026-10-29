// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app;

import android.content.Intent;

interface ICarHost {
    void reserved1();
    void startCarApp(in Intent p0);
    IBinder getHost(String p0);
    void finish();
}
