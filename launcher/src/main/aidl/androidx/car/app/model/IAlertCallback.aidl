// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.model;

import androidx.car.app.IOnDoneCallback;

interface IAlertCallback {
    void reserved1();
    oneway void onAlertCancelled(int p0, IOnDoneCallback p1);
    oneway void onAlertDismissed(IOnDoneCallback p0);
}
