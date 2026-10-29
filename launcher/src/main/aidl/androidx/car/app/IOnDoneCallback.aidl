// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app;

import androidx.car.app.serialization.Bundleable;

interface IOnDoneCallback {
    void reserved1();
    void onSuccess(in Bundleable p0);
    void onFailure(in Bundleable p0);
}
