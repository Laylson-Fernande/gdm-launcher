// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.messaging.model;

import androidx.car.app.IOnDoneCallback;

interface IConversationCallback {
    void reserved1();
    oneway void onMarkAsRead(IOnDoneCallback p0);
    oneway void onTextReply(IOnDoneCallback p0, String p1);
}
