// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app;

import android.content.Intent;
import android.content.res.Configuration;
import androidx.car.app.ICarHost;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.serialization.Bundleable;

interface ICarApp {
    void reserved1();
    oneway void onAppCreate(ICarHost p0, in Intent p1, in Configuration p2, IOnDoneCallback p3);
    oneway void onAppStart(IOnDoneCallback p0);
    oneway void onAppResume(IOnDoneCallback p0);
    oneway void onAppPause(IOnDoneCallback p0);
    oneway void onAppStop(IOnDoneCallback p0);
    oneway void onNewIntent(in Intent p0, IOnDoneCallback p1);
    oneway void onConfigurationChanged(in Configuration p0, IOnDoneCallback p1);
    oneway void getManager(String p0, IOnDoneCallback p1);
    oneway void getAppInfo(IOnDoneCallback p0);
    oneway void onHandshakeCompleted(in Bundleable p0, IOnDoneCallback p1);
    oneway void getAppTheme(IOnDoneCallback p0);
}
