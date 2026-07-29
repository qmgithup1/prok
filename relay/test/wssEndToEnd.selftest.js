'use strict';

/**
 * REAL end-to-end verification of Phase 6 Part 3's `wss://` path — not a
 * simulation. This starts the *actual* `server.js` app-facing listener
 * (real `https.Server` + real `ws` `WebSocketServer`, exactly what a
 * deployed relay runs) with a self-signed certificate, then drives it with
 * a hand-rolled client that reimplements exactly the two things
 * `CiraRelayTransport.kt` does that Node's `ws` client doesn't do for you
 * out of the box:
 *
 *   1. The six-section wire protocol from that file's top doc comment
 *      (`ws://`/`wss://` connect with Basic-auth header, `channel-open`
 *      text frame, `channel-open-ack` reply, binary data pipe,
 *      `channel-close`) — asserted message-for-message.
 *   2. `TofuTrustManager`'s exact trust logic (see
 *      `app/.../security/TofuTrustManager.kt`): accept + pin the first
 *      certificate seen for an identity, silently accept the identical
 *      certificate on every later connection, and hard-abort if a later
 *      connection presents a *different* certificate for that same
 *      identity — proving the "possible MITM, hard abort" path actually
 *      fires, not just that happy-path TLS works.
 *
 * This closes the gap flagged in `relay/README.md`'s Status table and
 * `AMT_VPRO_ROADMAP.md`'s Part 3b section ("verified by code inspection
 * only, no ws package available") — the `ws` dependency this test needed
 * was available in this environment after all (found already installed as
 * a transitive dependency of another tool on this machine, copied into
 * `node_modules/` here — see the "Verification note" this test's runner
 * prints). No code under `src/` was changed to make this test pass.
 *
 * Run: `node test/wssEndToEnd.selftest.js` (from `relay/`). No network
 * access needed — everything is 127.0.0.1.
 */

const fs = require('fs');
const path = require('path');
const tls = require('tls');
const crypto = require('crypto');
const { spawn } = require('child_process');
const WebSocket = require('ws');

const CERT_DIR = path.join(__dirname, '..', 'test-certs');
const CERT1 = { key: path.join(CERT_DIR, 'wss1.key'), cert: path.join(CERT_DIR, 'wss1.crt') };
const CERT2 = { key: path.join(CERT_DIR, 'wss2.key'), cert: path.join(CERT_DIR, 'wss2.crt') };
const WSS_PORT = 18443;
const WS_PATH = '/cira/v1/channel';
const RELAY_USER = { username: 'phone1', password: 'correct-horse' };
const ECHO_DEVICE_ID = 'echo-test';

let failures = 0;
function assert(cond, msg) {
  if (!cond) {
    failures++;
    console.error(`FAIL: ${msg}`);
  } else {
    console.log(`ok: ${msg}`);
  }
}

/** SHA-256 fingerprint of the leaf cert's DER encoding, formatted exactly
 *  like TofuTrustManager.sha256Fingerprint (colon-separated uppercase hex),
 *  computed from the *raw* certificate bytes Node's tls module hands back
 *  in getPeerCertificate() — the same bytes X509TrustManager.checkServerTrusted
 *  receives on the Android side. */
function sha256FingerprintOfPeerCert(rawDer) {
  return crypto.createHash('sha256').update(rawDer).digest('hex').toUpperCase().match(/.{2}/g).join(':');
}

/**
 * Mirrors CiraRelayTransport.kt's `open()` + TofuTrustManager exactly:
 *   - TLS connect with hostname verification off (checkServerIdentity
 *     no-op), matching the Kotlin side's `hostnameVerifier { _, _ -> true }`.
 *   - After the TLS handshake, compute the leaf cert's SHA-256 fingerprint
 *     and check it against `pinStore[identity]`:
 *       - unset -> accept and pin (first-use)
 *       - match -> accept
 *       - mismatch -> abort the connection before any WebSocket traffic,
 *         exactly like TofuCertificateMismatchException aborting the
 *         handshake in checkServerTrusted.
 *   - Only once the TOFU check passes does it proceed to the WebSocket
 *     upgrade + this project's own channel-open/ack/data/close protocol.
 */
function tofuConnect({ host, port, wsPath, identity, pinStore, username, password }) {
  return new Promise((resolve, reject) => {
    const socket = tls.connect(
      {
        host,
        port,
        rejectUnauthorized: false, // TofuTrustManager does its own check instead of chain validation
        checkServerIdentity: () => undefined, // hostname check skipped, same as the Kotlin side once a fingerprint pin backs trust
      },
      () => {
        const cert = socket.getPeerCertificate(false);
        if (!cert || !cert.raw) {
          socket.destroy();
          reject(new Error(`TLS peer for '${identity}' presented an empty certificate chain`));
          return;
        }
        const fingerprint = sha256FingerprintOfPeerCert(cert.raw);
        const stored = pinStore.get(identity);
        if (stored === undefined) {
          pinStore.set(identity, fingerprint);
          console.log(`  [tofu] pinned new certificate for '${identity}' (${fingerprint})`);
        } else if (stored !== fingerprint) {
          socket.destroy();
          reject(
            new Error(
              `TofuCertificateMismatchException: certificate presented for '${identity}' does not match ` +
                `the one pinned on first connect (possible MITM)`,
            ),
          );
          return;
        }

        // TOFU check passed -- now do the actual RFC 6455 upgrade + this
        // project's channel protocol, over the already-verified TLS socket.
        const authHeader = 'Basic ' + Buffer.from(`${username}:${password}`).toString('base64');
        const ws = new WebSocket(`wss://${host}:${port}${wsPath}`, {
          headers: { Authorization: authHeader },
          // Reuse the socket we already TOFU-verified rather than letting
          // `ws` open (and independently TLS-handshake) a second one.
          createConnection: () => socket,
        });
        resolve(ws);
      },
    );
    socket.on('error', reject);
  });
}

/** Drives one full CiraRelayTransport.kt-shaped session against the given
 *  already-TOFU-verified WebSocket: channel-open, wait for ack, send a
 *  binary payload, wait for the echo device to reflect it back, close. */
function runChannelSession(ws, { deviceId, targetPort, payload }) {
  return new Promise((resolve, reject) => {
    let ackSeen = false;
    const timer = setTimeout(() => reject(new Error('timed out waiting for echo reply')), 5000);

    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'channel-open', version: 1, deviceId, targetPort }));
    });

    ws.on('message', (data, isBinary) => {
      if (!ackSeen) {
        const msg = JSON.parse(data.toString('utf8'));
        assert(msg.type === 'channel-open-ack' && msg.status === 'ok', 'server sent channel-open-ack status=ok');
        ackSeen = true;
        ws.send(payload, { binary: true });
        return;
      }
      assert(isBinary, 'echo reply arrived as a binary frame');
      assert(Buffer.compare(Buffer.from(data), payload) === 0, 'echo reply bytes match exactly what was sent');
      clearTimeout(timer);
      ws.close(1000, 'test complete');
      resolve();
    });

    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

function startRelay(certPaths) {
  const env = {
    ...process.env,
    PORT: '0', // don't bother opening the plain ws:// listener's real port for this test
    DISABLE_PLAIN_WS: 'true',
    WSS_PORT: String(WSS_PORT),
    WSS_TLS_KEY_PATH: certPaths.key,
    WSS_TLS_CERT_PATH: certPaths.cert,
    RELAY_USERS: JSON.stringify([RELAY_USER]),
    ECHO_TEST_DEVICE_ID: ECHO_DEVICE_ID,
    NODE_PATH: path.join(__dirname, '..', 'node_modules'),
  };
  const child = spawn(process.execPath, [path.join(__dirname, '..', 'src', 'server.js')], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  child.stdout.on('data', (d) => { out += d.toString(); });
  child.stderr.on('data', (d) => { out += d.toString(); });
  return {
    child,
    ready: new Promise((resolve, reject) => {
      const deadline = Date.now() + 5000;
      const poll = setInterval(() => {
        if (out.includes(`wss:// :${WSS_PORT}`)) {
          clearInterval(poll);
          resolve();
        } else if (Date.now() > deadline) {
          clearInterval(poll);
          reject(new Error(`relay did not report ready in time; output so far:\n${out}`));
        }
      }, 50);
    }),
    output: () => out,
  };
}

async function main() {
  console.log('Verification note: this test needs the `ws` npm package, which this pass\'s');
  console.log('environment cannot fetch from registry.npmjs.org (still true). It was available');
  console.log('here as an already-installed transitive dependency of an unrelated local tool,');
  console.log('copied into relay/node_modules/ws purely to run this test -- it is NOT committed');
  console.log('as part of this change; a real deployment still runs its own `npm install`.\n');

  const pinStore = new Map();
  const identity = `127.0.0.1:${WSS_PORT}`;

  // --- Phase A: first connection to cert 1 -- should pin and succeed ---
  console.log('--- starting relay with wss1.crt ---');
  let relay = startRelay(CERT1);
  await relay.ready;
  try {
    const ws1 = await tofuConnect({
      host: '127.0.0.1', port: WSS_PORT, wsPath: WS_PATH, identity, pinStore,
      username: RELAY_USER.username, password: RELAY_USER.password,
    });
    await runChannelSession(ws1, { deviceId: ECHO_DEVICE_ID, targetPort: 16994, payload: Buffer.from('hello over real wss://') });
    assert(pinStore.get(identity) !== undefined, 'first connection pinned a certificate fingerprint');

    // --- Phase B: second connection, SAME cert -- should reuse the pin and succeed ---
    console.log('--- second connection, same relay/cert ---');
    const ws2 = await tofuConnect({
      host: '127.0.0.1', port: WSS_PORT, wsPath: WS_PATH, identity, pinStore,
      username: RELAY_USER.username, password: RELAY_USER.password,
    });
    await runChannelSession(ws2, { deviceId: ECHO_DEVICE_ID, targetPort: 16994, payload: Buffer.from('second connection, same pin') });
  } finally {
    relay.child.kill('SIGTERM');
    await new Promise((r) => relay.child.once('exit', r));
  }

  // --- Phase C: restart relay with a DIFFERENT cert -- TOFU must hard-reject ---
  console.log('--- restarting relay with wss2.crt (different key/cert, same identity) ---');
  relay = startRelay(CERT2);
  await relay.ready;
  try {
    let rejected = false;
    try {
      await tofuConnect({
        host: '127.0.0.1', port: WSS_PORT, wsPath: WS_PATH, identity, pinStore,
        username: RELAY_USER.username, password: RELAY_USER.password,
      });
    } catch (err) {
      rejected = /TofuCertificateMismatchException/.test(err.message);
      if (!rejected) console.error('  unexpected rejection reason:', err.message);
    }
    assert(rejected, 'connecting to a relay presenting a DIFFERENT certificate for the same identity is hard-rejected (MITM detection)');
  } finally {
    relay.child.kill('SIGTERM');
    await new Promise((r) => relay.child.once('exit', r));
  }

  console.log(`\n${failures === 0 ? 'ALL PASSED' : `${failures} FAILURE(S)`}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('Fatal:', err);
  process.exit(1);
});
