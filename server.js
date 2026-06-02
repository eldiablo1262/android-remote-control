const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const os = require('os');
const fs = require('fs');
const WebSocket = require('ws');

const app = express();

const PORT = process.env.PORT || 3000;

// On Render/cloud: they handle HTTPS. Locally: just HTTP (use ngrok/cloudflare for HTTPS)
const server = http.createServer(app);

const io = new Server(server, {
  cors: { origin: '*' },
  maxHttpBufferSize: 50e6 // 50MB for file transfers
});

// Store active sessions
const sessions = new Map();
// Store Android WebSocket connections per session
const androidSockets = new Map();

app.use(express.static(path.join(__dirname, 'public')));
app.use(express.json({ limit: '50mb' }));

// Main dashboard
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'dashboard.html'));
});

// Generate a new session link
app.post('/api/create-session', (req, res) => {
  const sessionId = uuidv4();
  sessions.set(sessionId, {
    id: sessionId,
    created: Date.now(),
    status: 'waiting', // waiting, connected, streaming
    androidConnected: false,
    viewerConnected: false,
    deviceInfo: null
  });

  const host = req.headers.host;
  const protocol = req.protocol;
  const viewerLink = `${protocol}://${host}/viewer/${sessionId}`;
  const wsLink = `ws://${host}/ws/android/${sessionId}`;

  res.json({ sessionId, viewerLink, wsLink });
});

// Get all sessions
app.get('/api/sessions', (req, res) => {
  const sessionList = Array.from(sessions.values());
  res.json(sessionList);
});

// Mobile setup page (shows WebSocket URL for Android app)
app.get('/mobile/:sessionId', (req, res) => {
  const { sessionId } = req.params;
  if (!sessions.has(sessionId)) {
    return res.status(404).send('Session not found or expired');
  }
  res.sendFile(path.join(__dirname, 'public', 'mobile.html'));
});

// APK download - proxy from GitHub to avoid Play Protect flagging external redirects
app.get('/download/apk', async (req, res) => {
  try {
    const https = require('https');
    const apkUrl = 'https://github.com/eldiablo1262/android-remote-control/releases/download/latest/remote-control.apk';
    
    // Follow redirects and stream the APK binary directly
    const fetchApk = (url) => {
      return new Promise((resolve, reject) => {
        https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, (response) => {
          if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
            fetchApk(response.headers.location).then(resolve).catch(reject);
          } else if (response.statusCode === 200) {
            resolve(response);
          } else {
            reject(new Error('Failed: ' + response.statusCode));
          }
        }).on('error', reject);
      });
    };

    const apkStream = await fetchApk(apkUrl);
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Disposition', 'attachment; filename="RemoteControl.apk"');
    if (apkStream.headers['content-length']) {
      res.setHeader('Content-Length', apkStream.headers['content-length']);
    }
    apkStream.pipe(res);
  } catch (err) {
    console.error('APK proxy error:', err.message);
    res.redirect('https://github.com/eldiablo1262/android-remote-control/releases/download/latest/remote-control.apk');
  }
});

// Viewer page (control interface on PC)
app.get('/viewer/:sessionId', (req, res) => {
  const { sessionId } = req.params;
  if (!sessions.has(sessionId)) {
    return res.status(404).send('Session not found or expired');
  }
  res.sendFile(path.join(__dirname, 'public', 'viewer.html'));
});

// Socket.IO for viewers (PC browser)
io.on('connection', (socket) => {
  console.log(`[Viewer] Connected: ${socket.id}`);

  socket.on('join-session', ({ sessionId, role }) => {
    socket.join(sessionId);
    socket.sessionId = sessionId;
    socket.role = role;

    const session = sessions.get(sessionId);
    if (!session) return;

    if (role === 'viewer') {
      session.viewerConnected = true;
      io.to(sessionId).emit('session-update', session);
      console.log(`[Session ${sessionId}] Viewer joined`);
      // Notify Android that viewer is ready
      const androidWs = androidSockets.get(sessionId);
      if (androidWs && androidWs.readyState === WebSocket.OPEN) {
        androidWs.send(JSON.stringify({ type: 'viewer-ready' }));
      }
    } else if (role === 'mobile') {
      session.androidConnected = true;
      session.status = 'connected';
      io.to(sessionId).emit('session-update', session);
      console.log(`[Session ${sessionId}] Mobile browser joined`);
    }
  });

  // Frames from mobile browser (getDisplayMedia capture)
  socket.on('browser-frame', ({ sessionId, frame, timestamp }) => {
    const session = sessions.get(sessionId);
    if (session && !session.status !== 'streaming') {
      session.status = 'streaming';
      session.androidConnected = true;
    }
    // Relay to viewers
    socket.to(sessionId).emit('screen-frame', {
      frame,
      timestamp,
      size: frame.length
    });
  });

  // ===== Commands from Viewer -> Android =====

  // Touch/gesture events
  socket.on('touch-event', ({ sessionId, event }) => {
    sendToAndroid(sessionId, { type: 'touch', event });
  });

  // Text input
  socket.on('text-input', ({ sessionId, text }) => {
    sendToAndroid(sessionId, { type: 'text-input', text });
  });

  // System key
  socket.on('system-key', ({ sessionId, key }) => {
    sendToAndroid(sessionId, { type: 'system-key', key });
  });

  // Launch app
  socket.on('launch-app', ({ sessionId, packageName }) => {
    sendToAndroid(sessionId, { type: 'launch-app', packageName });
  });

  // Close app
  socket.on('close-app', ({ sessionId, packageName }) => {
    sendToAndroid(sessionId, { type: 'close-app', packageName });
  });

  // Request app list
  socket.on('get-apps', ({ sessionId }) => {
    sendToAndroid(sessionId, { type: 'get-apps' });
  });

  // File operations
  socket.on('file-list', ({ sessionId, dirPath }) => {
    sendToAndroid(sessionId, { type: 'file-list', path: dirPath });
  });

  socket.on('file-download', ({ sessionId, filePath }) => {
    sendToAndroid(sessionId, { type: 'file-download', path: filePath });
  });

  socket.on('file-upload', ({ sessionId, filePath, data }) => {
    sendToAndroid(sessionId, { type: 'file-upload', path: filePath, data });
  });

  socket.on('file-delete', ({ sessionId, filePath }) => {
    sendToAndroid(sessionId, { type: 'file-delete', path: filePath });
  });

  socket.on('file-rename', ({ sessionId, oldPath, newPath }) => {
    sendToAndroid(sessionId, { type: 'file-rename', oldPath, newPath });
  });

  // Stream quality control
  socket.on('set-quality', ({ sessionId, quality }) => {
    sendToAndroid(sessionId, { type: 'set-quality', quality });
  });

  // Request keyframe (H.264)
  socket.on('request-keyframe', ({ sessionId }) => {
    sendToAndroid(sessionId, { type: 'request-keyframe' });
  });

  socket.on('disconnect', () => {
    const { sessionId, role } = socket;
    if (sessionId && sessions.has(sessionId)) {
      const session = sessions.get(sessionId);
      if (role === 'viewer') {
        session.viewerConnected = false;
        sendToAndroid(sessionId, { type: 'viewer-disconnected' });
      } else if (role === 'mobile') {
        session.androidConnected = false;
        session.status = 'waiting';
      }
      io.to(sessionId).emit('session-update', session);
    }
    console.log(`[Socket.IO] Disconnected: ${socket.id} (${socket.role || 'unknown'})`);
  });
});

// Helper: send JSON to Android WebSocket
function sendToAndroid(sessionId, msg) {
  const androidWs = androidSockets.get(sessionId);
  if (androidWs && androidWs.readyState === WebSocket.OPEN) {
    androidWs.send(JSON.stringify(msg));
  }
}

// ===== Raw WebSocket for Android app (connects over WiFi/4G/Internet) =====
const wss = new WebSocket.Server({ noServer: true });

server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url, `http://${request.headers.host}`);

  // Handle /ws/android/:sessionId
  const match = url.pathname.match(/^\/ws\/android\/(.+)$/);
  if (match) {
    const sessionId = match[1];
    if (!sessions.has(sessionId)) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      ws.sessionId = sessionId;
      wss.emit('connection', ws, request);
    });
  } else if (!url.pathname.startsWith('/socket.io')) {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
  }
});

wss.on('connection', (ws) => {
  const sessionId = ws.sessionId;
  console.log(`[Android] Connected for session: ${sessionId}`);

  androidSockets.set(sessionId, ws);

  // Update session state
  const session = sessions.get(sessionId);
  if (session) {
    session.androidConnected = true;
    session.status = 'streaming';
    io.to(sessionId).emit('session-update', session);
  }

  // Send config to Android
  ws.send(JSON.stringify({
    type: 'config',
    streaming: { fps: 20, quality: 70, codec: 'h264' }
  }));

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      // Binary data = H.264 encoded video stream
      // Packet format: [flags(1)] + [timestamp(8)] + [h264 NAL data]
      if (data.length > 9) {
        const flags = data[0];
        const isKeyframe = (flags & 0x01) !== 0;
        // Relay binary H.264 data directly to viewers
        io.to(sessionId).emit('h264-data', {
          data: data.toString('base64'),
          keyframe: isKeyframe,
          size: data.length
        });
      } else {
        // Small binary = likely JPEG frame (legacy)
        const base64Frame = data.toString('base64');
        io.to(sessionId).emit('screen-frame', {
          frame: base64Frame,
          timestamp: Date.now(),
          size: data.length
        });
      }
    } else {
      // Text data = JSON messages from Android
      try {
        const msg = JSON.parse(data.toString());
        handleAndroidMessage(sessionId, msg);
      } catch (e) {
        // ignore
      }
    }
  });

  ws.on('close', () => {
    console.log(`[Android] Disconnected: ${sessionId}`);
    androidSockets.delete(sessionId);
    const session = sessions.get(sessionId);
    if (session) {
      session.androidConnected = false;
      session.status = 'waiting';
      session.deviceInfo = null;
      io.to(sessionId).emit('session-update', session);
    }
  });

  ws.on('error', (err) => {
    console.error(`[Android] Error: ${err.message}`);
  });

  // Ping/pong keep-alive for mobile networks
  const pingInterval = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.ping();
    } else {
      clearInterval(pingInterval);
    }
  }, 15000);

  ws.on('close', () => clearInterval(pingInterval));
});

// Handle JSON messages from Android
function handleAndroidMessage(sessionId, msg) {
  switch (msg.type) {
    case 'device-info':
      const session = sessions.get(sessionId);
      if (session) {
        session.deviceInfo = msg.data;
        io.to(sessionId).emit('session-update', session);
      }
      io.to(sessionId).emit('device-info', msg.data);
      console.log(`[Android] ${sessionId} device: ${msg.data.model} (${msg.data.androidVersion})`);
      break;

    case 'app-list':
      io.to(sessionId).emit('app-list', msg.apps);
      break;

    case 'file-list-result':
      io.to(sessionId).emit('file-list-result', { path: msg.path, files: msg.files });
      break;

    case 'file-download-result':
      io.to(sessionId).emit('file-download-result', {
        path: msg.path,
        name: msg.name,
        data: msg.data,
        mimeType: msg.mimeType
      });
      break;

    case 'file-operation-result':
      io.to(sessionId).emit('file-operation-result', msg);
      break;

    case 'error':
      io.to(sessionId).emit('android-error', { message: msg.message });
      console.log(`[Android] ${sessionId} error: ${msg.message}`);
      break;

    case 'info':
      console.log(`[Android] ${sessionId}: ${msg.message}`);
      break;

    case 'bandwidth-report':
      io.to(sessionId).emit('bandwidth-report', msg.data);
      break;

    case 'stream-config':
      // H.264 stream configuration (codec, resolution, fps, bitrate)
      io.to(sessionId).emit('stream-config', msg);
      console.log(`[Android] ${sessionId} stream: ${msg.codec} ${msg.width}x${msg.height} @ ${msg.fps}fps`);
      break;

    case 'codec-data':
      // H.264 SPS/PPS initialization data
      io.to(sessionId).emit('codec-data', msg);
      console.log(`[Android] ${sessionId} codec data received (SPS+PPS)`);
      break;

    case 'frame':
      // Legacy JPEG frame (JSON with base64)
      io.to(sessionId).emit('screen-frame', {
        frame: msg.frame,
        timestamp: msg.timestamp,
        width: msg.width,
        height: msg.height,
        size: msg.frame ? msg.frame.length : 0
      });
      break;

    default:
      // Forward unknown messages to viewers
      io.to(sessionId).emit('android-message', msg);
  }
}

// Get local IP for network access
function getLocalIP() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return 'localhost';
}

server.listen(PORT, '0.0.0.0', () => {
  const localIP = getLocalIP();
  const isCloud = !!process.env.RENDER || !!process.env.RAILWAY_STATIC_URL || !!process.env.HEROKU_APP_NAME;
  console.log(`\n=============================================`);
  console.log(`  Android Remote Control Server`);
  console.log(`=============================================`);
  if (isCloud) {
    console.log(`  Mode: CLOUD (HTTPS provided by platform)`);
    console.log(`  Port: ${PORT}`);
  } else {
    console.log(`  Dashboard:    http://localhost:${PORT}`);
    console.log(`  LAN:          http://${localIP}:${PORT}`);
    console.log(`  WebSocket:    ws://${localIP}:${PORT}/ws/android/<sessionId>`);
  }
  console.log(`=============================================`);
  console.log(`  WiFi / 4G / Internet — NO USB`);
  console.log(`=============================================\n`);
});
