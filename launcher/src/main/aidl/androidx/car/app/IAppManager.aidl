// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app;

import androidx.car.app.IOnDoneCallback;

interface IAppManager {
    void reserved1();
    oneway void getTemplate(IOnDoneCallback p0);
    oneway void onBackPressed(IOnDoneCallback p0);
    oneway void startLocationUpdates(IOnDoneCallback p0);
    oneway void stopLocationUpdates(IOnDoneCallback p0);
}
