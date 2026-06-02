package com.remote.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.d(TAG, "Boot/update detected, checking auto-start config...");

            SharedPreferences prefs = context.getSharedPreferences("remote_control", Context.MODE_PRIVATE);
            boolean autoStart = prefs.getBoolean("auto_start", true);
            String serverUrl = prefs.getString("server_url", "");
            String sessionId = prefs.getString("session_id", "");

            if (!autoStart || serverUrl.isEmpty() || sessionId.isEmpty()) {
                Log.d(TAG, "Auto-start disabled or no config, skipping");
                return;
            }

            Log.d(TAG, "Auto-starting service: " + serverUrl + " session=" + sessionId);

            // Launch MainActivity which will handle the MediaProjection permission
            // Since we can't get MediaProjection without user interaction,
            // we start the activity in background mode
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchIntent.putExtra("auto_start_from_boot", true);
            launchIntent.putExtra("server_url", serverUrl);
            launchIntent.putExtra("session_id", sessionId);
            context.startActivity(launchIntent);
        }
    }
}
