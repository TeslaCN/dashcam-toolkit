package icu.wwj.drive.dashcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

/** GPS + network location listener feeding the on-screen watermark. */
public class GpsTracker implements LocationListener {

    private static final String TAG = "DashcamGps";

    public interface Listener {
        void onLocation(boolean fix, double lat, double lon, float speedKph);
    }

    private final Context ctx;
    private LocationManager lm;
    private Listener listener;
    private volatile boolean fix;
    private volatile double lat, lon;
    private volatile float speedKph;

    public GpsTracker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void start(Listener l) {
        this.listener = l;
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no location permission");
            return;
        }
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Location lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location best = lastGps != null ? lastGps : lastNet;
            if (best != null) {
                onLocationChanged(best);
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper());
            }
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
        }
    }

    public void stop() {
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Throwable ignored) {
        }
        lm = null;
        listener = null;
    }

    public boolean hasFix() {
        return fix;
    }

    public double latitude() {
        return lat;
    }

    public double longitude() {
        return lon;
    }

    public float speedKph() {
        return speedKph;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        lat = location.getLatitude();
        lon = location.getLongitude();
        if (location.hasSpeed()) {
            speedKph = location.getSpeed() * 3.6f;
        }
        long ageMs = System.currentTimeMillis() - location.getTime();
        fix = ageMs < 15000;
        if (listener != null) listener.onLocation(fix, lat, lon, speedKph);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        fix = false;
    }
}
