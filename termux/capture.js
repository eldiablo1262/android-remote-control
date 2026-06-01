/**
 * Termux Screen Capture & WebSocket Client
 * 
 * Usage: node capture.js <SERVER_IP> <PORT> <SESSION_ID>
 * Example: node capture.js 192.168.1.100 3000 abc-123-def
 * 
 * Requirements:
 *   - Termux with termux-api installed (pkg install termux-api)
 *   - Or root access for screencap
 */

const WebSocket = require('ws');
const { execSync, exec } = require('child_process');
const fs = require('fs');
const path = require('path');

const SERVER_IP = process.argv[2];
const PORT = process.argv[3] || '3000';
const SESSION_ID = process.argv[4];

if (!SERVER_IP || !SESSION_ID) {
  console.log('Usage: node capture.js <SERVER_IP> <PORT> <SESSION_ID>');
  console.log('Example: node capture.js 192.168.1.100 3000 my-session-id');
  process.exit(1);
}

const WS_URL = `ws://${SERVER_IP}:${PORT}/ws/android/${SESSION_ID}`;
const CAPTURE_INTERVAL = 100; // ms (~10 FPS)
const TEMP_FILE = '/data/data/com.termux/files/home/screen.jpg';

let ws = null;
let capturing = false;

function connect() {
  console.log(`Connecting to: ${WS_URL}`);
  
  ws = new WebSocket(WS_URL);

  ws.on('open', () => {
    console.log('✅ Connected to server!');
    ws.send(JSON.stringify({ type: 'info', message: 'Termux client connected' }));
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
  });
}

function captureLoop() {
  if (!capturing) return;

  try {
    // Method 1: screencap (works on most Android, needs shell access)
    execSync(`screencap -p ${TEMP_FILE}.png`, { timeout: 5000 });
    // Convert to JPEG for smaller size
    execSync(`convert ${TEMP_FILE}.png -quality 40 -resize 720x ${TEMP_FILE}`, { timeout: 5000 });
    
    const frameData = fs.readFileSync(TEMP_FILE);
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(frameData);
    }
  } catch (e) {
    // Method 2: Try with termux-api screenshot (if convert not available)
    try {
      execSync(`screencap -p > ${TEMP_FILE}.png`, { timeout: 5000, shell: true });
      const frameData = fs.readFileSync(`${TEMP_FILE}.png`);
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(frameData);
      }
    } catch (e2) {
      console.error('Capture failed:', e2.message);
      console.log('Tip: Make sure Termux has storage permission');
    }
  }

  setTimeout(captureLoop, CAPTURE_INTERVAL);
}

function handleTouch(event) {
  // Simulate touches using input command (requires root or adb)
  const { type, x, y, endX, endY, extra } = event;
  
  // Get screen dimensions
  let width = 1080, height = 2340;
  try {
    const size = execSync('wm size').toString();
    const match = size.match(/(\d+)x(\d+)/);
    if (match) {
      width = parseInt(match[1]);
      height = parseInt(match[2]);
    }
  } catch (e) {}

  const px = Math.round(x * width);
  const py = Math.round(y * height);

  try {
    switch (type) {
      case 'tap':
        execSync(`input tap ${px} ${py}`);
        console.log(`Tap: ${px}, ${py}`);
        break;
      case 'swipe':
        const epx = Math.round(endX * width);
        const epy = Math.round(endY * height);
        execSync(`input swipe ${px} ${py} ${epx} ${epy} 300`);
        console.log(`Swipe: ${px},${py} -> ${epx},${epy}`);
        break;
      case 'key':
        switch (extra) {
          case 'back': execSync('input keyevent 4'); break;
          case 'home': execSync('input keyevent 3'); break;
          case 'recent': execSync('input keyevent 187'); break;
        }
        console.log(`Key: ${extra}`);
        break;
    }
  } catch (e) {
    console.error('Touch simulation failed (needs root/adb):', e.message);
  }
}

// Start
console.log('=== Android Remote Control (Termux) ===');
console.log(`Server: ${WS_URL}`);
console.log('');
connect();
