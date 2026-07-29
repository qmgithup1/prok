'use strict';

/**
 * APF ("AMT Port Forwarding") wire format — the protocol an Intel AMT
 * device speaks over the outbound TLS connection it opens to an MPS. Field
 * layouts below follow the SSHv2 connection-protocol conventions APF
 * reuses (RFC 4254-style: 1-byte message type, big-endian uint32 lengths,
 * length-prefixed strings, no outer per-message length/padding wrapper —
 * message boundaries are implicit from each type's own field shapes), as
 * described by Intel's "AMT Port Forwarding Protocol Reference Manual" and
 * cross-checked against the message-number list already pinned down in
 * AMT_VPRO_ROADMAP.md's Phase 6 section.
 *
 * **Verification status**: this module is written from that reference
 * material and code review, not from a packet capture against real AMT
 * hardware or a live comparison run against MeshCentral's `mpsserver.js` —
 * neither was possible in this pass's network-disconnected environment
 * (see relay/README.md's "Known gaps"). Field-for-field byte offsets for
 * APF_PROTOCOLVERSION's fixed-size reserved padding and the exact
 * KEEPALIVE cookie semantics are the two spots most worth double-checking
 * against a real device or a Wireshark capture before depending on this in
 * production — everything else here is the same string/uint32 shapes used
 * throughout the rest of the message set, which is a much smaller surface
 * to get wrong.
 */

const MSG = {
  APF_DISCONNECT: 1,
  APF_SERVICE_REQUEST: 5,
  APF_SERVICE_ACCEPT: 6,
  APF_USERAUTH_REQUEST: 50,
  APF_USERAUTH_FAILURE: 51,
  APF_USERAUTH_SUCCESS: 52,
  APF_GLOBAL_REQUEST: 80,
  APF_REQUEST_SUCCESS: 81,
  APF_REQUEST_FAILURE: 82,
  APF_CHANNEL_OPEN: 90,
  APF_CHANNEL_OPEN_CONFIRMATION: 91,
  APF_CHANNEL_OPEN_FAILURE: 92,
  APF_CHANNEL_WINDOW_ADJUST: 93,
  APF_CHANNEL_DATA: 94,
  APF_CHANNEL_CLOSE: 97,
  APF_PROTOCOLVERSION: 192,
  APF_KEEPALIVE_REQUEST: 208,
  APF_KEEPALIVE_REPLY: 209,
};

const MSG_NAME = Object.fromEntries(Object.entries(MSG).map(([k, v]) => [v, k]));

/** Thrown by the reader when a buffer doesn't yet contain a full message —
 * callers (see apfDeviceServer.js) catch this, keep buffering incoming TLS
 * bytes, and retry the same parse once more data has arrived. Never means
 * "malformed", only "incomplete so far". */
class InsufficientDataError extends Error {}

/** Malformed/unexpected data that will never become valid by waiting for
 * more bytes — callers should treat this as a fatal per-connection error
 * (log + close the device's TLS connection), not retry. */
class ApfProtocolError extends Error {}

/** Small cursor over a Buffer so every decoder shares one bounds-checked
 * read path instead of hand-rolling offset math per message type. */
class Cursor {
  constructor(buf) {
    this.buf = buf;
    this.pos = 0;
  }

  _need(n) {
    if (this.pos + n > this.buf.length) throw new InsufficientDataError();
  }

  readByte() {
    this._need(1);
    return this.buf.readUInt8(this.pos++);
  }

  readUInt32() {
    this._need(4);
    const v = this.buf.readUInt32BE(this.pos);
    this.pos += 4;
    return v;
  }

  /** SSH-style string: uint32 length prefix, then that many raw bytes. */
  readString() {
    const len = this.readUInt32();
    this._need(len);
    const v = this.buf.subarray(this.pos, this.pos + len);
    this.pos += len;
    return v;
  }

  readUtf8() {
    return this.readString().toString('utf8');
  }

  remaining() {
    return this.buf.length - this.pos;
  }
}

function writeUInt32(v) {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(v >>> 0, 0);
  return b;
}

function writeString(strOrBuf) {
  const data = Buffer.isBuffer(strOrBuf) ? strOrBuf : Buffer.from(strOrBuf, 'utf8');
  return Buffer.concat([writeUInt32(data.length), data]);
}

function concatFields(typeByte, ...fields) {
  return Buffer.concat([Buffer.from([typeByte]), ...fields]);
}

/**
 * Attempts to decode exactly one APF message from the *start* of `buf`.
 * Returns `{ message, bytesConsumed }` on success. Throws
 * `InsufficientDataError` if `buf` doesn't yet hold a full message (caller
 * should wait for more bytes and retry with the same, now-longer buffer),
 * or `ApfProtocolError` for anything structurally invalid.
 */
function tryDecodeOne(buf) {
  if (buf.length < 1) throw new InsufficientDataError();
  const c = new Cursor(buf);
  const type = c.readByte();

  switch (type) {
    case MSG.APF_PROTOCOLVERSION: {
      const majorVersion = c.readUInt32();
      const minorVersion = c.readUInt32();
      const triggerReason = c.readUInt32();
      c._need(16);
      const uuid = buf.subarray(c.pos, c.pos + 16);
      c.pos += 16;
      // Reserved padding out to a fixed-size record; APF pads
      // APF_PROTOCOLVERSION to 64 bytes of numeric fields regardless of
      // TriggerReason. See module doc comment re: verification status.
      const reservedLen = 64 - (4 + 4 + 4 + 16);
      c._need(reservedLen);
      c.pos += reservedLen;
      return {
        message: { type, name: 'APF_PROTOCOLVERSION', majorVersion, minorVersion, triggerReason, uuid: Buffer.from(uuid) },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_SERVICE_REQUEST: {
      const serviceName = c.readUtf8();
      return { message: { type, name: 'APF_SERVICE_REQUEST', serviceName }, bytesConsumed: c.pos };
    }
    case MSG.APF_SERVICE_ACCEPT: {
      const serviceName = c.readUtf8();
      return { message: { type, name: 'APF_SERVICE_ACCEPT', serviceName }, bytesConsumed: c.pos };
    }
    case MSG.APF_USERAUTH_REQUEST: {
      const username = c.readUtf8();
      const serviceName = c.readUtf8();
      const methodName = c.readUtf8();
      let password = null;
      if (methodName === 'password') {
        c.readByte(); // boolean "change password" flag, always FALSE for APF
        password = c.readUtf8();
      }
      return {
        message: { type, name: 'APF_USERAUTH_REQUEST', username, serviceName, methodName, password },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_USERAUTH_FAILURE: {
      const authsThatCanContinue = c.readUtf8();
      const partialSuccess = c.readByte() !== 0;
      return { message: { type, name: 'APF_USERAUTH_FAILURE', authsThatCanContinue, partialSuccess }, bytesConsumed: c.pos };
    }
    case MSG.APF_USERAUTH_SUCCESS: {
      return { message: { type, name: 'APF_USERAUTH_SUCCESS' }, bytesConsumed: c.pos };
    }
    case MSG.APF_REQUEST_SUCCESS: {
      // This relay's own encodeRequestSuccess() never includes the
      // optional bound-port field (see apfDeviceServer.js -- every
      // tcpip-forward request handled here specifies an explicit nonzero
      // port), so decoding it as always-empty matches actual usage. A
      // future caller that requests port 0 (let the peer choose) would
      // need to special-case this the way a real SSH/APF stack does.
      return { message: { type, name: 'APF_REQUEST_SUCCESS' }, bytesConsumed: c.pos };
    }
    case MSG.APF_REQUEST_FAILURE: {
      return { message: { type, name: 'APF_REQUEST_FAILURE' }, bytesConsumed: c.pos };
    }
    case MSG.APF_GLOBAL_REQUEST: {
      const requestType = c.readUtf8();
      const wantReply = c.readByte() !== 0;
      let addressToBind = null;
      let portToBind = null;
      if (requestType === 'tcpip-forward' || requestType === 'cancel-tcpip-forward') {
        addressToBind = c.readUtf8();
        portToBind = c.readUInt32();
      }
      return {
        message: { type, name: 'APF_GLOBAL_REQUEST', requestType, wantReply, addressToBind, portToBind },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_CHANNEL_OPEN: {
      const chanType = c.readUtf8();
      const senderChannel = c.readUInt32();
      const initialWindowSize = c.readUInt32();
      const maxPacketSize = c.readUInt32();
      let addressConnected = null;
      let portConnected = null;
      let originatorIp = null;
      let originatorPort = null;
      if (chanType === 'forwarded-tcpip' || chanType === 'direct-tcpip') {
        addressConnected = c.readUtf8();
        portConnected = c.readUInt32();
        originatorIp = c.readUtf8();
        originatorPort = c.readUInt32();
      }
      return {
        message: {
          type,
          name: 'APF_CHANNEL_OPEN',
          chanType,
          senderChannel,
          initialWindowSize,
          maxPacketSize,
          addressConnected,
          portConnected,
          originatorIp,
          originatorPort,
        },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_CHANNEL_OPEN_CONFIRMATION: {
      const recipientChannel = c.readUInt32();
      const senderChannel = c.readUInt32();
      const initialWindowSize = c.readUInt32();
      const maxPacketSize = c.readUInt32();
      return {
        message: {
          type,
          name: 'APF_CHANNEL_OPEN_CONFIRMATION',
          recipientChannel,
          senderChannel,
          initialWindowSize,
          maxPacketSize,
        },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_CHANNEL_OPEN_FAILURE: {
      const recipientChannel = c.readUInt32();
      const reasonCode = c.readUInt32();
      const reason = c.readUtf8();
      const languageTag = c.readUtf8();
      return {
        message: { type, name: 'APF_CHANNEL_OPEN_FAILURE', recipientChannel, reasonCode, reason, languageTag },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_CHANNEL_WINDOW_ADJUST: {
      const recipientChannel = c.readUInt32();
      const bytesToAdd = c.readUInt32();
      return { message: { type, name: 'APF_CHANNEL_WINDOW_ADJUST', recipientChannel, bytesToAdd }, bytesConsumed: c.pos };
    }
    case MSG.APF_CHANNEL_DATA: {
      const recipientChannel = c.readUInt32();
      const data = c.readString();
      return {
        message: { type, name: 'APF_CHANNEL_DATA', recipientChannel, data: Buffer.from(data) },
        bytesConsumed: c.pos,
      };
    }
    case MSG.APF_CHANNEL_CLOSE: {
      const recipientChannel = c.readUInt32();
      return { message: { type, name: 'APF_CHANNEL_CLOSE', recipientChannel }, bytesConsumed: c.pos };
    }
    case MSG.APF_KEEPALIVE_REQUEST: {
      const cookie = c.readUInt32();
      return { message: { type, name: 'APF_KEEPALIVE_REQUEST', cookie }, bytesConsumed: c.pos };
    }
    case MSG.APF_KEEPALIVE_REPLY: {
      const cookie = c.readUInt32();
      return { message: { type, name: 'APF_KEEPALIVE_REPLY', cookie }, bytesConsumed: c.pos };
    }
    case MSG.APF_DISCONNECT: {
      const reasonCode = c.readUInt32();
      const description = c.readUtf8();
      const languageTag = c.readUtf8();
      return { message: { type, name: 'APF_DISCONNECT', reasonCode, description, languageTag }, bytesConsumed: c.pos };
    }
    default:
      throw new ApfProtocolError(`Unknown/unsupported APF message type ${type}`);
  }
}

/** Feed a growing Buffer of incoming TLS bytes; returns as many complete
 * messages as are currently available and the number of leading bytes that
 * were consumed by them (caller should slice its buffer by that amount and
 * keep the remainder for next time). Malformed data throws
 * `ApfProtocolError` immediately (fatal for the connection); incomplete
 * trailing data is left unconsumed, not an error. */
function decodeAll(buf) {
  const messages = [];
  let offset = 0;
  for (;;) {
    let result;
    try {
      result = tryDecodeOne(buf.subarray(offset));
    } catch (e) {
      if (e instanceof InsufficientDataError) break;
      throw e;
    }
    messages.push(result.message);
    offset += result.bytesConsumed;
  }
  return { messages, bytesConsumed: offset };
}

// ---- Encoders (server -> device direction, i.e. what this relay sends) ----

function encodeServiceAccept(serviceName) {
  return concatFields(MSG.APF_SERVICE_ACCEPT, writeString(serviceName));
}

function encodeUserAuthFailure(authsThatCanContinue = '', partialSuccess = false) {
  return concatFields(MSG.APF_USERAUTH_FAILURE, writeString(authsThatCanContinue), Buffer.from([partialSuccess ? 1 : 0]));
}

function encodeUserAuthSuccess() {
  return Buffer.from([MSG.APF_USERAUTH_SUCCESS]);
}

function encodeRequestSuccess(boundPort = null) {
  if (boundPort === null) return Buffer.from([MSG.APF_REQUEST_SUCCESS]);
  return concatFields(MSG.APF_REQUEST_SUCCESS, writeUInt32(boundPort));
}

function encodeRequestFailure() {
  return Buffer.from([MSG.APF_REQUEST_FAILURE]);
}

/** MPS-initiated channel open, chan_type "forwarded-tcpip" — this relay is
 * always the one opening channels toward the device (never the reverse),
 * per AMT_VPRO_ROADMAP.md's Phase 6 message flow. */
function encodeChannelOpenForwardedTcpip({ senderChannel, initialWindowSize, maxPacketSize, addressConnected, portConnected, originatorIp, originatorPort }) {
  return concatFields(
    MSG.APF_CHANNEL_OPEN,
    writeString('forwarded-tcpip'),
    writeUInt32(senderChannel),
    writeUInt32(initialWindowSize),
    writeUInt32(maxPacketSize),
    writeString(addressConnected),
    writeUInt32(portConnected),
    writeString(originatorIp),
    writeUInt32(originatorPort),
  );
}

function encodeChannelData(recipientChannel, data) {
  return concatFields(MSG.APF_CHANNEL_DATA, writeUInt32(recipientChannel), writeString(data));
}

function encodeChannelClose(recipientChannel) {
  return concatFields(MSG.APF_CHANNEL_CLOSE, writeUInt32(recipientChannel));
}

function encodeChannelWindowAdjust(recipientChannel, bytesToAdd) {
  return concatFields(MSG.APF_CHANNEL_WINDOW_ADJUST, writeUInt32(recipientChannel), writeUInt32(bytesToAdd));
}

function encodeKeepaliveRequest(cookie) {
  return concatFields(MSG.APF_KEEPALIVE_REQUEST, writeUInt32(cookie));
}

function encodeKeepaliveReply(cookie) {
  return concatFields(MSG.APF_KEEPALIVE_REPLY, writeUInt32(cookie));
}

function encodeDisconnect(reasonCode, description = '') {
  return concatFields(MSG.APF_DISCONNECT, writeUInt32(reasonCode), writeString(description), writeString(''));
}

module.exports = {
  MSG,
  MSG_NAME,
  InsufficientDataError,
  ApfProtocolError,
  decodeAll,
  encodeServiceAccept,
  encodeUserAuthFailure,
  encodeUserAuthSuccess,
  encodeRequestSuccess,
  encodeRequestFailure,
  encodeChannelOpenForwardedTcpip,
  encodeChannelData,
  encodeChannelClose,
  encodeChannelWindowAdjust,
  encodeKeepaliveRequest,
  encodeKeepaliveReply,
  encodeDisconnect,
};
