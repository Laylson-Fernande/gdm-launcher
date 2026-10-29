// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.serialization;

import androidx.car.app.IOnDoneCallback;

interface IRemoteList {
    void reserved1();
    oneway void requestItemRange(int p0, int p1, IOnDoneCallback p2);
}
