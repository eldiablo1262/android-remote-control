/**
 * Termux Screen Capture & WebSocket Client
 * 
 * Connects to the remote control server over WiFi/4G (NO USB).
 * Run this directly on the Android device in Termux.
 * 
 * Usage: node capture.js <SERVER_URL> <SESSION_ID>
 * Example: node capture.js ws://my-server.com:3000 abc-123-def
 * Example: node capture.js ws://192.168.1.100:3000 abc-123-def
 * 
 * Requirements:
 *   - Termux with termux-api installed (pkg install termux-api)
 *   - Root access for screencap + input commands
 *   - Network connection (WiFi or 4G)
 */

const WebSocket = require('ws');
const { execSync, exec } = require('child_process');
const fs = require('fs');

const SERVER_URL = process.argv[2];
const SESSION_ID = process.argv[3];

if (!SERVER_URL || !SESSION_ID) {
  console.log('');
  console.log('=== Android Remote Control (Termux) ===');
  console.log('Connects over WiFi/4G — NO USB required');
  console.log('');
  console.log('Usage: node capture.js <SERVER_URL> <SESSION_ID>');
  console.log('');
  console.log('Examples:');
  console.log('  node capture.js ws://192.168.1.100:3000 my-session-id');
  console.log('  node capture.js ws://my-server.render.com my-session-id');
  console.log('  node capture.js wss://remote.example.com my-session-id');
  console.log('');
  process.exit(1);
}

const WS_URL = `${SERVER_URL}/ws/android/${SESSION_ID}`;
const TEMP_FILE = '/data/data/com.termux/files/home/screen';
let ws = null;
let capturing = false;
let config = { fps: 15, quality: 50, maxHeight: 720 };

// Get screen dimensions
let screenWidth = 1080, screenHeight = 2340;
try {
  const size = execSync('wm size', { encoding: 'utf-8' });
  const match = size.match(/(\d+)x(\d+)/);
  if (match) {
    screenWidth = parseInt(match[1]);
    screenHeight = parseInt(match[2]);
  }
} catch (e) {}

function connect() {
  console.log(`🔌 Connecting to: ${WS_URL}`);
  console.log('   Mode: WiFi/4G (no USB)');

  ws = new WebSocket(WS_URL);

  ws.on('open', () => {
    console.log('✅ Connected to server via network!');

    // Send device info
    ws.send(JSON.stringify({
      type: 'device-info',
      data: {
        model: getDeviceModel(),
        manufacturer: 'Android (Termux)',
        androidVersion: getAndroidVersion(),
        sdkVersion: 0,
        screenWidth,
        screenHeight,
        battery: getBattery(),
        networkType: getNetworkType()
      }
    }));

    ws.send(JSON.stringify({ type: 'info', message: 'Termux client connected via network' }));
    capturing = true;
    captureLoop();
  });

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      handleCommand(msg);
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
    // Capture screen as PNG then convert to JPEG
    execSync(`screencap -p ${TEMP_FILE}.png`, { timeout: 5000 });

    // Try to convert to smaller JPEG (if ImageMagick available)
    try {
      execSync(`convert ${TEMP_FILE}.png -quality ${config.quality} -resize ${config.maxHeight}x ${TEMP_FILE}.jpg`, { timeout: 5000 });
      const frameData = fs.readFileSync(`${TEMP_FILE}.jpg`);
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(frameData);
    } catch (e) {
      // Fallback: send raw PNG
      const frameData = fs.readFileSync(`${TEMP_FILE}.png`);
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(frameData);
    }
  } catch (e) {
    console.error('Capture failed:', e.message);
  }

  setTimeout(captureLoop, 1000 / config.fps);
}

function handleCommand(msg) {
  switch (msg.type) {
    case 'config':
      if (msg.streaming) {
        config.fps = msg.streaming.fps || config.fps;
        config.quality = msg.streaming.quality || config.quality;
      }
      console.log(`⚙️  Config: FPS=${config.fps}, Quality=${config.quality}`);
      break;

    case 'touch':
      handleTouch(msg.event);
      break;

    case 'text-input':
      try {
        const escaped = msg.text.replace(/'/g, "\\'").replace(/ /g, '%s');
        execSync(`input text '${escaped}'`);
        console.log(`⌨️  Text: "${msg.text}"`);
      } catch (e) { console.error('Text input failed:', e.message); }
      break;

    case 'system-key':
      handleSystemKey(msg.key);
      break;

    case 'launch-app':
      try {
        execSync(`monkey -p ${msg.packageName} -c android.intent.category.LAUNCHER 1`);
        console.log(`🚀 Launch: ${msg.packageName}`);
      } catch (e) { console.error('Launch failed:', e.message); }
      break;

    case 'close-app':
      try {
        execSync(`am force-stop ${msg.packageName}`);
        console.log(`❌ Close: ${msg.packageName}`);
      } catch (e) { console.error('Close failed:', e.message); }
      break;

    case 'get-apps':
      try {
        const output = execSync('pm list packages -3', { encoding: 'utf-8' });
        const apps = output.trim().split('\n')
          .map(l => l.replace('package:', ''))
          .map(pkg => ({ name: pkg.split('.').pop(), packageName: pkg }));
        ws.send(JSON.stringify({ type: 'app-list', apps }));
      } catch (e) { console.error('App list failed:', e.message); }
      break;

    case 'file-list':
      handleFileList(msg.path);
      break;

    case 'file-download':
      handleFileDownload(msg.path);
      break;

    case 'file-upload':
      handleFileUpload(msg.path, msg.data);
      break;

    case 'file-delete':
      try {
        execSync(`rm -rf "${msg.path}"`);
        ws.send(JSON.stringify({ type: 'file-operation-result', success: true, operation: 'delete' }));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'file-operation-result', success: false, error: e.message }));
      }
      break;

    case 'file-rename':
      try {
        execSync(`mv "${msg.oldPath}" "${msg.newPath}"`);
        ws.send(JSON.stringify({ type: 'file-operation-result', success: true, operation: 'rename' }));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'file-operation-result', success: false, error: e.message }));
      }
      break;

    case 'set-quality':
      if (msg.quality) {
        config.fps = msg.quality.fps || config.fps;
        config.quality = msg.quality.jpegQuality || config.quality;
        config.maxHeight = msg.quality.maxHeight || config.maxHeight;
      }
      console.log(`🎚️  Quality updated: FPS=${config.fps}, JPEG=${config.quality}%, Res=${config.maxHeight}p`);
      break;

    case 'viewer-ready':
      console.log('👁️  Viewer connected');
      break;
  }
}

function handleTouch(event) {
  const { type, x, y, endX, endY, extra } = event;
  const px = Math.round(x * screenWidth);
  const py = Math.round(y * screenHeight);

  try {
    switch (type) {
      case 'tap':
        execSync(`input tap ${px} ${py}`);
        break;
      case 'swipe':
        const epx = Math.round(endX * screenWidth);
        const epy = Math.round(endY * screenHeight);
        const duration = typeof extra === 'number' ? extra : 300;
        execSync(`input swipe ${px} ${py} ${epx} ${epy} ${duration}`);
        break;
      case 'scroll':
        const dist = Math.round(screenHeight * 0.25);
        if (extra === 'down') {
          execSync(`input swipe ${px} ${py} ${px} ${py - dist} 200`);
        } else {
          execSync(`input swipe ${px} ${py} ${px} ${py + dist} 200`);
        }
        break;
      case 'pinch':
        // Pinch zoom simulation
        const cx = screenWidth / 2, cy = screenHeight / 2;
        if (extra === 'in') {
          execSync(`input swipe ${cx - 200} ${cy} ${cx - 50} ${cy} 300 & input swipe ${cx + 200} ${cy} ${cx + 50} ${cy} 300`);
        } else {
          execSync(`input swipe ${cx - 50} ${cy} ${cx - 200} ${cy} 300 & input swipe ${cx + 50} ${cy} ${cx + 200} ${cy} 300`);
        }
        break;
    }
  } catch (e) {
    console.error('Touch failed (needs root):', e.message);
  }
}

function handleSystemKey(key) {
  const keyMap = {
    'back': 4, 'home': 3, 'recent': 187,
    'power': 26, 'volume_up': 24, 'volume_down': 25,
    'menu': 82, 'notifications': 'cmd statusbar expand-notifications'
  };
  try {
    if (key === 'notifications') {
      execSync('cmd statusbar expand-notifications');
    } else if (keyMap[key]) {
      execSync(`input keyevent ${keyMap[key]}`);
    }
    console.log(`🔑 Key: ${key}`);
  } catch (e) { console.error('Key failed:', e.message); }
}

function handleFileList(dirPath) {
  try {
    const output = execSync(`ls -la "${dirPath}" 2>/dev/null || ls "${dirPath}"`, { encoding: 'utf-8' });
    const files = [];
    const lines = output.trim().split('\n');
    for (const line of lines) {
      if (line.startsWith('total') || !line.trim()) continue;
      const parts = line.trim().split(/\s+/);
      if (parts.length >= 8) {
        const name = parts.slice(7).join(' ');
        const isDir = line.startsWith('d');
        const size = parseInt(parts[4]) || 0;
        if (name !== '.' && name !== '..') files.push({ name, isDirectory: isDir, size });
      } else if (parts.length === 1) {
        // Simple ls output
        const name = parts[0];
        try {
          const stat = execSync(`stat -c '%s %F' "${dirPath}/${name}"`, { encoding: 'utf-8' }).trim();
          const [size, type] = stat.split(' ');
          files.push({ name, isDirectory: type.includes('directory'), size: parseInt(size) || 0 });
        } catch (e2) {
          files.push({ name, isDirectory: false, size: 0 });
        }
      }
    }
    ws.send(JSON.stringify({ type: 'file-list-result', path: dirPath, files }));
  } catch (e) {
    ws.send(JSON.stringify({ type: 'error', message: `Cannot list ${dirPath}: ${e.message}` }));
  }
}

function handleFileDownload(filePath) {
  try {
    const data = fs.readFileSync(filePath);
    const name = filePath.split('/').pop();
    ws.send(JSON.stringify({
      type: 'file-download-result',
      path: filePath,
      name,
      data: data.toString('base64'),
      mimeType: 'application/octet-stream'
    }));
  } catch (e) {
    ws.send(JSON.stringify({ type: 'error', message: `Download failed: ${e.message}` }));
  }
}

function handleFileUpload(filePath, base64Data) {
  try {
    const buffer = Buffer.from(base64Data, 'base64');
    fs.writeFileSync(filePath, buffer);
    ws.send(JSON.stringify({ type: 'file-operation-result', success: true, operation: 'upload' }));
    console.log(`⬆️  Uploaded: ${filePath} (${buffer.length} bytes)`);
  } catch (e) {
    ws.send(JSON.stringify({ type: 'file-operation-result', success: false, error: e.message }));
  }
}

// Device info helpers
function getDeviceModel() {
  try { return execSync('getprop ro.product.model', { encoding: 'utf-8' }).trim(); }
  catch (e) { return 'Unknown'; }
}
function getAndroidVersion() {
  try { return execSync('getprop ro.build.version.release', { encoding: 'utf-8' }).trim(); }
  catch (e) { return '?'; }
}
function getBattery() {
  try {
    const out = execSync('cat /sys/class/power_supply/battery/capacity', { encoding: 'utf-8' });
    return parseInt(out.trim());
  } catch (e) { return -1; }
}
function getNetworkType() {
  try {
    const wifi = execSync('dumpsys wifi | grep "Wi-Fi is"', { encoding: 'utf-8' });
    return wifi.includes('enabled') ? 'WiFi' : '4G';
  } catch (e) { return 'Unknown'; }
}

// Handle Ctrl+C
process.on('SIGINT', () => {
  console.log('\n🛑 Stopping...');
  capturing = false;
  if (ws) ws.close();
  process.exit(0);
});

// Start
console.log('=== Android Remote Control (Termux) ===');
console.log(`Mode: WiFi/4G/Internet (NO USB)`);
console.log(`Screen: ${screenWidth}x${screenHeight}`);
connect();
