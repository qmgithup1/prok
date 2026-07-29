'use strict';

/**
 * Real verification for the `wss://` app-facing listener added in
 * AMT-VPRO phase 6, Part 3 (`config.js`'s `loadWssConfig`, `server.js`'s
 * `https.createServer(...)` branch).
 *
 * ## Why this test does NOT `require('../src/server.js')`
 * `server.js` unconditionally `require('./wsChannelServer')`, which
 * `require('ws')` at module load time -- and, per
 * `AMT_VPRO_ROADMAP.md`/README.md's already-documented environment
 * constraint (no `registry.npmjs.org` access, carried over unchanged from
 * Part 2), `ws` is not installed here, so *any* import of `server.js` or
 * `wsChannelServer.js` throws `Cannot find module 'ws'` before a single
 * line of this test could run. That is a pre-existing gap, not something
 * this pass introduces or can close without network access.
 *
 * What this test *can* and does verify for real, with zero mocking:
 *   - `config.js`'s `loadWssConfig()` (no `ws` dependency at all) reads
 *     `WSS_TLS_KEY_PATH`/`WSS_TLS_CERT_PATH`/`WSS_PORT` correctly.
 *   - The exact `https.createServer({ key, cert }, requestHandler)`
 *     construction `server.js` uses for the wss branch, built the same
 *     way here, actually completes a real TLS handshake with a real
 *     client (`tls.connect`) and serves a real HTTP response over it --
 *     i.e. the certificate-loading + listener wiring this pass added is
 *     provably correct at the Node builtin level.
 *
 * What this does NOT verify (flagged, not silently skipped): the `ws`
 * package's `WebSocketServer({ server: httpsServer })` upgrade handling
 * on top of that listener -- i.e. a real `wss://` WebSocket handshake and
 * the `channel-open`/binary-pipe protocol from `wsChannelServer.js`. `ws`
 * documents `server` as accepting any `http.Server`-compatible instance
 * (both `http.Server` and `https.Server` emit the same `'upgrade'` event
 * `ws` listens for), and `wsChannelServer.js` itself is completely
 * unaware of which one it's attached to (see its own top doc comment) --
 * so this is a reasonable, but not yet independently run, inference. A
 * real device (or `npm run test:echo` pointed at `wss://` once `ws` is
 * installed) is the next verification step -- see README.md's "Testing"
 * section for exact commands.
 *
 * Run (after generating relay/test-certs/{server.key,server.crt} -- same
 * ones `apfDeviceServer.selftest.js` uses; see README.md):
 *   node test/wssListener.selftest.js
 */

const https = require('https');
const tls = require('tls');
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const CERT_DIR = path.join(__dirname, '..', 'test-certs');
const PORT = 34443;

function log(...args) {
  console.log('[wss-selftest]', ...args);
}

async function main() {
  const keyPath = path.join(CERT_DIR, 'server.key');
  const certPath = path.join(CERT_DIR, 'server.crt');
  assert.ok(fs.existsSync(keyPath) && fs.existsSync(certPath), `Missing ${keyPath}/${certPath} -- see README.md's "Testing" section`);

  // Exactly the construction server.js's wss branch uses.
  const requestHandler = (req, res) => {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
  };
  const httpsServer = https.createServer(
    { key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) },
    requestHandler,
  );

  await new Promise((resolve) => httpsServer.listen(PORT, resolve));
  log(`https listener up on :${PORT}`);

  try {
    await assertRealTlsHandshakeAndHttpResponse();
    log('real TLS handshake + HTTP round trip over the wss listener: OK');

    assertConfigLoading();
    log('config.js loadWssConfig() env parsing: OK');

    console.log('[wss-selftest] ALL CHECKS PASSED');
  } finally {
    httpsServer.close();
  }
}

function assertRealTlsHandshakeAndHttpResponse() {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({ host: '127.0.0.1', port: PORT, rejectUnauthorized: false }, () => {
      assert.ok(socket.authorized === false, 'self-signed cert should not be CA-trusted (rejectUnauthorized:false expected here)');
      socket.write('GET / HTTP/1.1\r\nHost: relay-test\r\nConnection: close\r\n\r\n');
    });
    let buf = '';
    socket.on('data', (chunk) => { buf += chunk.toString('utf8'); });
    socket.on('end', () => {
      try {
        assert.ok(buf.startsWith('HTTP/1.1 404'), `expected a real 404 from the https listener, got: ${buf.slice(0, 80)}`);
        resolve();
      } catch (e) {
        reject(e);
      }
    });
    socket.on('error', reject);
  });
}

function assertConfigLoading() {
  const oldEnv = { ...process.env };
  try {
    process.env.WSS_TLS_KEY_PATH = '/tmp/does-not-need-to-exist.key';
    process.env.WSS_TLS_CERT_PATH = '/tmp/does-not-need-to-exist.crt';
    process.env.WSS_PORT = '9999';
    delete require.cache[require.resolve('../src/config.js')];
    const config = require('../src/config.js');
    assert.strictEqual(config.wss.tlsKeyPath, '/tmp/does-not-need-to-exist.key');
    assert.strictEqual(config.wss.tlsCertPath, '/tmp/does-not-need-to-exist.crt');
    assert.strictEqual(config.wss.port, 9999);
    assert.strictEqual(config.wss.disablePlainWs, false);
  } finally {
    process.env = oldEnv;
    delete require.cache[require.resolve('../src/config.js')];
  }
}

main().catch((err) => {
  console.error('[wss-selftest] FAILED:', err);
  process.exit(1);
});
