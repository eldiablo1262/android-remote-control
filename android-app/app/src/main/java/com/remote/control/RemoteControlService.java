package com.remote.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class RemoteControlService extends AccessibilityService {

    private static final String TAG = "RemoteControl";
    public static RemoteControlService instance = null;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "AccessibilityService connected - remote control enabled");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used - we only need gesture dispatch
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
        Log.d(TAG, "AccessibilityService destroyed");
    }

    /**
     * Simulate a tap at screen coordinates
     */
    public void performTap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "Tap completed at " + x + "," + y);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Tap cancelled at " + x + "," + y);
            }
        }, null);
    }

    /**
     * Simulate a swipe from (x1,y1) to (x2,y2)
     */
    public void performSwipe(int x1, int y1, int x2, int y2, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(durationMs, 100)));

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
     * Input text into the currently focused field
     */
    public void performTextInput(String text) {
        AccessibilityNodeInfo focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focusedNode.recycle();
            Log.d(TAG, "Text input: " + text);
        } else {
            Log.w(TAG, "No focused input field for text input");
        }
    }

    /**
     * Perform system key actions
     */
    public void performSystemKey(String key) {
        switch (key) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case "notifications":
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case "quick-settings":
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case "power":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                }
                break;
            case "lock":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                }
                break;
            case "screenshot":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
                }
                break;
            case "split-screen":
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
                break;
            default:
                Log.w(TAG, "Unknown system key: " + key);
        }
        Log.d(TAG, "System key: " + key);
    }
}
