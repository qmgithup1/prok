'use strict';

/**
 * Not a unit test framework — a small standalone script exercising the
 * exact same handshake CiraRelayTransport.kt performs, against the
 * relay's echo-test device (see deviceRegistry.js), so the app-facing
 * protocol can be validated end-to-end with `node src/server.js` running
 * in one terminal and `npm run test:echo` in another, no AMT hardware or
 * Android app required.
 *
 * Env vars mirror config.js: RELAY_HOST/RELAY_PORT/WS_PATH/RELAY_USER/
 * RELAY_PASS, defaulting to a local unauthenticated relay on the default
 * port.
 */
const WebSocket = require('ws');

const host = process.env.RELAY_HOST || '127.0.0.1';
const port = process.env.RELAY_PORT || '8787';
const wsPath = process.env.WS_PATH || '/cira/v1/channel';
const deviceId = process.env.ECHO_TEST_DEVICE_ID || 'echo-test';
const user = process.env.RELAY_USER;
const pass = process.env.RELAY_PASS;

const headers = {};
if (user || pass) {
  headers['Authorization'] = 'Basic ' + Buffer.from(`${user || ''}:${pass || ''}`).toString('base64');
}

const ws = new WebSocket(`ws://${host}:${port}${wsPath}`, { headers });

let acked = false;
const testPayload = Buffer.from('hello from echoClient.js\n', 'utf8');

ws.on('open', () => {
  console.log('[echoClient] connected, sending channel-open');
  ws.send(JSON.stringify({ type: 'channel-open', version: 1, deviceId, targetPort: 16994 }));
});

ws.on('message', (data, isBinary) => {
  if (!acked) {
    const msg = JSON.parse(data.toString('utf8'));
    if (msg.type !== 'channel-open-ack') {
      console.error('[echoClient] FAIL: expected channel-open-ack, got', msg);
      process.exit(1);
    }
    if (msg.status !== 'ok') {
      console.error('[echoClient] FAIL: relay rejected channel-open:', msg.message);
      process.exit(1);
    }
    acked = true;
    console.log('[echoClient] channel-open-ack ok, sending test payload');
    ws.send(testPayload, { binary: true });
    return;
  }

  if (!isBinary) {
    console.log('[echoClient] got unexpected text frame:', data.toString('utf8'));
    return;
  }
  const echoed = Buffer.isBuffer(data) ? data : Buffer.from(data);
  if (echoed.equals(testPayload)) {
    console.log('[echoClient] PASS: payload echoed back correctly');
    ws.close(1000);
    process.exit(0);
  } else {
    console.error('[echoClient] FAIL: echoed payload did not match. Got:', echoed);
    process.exit(1);
  }
});

ws.on('error', (err) => {
  console.error('[echoClient] FAIL: WebSocket error:', err.message);
  process.exit(1);
});

setTimeout(() => {
  console.error('[echoClient] FAIL: timed out waiting for echo');
  process.exit(1);
}, 8000).unref();
