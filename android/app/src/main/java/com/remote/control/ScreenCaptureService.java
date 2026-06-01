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
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int JPEG_QUALITY = 50;
    private static final int TARGET_FPS = 15;

    public static boolean isRunning = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler captureHandler;
    private HandlerThread captureThread;
    private WebSocketClient wsClient;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private boolean capturing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if ("START".equals(action)) {
            startForeground(NOTIFICATION_ID, createNotification());
            startProjection(intent);
        } else if ("STOP".equals(action)) {
            stopProjection();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");
        String wsUrl = intent.getStringExtra("wsUrl");

        if (data == null || wsUrl == null) {
            stopSelf();
            return;
        }

        // Get screen dimensions (scaled down for performance)
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);

        // Scale down to 720p max width for bandwidth
        float scale = Math.min(1.0f, 720.0f / metrics.widthPixels);
        screenWidth = (int) (metrics.widthPixels * scale);
        screenHeight = (int) (metrics.heightPixels * scale);
        screenDensity = metrics.densityDpi;

        // Start WebSocket connection
        wsClient = new WebSocketClient(wsUrl);
        wsClient.connectAsync();

        // Start capture thread
        captureThread = new HandlerThread("CaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        // Create MediaProjection
        MediaProjectionManager projectionManager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopProjection();
            }
        }, captureHandler);

        // Create ImageReader
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (!capturing) return;
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    processImage(image);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing image", e);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, captureHandler);

        // Create VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null, captureHandler
        );

        capturing = true;
        isRunning = true;

        // Start frame rate limiter
        startFrameCapture();

        Log.i(TAG, "Screen capture started: " + screenWidth + "x" + screenHeight);
    }

    private void startFrameCapture() {
        // The ImageReader callback handles frame capture automatically
        // We just need to ensure we don't send too many frames
        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                if (capturing) {
                    // Frame rate is naturally limited by ImageReader
                    captureHandler.postDelayed(this, 1000 / TARGET_FPS);
                }
            }
        });
    }

    private void processImage(Image image) {
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

        // Crop to actual screen size if there's padding
        if (rowPadding > 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            bitmap.recycle();
            bitmap = cropped;
        }

        // Compress to JPEG
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream);
        bitmap.recycle();

        byte[] jpegData = outputStream.toByteArray();

        // Send via WebSocket
        if (wsClient != null && wsClient.isConnected()) {
            wsClient.sendBinary(jpegData);
        }
    }

    private void stopProjection() {
        capturing = false;
        isRunning = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (wsClient != null) {
            wsClient.disconnect();
            wsClient = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }

        Log.i(TAG, "Screen capture stopped");
    }

    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Control")
            .setContentText("Capture d'écran en cours...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification pour la capture d'écran");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopProjection();
        super.onDestroy();
    }
}
