// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.model;

import androidx.car.app.IOnDoneCallback;

interface IInputCallback {
    void reserved1();
    oneway void onInputTextChanged(String p0, IOnDoneCallback p1);
    oneway void onInputSubmitted(String p0, IOnDoneCallback p1);
}
