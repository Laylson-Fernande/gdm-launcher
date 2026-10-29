// Gerado por tools/gen_aidl.py a partir do smali do Waze. Não editar.
package androidx.car.app;

import android.graphics.Rect;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.serialization.Bundleable;

interface ISurfaceCallback {
    void reserved1();
    oneway void onSurfaceAvailable(in Bundleable p0, IOnDoneCallback p1);
    oneway void onVisibleAreaChanged(in Rect p0, IOnDoneCallback p1);
    oneway void onStableAreaChanged(in Rect p0, IOnDoneCallback p1);
    oneway void onSurfaceDestroyed(in Bundleable p0, IOnDoneCallback p1);
    oneway void onScroll(float p0, float p1);
    oneway void onFling(float p0, float p1);
    oneway void onScale(float p0, float p1, float p2);
    oneway void onClick(float p0, float p1);
}
