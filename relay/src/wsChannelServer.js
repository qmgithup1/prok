'use strict';

const { WebSocketServer } = require('ws');

/**
 * The app-facing half of the relay — implements CiraRelayTransport.kt's
 * protocol from the *server* side, message-for-message. That file's top
 * doc comment is the normative spec; this is deliberately written to
 * mirror its six numbered sections so the two can be diffed against each
 * other by inspection:
 *
 *   1. Connect       — RFC 6455 handshake at `config.wsPath`, optional
 *                       HTTP Basic auth checked in `verifyClient` below.
 *   2. Channel open  — first *text* frame from the app must be
 *                       `{"type":"channel-open","version":1,"deviceId":...,"targetPort":...}`.
 *   3. Ack           — exactly one text frame back:
 *                       `{"type":"channel-open-ack","status":"ok"}` or
 *                       `{"type":"channel-open-ack","status":"error","message":"..."}`.
 *   4. Data          — binary frames, both directions, raw payload bytes,
 *                       no additional framing.
 *   5. Keepalive     — the app pings every 20s; `ws` auto-replies to pings
 *                       with no application code needed, so there is
 *                       nothing to implement here beyond not disabling it.
 *   6. Close         — either side may just close (code 1000); this server
 *                       additionally sends `{"type":"channel-close"}` right
 *                       before closing when the *device* side ends first,
 *                       so the app can distinguish that from a transport
 *                       failure (see CiraRelayTransport.kt's `onMessage`
 *                       handling of that exact frame).
 *
 * One `DeviceRegistry` (see deviceRegistry.js) backs every connection this
 * server accepts — passed in by server.js, not constructed here, so tests
 * can substitute their own.
 */
function createWsChannelServer({ httpServer, path, relayUsers, channelOpenTimeoutMs, registry, logger = console }) {
  const wss = new WebSocketServer({
    server: httpServer,
    path,
    verifyClient: (info, callback) => {
      if (relayUsers.length === 0) {
        callback(true);
        return;
      }
      const header = info.req.headers['authorization'] || '';
      const match = /^Basic\s+(.+)$/i.exec(header);
      if (!match) {
        callback(false, 401, 'Unauthorized');
        return;
      }
      let decoded;
      try {
        decoded = Buffer.from(match[1], 'base64').toString('utf8');
      } catch {
        callback(false, 401, 'Unauthorized');
        return;
      }
      const sep = decoded.indexOf(':');
      const user = sep === -1 ? decoded : decoded.slice(0, sep);
      const pass = sep === -1 ? '' : decoded.slice(sep + 1);
      const ok = relayUsers.some((u) => u.username === user && u.password === pass);
      callback(ok, ok ? undefined : 401, ok ? undefined : 'Unauthorized');
    },
  });

  wss.on('connection', (ws, req) => {
    handleConnection(ws, req).catch((err) => {
      logger.error('[wsChannelServer] unhandled connection error:', err);
      safeClose(ws, 1011, 'internal error');
    });
  });

  async function handleConnection(ws, req) {
    const remote = req.socket.remoteAddress;
    /** @type {import('./deviceRegistry').Channel | null} */
    let channel = null;
    let openReceived = false;

    const openTimer = setTimeout(() => {
      if (!openReceived) {
        logger.warn(`[wsChannelServer] ${remote}: no channel-open within ${channelOpenTimeoutMs}ms`);
        safeClose(ws, 1002, 'channel-open timeout');
      }
    }, channelOpenTimeoutMs);

    ws.on('message', (data, isBinary) => {
      if (!openReceived) {
        // Everything before the first channel-open must be the channel-open
        // frame itself, and it must be text (JSON) per the spec — binary
        // data this early is a protocol violation, not something to buffer.
        if (isBinary) {
          sendJson(ws, { type: 'error', message: 'Expected channel-open text frame before any binary data' });
          safeClose(ws, 1002, 'protocol violation');
          return;
        }
        openReceived = true;
        clearTimeout(openTimer);
        void onChannelOpen(data.toString('utf8'));
        return;
      }

      // Post-open: binary frames are raw payload; the only remaining
      // defined text frame is a client-originated close/error notice,
      // which this relay tolerates symmetrically even though
      // CiraRelayTransport.kt's client today never sends one itself.
      if (isBinary) {
        if (channel) channel.write(Buffer.isBuffer(data) ? data : Buffer.from(data));
        return;
      }
      const msg = tryParseJson(data.toString('utf8'));
      if (msg && msg.type === 'channel-close') {
        if (channel) channel.close();
      }
      // Any other stray text frame post-open is ignored, not fed into the
      // byte stream — mirrors CiraRelayTransport.kt's own "ignored, not
      // fed into the data stream" handling for unrecognized control frames.
    });

    ws.on('close', () => {
      clearTimeout(openTimer);
      if (channel) channel.close();
    });

    ws.on('error', (err) => {
      logger.warn(`[wsChannelServer] ${remote}: socket error:`, err.message);
      if (channel) channel.close();
    });

    async function onChannelOpen(text) {
      const msg = tryParseJson(text);
      if (!msg || msg.type !== 'channel-open') {
        sendJson(ws, { type: 'channel-open-ack', status: 'error', message: 'First frame must be a channel-open message' });
        safeClose(ws, 1002, 'protocol violation');
        return;
      }
      if (msg.version !== 1) {
        sendJson(ws, { type: 'channel-open-ack', status: 'error', message: `Unsupported protocol version ${msg.version}` });
        safeClose(ws, 1002, 'unsupported version');
        return;
      }
      const deviceId = typeof msg.deviceId === 'string' ? msg.deviceId : '';
      const targetPort = Number.isInteger(msg.targetPort) ? msg.targetPort : -1;
      if (!deviceId || targetPort < 0) {
        sendJson(ws, { type: 'channel-open-ack', status: 'error', message: 'channel-open requires non-empty deviceId and a valid targetPort' });
        safeClose(ws, 1002, 'invalid channel-open');
        return;
      }

      try {
        channel = await registry.openChannel(deviceId, targetPort);
      } catch (err) {
        sendJson(ws, { type: 'channel-open-ack', status: 'error', message: err.message || String(err) });
        safeClose(ws, 1000, 'channel-open rejected');
        return;
      }

      channel.on('data', (buf) => {
        if (ws.readyState === ws.OPEN) ws.send(buf, { binary: true });
      });
      channel.on('close', () => {
        sendJson(ws, { type: 'channel-close' });
        safeClose(ws, 1000, 'device channel closed');
      });
      channel.on('error', (err) => {
        sendJson(ws, { type: 'error', message: err.message || String(err) });
        safeClose(ws, 1011, 'device channel error');
      });

      sendJson(ws, { type: 'channel-open-ack', status: 'ok' });
      logger.info(`[wsChannelServer] ${remote}: channel open for device '${deviceId}' port ${targetPort}`);
    }
  }

  return wss;
}

function sendJson(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

function safeClose(ws, code, reason) {
  try {
    if (ws.readyState === ws.OPEN || ws.readyState === ws.CONNECTING) ws.close(code, reason);
  } catch {
    /* already closing/closed */
  }
}

function tryParseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

module.exports = { createWsChannelServer };
