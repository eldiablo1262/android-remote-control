const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const os = require('os');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' }
});

const PORT = process.env.PORT || 3000;

// Store active sessions
const sessions = new Map();

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

// Mobile page (opened on Android)
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

// WebRTC Signaling via Socket.IO
io.on('connection', (socket) => {
  console.log(`[Socket] Connected: ${socket.id}`);

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
      // Notify mobile to start streaming
      socket.to(sessionId).emit('viewer-ready');
    }
  });

  // WebRTC signaling
  socket.on('offer', ({ sessionId, offer }) => {
    socket.to(sessionId).emit('offer', { offer });
  });

  socket.on('answer', ({ sessionId, answer }) => {
    socket.to(sessionId).emit('answer', { answer });
  });

  socket.on('ice-candidate', ({ sessionId, candidate }) => {
    socket.to(sessionId).emit('ice-candidate', { candidate });
  });

  // Touch events from viewer to mobile
  socket.on('touch-event', ({ sessionId, event }) => {
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
    console.log(`[Socket] Disconnected: ${socket.id}`);
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
  console.log(`  Local:   http://localhost:${PORT}`);
  console.log(`  Network: http://${localIP}:${PORT}`);
  console.log(`========================================`);
  console.log(`\n  Open the dashboard to generate links!`);
  console.log(`  Send the mobile link to your Android.\n`);
});
