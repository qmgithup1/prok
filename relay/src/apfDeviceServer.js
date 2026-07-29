'use strict';

const tls = require('tls');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const apf = require('./apfProtocol');

const AUTH_SERVICE = 'auth@amt.intel.com';
const PFWD_SERVICE = 'pfwd@amt.intel.com';
const CHAN_INITIAL_WINDOW = 128 * 1024;
const CHAN_MAX_PACKET = 32 * 1024;
const MAX_AUTH_ATTEMPTS = 3;

/**
 * One device's live, authenticated APF connection. Owns channel-id
 * bookkeeping for that device only — `ApfDeviceServer` (below) owns the
 * deviceId -> connection map across all devices.
 *
 * Channel id convention (mirrors SSH connection-protocol semantics, which
 * APF_CHANNEL_* reuses): every open channel has a *local* id (one we chose
 * when we sent APF_CHANNEL_OPEN) and a *remote* id (the device's own id
 * for the same channel, learned from APF_CHANNEL_OPEN_CONFIRMATION).
 * Inbound APF_CHANNEL_DATA/APF_CHANNEL_CLOSE address us by *our* local id
 * (their "recipient_channel"); outbound frames we send must address the
 * device by *its* remote id.
 */
class ApfDeviceConnection extends EventEmitter {
  constructor(socket, { trustStore, keepaliveIntervalMs, keepaliveTimeoutMs, channelOpenTimeoutMs, log }) {
    super();
    this.socket = socket;
    this.trustStore = trustStore;
    this.channelOpenTimeoutMs = channelOpenTimeoutMs;
    this.log = log;

    this.recvBuf = Buffer.alloc(0);
    this.phase = 'preauth'; // preauth -> authenticated
    this.authAttempts = 0;
    this.username = null;
    this.deviceId = null;
    this.forwardedPorts = new Set();

    this._nextLocalChannelId = 1;
    this.channelsByLocalId = new Map(); // localId -> ApfChannel
    this.pendingOpens = new Map(); // localId -> { resolve, reject, timeoutHandle }

    this._pendingKeepaliveCookie = null;
    this._keepaliveTimer = setInterval(() => this._sendKeepalive(), keepaliveIntervalMs);
    this._keepaliveTimeoutMs = keepaliveTimeoutMs;
    this._keepaliveMissTimer = null;

    socket.on('data', (chunk) => this._onData(chunk));
    socket.on('error', (err) => this._fail(err));
    socket.on('close', () => this._onSocketClose());

    // Send our own APF_PROTOCOLVERSION promptly; devices are expected to
    // send theirs independently rather than strictly waiting on ours
    // first (see apfProtocol.js doc comment re: verification status).
    this._sendOurProtocolVersion();
  }

  _sendOurProtocolVersion() {
    const uuid = crypto.randomBytes(16); // MPS-side identifier for this session; not the device's own UUID
    const reserved = Buffer.alloc(64 - (4 + 4 + 4 + 16), 0);
    const body = Buffer.concat([
      Buffer.from([apf.MSG.APF_PROTOCOLVERSION]),
      u32(1), // majorVersion
      u32(0), // minorVersion
      u32(0), // triggerReason: 0 = normal MPS-initiated presence, per Intel's reference manual
      uuid,
      reserved,
    ]);
    this._write(body);
  }

  _write(buf) {
    if (this.socket.destroyed || this.socket.writableEnded) return;
    this.socket.write(buf);
  }

  _fail(err) {
    this.log('error', `[${this.deviceId || this.username || 'unauthenticated'}] connection error: ${err.message}`);
    this._teardown(err);
  }

  _onSocketClose() {
    this._teardown(new Error('device closed the APF connection'));
  }

  _teardown(err) {
    clearInterval(this._keepaliveTimer);
    if (this._keepaliveMissTimer) clearTimeout(this._keepaliveMissTimer);
    for (const { reject, timeoutHandle } of this.pendingOpens.values()) {
      clearTimeout(timeoutHandle);
      reject(err);
    }
    this.pendingOpens.clear();
    for (const channel of this.channelsByLocalId.values()) {
      channel._onDeviceGone(err);
    }
    this.channelsByLocalId.clear();
    this.emit('close', err);
  }

  _onData(chunk) {
    this.recvBuf = this.recvBuf.length ? Buffer.concat([this.recvBuf, chunk]) : chunk;
    let decoded;
    try {
      decoded = apf.decodeAll(this.recvBuf);
    } catch (e) {
      this._fail(new apf.ApfProtocolError(`malformed APF stream: ${e.message}`));
      this.socket.destroy();
      return;
    }
    this.recvBuf = this.recvBuf.subarray(decoded.bytesConsumed);
    for (const msg of decoded.messages) {
      try {
        this._handleMessage(msg);
      } catch (e) {
        this.log('error', `handling ${msg.name}: ${e.message}`);
        this.socket.destroy();
        return;
      }
    }
  }

  _handleMessage(msg) {
    switch (msg.name) {
      case 'APF_PROTOCOLVERSION':
        // Informational only in this pass — nothing here gates the rest of
        // the handshake on the device's advertised version/uuid.
        return;

      case 'APF_SERVICE_REQUEST':
        if (msg.serviceName === AUTH_SERVICE && this.phase === 'preauth') {
          this._write(apf.encodeServiceAccept(AUTH_SERVICE));
        } else if (msg.serviceName === PFWD_SERVICE && this.phase === 'authenticated') {
          this._write(apf.encodeServiceAccept(PFWD_SERVICE));
        } else {
          throw new apf.ApfProtocolError(`unexpected APF_SERVICE_REQUEST "${msg.serviceName}" in phase ${this.phase}`);
        }
        return;

      case 'APF_USERAUTH_REQUEST':
        this._handleUserAuth(msg);
        return;

      case 'APF_GLOBAL_REQUEST':
        this._handleGlobalRequest(msg);
        return;

      case 'APF_CHANNEL_OPEN_CONFIRMATION':
        this._handleChannelOpenConfirmation(msg);
        return;

      case 'APF_CHANNEL_OPEN_FAILURE':
        this._handleChannelOpenFailure(msg);
        return;

      case 'APF_CHANNEL_DATA': {
        const channel = this.channelsByLocalId.get(msg.recipientChannel);
        if (channel) channel._onDeviceData(msg.data);
        // Silently drop data for an unknown/already-closed channel id
        // rather than tearing down the whole device connection over it.
        return;
      }

      case 'APF_CHANNEL_CLOSE': {
        const channel = this.channelsByLocalId.get(msg.recipientChannel);
        if (channel) {
          this.channelsByLocalId.delete(msg.recipientChannel);
          channel._onDeviceClosed();
        }
        return;
      }

      case 'APF_CHANNEL_WINDOW_ADJUST':
        // Flow control is not throttled in this pass (see module doc
        // comment in apfDeviceRegistry.js) — acknowledged but unused.
        return;

      case 'APF_KEEPALIVE_REQUEST':
        this._write(apf.encodeKeepaliveReply(msg.cookie));
        return;

      case 'APF_KEEPALIVE_REPLY':
        if (this._pendingKeepaliveCookie !== null && msg.cookie === this._pendingKeepaliveCookie) {
          this._pendingKeepaliveCookie = null;
          if (this._keepaliveMissTimer) {
            clearTimeout(this._keepaliveMissTimer);
            this._keepaliveMissTimer = null;
          }
        }
        return;

      case 'APF_DISCONNECT':
        this.log('info', `[${this.deviceId || this.username}] device sent APF_DISCONNECT: ${msg.description}`);
        this.socket.end();
        return;

      default:
        // Every message type apfProtocol.js can decode is handled above;
        // reaching here means a new type was added there without a
        // matching case here.
        throw new apf.ApfProtocolError(`no handler for decoded message ${msg.name}`);
    }
  }

  _handleUserAuth(msg) {
    if (this.phase !== 'preauth') {
      throw new apf.ApfProtocolError('APF_USERAUTH_REQUEST received after authentication already completed');
    }
    if (msg.methodName !== 'password') {
      this._write(apf.encodeUserAuthFailure('password'));
      return;
    }
    const peerCert = this.socket.getPeerCertificate(false);
    const peerCertDer = peerCert && peerCert.raw ? peerCert.raw : null;
    const entry = this.trustStore.verify({ username: msg.username, password: msg.password, peerCertDer });
    if (!entry) {
      this.authAttempts += 1;
      this.log('warn', `APF_USERAUTH_REQUEST rejected for username "${msg.username}" (attempt ${this.authAttempts})`);
      this._write(apf.encodeUserAuthFailure('password'));
      if (this.authAttempts >= MAX_AUTH_ATTEMPTS) {
        this.log('warn', `closing connection after ${this.authAttempts} failed auth attempts`);
        this.socket.destroy();
      }
      return;
    }
    this.username = entry.username;
    this.deviceId = entry.deviceId;
    this.phase = 'authenticated';
    this._write(apf.encodeUserAuthSuccess());
    this.log('info', `device authenticated: deviceId=${this.deviceId} username=${this.username}`);
    this.emit('authenticated', this.deviceId);
  }

  _handleGlobalRequest(msg) {
    if (msg.requestType === 'tcpip-forward') {
      if (this.phase !== 'authenticated') {
        throw new apf.ApfProtocolError('tcpip-forward requested before authentication');
      }
      this.forwardedPorts.add(msg.portToBind);
      this.log('info', `[${this.deviceId}] tcpip-forward registered for port ${msg.portToBind}`);
      if (msg.wantReply) this._write(apf.encodeRequestSuccess());
    } else if (msg.requestType === 'cancel-tcpip-forward') {
      this.forwardedPorts.delete(msg.portToBind);
      if (msg.wantReply) this._write(apf.encodeRequestSuccess());
    } else if (msg.wantReply) {
      this._write(apf.encodeRequestFailure());
    }
  }

  _handleChannelOpenConfirmation(msg) {
    const pending = this.pendingOpens.get(msg.recipientChannel);
    if (!pending) return; // stale/unexpected confirmation; nothing to resolve
    clearTimeout(pending.timeoutHandle);
    this.pendingOpens.delete(msg.recipientChannel);
    const channel = new ApfChannel({
      connection: this,
      localId: msg.recipientChannel,
      remoteId: msg.senderChannel,
    });
    this.channelsByLocalId.set(msg.recipientChannel, channel);
    pending.resolve(channel);
  }

  _handleChannelOpenFailure(msg) {
    const pending = this.pendingOpens.get(msg.recipientChannel);
    if (!pending) return;
    clearTimeout(pending.timeoutHandle);
    this.pendingOpens.delete(msg.recipientChannel);
    pending.reject(new Error(`device refused channel open (reason ${msg.reasonCode}): ${msg.reason}`));
  }

  _sendKeepalive() {
    if (this._pendingKeepaliveCookie !== null) return; // previous one still outstanding; wait for its timeout
    const cookie = crypto.randomInt(0, 0xffffffff);
    this._pendingKeepaliveCookie = cookie;
    this._write(apf.encodeKeepaliveRequest(cookie));
    this._keepaliveMissTimer = setTimeout(() => {
      this.log('warn', `[${this.deviceId || this.username}] keepalive timed out — closing connection`);
      this.socket.destroy();
    }, this._keepaliveTimeoutMs);
  }

  /** Used by ApfDeviceServer.openChannel — see that method's doc comment
   * for `addressConnected`/`originatorIp` semantics. */
  openChannel(targetPort) {
    return new Promise((resolve, reject) => {
      const localId = this._nextLocalChannelId++;
      const body = apf.encodeChannelOpenForwardedTcpip({
        senderChannel: localId,
        initialWindowSize: CHAN_INITIAL_WINDOW,
        maxPacketSize: CHAN_MAX_PACKET,
        addressConnected: '127.0.0.1',
        portConnected: targetPort,
        originatorIp: '0.0.0.0',
        originatorPort: 0,
      });
      const timeoutHandle = setTimeout(() => {
        this.pendingOpens.delete(localId);
        reject(new Error(`timed out waiting for APF_CHANNEL_OPEN_CONFIRMATION on port ${targetPort}`));
      }, this.channelOpenTimeoutMs);
      this.pendingOpens.set(localId, { resolve, reject, timeoutHandle });
      this._write(body);
    });
  }
}

/** The `Channel` implementation handed back to `ApfDeviceRegistry.openChannel`
 * (see deviceRegistry.js for the contract). Thin wrapper translating
 * `write()`/`close()` to APF_CHANNEL_DATA/APF_CHANNEL_CLOSE frames on its
 * connection, and the connection's dispatch back into `'data'`/`'close'`/
 * `'error'` events. */
class ApfChannel extends EventEmitter {
  constructor({ connection, localId, remoteId }) {
    super();
    this.connection = connection;
    this.localId = localId;
    this.remoteId = remoteId;
    this._closed = false;
  }

  write(buffer) {
    if (this._closed) return;
    // No outbound flow-control throttling against the device's advertised
    // window in this pass (see APF_CHANNEL_WINDOW_ADJUST handling above) —
    // acceptable for SOL/KVM/IDE-R's own request/response-paced traffic,
    // worth revisiting if a future channel type pushes sustained
    // high-throughput data.
    this.connection._write(apf.encodeChannelData(this.remoteId, buffer));
  }

  close() {
    if (this._closed) return;
    this._closed = true;
    this.connection.channelsByLocalId.delete(this.localId);
    this.connection._write(apf.encodeChannelClose(this.remoteId));
  }

  _onDeviceData(buffer) {
    if (!this._closed) this.emit('data', buffer);
  }

  _onDeviceClosed() {
    if (this._closed) return;
    this._closed = true;
    this.emit('close');
  }

  _onDeviceGone(err) {
    if (this._closed) return;
    this._closed = true;
    this.emit('error', err);
  }
}

/**
 * The device-facing TLS listener. One instance per relay process. Accepts
 * devices' outbound connections (inverted direction vs. every other
 * protocol in this project — see AMT_VPRO_ROADMAP.md), authenticates them
 * against `trustStore`, and tracks one live `ApfDeviceConnection` per
 * `deviceId` (a new authenticated connection for an already-connected
 * deviceId replaces the old one — the natural behaviour for a device that
 * reconnected).
 */
class ApfDeviceServer extends EventEmitter {
  constructor({ tlsOptions, trustStore, keepaliveIntervalMs, keepaliveTimeoutMs, channelOpenTimeoutMs, log = defaultLog }) {
    super();
    this.trustStore = trustStore;
    this.log = log;
    this.connectionsByDeviceId = new Map();

    this.tlsServer = tls.createServer(
      {
        ...tlsOptions,
        requestCert: true,
        // We intentionally accept the TLS handshake even for a client cert
        // this Node TLS stack doesn't itself validate against a CA (AMT
        // devices typically present certificates from a private
        // provisioning CA, or self-signed ones) — see apfTrustStore.js.
        // Trust is enforced ourselves, at the APF_USERAUTH_REQUEST step,
        // by pinning the exact certificate fingerprint, which is a
        // stronger check for this use case than chain validation against
        // an arbitrary CA would be.
        rejectUnauthorized: false,
      },
      (socket) => this._onConnection(socket),
    );
    this.tlsServer.on('error', (err) => this.log('error', `TLS server error: ${err.message}`));

    this._opts = { trustStore, keepaliveIntervalMs, keepaliveTimeoutMs, channelOpenTimeoutMs, log };
  }

  listen(port, host) {
    return new Promise((resolve, reject) => {
      this.tlsServer.once('error', reject);
      this.tlsServer.listen(port, host, () => {
        this.tlsServer.removeListener('error', reject);
        resolve();
      });
    });
  }

  close() {
    return new Promise((resolve) => this.tlsServer.close(() => resolve()));
  }

  _onConnection(socket) {
    const remote = `${socket.remoteAddress}:${socket.remotePort}`;
    this.log('info', `APF TLS connection from ${remote}`);
    const conn = new ApfDeviceConnection(socket, this._opts);

    conn.on('authenticated', (deviceId) => {
      const existing = this.connectionsByDeviceId.get(deviceId);
      if (existing && existing !== conn) {
        this.log('info', `[${deviceId}] replacing existing connection (device reconnected)`);
        existing.socket.destroy();
      }
      this.connectionsByDeviceId.set(deviceId, conn);
      this.emit('device-online', deviceId);
    });

    conn.on('close', () => {
      if (conn.deviceId && this.connectionsByDeviceId.get(conn.deviceId) === conn) {
        this.connectionsByDeviceId.delete(conn.deviceId);
        this.emit('device-offline', conn.deviceId);
      }
      this.log('info', `APF TLS connection from ${remote} closed (deviceId=${conn.deviceId || 'n/a'})`);
    });
  }

  /** Resolves once the device replies APF_CHANNEL_OPEN_CONFIRMATION, or
   * rejects on APF_CHANNEL_OPEN_FAILURE / timeout / device not currently
   * connected. See deviceRegistry.js's `DeviceRegistry.openChannel`
   * contract — `ApfDeviceRegistry` delegates straight to this. */
  openChannel(deviceId, targetPort) {
    const conn = this.connectionsByDeviceId.get(deviceId);
    if (!conn) {
      return Promise.reject(new Error(`device "${deviceId}" is not currently connected to this relay`));
    }
    return conn.openChannel(targetPort);
  }

  isDeviceOnline(deviceId) {
    return this.connectionsByDeviceId.has(deviceId);
  }
}

function u32(v) {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(v >>> 0, 0);
  return b;
}

function defaultLog(level, msg) {
  console.log(`[apf-device-server] [${level}] ${msg}`);
}

module.exports = { ApfDeviceServer, ApfDeviceConnection, ApfChannel };
