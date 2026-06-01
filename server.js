const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const os = require('os');
const WebSocket = require('ws');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' },
  maxHttpBufferSize: 10e6 // 10MB for large frames
});

const PORT = process.env.PORT || 3000;

// Store active sessions
const sessions = new Map();
// Store Android WebSocket connections per session
const androidSockets = new Map();

app.use(express.static(path.join(__dirname, 'public')));
app.use(express.json());

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
    viewerConnected: false
  });

  const host = req.headers.host;
  const protocol = req.protocol;
  const link = `${protocol}://${host}/mobile/${sessionId}`;
  const viewerLink = `${protocol}://${host}/viewer/${sessionId}`;

  res.json({ sessionId, link, viewerLink });
});

// Get all sessions
app.get('/api/sessions', (req, res) => {
  const sessionList = Array.from(sessions.values());
  res.json(sessionList);
});

// Mobile page (opened on Android browser - fallback)
app.get('/mobile/:sessionId', (req, res) => {
  const { sessionId } = req.params;
  if (!sessions.has(sessionId)) {
    return res.status(404).send('Session not found or expired');
  }
  res.sendFile(path.join(__dirname, 'public', 'mobile.html'));
});

// Viewer page (control interface on PC)
app.get('/viewer/:sessionId', (req, res) => {
  const { sessionId } = req.params;
  if (!sessions.has(sessionId)) {
    return res.status(404).send('Session not found or expired');
  }
  res.sendFile(path.join(__dirname, 'public', 'viewer.html'));
});

// Socket.IO for signaling + frame relay to viewers
io.on('connection', (socket) => {
  console.log(`[Socket.IO] Connected: ${socket.id}`);

  socket.on('join-session', ({ sessionId, role }) => {
    socket.join(sessionId);
    socket.sessionId = sessionId;
    socket.role = role;

    const session = sessions.get(sessionId);
    if (!session) return;

    if (role === 'mobile') {
      session.androidConnected = true;
      session.status = 'connected';
      io.to(sessionId).emit('session-update', session);
      console.log(`[Session ${sessionId}] Mobile joined`);
    } else if (role === 'viewer') {
      session.viewerConnected = true;
      io.to(sessionId).emit('session-update', session);
      console.log(`[Session ${sessionId}] Viewer joined`);
      socket.to(sessionId).emit('viewer-ready');
    }
  });

  // WebRTC signaling (fallback browser mode)
  socket.on('offer', ({ sessionId, offer }) => {
    socket.to(sessionId).emit('offer', { offer });
  });

  socket.on('answer', ({ sessionId, answer }) => {
    socket.to(sessionId).emit('answer', { answer });
  });

  socket.on('ice-candidate', ({ sessionId, candidate }) => {
    socket.to(sessionId).emit('ice-candidate', { candidate });
  });

  // Touch events from viewer -> relay to Android via WebSocket
  socket.on('touch-event', ({ sessionId, event }) => {
    // Forward to native Android WebSocket client
    const androidWs = androidSockets.get(sessionId);
    if (androidWs && androidWs.readyState === WebSocket.OPEN) {
      androidWs.send(JSON.stringify({ type: 'touch', event }));
    }
    // Also relay via Socket.IO (browser fallback)
    socket.to(sessionId).emit('touch-event', { event });
  });

  socket.on('disconnect', () => {
    const { sessionId, role } = socket;
    if (sessionId && sessions.has(sessionId)) {
      const session = sessions.get(sessionId);
      if (role === 'mobile') {
        session.androidConnected = false;
        session.status = 'waiting';
      } else if (role === 'viewer') {
        session.viewerConnected = false;
      }
      io.to(sessionId).emit('session-update', session);
    }
    console.log(`[Socket.IO] Disconnected: ${socket.id}`);
  });
});

// ===== Raw WebSocket server for native Android app =====
const wss = new WebSocket.Server({ noServer: true });

server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url, `http://${request.headers.host}`);
  
  // Only handle /ws/android/:sessionId
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
  console.log(`[Android WS] Connected for session: ${sessionId}`);

  androidSockets.set(sessionId, ws);

  // Update session state
  const session = sessions.get(sessionId);
  if (session) {
    session.androidConnected = true;
    session.status = 'streaming';
    io.to(sessionId).emit('session-update', session);
  }

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      // Binary data = JPEG frame from MediaProjection
      // Convert to base64 and relay to viewers via Socket.IO
      const base64Frame = data.toString('base64');
      io.to(sessionId).emit('screen-frame', {
        frame: base64Frame,
        timestamp: Date.now()
      });
    } else {
      // Text data = JSON messages (status, info, etc.)
      try {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'info') {
          console.log(`[Android] ${sessionId}: ${msg.message}`);
        }
      } catch (e) {
        // ignore
      }
    }
  });

  ws.on('close', () => {
    console.log(`[Android WS] Disconnected: ${sessionId}`);
    androidSockets.delete(sessionId);
    const session = sessions.get(sessionId);
    if (session) {
      session.androidConnected = false;
      session.status = 'waiting';
      io.to(sessionId).emit('session-update', session);
    }
  });

  ws.on('error', (err) => {
    console.error(`[Android WS] Error: ${err.message}`);
  });
});

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
  console.log(`\n========================================`);
  console.log(`  Android Remote Control Server`);
  console.log(`========================================`);
  console.log(`  Dashboard:  http://localhost:${PORT}`);
  console.log(`  Network:    http://${localIP}:${PORT}`);
  console.log(`  Android WS: ws://${localIP}:${PORT}/ws/android/<sessionId>`);
  console.log(`========================================`);
  console.log(`\n  1. Open dashboard, create a session`);
  console.log(`  2. Configure Android app with session ID`);
  console.log(`  3. Open viewer to see the screen\n`);
});
