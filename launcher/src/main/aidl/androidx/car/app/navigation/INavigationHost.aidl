// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app.navigation;

import androidx.car.app.serialization.Bundleable;

interface INavigationHost {
    void reserved1();
    void navigationStarted();
    void navigationEnded();
    void updateTrip(in Bundleable p0);
    void setVoiceAssistantCapabilities(in Bundleable p0);
}
