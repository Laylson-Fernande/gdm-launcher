package com.k4811.common.home;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Head unit GPS for the speed and GPS data modules, through the standard Android location API
 * (works offline). Updates about once a second while started; a fix older than a few seconds
 * counts as no signal (tunnel, garage, GPS still searching). Fixes without speed or bearing get
 * them from the previous fix (distance and direction over the time between them).
 */
public final class GpsClient {

    /** Below this the car counts as stopped: GPS speed jitters around zero when parked. */
    public static final float MOVING_KMH = 3f;
    private static final long INTERVAL_MS = 1000;
    private static final long STALE_MS = 5000;
    private static final String TAG = "K4811Gps";

    public interface Listener {
        /** {@code fix} null = no recent position. */
        void onGps(Fix fix);
    }

    public static final class Fix {
        public final double latitude;
        public final double longitude;
        /** Meters above sea level; NaN if unknown. */
        public final double altitude;
        /** km/h; NaN if unknown. */
        public final float speedKmh;
        /** Degrees from north (0–360); NaN if unknown or stopped. */
        public final float bearing;

        Fix(Location l, Location previous) {
            latitude = l.getLatitude();
            longitude = l.getLongitude();
            altitude = l.hasAltitude() ? l.getAltitude() : Double.NaN;
            long dt = previous == null ? 0 : l.getTime() - previous.getTime();
            boolean derive = dt > 0 && dt <= STALE_MS;
            if (l.hasSpeed()) speedKmh = l.getSpeed() * 3.6f;
            else speedKmh = derive ? previous.distanceTo(l) / (dt / 1000f) * 3.6f : Float.NaN;
            float heading = l.hasBearing() ? l.getBearing()
                : derive ? (previous.bearingTo(l) + 360f) % 360f : Float.NaN;
            bearing = moving() ? heading : Float.NaN;
        }

        public boolean moving() {
            return !Float.isNaN(speedKmh) && speedKmh >= MOVING_KMH;
        }
    }

    private final LocationManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean started;
    private boolean available = true;
    private long lastFixAt;
    private Location previous;

    private final LocationListener updates = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastFixAt = System.currentTimeMillis();
            Fix fix = new Fix(location, previous);
            previous = location;
            if (listener != null) listener.onGps(fix);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    /** Reports "no signal" when the fixes stop coming. */
    private final Runnable staleCheck = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastFixAt > STALE_MS && listener != null) listener.onGps(null);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public GpsClient(Context context) {
        manager = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** false if the head unit has no GPS (or it can't be used): the modules say so instead of waiting. */
    public boolean available() {
        return available;
    }

    public void start() {
        if (started) return;
        started = true;
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0, updates, Looper.getMainLooper());
            available = true;
            Location last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < STALE_MS) updates.onLocationChanged(last);
        } catch (Exception e) {
            // No GPS provider, or no permission.
            available = false;
            Log.w(TAG, "GPS unavailable: " + e.getMessage());
        }
        handler.post(staleCheck);
    }

    public void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(staleCheck);
        try {
            manager.removeUpdates(updates);
        } catch (Exception ignored) {
        }
    }
}
