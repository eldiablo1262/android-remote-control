package com.remote.control;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_OVERLAY_PERMISSION = 1002;

    private EditText etServerIp;
    private EditText etServerPort;
    private EditText etSessionId;
    private Button btnStart;
    private Button btnStop;
    private Button btnAccessibility;
    private TextView tvStatus;

    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerIp = findViewById(R.id.et_server_ip);
        etServerPort = findViewById(R.id.et_server_port);
        etSessionId = findViewById(R.id.et_session_id);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnAccessibility = findViewById(R.id.btn_accessibility);
        tvStatus = findViewById(R.id.tv_status);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Load saved preferences
        SharedPreferences prefs = getSharedPreferences("remote_control", MODE_PRIVATE);
        etServerIp.setText(prefs.getString("server_ip", "192.168.1.100"));
        etServerPort.setText(prefs.getString("server_port", "3000"));
        etSessionId.setText(prefs.getString("session_id", ""));

        btnStart.setOnClickListener(v -> startCapture());
        btnStop.setOnClickListener(v -> stopCapture());
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        updateStatus();
    }

    private void startCapture() {
        String ip = etServerIp.getText().toString().trim();
        String port = etServerPort.getText().toString().trim();
        String sessionId = etSessionId.getText().toString().trim();

        if (ip.isEmpty() || port.isEmpty() || sessionId.isEmpty()) {
            Toast.makeText(this, "Remplissez tous les champs", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save preferences
        SharedPreferences prefs = getSharedPreferences("remote_control", MODE_PRIVATE);
        prefs.edit()
            .putString("server_ip", ip)
            .putString("server_port", port)
            .putString("session_id", sessionId)
            .apply();

        // Request media projection permission
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    private void stopCapture() {
        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction("STOP");
        startService(stopIntent);
        tvStatus.setText("Status: Arrêté");
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "Activez 'RemoteControl' dans les services d'accessibilité", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                String ip = etServerIp.getText().toString().trim();
                String port = etServerPort.getText().toString().trim();
                String sessionId = etSessionId.getText().toString().trim();
                String wsUrl = "ws://" + ip + ":" + port + "/ws/android/" + sessionId;

                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                serviceIntent.setAction("START");
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("data", data);
                serviceIntent.putExtra("wsUrl", wsUrl);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                tvStatus.setText("Status: Streaming...");
                Toast.makeText(this, "Capture démarrée", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateStatus() {
        if (ScreenCaptureService.isRunning) {
            tvStatus.setText("Status: Streaming...");
        } else {
            tvStatus.setText("Status: Arrêté");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }
}
