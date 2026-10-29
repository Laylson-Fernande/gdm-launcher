// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.model;

import androidx.car.app.IOnDoneCallback;

interface ITelephoneKeypadEventListener {
    void reserved1();
    oneway void onKeyLongPress(int p0, IOnDoneCallback p1);
    oneway void onKeyDown(int p0, IOnDoneCallback p1);
    oneway void onKeyUp(int p0, IOnDoneCallback p1);
}
