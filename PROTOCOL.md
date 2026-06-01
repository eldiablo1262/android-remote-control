# Android Remote Control - WebSocket Protocol

## Architecture

```
┌─────────────┐      WiFi/4G/Internet      ┌─────────────┐      Socket.IO      ┌─────────────┐
│  Android    │ ──── WebSocket (binary) ──→ │   Server    │ ←── Socket.IO ────→ │  PC Viewer  │
│  App        │ ←── WebSocket (JSON) ────── │  (Node.js)  │                     │  (Browser)  │
└─────────────┘                             └─────────────┘                     └─────────────┘
```

**NO USB REQUIRED** - Everything works over network (WiFi, 4G, 5G, Internet).

## Connection

The Android app connects via WebSocket to:
```
ws://<SERVER_HOST>:<PORT>/ws/android/<SESSION_ID>
```
or with TLS:
```
wss://<SERVER_HOST>/ws/android/<SESSION_ID>
```

## Messages: Android → Server (JSON)

### device-info
Sent immediately after connection.
```json
{
  "type": "device-info",
  "data": {
    "model": "Samsung Galaxy S24",
    "manufacturer": "Samsung",
    "androidVersion": "14",
    "sdkVersion": 34,
    "screenWidth": 1080,
    "screenHeight": 2400,
    "battery": 85,
    "networkType": "WiFi"
  }
}
```

### Screen frames (binary)
Send JPEG or H.264 NAL units as **binary WebSocket frames**.
- JPEG: one frame per message, compressed at configured quality
- H.264: encode using MediaCodec, send NAL units

### app-list
Response to `get-apps` command.
```json
{
  "type": "app-list",
  "apps": [
    { "name": "Chrome", "packageName": "com.android.chrome" },
    { "name": "WhatsApp", "packageName": "com.whatsapp" }
  ]
}
```

### file-list-result
```json
{
  "type": "file-list-result",
  "path": "/sdcard/DCIM",
  "files": [
    { "name": "photo.jpg", "isDirectory": false, "size": 2450000 },
    { "name": "Camera", "isDirectory": true, "size": 0 }
  ]
}
```

### file-download-result
```json
{
  "type": "file-download-result",
  "path": "/sdcard/photo.jpg",
  "name": "photo.jpg",
  "data": "<base64-encoded-content>",
  "mimeType": "image/jpeg"
}
```

### file-operation-result
```json
{
  "type": "file-operation-result",
  "success": true,
  "operation": "upload|delete|rename"
}
```

### bandwidth-report
Periodically report network stats.
```json
{
  "type": "bandwidth-report",
  "data": {
    "bytesPerSec": 150000,
    "droppedFrames": 2
  }
}
```

### error
```json
{
  "type": "error",
  "message": "Permission denied"
}
```

## Messages: Server → Android (JSON)

### config
Sent immediately after connection.
```json
{
  "type": "config",
  "streaming": { "fps": 20, "quality": 70, "codec": "h264" }
}
```

### touch
```json
{
  "type": "touch",
  "event": {
    "type": "tap|swipe|scroll|pinch",
    "x": 0.5,
    "y": 0.3,
    "endX": 0.5,
    "endY": 0.7,
    "extra": "up|down|in|out|<duration_ms>",
    "timestamp": 1700000000000
  }
}
```
Coordinates are **normalized (0.0 to 1.0)**. Multiply by screen dimensions.

### text-input
```json
{
  "type": "text-input",
  "text": "Hello world"
}
```
Use AccessibilityService or `input text` to insert.

### system-key
```json
{
  "type": "system-key",
  "key": "back|home|recent|power|volume_up|volume_down|menu|notifications"
}
```

### launch-app
```json
{
  "type": "launch-app",
  "packageName": "com.whatsapp"
}
```

### close-app
```json
{
  "type": "close-app",
  "packageName": "com.whatsapp"
}
```

### get-apps
```json
{ "type": "get-apps" }
```

### file-list
```json
{
  "type": "file-list",
  "path": "/sdcard/DCIM"
}
```

### file-download
```json
{
  "type": "file-download",
  "path": "/sdcard/photo.jpg"
}
```

### file-upload
```json
{
  "type": "file-upload",
  "path": "/sdcard/Download/file.pdf",
  "data": "<base64-encoded-content>"
}
```

### file-delete
```json
{
  "type": "file-delete",
  "path": "/sdcard/old-file.txt"
}
```

### file-rename
```json
{
  "type": "file-rename",
  "oldPath": "/sdcard/old.txt",
  "newPath": "/sdcard/new.txt"
}
```

### set-quality
Dynamically adjust streaming quality.
```json
{
  "type": "set-quality",
  "quality": {
    "fps": 15,
    "jpegQuality": 50,
    "maxHeight": 720
  }
}
```

### viewer-ready / viewer-disconnected
```json
{ "type": "viewer-ready" }
{ "type": "viewer-disconnected" }
```

## Android App Implementation Guide

### Required Permissions
- `MediaProjection` - for screen capture
- `AccessibilityService` - for touch simulation, text input, system actions
- `INTERNET` - for WebSocket connection
- `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` - for file operations

### Screen Capture (MediaProjection + MediaCodec)
1. Request MediaProjection permission
2. Create VirtualDisplay
3. Encode frames using MediaCodec (H.264) or compress to JPEG
4. Send as binary WebSocket frames
5. Adapt quality based on network speed

### Touch Simulation (AccessibilityService)
- `performAction(ACTION_CLICK)` for taps
- `dispatchGesture()` for swipes, scrolls, pinch
- `performGlobalAction()` for back, home, recent, notifications

### Text Input
- Use `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)` on focused field
- Or use `InputConnection` if available

### Keep-Alive
The server sends WebSocket ping every 15 seconds. The Android WebSocket library should respond with pong automatically.

### Reconnection
If the connection drops (mobile network switch, etc.), reconnect automatically with exponential backoff.
