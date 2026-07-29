'use strict';

/**
 * Config is env-driven on purpose — this component is meant to be dropped
 * into a container/systemd unit/whatever the deployer prefers, not to carry
 * its own config-file format. See README.md for the full list with
 * defaults and examples.
 *
 * RELAY_USERS: the credential(s) this relay accepts on the app-facing
 * WebSocket handshake (CiraRelayTransport's Basic-auth header — see that
 * file's doc comment). This is deliberately a *relay* credential, unrelated
 * to any AMT device's own WS-Man/redirection-port password or to whatever
 * secret a device presents on its device-facing APF connection (that
 * device-side auth is Part 2's concern, not this file's).
 *
 * Format: JSON array of {"username": "...", "password": "..."}, e.g.
 *   RELAY_USERS='[{"username":"phone1","password":"correct-horse"}]'
 * Empty/unset means the relay accepts every app connection with no
 * Authorization check at all — fine for local dev against the echo-test
 * device (see deviceRegistry.js), never for anything reachable off a
 * trusted network.
 */
function loadRelayUsers() {
  const raw = process.env.RELAY_USERS;
  if (!raw || raw.trim() === '') return [];
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    throw new Error(`RELAY_USERS is set but isn't valid JSON: ${e.message}`);
  }
  if (!Array.isArray(parsed)) {
    throw new Error('RELAY_USERS must be a JSON array of {"username","password"} objects');
  }
  for (const entry of parsed) {
    if (typeof entry.username !== 'string' || typeof entry.password !== 'string') {
      throw new Error('Every RELAY_USERS entry needs string "username" and "password"');
    }
  }
  return parsed;
}

/**
 * Device-facing APF server config. All of it is required together for a
 * "real devices" deployment (`APF_TLS_PORT` set); with `APF_TLS_PORT`
 * unset, server.js falls back to `EchoDeviceRegistry` exactly as before
 * this pass, so existing local-dev/echo-test setups keep working
 * unchanged.
 */
function loadApfConfig() {
  const tlsPort = process.env.APF_TLS_PORT ? parseInt(process.env.APF_TLS_PORT, 10) : null;
  return {
    tlsPort,
    tlsHost: process.env.APF_TLS_HOST || '0.0.0.0',
    // The relay's own TLS server certificate/key -- what devices see and
    // must be provisioned (out-of-band, on the AMT side) to connect to.
    // Unrelated to the app-facing wss:// certificate (Part 2 concern -- see
    // README.md).
    tlsKeyPath: process.env.APF_TLS_KEY_PATH || null,
    tlsCertPath: process.env.APF_TLS_CERT_PATH || null,
    // See apfTrustStore.js for the exact allowlist entry shape and why
    // there's deliberately no TOFU/accept-anything fallback.
    deviceAllowlistFile: process.env.APF_DEVICE_ALLOWLIST_FILE || null,
    deviceAllowlistInline: process.env.APF_DEVICE_ALLOWLIST || null,
    keepaliveIntervalMs: parseInt(process.env.APF_KEEPALIVE_INTERVAL_MS || '30000', 10),
    keepaliveTimeoutMs: parseInt(process.env.APF_KEEPALIVE_TIMEOUT_MS || '10000', 10),
    channelOpenTimeoutMs: parseInt(process.env.APF_CHANNEL_OPEN_TIMEOUT_MS || '8000', 10),
  };
}

/**
 * App-facing `wss://` config (AMT-VPRO phase 6, Part 3). Unrelated to
 * `apf.tlsKeyPath`/`apf.tlsCertPath` above -- this is the certificate the
 * *relay* presents to the *app* (CiraRelayTransport.kt), a completely
 * different audience/trust boundary than the device-facing APF TLS server
 * (which presents a certificate devices are provisioned, out-of-band, to
 * expect). Reusing one certificate for both would conflate two unrelated
 * trust relationships -- see relay/README.md's "wss:// (app-facing TLS)"
 * section for the full reasoning.
 *
 * Deliberately a *separate port*, not the same `PORT` speaking both
 * ws:// and wss:// -- Node's `http`/`https` servers aren't the same object
 * and there's no protocol-sniffing shim in this pass (would need to peek
 * the first bytes of every connection to tell a TLS ClientHello from a
 * plaintext HTTP request before handing off -- real complexity for a case
 * most deployments don't actually need: a deployer who wants both on one
 * external port can put a TLS-terminating reverse proxy in front, same
 * recommendation CiraRelayTransport.kt's doc comment already made before
 * this pass).
 */
function loadWssConfig() {
  return {
    tlsKeyPath: process.env.WSS_TLS_KEY_PATH || null,
    tlsCertPath: process.env.WSS_TLS_CERT_PATH || null,
    port: parseInt(process.env.WSS_PORT || '8788', 10),
    // When true, the plain `http.Server`/`ws://` listener on `PORT` is not
    // started at all -- only `wss://` on `WSS_PORT`. Defaults to false so
    // existing ws://-only deployments (and this project's own echo/APF
    // selftest workflow) keep working with zero config change; a deployer
    // exposing this relay past a trusted network should set this once
    // WSS_TLS_KEY_PATH/WSS_TLS_CERT_PATH are configured, matching
    // CiraRelayTransport.kt's own doc-comment recommendation.
    disablePlainWs: process.env.DISABLE_PLAIN_WS === 'true',
  };
}

const config = {
  apf: loadApfConfig(),
  wss: loadWssConfig(),

  /** Port the app-facing WebSocket server listens on. Matches whatever the
   *  app's `RdpProfile.ciraRelayPort` is configured to for a given deployment. */
  port: parseInt(process.env.PORT || '8787', 10),

  /** Must match CiraRelayTransport.kt's `WS_PATH` constant exactly. */
  wsPath: process.env.WS_PATH || '/cira/v1/channel',

  relayUsers: loadRelayUsers(),

  /** How long to wait for the app's `channel-open` frame after the
   *  WebSocket handshake completes before giving up — mirrors
   *  CiraRelayTransport.open's own connectTimeoutMs on the app side, so
   *  neither side hangs open past what the other is willing to wait. */
  channelOpenTimeoutMs: parseInt(process.env.CHANNEL_OPEN_TIMEOUT_MS || '8000', 10),

  /** See deviceRegistry.js: with no real APF device-facing server yet
   *  (Part 2's job), the only deviceId this relay can actually open a
   *  channel to is this fixed echo-test one, useful for validating the
   *  app <-> relay WebSocket protocol end-to-end without any AMT hardware. */
  echoTestDeviceId: process.env.ECHO_TEST_DEVICE_ID || 'echo-test',
};

module.exports = config;
