// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.model;

import androidx.car.app.IOnDoneCallback;

interface ISearchCallback {
    void reserved1();
    oneway void onSearchTextChanged(String p0, IOnDoneCallback p1);
    oneway void onSearchSubmitted(String p0, IOnDoneCallback p1);
}
