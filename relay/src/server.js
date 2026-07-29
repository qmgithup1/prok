'use strict';

const fs = require('fs');
const http = require('http');
const https = require('https');
const config = require('./config');
const { EchoDeviceRegistry, ApfDeviceRegistry } = require('./deviceRegistry');
const { createWsChannelServer } = require('./wsChannelServer');
const { ApfDeviceServer } = require('./apfDeviceServer');
const { loadTrustStoreFromConfig } = require('./apfTrustStore');

/**
 * Entry point. Wires together:
 *   - a plain `http.Server` (`ws://`) and/or, when `WSS_TLS_KEY_PATH`/
 *     `WSS_TLS_CERT_PATH` are configured, an additional `https.Server`
 *     (`wss://`) on its own port (`WSS_PORT`) -- both host the exact same
 *     app-facing WebSocket protocol via `createWsChannelServer`, which is
 *     unaware of (and unaffected by) which one it's attached to. See
 *     README.md's "wss:// (app-facing TLS)" section for the full picture,
 *     including why this is a separate port rather than one port speaking
 *     both, and why it's a different certificate than the device-facing
 *     APF TLS listener below;
 *   - the device registry backing every channel: `ApfDeviceRegistry` (real
 *     APF devices, via a TLS listener) when `APF_TLS_PORT` is configured,
 *     else `EchoDeviceRegistry` (the loopback stub from Part 1) so local
 *     dev/CI against the echo-test device keeps working with zero config;
 *   - the app-facing WebSocket protocol server (wsChannelServer.js), which
 *     implements CiraRelayTransport.kt's wire contract exactly and is
 *     completely unaware of which registry is behind it.
 */
async function main() {
  const registry = await buildRegistry();

  const requestHandler = (req, res) => {
    // Anything that isn't a WebSocket upgrade to config.wsPath is not this
    // relay's concern -- a bare 404 is enough (no dashboard/status page in
    // this pass).
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
  };

  const wssConfigured = Boolean(config.wss.tlsKeyPath && config.wss.tlsCertPath);
  if (config.wss.disablePlainWs && !wssConfigured) {
    throw new Error(
      'DISABLE_PLAIN_WS=true but WSS_TLS_KEY_PATH/WSS_TLS_CERT_PATH are not both set -- ' +
        'refusing to start with no app-facing listener at all.',
    );
  }
  if (config.wss.tlsKeyPath || config.wss.tlsCertPath) {
    // Both-or-neither, same validation shape as the APF TLS pair below.
    if (!wssConfigured) {
      throw new Error('WSS_TLS_KEY_PATH and WSS_TLS_CERT_PATH must both be set (or both unset) -- only one was provided.');
    }
  }

  const servers = [];

  if (!config.wss.disablePlainWs) {
    const httpServer = http.createServer(requestHandler);
    createWsChannelServer({
      httpServer,
      path: config.wsPath,
      relayUsers: config.relayUsers,
      channelOpenTimeoutMs: config.channelOpenTimeoutMs,
      registry,
    });
    servers.push({ server: httpServer, port: config.port, label: `ws:// :${config.port}` });
  }

  if (wssConfigured) {
    // A *different* certificate/key pair than apf.tlsKeyPath/tlsCertPath --
    // see config.js's loadWssConfig doc comment for why reusing the
    // device-facing APF certificate here would be wrong.
    const httpsServer = https.createServer(
      {
        key: fs.readFileSync(config.wss.tlsKeyPath),
        cert: fs.readFileSync(config.wss.tlsCertPath),
      },
      requestHandler,
    );
    createWsChannelServer({
      httpServer: httpsServer,
      path: config.wsPath,
      relayUsers: config.relayUsers,
      channelOpenTimeoutMs: config.channelOpenTimeoutMs,
      registry,
    });
    servers.push({ server: httpsServer, port: config.wss.port, label: `wss:// :${config.wss.port}` });
  }

  for (const { server, port, label } of servers) {
    server.listen(port, () => {
      console.log(`[systemsgo-cira-relay] app-facing listening on ${label}, path ${config.wsPath}`);
    });
  }
  console.log(
    config.relayUsers.length > 0
      ? `[systemsgo-cira-relay] Basic auth required (${config.relayUsers.length} user(s) configured)`
      : '[systemsgo-cira-relay] WARNING: no RELAY_USERS configured -- accepting unauthenticated app connections',
  );
  if (!wssConfigured) {
    console.log(
      '[systemsgo-cira-relay] WSS_TLS_KEY_PATH/WSS_TLS_CERT_PATH not set -- app-facing traffic is ws:// only (plaintext). ' +
        'See README.md "wss:// (app-facing TLS)" before exposing this relay past a trusted network.',
    );
  }

  const shutdown = () => {
    console.log('[systemsgo-cira-relay] shutting down');
    let pending = servers.length;
    if (pending === 0) process.exit(0);
    for (const { server } of servers) server.close(() => { if (--pending === 0) process.exit(0); });
    setTimeout(() => process.exit(0), 2000).unref();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

async function buildRegistry() {
  if (!config.apf.tlsPort) {
    console.log(
      `[systemsgo-cira-relay] APF_TLS_PORT not set -- no real APF device backend; only deviceId ` +
        `'${config.echoTestDeviceId}' (loopback echo) will open a channel. Set APF_TLS_PORT (+ ` +
        `APF_TLS_KEY_PATH/APF_TLS_CERT_PATH/APF_DEVICE_ALLOWLIST[_FILE]) to accept real devices.`,
    );
    return new EchoDeviceRegistry({ echoTestDeviceId: config.echoTestDeviceId });
  }

  if (!config.apf.tlsKeyPath || !config.apf.tlsCertPath) {
    throw new Error('APF_TLS_PORT is set but APF_TLS_KEY_PATH/APF_TLS_CERT_PATH are missing -- both are required.');
  }

  const trustStore = loadTrustStoreFromConfig({
    allowlistInline: config.apf.deviceAllowlistInline,
    allowlistFile: config.apf.deviceAllowlistFile,
  });

  const apfDeviceServer = new ApfDeviceServer({
    tlsOptions: {
      key: fs.readFileSync(config.apf.tlsKeyPath),
      cert: fs.readFileSync(config.apf.tlsCertPath),
    },
    trustStore,
    keepaliveIntervalMs: config.apf.keepaliveIntervalMs,
    keepaliveTimeoutMs: config.apf.keepaliveTimeoutMs,
    channelOpenTimeoutMs: config.apf.channelOpenTimeoutMs,
  });
  apfDeviceServer.on('device-online', (deviceId) => console.log(`[systemsgo-cira-relay] device online: ${deviceId}`));
  apfDeviceServer.on('device-offline', (deviceId) => console.log(`[systemsgo-cira-relay] device offline: ${deviceId}`));

  await apfDeviceServer.listen(config.apf.tlsPort, config.apf.tlsHost);
  console.log(`[systemsgo-cira-relay] device-facing APF TLS listening on ${config.apf.tlsHost}:${config.apf.tlsPort}`);

  return new ApfDeviceRegistry({ apfDeviceServer, trustStore });
}

main().catch((err) => {
  console.error(`[systemsgo-cira-relay] fatal startup error: ${err.message}`);
  process.exit(1);
});
