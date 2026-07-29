'use strict';

const { EventEmitter } = require('events');

/**
 * The seam between this pass (app-facing WebSocket protocol, fully done —
 * see wsChannelServer.js) and the next one (a real APF server terminating
 * actual AMT device connections — see AMT_VPRO_ROADMAP.md's Phase 6
 * section, "Not yet started"). wsChannelServer.js only ever talks to a
 * `DeviceRegistry` through the two methods below; it has no idea whether
 * channels are backed by real APF_CHANNEL_DATA framing to a device or, as
 * today, an in-process echo stub. That's the whole point: Part 2 can
 * replace `EchoDeviceRegistry` with a real `ApfDeviceRegistry` and nothing
 * in wsChannelServer.js/server.js needs to change.
 *
 * ## Contract a real implementation (Part 2) must satisfy
 *
 * `openChannel(deviceId, targetPort)` — returns a Promise resolving to a
 * `Channel` (see below), or rejecting with an `Error` whose `message` is
 * safe to send verbatim back to the app in a `channel-open-ack`
 * status:"error" frame (see CiraRelayTransport.kt's doc comment). For a
 * real APF-backed registry this means: find (or wait briefly for) an
 * already-authenticated APF connection from `deviceId`, send it
 * `APF_CHANNEL_OPEN` (90, chan_type="forwarded-tcpip", target_port=
 * `targetPort`), and resolve once `APF_CHANNEL_OPEN_CONFIRMATION` (91)
 * comes back — or reject on `APF_CHANNEL_OPEN_FAILURE` (92), unknown
 * deviceId, or timeout. See AMT_VPRO_ROADMAP.md's Phase 6 section for the
 * full message-number reference this must implement against.
 *
 * A `Channel` is an `EventEmitter` that:
 *   - emits `'data'` with a `Buffer` whenever bytes arrive from the device
 *     side (for a real registry: the payload of an `APF_CHANNEL_DATA`
 *     frame for this channel, already stripped of APF framing) — these get
 *     forwarded to the app as WebSocket *binary* frames verbatim, no
 *     re-framing (see wsChannelServer.js).
 *   - emits `'close'` when the device (or the registry) ends the channel
 *     normally (for a real registry: on `APF_CHANNEL_CLOSE`) — triggers a
 *     `{"type":"channel-close"}` app-facing frame then a clean WS close.
 *   - emits `'error'` with an `Error` on any abnormal failure — triggers a
 *     `{"type":"error",...}` app-facing frame then a WS close.
 *   - exposes `write(buffer)` — for a real registry: wrap `buffer` in
 *     `APF_CHANNEL_DATA` (94) for this channel and send it down the
 *     device's APF connection. Must be safe to call until `close()`.
 *   - exposes `close()` — for a real registry: send `APF_CHANNEL_CLOSE`
 *     (97) for this channel. Idempotent (calling twice must not throw).
 */
class Channel extends EventEmitter {
  write(_buffer) {
    throw new Error('Channel.write must be implemented by a concrete subclass');
  }

  close() {
    throw new Error('Channel.close must be implemented by a concrete subclass');
  }
}

/**
 * A trivial in-process loopback `Channel`: whatever the app writes, it
 * reads back, after `echoDelayMs` (0 by default). Exists purely so the
 * app-facing half of this relay (wsChannelServer.js) can be exercised
 * end-to-end — real SOL/KVM/IDE-R bytes round-tripping through an actual
 * `AmtSolSession`/etc. on the phone — without any AMT hardware or APF
 * server existing yet. **Not a stand-in for real device behaviour**: SOL/
 * KVM/IDE-R sessions will fail their own handshakes against an echo (the
 * app will see its own `StartRedirectionSession` bytes reflected back
 * rather than AMT's `StartRedirectionSessionReply`) — this is for
 * validating the *relay's* framing only, e.g. with `test/echoClient.js` or
 * a raw WebSocket client, not for validating `AmtSolSession` against it.
 */
class EchoChannel extends Channel {
  constructor(echoDelayMs = 0) {
    super();
    this.echoDelayMs = echoDelayMs;
    this._closed = false;
  }

  write(buffer) {
    if (this._closed) return;
    if (this.echoDelayMs > 0) {
      setTimeout(() => {
        if (!this._closed) this.emit('data', buffer);
      }, this.echoDelayMs);
    } else {
      // Still defer to next tick so callers never observe a synchronous
      // write -> data re-entrancy, matching how a real network-backed
      // channel would always be async.
      setImmediate(() => {
        if (!this._closed) this.emit('data', buffer);
      });
    }
  }

  close() {
    if (this._closed) return;
    this._closed = true;
    this.emit('close');
  }
}

class UnknownDeviceError extends Error {}

/**
 * The registry actually wired up in this pass (see server.js). Knows
 * exactly one "device": `config.echoTestDeviceId`, backed by
 * `EchoChannel`. Every other `deviceId` rejects with a clear message
 * rather than hanging or silently succeeding, so it's obvious to anyone
 * testing against this relay today that real-device channels aren't
 * implemented yet — that's Part 2, via a new `ApfDeviceRegistry` satisfying
 * the same two-method contract documented above.
 */
class EchoDeviceRegistry {
  constructor({ echoTestDeviceId }) {
    this.echoTestDeviceId = echoTestDeviceId;
  }

  async openChannel(deviceId, targetPort) {
    if (deviceId !== this.echoTestDeviceId) {
      throw new UnknownDeviceError(
        `No real APF device backend is implemented yet (Part 2) — the only deviceId this relay ` +
          `build can open a channel to is the echo-test one ('${this.echoTestDeviceId}'), not '${deviceId}'.`,
      );
    }
    // targetPort is accepted but unused by the echo stub — a real registry
    // uses it as APF's target_port when opening the forwarded-tcpip channel.
    void targetPort;
    return new EchoChannel();
  }
}

/**
 * The real implementation (this pass -- see AMT_VPRO_ROADMAP.md's Phase 6,
 * "Not yet started" -> now done). Delegates straight to an
 * `ApfDeviceServer` (apfDeviceServer.js), which owns the actual TLS
 * listener, APF handshake, and per-device channel bookkeeping -- this class
 * exists only to adapt that server's `openChannel` to the exact
 * `DeviceRegistry` contract documented above (in particular: reject with
 * `UnknownDeviceError` for a `deviceId` that isn't even in the trust store,
 * as distinct from one that's allowlisted but not currently connected).
 */
class ApfDeviceRegistry {
  constructor({ apfDeviceServer, trustStore }) {
    this.apfDeviceServer = apfDeviceServer;
    this.trustStore = trustStore;
  }

  async openChannel(deviceId, targetPort) {
    if (!this.trustStore.hasDeviceId(deviceId)) {
      throw new UnknownDeviceError(`"${deviceId}" is not in the APF device allowlist`);
    }
    return this.apfDeviceServer.openChannel(deviceId, targetPort);
  }
}

module.exports = { Channel, EchoChannel, EchoDeviceRegistry, ApfDeviceRegistry, UnknownDeviceError };
