'use strict';

/**
 * Ad-hoc verification script for apfDeviceServer.js / apfProtocol.js /
 * apfTrustStore.js, written because this pass's environment has no
 * outbound network access to `npm install` the `ws` package that
 * wsChannelServer.js/server.js need — but apfDeviceServer.js and its
 * dependencies use *only* Node builtins (`tls`, `crypto`, `events`), so
 * this test drives them directly with no external deps at all.
 *
 * It plays the *device* role over a raw `tls` socket by hand-encoding the
 * same APF frames apfProtocol.js decodes, against a real `ApfDeviceServer`
 * listening on localhost — i.e. this exercises the actual production
 * classes, not a mock.
 *
 * Run: `node test/apfDeviceServer.selftest.js` (after generating
 * relay/test-certs/{server,device}.{crt,key} — see the two `openssl req`
 * commands in the PR/commit notes, or README.md's "Testing" section).
 */

const tls = require('tls');
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const apf = require('../src/apfProtocol');
const { ApfDeviceServer } = require('../src/apfDeviceServer');
const { ApfTrustStore, sha256Fingerprint } = require('../src/apfTrustStore');

const CERT_DIR = path.join(__dirname, '..', 'test-certs');
const PORT = 34433;
const DEVICE_ID = 'test-device-01';
const USERNAME = 'test-device-01-user';
const PASSWORD = 'correct-horse-battery-staple';

function log(...args) {
  console.log('[selftest]', ...args);
}

async function main() {
  const serverKey = fs.readFileSync(path.join(CERT_DIR, 'server.key'));
  const serverCert = fs.readFileSync(path.join(CERT_DIR, 'server.crt'));
  const deviceKey = fs.readFileSync(path.join(CERT_DIR, 'device.key'));
  const deviceCert = fs.readFileSync(path.join(CERT_DIR, 'device.crt'));

  const deviceCertFingerprint = sha256Fingerprint(
    new (require('crypto').X509Certificate)(deviceCert).raw,
  );
  log('device cert fingerprint:', deviceCertFingerprint);

  const trustStore = new ApfTrustStore([
    { username: USERNAME, password: PASSWORD, deviceId: DEVICE_ID, certSha256: deviceCertFingerprint },
  ]);

  const server = new ApfDeviceServer({
    tlsOptions: { key: serverKey, cert: serverCert },
    trustStore,
    keepaliveIntervalMs: 60000, // long enough to not fire during this test
    keepaliveTimeoutMs: 5000,
    channelOpenTimeoutMs: 4000,
    log: (level, msg) => log(`[server:${level}]`, msg),
  });

  let onlineResolve;
  const onlinePromise = new Promise((r) => (onlineResolve = r));
  server.on('device-online', (id) => {
    if (id === DEVICE_ID) onlineResolve();
  });

  await server.listen(PORT, '127.0.0.1');
  log(`server listening on 127.0.0.1:${PORT}`);

  // ---- Device side: raw TLS socket, hand-encoded APF frames ----
  const deviceSocket = tls.connect({
    host: '127.0.0.1',
    port: PORT,
    key: deviceKey,
    cert: deviceCert,
    rejectUnauthorized: false, // self-signed relay test cert
  });

  let recvBuf = Buffer.alloc(0);
  const deviceMessageQueue = [];
  const waiters = [];
  deviceSocket.on('data', (chunk) => {
    recvBuf = Buffer.concat([recvBuf, chunk]);
    const { messages, bytesConsumed } = apf.decodeAll(recvBuf);
    recvBuf = recvBuf.subarray(bytesConsumed);
    for (const m of messages) {
      log('device <- server:', m.name);
      if (waiters.length) waiters.shift()(m);
      else deviceMessageQueue.push(m);
    }
  });

  function waitForDeviceMessage() {
    if (deviceMessageQueue.length) return Promise.resolve(deviceMessageQueue.shift());
    return new Promise((resolve) => waiters.push(resolve));
  }

  function deviceSend(buf) {
    deviceSocket.write(buf);
  }

  function u32(v) {
    const b = Buffer.alloc(4);
    b.writeUInt32BE(v >>> 0, 0);
    return b;
  }
  function str(s) {
    const b = Buffer.from(s, 'utf8');
    return Buffer.concat([u32(b.length), b]);
  }

  await new Promise((resolve, reject) => {
    deviceSocket.once('secureConnect', resolve);
    deviceSocket.once('error', reject);
  });
  log('device: TLS connected');

  // 1. Our own APF_PROTOCOLVERSION (content doesn't matter to the server
  //    in this pass -- see apfDeviceServer.js).
  const reserved = Buffer.alloc(64 - (4 + 4 + 4 + 16), 0);
  deviceSend(
    Buffer.concat([Buffer.from([apf.MSG.APF_PROTOCOLVERSION]), u32(1), u32(0), u32(0), Buffer.alloc(16, 1), reserved]),
  );

  // Server should also have sent us its own APF_PROTOCOLVERSION by now;
  // drain it (order-independent, don't assert position).
  const first = await waitForDeviceMessage();
  assert.strictEqual(first.name, 'APF_PROTOCOLVERSION', 'expected server APF_PROTOCOLVERSION first');

  // 2. APF_SERVICE_REQUEST "auth@amt.intel.com"
  deviceSend(Buffer.concat([Buffer.from([apf.MSG.APF_SERVICE_REQUEST]), str('auth@amt.intel.com')]));
  const serviceAccept = await waitForDeviceMessage();
  assert.strictEqual(serviceAccept.name, 'APF_SERVICE_ACCEPT');
  assert.strictEqual(serviceAccept.serviceName, 'auth@amt.intel.com');

  // 3. APF_USERAUTH_REQUEST (password method)
  deviceSend(
    Buffer.concat([
      Buffer.from([apf.MSG.APF_USERAUTH_REQUEST]),
      str(USERNAME),
      str('auth@amt.intel.com'),
      str('password'),
      Buffer.from([0]),
      str(PASSWORD),
    ]),
  );
  const authResult = await waitForDeviceMessage();
  assert.strictEqual(authResult.name, 'APF_USERAUTH_SUCCESS', `expected auth success, got ${authResult.name}`);
  log('device: authenticated OK');

  await onlinePromise;
  assert.strictEqual(server.isDeviceOnline(DEVICE_ID), true);

  // 4. Second APF_SERVICE_REQUEST "pfwd@amt.intel.com"
  deviceSend(Buffer.concat([Buffer.from([apf.MSG.APF_SERVICE_REQUEST]), str('pfwd@amt.intel.com')]));
  const pfwdAccept = await waitForDeviceMessage();
  assert.strictEqual(pfwdAccept.name, 'APF_SERVICE_ACCEPT');
  assert.strictEqual(pfwdAccept.serviceName, 'pfwd@amt.intel.com');

  // 5. APF_GLOBAL_REQUEST tcpip-forward for port 16994 (IDE-R/SOL/KVM redir)
  deviceSend(
    Buffer.concat([
      Buffer.from([apf.MSG.APF_GLOBAL_REQUEST]),
      str('tcpip-forward'),
      Buffer.from([1]),
      str('0.0.0.0'),
      u32(16994),
    ]),
  );
  const fwdResult = await waitForDeviceMessage();
  assert.strictEqual(fwdResult.name, 'APF_REQUEST_SUCCESS');
  log('device: tcpip-forward acknowledged for port 16994');

  // ---- Now drive the *relay* side: ask ApfDeviceServer to open a channel ----
  const channelPromise = server.openChannel(DEVICE_ID, 16994);

  const channelOpen = await waitForDeviceMessage();
  assert.strictEqual(channelOpen.name, 'APF_CHANNEL_OPEN');
  assert.strictEqual(channelOpen.chanType, 'forwarded-tcpip');
  assert.strictEqual(channelOpen.portConnected, 16994);
  log('device: received APF_CHANNEL_OPEN, sender_channel(relay)=', channelOpen.senderChannel);

  const deviceLocalChannelId = 777; // arbitrary "device's own" channel id
  deviceSend(
    Buffer.concat([
      Buffer.from([apf.MSG.APF_CHANNEL_OPEN_CONFIRMATION]),
      u32(channelOpen.senderChannel), // recipient_channel = relay's id, echoed back
      u32(deviceLocalChannelId), // sender_channel = device's own id for this channel
      u32(128 * 1024),
      u32(32 * 1024),
    ]),
  );

  const channel = await channelPromise;
  log('relay: channel open confirmed, testing data flow...');

  const relayToDevicePayload = Buffer.from('hello device, this is the relay');
  channel.write(relayToDevicePayload);
  const dataFromRelay = await waitForDeviceMessage();
  assert.strictEqual(dataFromRelay.name, 'APF_CHANNEL_DATA');
  assert.strictEqual(dataFromRelay.recipientChannel, deviceLocalChannelId);
  assert.ok(dataFromRelay.data.equals(relayToDevicePayload), 'relay->device payload mismatch');
  log('relay -> device data OK');

  const deviceToRelayPayload = Buffer.from('hello relay, this is the device');
  const gotDataPromise = new Promise((resolve) => channel.once('data', resolve));
  deviceSend(
    Buffer.concat([Buffer.from([apf.MSG.APF_CHANNEL_DATA]), u32(channelOpen.senderChannel), str(deviceToRelayPayload)]),
  );
  const gotData = await gotDataPromise;
  assert.ok(gotData.equals(deviceToRelayPayload), 'device->relay payload mismatch');
  log('device -> relay data OK');

  const gotClosePromise = new Promise((resolve) => channel.once('close', resolve));
  deviceSend(Buffer.concat([Buffer.from([apf.MSG.APF_CHANNEL_CLOSE]), u32(channelOpen.senderChannel)]));
  await gotClosePromise;
  log('device-initiated channel close OK');

  // ---- Negative case: wrong password must be rejected ----
  const badSocket = tls.connect({ host: '127.0.0.1', port: PORT, key: deviceKey, cert: deviceCert, rejectUnauthorized: false });
  await new Promise((resolve, reject) => {
    badSocket.once('secureConnect', resolve);
    badSocket.once('error', reject);
  });
  let badBuf = Buffer.alloc(0);
  const badWaiters = [];
  const badQueue = [];
  badSocket.on('data', (chunk) => {
    badBuf = Buffer.concat([badBuf, chunk]);
    const { messages, bytesConsumed } = apf.decodeAll(badBuf);
    badBuf = badBuf.subarray(bytesConsumed);
    for (const m of messages) {
      if (badWaiters.length) badWaiters.shift()(m);
      else badQueue.push(m);
    }
  });
  const waitBad = () => (badQueue.length ? Promise.resolve(badQueue.shift()) : new Promise((r) => badWaiters.push(r)));
  badSocket.write(
    Buffer.concat([Buffer.from([apf.MSG.APF_PROTOCOLVERSION]), u32(1), u32(0), u32(0), Buffer.alloc(16, 1), reserved]),
  );
  await waitBad(); // server's own APF_PROTOCOLVERSION
  badSocket.write(Buffer.concat([Buffer.from([apf.MSG.APF_SERVICE_REQUEST]), str('auth@amt.intel.com')]));
  await waitBad(); // APF_SERVICE_ACCEPT
  badSocket.write(
    Buffer.concat([
      Buffer.from([apf.MSG.APF_USERAUTH_REQUEST]),
      str(USERNAME),
      str('auth@amt.intel.com'),
      str('password'),
      Buffer.from([0]),
      str('totally-wrong-password'),
    ]),
  );
  const badAuthResult = await waitBad();
  assert.strictEqual(badAuthResult.name, 'APF_USERAUTH_FAILURE', 'wrong password should be rejected');
  log('negative case: wrong password correctly rejected');
  badSocket.destroy();

  deviceSocket.destroy();
  await server.close();
  log('ALL CHECKS PASSED');
  process.exit(0);
}

main().catch((err) => {
  console.error('[selftest] FAILED:', err);
  process.exit(1);
});
