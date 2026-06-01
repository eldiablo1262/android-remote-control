/**
 * Android Remote Control - Test Client (Simulates Android app over network)
 * 
 * This is a TEST/DEBUG tool that simulates the Android app connecting
 * via WebSocket over the network (WiFi/4G/Internet).
 * 
 * For production use, install the Android companion app which connects
 * via the same WebSocket protocol over WiFi/4G.
 * 
 * Usage: node android-client.js <SERVER_URL> <SESSION_ID>
 * Example: node android-client.js ws://your-server.com:3000 abc-123-def
 * 
 * NO USB REQUIRED - connects over network only.
 */

const WebSocket = require('ws');

const SERVER_URL = process.argv[2];
const SESSION_ID = process.argv[3];

if (!SERVER_URL || !SESSION_ID) {
  console.log('');
  console.log('=== Android Remote Control - Test Client ===');
  console.log('');
  console.log('Usage: node android-client.js <SERVER_URL> <SESSION_ID>');
  console.log('');
  console.log('Examples:');
  console.log('  node android-client.js ws://localhost:3000 my-session-id');
  console.log('  node android-client.js ws://192.168.1.50:3000 my-session-id');
  console.log('  node android-client.js wss://my-server.render.com my-session-id');
  console.log('');
  console.log('This tool simulates the Android app for testing purposes.');
  console.log('In production, use the Android companion app which connects');
  console.log('via the same protocol over WiFi or 4G (no USB needed).');
  console.log('');
  process.exit(1);
}

const WS_URL = `${SERVER_URL}/ws/android/${SESSION_ID}`;
let ws = null;
let connected = false;

function connect() {
  console.log(`\n🔌 Connecting to: ${WS_URL}`);
  console.log('   (Network mode - no USB)');

  ws = new WebSocket(WS_URL);

  ws.on('open', () => {
    connected = true;
    console.log('✅ Connected to server via network!');
    console.log('📡 Ready to receive commands...\n');

    // Send device info (simulated)
    ws.send(JSON.stringify({
      type: 'device-info',
      data: {
        model: 'Test Device (Node.js client)',
        manufacturer: 'Debug',
        androidVersion: '14',
        sdkVersion: 34,
        screenWidth: 1080,
        screenHeight: 2400,
        battery: 85,
        networkType: 'WiFi'
      }
    }));

    ws.send(JSON.stringify({ type: 'info', message: 'Test client connected via network' }));
  });

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      handleCommand(msg);
    } catch (e) {
      // Binary data or parse error
    }
  });

  ws.on('close', () => {
    connected = false;
    console.log('❌ Disconnected. Reconnecting in 3s...');
    setTimeout(connect, 3000);
  });

  ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
    connected = false;
  });

  ws.on('ping', () => {
    // Keep-alive response (automatic with ws library)
  });
}

function handleCommand(msg) {
  switch (msg.type) {
    case 'config':
      console.log(`⚙️  Config received: FPS=${msg.streaming?.fps}, Quality=${msg.streaming?.quality}`);
      break;

    case 'touch':
      const e = msg.event;
      console.log(`👆 Touch: ${e.type} at (${(e.x*100).toFixed(0)}%, ${(e.y*100).toFixed(0)}%)`);
      break;

    case 'text-input':
      console.log(`⌨️  Text input: "${msg.text}"`);
      break;

    case 'system-key':
      console.log(`🔑 System key: ${msg.key}`);
      break;

    case 'launch-app':
      console.log(`🚀 Launch app: ${msg.packageName}`);
      break;

    case 'close-app':
      console.log(`❌ Close app: ${msg.packageName}`);
      break;

    case 'get-apps':
      console.log('📱 App list requested');
      // Send sample app list
      ws.send(JSON.stringify({
        type: 'app-list',
        apps: [
          { name: 'Chrome', packageName: 'com.android.chrome' },
          { name: 'YouTube', packageName: 'com.google.android.youtube' },
          { name: 'WhatsApp', packageName: 'com.whatsapp' },
          { name: 'Camera', packageName: 'com.android.camera' },
          { name: 'Settings', packageName: 'com.android.settings' },
          { name: 'Gallery', packageName: 'com.android.gallery3d' },
          { name: 'Maps', packageName: 'com.google.android.apps.maps' },
          { name: 'Gmail', packageName: 'com.google.android.gm' }
        ]
      }));
      break;

    case 'file-list':
      console.log(`📂 File list: ${msg.path}`);
      // Send sample file list
      ws.send(JSON.stringify({
        type: 'file-list-result',
        path: msg.path,
        files: [
          { name: 'DCIM', isDirectory: true, size: 0 },
          { name: 'Download', isDirectory: true, size: 0 },
          { name: 'Documents', isDirectory: true, size: 0 },
          { name: 'Music', isDirectory: true, size: 0 },
          { name: 'Pictures', isDirectory: true, size: 0 },
          { name: 'screenshot.png', isDirectory: false, size: 2450000 },
          { name: 'notes.txt', isDirectory: false, size: 1240 },
          { name: 'video.mp4', isDirectory: false, size: 15600000 }
        ]
      }));
      break;

    case 'file-download':
      console.log(`⬇️  Download: ${msg.path}`);
      ws.send(JSON.stringify({
        type: 'file-download-result',
        path: msg.path,
        name: msg.path.split('/').pop(),
        data: Buffer.from('Sample file content for testing').toString('base64'),
        mimeType: 'text/plain'
      }));
      break;

    case 'file-upload':
      console.log(`⬆️  Upload: ${msg.path} (${msg.data?.length || 0} bytes base64)`);
      ws.send(JSON.stringify({ type: 'file-operation-result', success: true, operation: 'upload' }));
      break;

    case 'file-delete':
      console.log(`🗑️  Delete: ${msg.path}`);
      ws.send(JSON.stringify({ type: 'file-operation-result', success: true, operation: 'delete' }));
      break;

    case 'file-rename':
      console.log(`✏️  Rename: ${msg.oldPath} -> ${msg.newPath}`);
      ws.send(JSON.stringify({ type: 'file-operation-result', success: true, operation: 'rename' }));
      break;

    case 'set-quality':
      console.log(`🎚️  Quality: FPS=${msg.quality?.fps}, JPEG=${msg.quality?.jpegQuality}%, Resolution=${msg.quality?.maxHeight}p`);
      break;

    case 'viewer-ready':
      console.log('👁️  Viewer connected - starting simulated stream...');
      startSimulatedStream();
      break;

    case 'viewer-disconnected':
      console.log('👁️  Viewer disconnected');
      break;

    default:
      console.log(`❓ Unknown command: ${msg.type}`, msg);
  }
}

// Simulate screen streaming (sends a colored JPEG frame)
let streamInterval = null;
function startSimulatedStream() {
  if (streamInterval) return;
  console.log('📡 Simulated stream started (sending test frames)');

  // Create a simple 1x1 pixel JPEG as test frame
  // In production, the Android app sends real screen captures
  const testJpeg = Buffer.from(
    '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof' +
    'Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwh' +
    'MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAAR' +
    'CAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA' +
    'AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK' +
    'FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG' +
    'h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl' +
    '5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA' +
    'AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYk' +
    'NOEl8RcYI4Q/RFhHaFJyeyg0NTY3ODk6QkNERUZHSElKUlNUVVZXWFlaYmNkZWZnaGlqcnN0' +
    'dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU' +
    '1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD3+gD/2Q==',
    'base64'
  );

  streamInterval = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(testJpeg);
    }
  }, 100); // ~10 FPS for test
}

// Handle Ctrl+C
process.on('SIGINT', () => {
  console.log('\n\n🛑 Stopping...');
  if (streamInterval) clearInterval(streamInterval);
  if (ws) ws.close();
  process.exit(0);
});

// Start
console.log('=== Android Remote Control - Network Test Client ===');
console.log('Mode: WiFi / 4G / Internet (NO USB)');
connect();
