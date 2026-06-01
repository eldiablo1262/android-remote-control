package com.remote.control;

import android.util.Log;

import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;

/**
 * WebSocket client that connects to the Node.js server.
 * Sends JPEG frames (binary) and receives touch commands (JSON text).
 */
public class WebSocketClient {

    private static final String TAG = "WebSocketClient";

    private org.java_websocket.client.WebSocketClient client;
    private final String serverUrl;
    private boolean connected = false;
    private TouchEventListener touchListener;

    public interface TouchEventListener {
        void onTouchEvent(String type, float x, float y, float endX, float endY, String extra);
    }

    public WebSocketClient(String url) {
        this.serverUrl = url;
    }

    public void setTouchEventListener(TouchEventListener listener) {
        this.touchListener = listener;
    }

    public void connectAsync() {
        try {
            URI uri = new URI(serverUrl);
            client = new org.java_websocket.client.WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected = true;
                    Log.i(TAG, "WebSocket connected to: " + serverUrl);
                    // Send info message
                    send("{\"type\":\"info\",\"message\":\"Android connected\"}");
                }

                @Override
                public void onMessage(String message) {
                    handleTextMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    Log.i(TAG, "WebSocket closed: " + reason);
                    // Auto-reconnect after 3 seconds
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error: " + ex.getMessage());
                    connected = false;
                }
            };

            client.setConnectionLostTimeout(10);
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create WebSocket", e);
        }
    }

    private void handleTextMessage(String message) {
        try {
            // Parse JSON touch command from server
            org.json.JSONObject json = new org.json.JSONObject(message);
            String type = json.optString("type");

            if ("touch".equals(type)) {
                org.json.JSONObject event = json.getJSONObject("event");
                String touchType = event.getString("type");
                float x = (float) event.optDouble("x", 0);
                float y = (float) event.optDouble("y", 0);
                float endX = (float) event.optDouble("endX", 0);
                float endY = (float) event.optDouble("endY", 0);
                String extra = event.optString("extra", "");

                if (touchListener != null) {
                    touchListener.onTouchEvent(touchType, x, y, endX, endY, extra);
                }

                // Forward to AccessibilityService
                RemoteAccessibilityService service = RemoteAccessibilityService.getInstance();
                if (service != null) {
                    service.performRemoteAction(touchType, x, y, endX, endY, extra);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message: " + e.getMessage());
        }
    }

    public void sendBinary(byte[] data) {
        if (client != null && connected) {
            try {
                client.send(ByteBuffer.wrap(data));
            } catch (Exception e) {
                Log.e(TAG, "Error sending binary data", e);
            }
        }
    }

    public void sendText(String text) {
        if (client != null && connected) {
            try {
                client.send(text);
            } catch (Exception e) {
                Log.e(TAG, "Error sending text", e);
            }
        }
    }

    public boolean isConnected() {
        return connected && client != null && client.isOpen();
    }

    public void disconnect() {
        connected = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket", e);
            }
            client = null;
        }
    }

    private void scheduleReconnect() {
        if (client != null) {
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    if (!connected && client != null) {
                        Log.i(TAG, "Attempting reconnect...");
                        client.reconnect();
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            }).start();
        }
    }
}
