package com.remote.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility Service that simulates touch events on the device.
 * Receives commands from the WebSocket client and executes them as gestures.
 */
public class RemoteAccessibilityService extends AccessibilityService {

    private static final String TAG = "RemoteAccessibility";
    private static RemoteAccessibilityService instance = null;

    private int screenWidth;
    private int screenHeight;

    public static RemoteAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        // Get screen dimensions
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        Log.i(TAG, "Accessibility Service connected. Screen: " + screenWidth + "x" + screenHeight);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used - we only need gesture dispatch capability
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /**
     * Perform a remote action based on touch event data.
     * Coordinates are normalized (0.0 to 1.0).
     */
    public void performRemoteAction(String type, float x, float y, float endX, float endY, String extra) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gestures require API 24+");
            return;
        }

        switch (type) {
            case "tap":
                performTap(x, y);
                break;
            case "swipe":
                performSwipe(x, y, endX, endY);
                break;
            case "scroll":
                performScroll(x, y, extra);
                break;
            case "key":
                performKey(extra);
                break;
            default:
                Log.w(TAG, "Unknown action type: " + type);
        }
    }

    /**
     * Simulate a tap at normalized coordinates.
     */
    private void performTap(float normalizedX, float normalizedY) {
        int px = (int) (normalizedX * screenWidth);
        int py = (int) (normalizedY * screenHeight);

        // Clamp to screen bounds
        px = Math.max(0, Math.min(px, screenWidth - 1));
        py = Math.max(0, Math.min(py, screenHeight - 1));

        Path path = new Path();
        path.moveTo(px, py);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "Tap completed at: " + px + ", " + py);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Tap cancelled");
            }
        }, null);
    }

    /**
     * Simulate a swipe from start to end (normalized coordinates).
     */
    private void performSwipe(float startX, float startY, float endX, float endY) {
        int sx = (int) (startX * screenWidth);
        int sy = (int) (startY * screenHeight);
        int ex = (int) (endX * screenWidth);
        int ey = (int) (endY * screenHeight);

        // Clamp
        sx = Math.max(0, Math.min(sx, screenWidth - 1));
        sy = Math.max(0, Math.min(sy, screenHeight - 1));
        ex = Math.max(0, Math.min(ex, screenWidth - 1));
        ey = Math.max(0, Math.min(ey, screenHeight - 1));

        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "Swipe completed");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Swipe cancelled");
            }
        }, null);
    }

    /**
     * Simulate a scroll gesture.
     */
    private void performScroll(float x, float y, String direction) {
        int px = (int) (x * screenWidth);
        int py = (int) (y * screenHeight);

        int scrollDistance = screenHeight / 4;
        int endY;

        if ("down".equals(direction)) {
            endY = py - scrollDistance;
        } else {
            endY = py + scrollDistance;
        }

        // Clamp
        px = Math.max(0, Math.min(px, screenWidth - 1));
        py = Math.max(0, Math.min(py, screenHeight - 1));
        endY = Math.max(0, Math.min(endY, screenHeight - 1));

        Path path = new Path();
        path.moveTo(px, py);
        path.lineTo(px, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 200));

        dispatchGesture(builder.build(), null, null);
    }

    /**
     * Simulate navigation keys (back, home, recents).
     */
    private void performKey(String key) {
        switch (key) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                Log.d(TAG, "Performed: BACK");
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                Log.d(TAG, "Performed: HOME");
                break;
            case "recent":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                Log.d(TAG, "Performed: RECENTS");
                break;
            case "notifications":
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                Log.d(TAG, "Performed: NOTIFICATIONS");
                break;
            case "power":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                    Log.d(TAG, "Performed: LOCK SCREEN");
                }
                break;
            default:
                Log.w(TAG, "Unknown key: " + key);
        }
    }
}
