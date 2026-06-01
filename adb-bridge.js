/**
 * ADB Bridge - Captures Android screen via ADB and sends to the server.
 * No app installation needed on the phone, just USB debugging enabled.
 * 
 * Usage: node adb-bridge.js <SESSION_ID> [PORT]
 * Example: node adb-bridge.js abc-123-def 3000
 * 
 * Prerequisites:
 *   1. ADB installed (Android SDK Platform Tools)
 *   2. USB Debugging enabled on Android
 *   3. Phone connected via USB (or wireless ADB)
 *   4. Server running (npm start)
 */

const { execSync, spawn } = require('child_process');
const WebSocket = require('ws');

const SESSION_ID = process.argv[2];
const PORT = process.argv[3] || '3000';
const SERVER = `ws://localhost:${PORT}/ws/android/${SESSION_ID}`;
const FPS = 10;
const QUALITY = 40;

if (!SESSION_ID) {
  console.log('Usage: node adb-bridge.js <SESSION_ID> [PORT]');
  console.log('');
  console.log('Steps:');
  console.log('  1. Enable USB Debugging on your Android');
  console.log('  2. Connect phone via USB');
  console.log('  3. Run: npm start (in another terminal)');
  console.log('  4. Create a session on the dashboard');
  console.log('  5. Run: node adb-bridge.js <session-id>');
  process.exit(1);
}

// Check ADB is available
try {
  const devices = execSync('adb devices', { encoding: 'utf-8' });
  const lines = devices.trim().split('\n').filter(l => l.includes('device') && !l.includes('List'));
  if (lines.length === 0) {
    console.error('❌ No Android device found. Check USB connection and debugging.');
    console.log('');
    console.log('To enable USB Debugging:');
    console.log('  Settings > About Phone > Tap "Build Number" 7 times');
    console.log('  Settings > Developer Options > USB Debugging > ON');
    process.exit(1);
  }
  console.log(`✅ Device found: ${lines[0].split('\t')[0]}`);
} catch (e) {
  console.error('❌ ADB not found. Install Android SDK Platform Tools.');
  console.error('   Download: https://developer.android.com/tools/releases/platform-tools');
  process.exit(1);
}

// Get screen size
let screenWidth = 1080;
let screenHeight = 2340;
try {
  const size = execSync('adb shell wm size', { encoding: 'utf-8' });
  const match = size.match(/(\d+)x(\d+)/);
  if (match) {
    screenWidth = parseInt(match[1]);
    screenHeight = parseInt(match[2]);
  }
  console.log(`📱 Screen: ${screenWidth}x${screenHeight}`);
} catch (e) {}

// Connect WebSocket
let ws = null;
let capturing = false;
let frameCount = 0;
let lastFpsTime = Date.now();

function connect() {
  console.log(`\n🔌 Connecting to: ${SERVER}`);
  ws = new WebSocket(SERVER);

  ws.on('open', () => {
    console.log('✅ Connected to server!');
    console.log('📡 Streaming started... (Ctrl+C to stop)\n');
    ws.send(JSON.stringify({ type: 'info', message: 'ADB bridge connected' }));
    capturing = true;
    captureLoop();
  });

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      if (msg.type === 'touch') {
        handleTouch(msg.event);
      }
    } catch (e) {}
  });

  ws.on('close', () => {
    console.log('❌ Disconnected. Reconnecting in 3s...');
    capturing = false;
    setTimeout(connect, 3000);
  });

  ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
    capturing = false;
    setTimeout(connect, 3000);
  });
}

function captureLoop() {
  if (!capturing) return;

  try {
    // Capture screen as PNG via ADB, convert to smaller JPEG-like format
    const rawPng = execSync('adb exec-out screencap -p', { 
      maxBuffer: 10 * 1024 * 1024,
      timeout: 5000 
    });

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(rawPng);
      frameCount++;

      // FPS display
      const now = Date.now();
      if (now - lastFpsTime >= 2000) {
        const fps = (frameCount / ((now - lastFpsTime) / 1000)).toFixed(1);
        process.stdout.write(`\r📊 FPS: ${fps} | Frames: ${frameCount}    `);
        frameCount = 0;
        lastFpsTime = now;
      }
    }
  } catch (e) {
    if (e.message.includes('device not found') || e.message.includes('no devices')) {
      console.error('\n❌ Device disconnected!');
      capturing = false;
      return;
    }
  }

  setTimeout(captureLoop, 1000 / FPS);
}

function handleTouch(event) {
  const { type, x, y, endX, endY, extra } = event;
  const px = Math.round(x * screenWidth);
  const py = Math.round(y * screenHeight);

  try {
    switch (type) {
      case 'tap':
        exec(`adb shell input tap ${px} ${py}`);
        break;
      case 'swipe':
        const epx = Math.round(endX * screenWidth);
        const epy = Math.round(endY * screenHeight);
        exec(`adb shell input swipe ${px} ${py} ${epx} ${epy} 300`);
        break;
      case 'scroll':
        const scrollDist = Math.round(screenHeight * 0.25);
        if (extra === 'down') {
          exec(`adb shell input swipe ${px} ${py} ${px} ${py - scrollDist} 200`);
        } else {
          exec(`adb shell input swipe ${px} ${py} ${px} ${py + scrollDist} 200`);
        }
        break;
      case 'key':
        switch (extra) {
          case 'back': exec('adb shell input keyevent 4'); break;
          case 'home': exec('adb shell input keyevent 3'); break;
          case 'recent': exec('adb shell input keyevent 187'); break;
        }
        break;
    }
  } catch (e) {
    // ignore touch errors
  }
}

function exec(cmd) {
  require('child_process').exec(cmd);
}

// Handle Ctrl+C
process.on('SIGINT', () => {
  console.log('\n\n🛑 Stopping...');
  capturing = false;
  if (ws) ws.close();
  process.exit(0);
});

// Start
console.log('=== Android Remote Control (ADB Bridge) ===');
connect();
