package com.k4811.launcher.waze;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.car.app.model.IAlertCallback;
import androidx.car.app.model.IOnCheckedChangeListener;
import androidx.car.app.model.IOnClickListener;
import androidx.car.app.model.IOnContentRefreshListener;
import androidx.car.app.model.IOnSelectedListener;
import androidx.car.app.model.ISearchCallback;
import androidx.car.app.navigation.model.IPanModeListener;

/** Toques do usuário -> listeners que o Waze mandou dentro dos templates (delegates). */
final class Calls {
    private Calls() {
    }

    private static IBinder binder(CarBundle.Obj delegate, String field) {
        if (delegate == null) return null;
        Object o = delegate.get(field);
        if (o instanceof CarBundle.Iface) return ((CarBundle.Iface) o).binder;
        if (o instanceof IBinder) return (IBinder) o;
        return null;
    }

    private static CarSession.Done done(String what) {
        return new CarSession.Done(what, null);
    }

    /** Action, Row ou GridItem com mOnClickDelegate. */
    static boolean click(CarBundle.Obj clickable) {
        IBinder b = binder(clickable == null ? null : clickable.obj("mOnClickDelegate"), "mListener");
        if (b == null) return false;
        try {
            IOnClickListener.Stub.asInterface(b).onClick(done("onClick"));
            return true;
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "click", e);
            return false;
        }
    }

    static void select(CarBundle.Obj itemList, int index) {
        IBinder b = binder(itemList == null ? null : itemList.obj("mOnSelectedDelegate"), "mStub");
        if (b == null) return;
        try {
            IOnSelectedListener.Stub.asInterface(b).onSelected(index, done("onSelected"));
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "select", e);
        }
    }

    static void checked(CarBundle.Obj toggle, boolean on) {
        IBinder b = binder(toggle == null ? null : toggle.obj("mOnCheckedChangeDelegate"), "mStub");
        if (b == null) return;
        try {
            IOnCheckedChangeListener.Stub.asInterface(b).onCheckedChange(on, done("onCheckedChange"));
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "toggle", e);
        }
    }

    static void search(CarBundle.Obj template, String text, boolean submit) {
        IBinder b = binder(template == null ? null : template.obj("mSearchCallbackDelegate"), "mStubCallback");
        if (b == null) return;
        try {
            ISearchCallback cb = ISearchCallback.Stub.asInterface(b);
            if (submit) cb.onSearchSubmitted(text, done("onSearchSubmitted"));
            else cb.onSearchTextChanged(text, done("onSearchTextChanged"));
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "search", e);
        }
    }

    static void panMode(CarBundle.Obj template, boolean on) {
        IBinder b = binder(template == null ? null : template.obj("mPanModeDelegate"), "mStub");
        if (b == null) return;
        try {
            IPanModeListener.Stub.asInterface(b).onPanModeChanged(on, done("onPanModeChanged"));
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "pan", e);
        }
    }

    static void refresh(CarBundle.Obj template) {
        IBinder b = binder(template == null ? null : template.obj("mOnContentRefreshDelegate"), "mListener");
        if (b == null) return;
        try {
            IOnContentRefreshListener.Stub.asInterface(b).onContentRefreshRequested(done("onContentRefresh"));
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "refresh", e);
        }
    }

    /** Alerta: dismissed depois de uma ação; cancelled com o motivo (1 tempo, 2 usuário). */
    static void alertClosed(CarBundle.Obj alert, int reason) {
        IBinder b = binder(alert == null ? null : alert.obj("mCallbackDelegate"), "mCallback");
        if (b == null) return;
        try {
            IAlertCallback cb = IAlertCallback.Stub.asInterface(b);
            if (reason == 0) cb.onAlertDismissed(done("onAlertDismissed"));
            else cb.onAlertCancelled(reason, done("onAlertCancelled"));
        } catch (RemoteException e) {
            Log.w(CarSession.TAG, "alert", e);
        }
    }
}
