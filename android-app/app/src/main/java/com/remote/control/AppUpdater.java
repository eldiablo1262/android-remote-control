package com.remote.control;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;

public class AppUpdater {

    private static final String TAG = "AppUpdater";
    private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L; // 6 hours

    private final Context context;
    private final OkHttpClient httpClient;
    private final Handler handler;
    private String serverBaseUrl;
    private boolean checking = false;

    public AppUpdater(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient();
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setServerUrl(String serverUrl) {
        // Extract base URL (remove /ws/android/... path if present)
        if (serverUrl.contains("/ws/")) {
            serverUrl = serverUrl.substring(0, serverUrl.indexOf("/ws/"));
        }
        // Convert ws:// to http://
        serverUrl = serverUrl.replace("wss://", "https://").replace("ws://", "http://");
        this.serverBaseUrl = serverUrl;
    }

    /**
     * Start periodic update checks
     */
    public void startPeriodicCheck() {
        handler.post(this::checkForUpdate);
    }

    /**
     * Check for update once
     */
    public void checkForUpdate() {
        if (checking || serverBaseUrl == null) return;
        checking = true;

        new Thread(() -> {
            try {
                String versionUrl = serverBaseUrl + "/api/version";
                Log.d(TAG, "Checking for update: " + versionUrl);

                Request request = new Request.Builder()
                        .url(versionUrl)
                        .build();

                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "Version check failed: " + response.code());
                    checking = false;
                    scheduleNextCheck();
                    return;
                }

                String body = response.body().string();
                JSONObject versionInfo = new JSONObject(body);

                int remoteVersionCode = versionInfo.optInt("versionCode", 0);
                String apkUrl = versionInfo.optString("apkUrl", "");
                int localVersionCode = getLocalVersionCode();

                Log.d(TAG, "Local version: " + localVersionCode + ", Remote version: " + remoteVersionCode);

                if (remoteVersionCode > localVersionCode && !apkUrl.isEmpty()) {
                    Log.d(TAG, "Update available! Downloading...");
                    downloadAndInstall(apkUrl);
                } else {
                    Log.d(TAG, "App is up to date");
                }

            } catch (Exception e) {
                Log.e(TAG, "Update check error: " + e.getMessage());
            } finally {
                checking = false;
                scheduleNextCheck();
            }
        }, "UpdateChecker").start();
    }

    /**
     * Force update from a remote command (via WebSocket)
     */
    public void forceUpdate(WebSocket webSocket, String apkUrl) {
        new Thread(() -> {
            try {
                sendStatus(webSocket, "downloading", "Telechargement en cours...");
                File apkFile = downloadApk(apkUrl);
                if (apkFile != null) {
                    sendStatus(webSocket, "installing", "Installation en cours...");
                    installApk(apkFile);
                } else {
                    sendStatus(webSocket, "error", "Echec du telechargement");
                }
            } catch (Exception e) {
                Log.e(TAG, "Force update error", e);
                sendStatus(webSocket, "error", e.getMessage());
            }
        }, "ForceUpdate").start();
    }

    private void downloadAndInstall(String apkUrl) {
        try {
            File apkFile = downloadApk(apkUrl);
            if (apkFile != null) {
                installApk(apkFile);
            }
        } catch (Exception e) {
            Log.e(TAG, "Download/install error", e);
        }
    }

    private File downloadApk(String apkUrl) {
        try {
            Log.d(TAG, "Downloading APK from: " + apkUrl);

            URL url = new URL(apkUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setFollowRedirects(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.connect();

            if (connection.getResponseCode() != 200) {
                Log.e(TAG, "Download failed: HTTP " + connection.getResponseCode());
                return null;
            }

            // Save to cache dir
            File outputDir = context.getExternalCacheDir();
            if (outputDir == null) {
                outputDir = context.getCacheDir();
            }
            File apkFile = new File(outputDir, "update.apk");

            InputStream inputStream = connection.getInputStream();
            FileOutputStream outputStream = new FileOutputStream(apkFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long contentLength = connection.getContentLength();

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }

            outputStream.flush();
            outputStream.close();
            inputStream.close();
            connection.disconnect();

            Log.d(TAG, "APK downloaded: " + totalRead + " bytes -> " + apkFile.getAbsolutePath());
            return apkFile;

        } catch (Exception e) {
            Log.e(TAG, "APK download error", e);
            return null;
        }
    }

    private void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ needs FileProvider
                Uri apkUri = FileProvider.getUriForFile(context,
                        context.getPackageName() + ".fileprovider", apkFile);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                Uri apkUri = Uri.fromFile(apkFile);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            }

            context.startActivity(intent);
            Log.d(TAG, "Install intent launched");

        } catch (Exception e) {
            Log.e(TAG, "Install error", e);
        }
    }

    private int getLocalVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void scheduleNextCheck() {
        handler.postDelayed(this::checkForUpdate, CHECK_INTERVAL);
    }

    private void sendStatus(WebSocket webSocket, String status, String message) {
        if (webSocket == null) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "update-status");
            msg.put("status", status);
            msg.put("message", message);
            webSocket.send(msg.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending update status", e);
        }
    }
}
