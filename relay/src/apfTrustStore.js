'use strict';

const fs = require('fs');
const crypto = require('crypto');

/**
 * Device identity/trust policy for the APF-facing TLS listener
 * (apfDeviceServer.js), per the decisions confirmed with the user for this
 * pass (see AMT_VPRO_ROADMAP.md's Phase 6 section, "Not yet started" ->
 * now being implemented):
 *
 *   - `deviceId` mapping: layered — the APF username from
 *     `APF_USERAUTH_REQUEST` is the primary lookup key (an operator-chosen
 *     label, set into a device during AMT provisioning as its MPS
 *     credential username), cross-checked against a *pinned* SHA-256
 *     fingerprint of the device's TLS client certificate as a second,
 *     independent factor. Both must match the same allowlist entry for
 *     the connection to be accepted — a leaked/guessed password alone
 *     isn't enough (needs the paired certificate too), and a
 *     man-in-the-middle presenting an unexpected certificate is rejected
 *     even if it somehow knew a valid username/password.
 *   - Trust policy: explicit allowlist, no TOFU and no "accept anything"
 *     dev bypass — every entry is provisioned by whoever operates this
 *     relay, ahead of time, matching how a device's own
 *     `AMT_ManagementPresenceRemoteSAP`/certificate trust is provisioned
 *     out-of-band on the AMT side. There is no code path in this module
 *     that accepts an unlisted device, even transiently.
 *
 * Config: `APF_DEVICE_ALLOWLIST_FILE` (preferred — path to a JSON file,
 * reloaded on SIGHUP, keeps device secrets out of process env/argv dumps)
 * or `APF_DEVICE_ALLOWLIST` (inline JSON, convenient for local dev/CI).
 * Shape:
 *   [
 *     {
 *       "username": "device-boiler-room-01",
 *       "password": "correct-horse-battery-staple",
 *       "deviceId": "boiler-room-01",
 *       "certSha256": "AA:BB:CC:...:FF"   // 32 colon-separated hex bytes,
 *                                          // case-insensitive; this is the
 *                                          // fingerprint of the device's
 *                                          // TLS client certificate as
 *                                          // provisioned into AMT, not the
 *                                          // relay's own server cert.
 *     }
 *   ]
 * `deviceId` is what the app sends in its `channel-open` frame (see
 * CiraRelayTransport.kt / RdpProfile.ciraDeviceId) — it does not need to
 * equal `username`, keeping the app-facing identifier decoupled from the
 * device's own APF credential.
 */
class ApfTrustStore {
  constructor(entries) {
    this._byUsername = new Map();
    this._byDeviceId = new Map();
    for (const e of entries) {
      this._validateEntry(e);
      const normalized = { ...e, certSha256: normalizeFingerprint(e.certSha256) };
      this._byUsername.set(e.username, normalized);
      this._byDeviceId.set(e.deviceId, normalized);
    }
  }

  _validateEntry(e) {
    for (const field of ['username', 'password', 'deviceId', 'certSha256']) {
      if (typeof e[field] !== 'string' || e[field].length === 0) {
        throw new Error(`APF device allowlist entry missing/invalid "${field}": ${JSON.stringify(e)}`);
      }
    }
  }

  /** True if `deviceId` has any allowlist entry at all — used to give a
   * fast, clear "unknown device" rejection for `openChannel` before
   * bothering to check whether it's currently connected. */
  hasDeviceId(deviceId) {
    return this._byDeviceId.has(deviceId);
  }

  /**
   * Verifies an `APF_USERAUTH_REQUEST` (username/password) *and* the peer
   * TLS certificate presented on this connection together as one unit.
   * Returns the allowlist entry (including its `deviceId`) on success, or
   * `null` on any mismatch — deliberately not distinguishing *which* factor
   * failed in the return value, so callers don't leak that detail back to
   * the device on the wire (e.g. "bad username" vs "bad password" vs "bad
   * cert" would help an attacker enumerate valid usernames).
   */
  verify({ username, password, peerCertDer }) {
    const entry = this._byUsername.get(username);
    if (!entry) return null;
    if (!timingSafeEqualStr(entry.password, password)) return null;
    if (!peerCertDer) return null;
    const presentedFingerprint = sha256Fingerprint(peerCertDer);
    if (!timingSafeEqualStr(entry.certSha256, presentedFingerprint)) return null;
    return entry;
  }
}

function timingSafeEqualStr(a, b) {
  const ab = Buffer.from(String(a), 'utf8');
  const bb = Buffer.from(String(b), 'utf8');
  if (ab.length !== bb.length) {
    // Still do a constant-time-ish comparison against a same-length dummy
    // so this branch doesn't short-circuit obviously faster than a match.
    crypto.timingSafeEqual(ab, ab);
    return false;
  }
  return crypto.timingSafeEqual(ab, bb);
}

function sha256Fingerprint(certDer) {
  return normalizeFingerprint(crypto.createHash('sha256').update(certDer).digest('hex'));
}

/** Accepts "AA:BB:...", "aa:bb:...", or bare "aabb..." hex and normalizes
 * to lowercase colon-separated form so comparisons don't depend on
 * whatever format an operator pasted the fingerprint in as. */
function normalizeFingerprint(fp) {
  const hex = fp.replace(/:/g, '').toLowerCase();
  if (!/^[0-9a-f]+$/.test(hex) || hex.length % 2 !== 0) {
    throw new Error(`Invalid certificate fingerprint: ${fp}`);
  }
  return (hex.match(/../g) || []).join(':');
}

function loadTrustStoreFromConfig({ allowlistInline, allowlistFile }) {
  let raw;
  if (allowlistFile) {
    raw = fs.readFileSync(allowlistFile, 'utf8');
  } else if (allowlistInline) {
    raw = allowlistInline;
  } else {
    throw new Error(
      'Real APF device support requires APF_DEVICE_ALLOWLIST_FILE or APF_DEVICE_ALLOWLIST to be set — ' +
        'there is no TOFU/accept-anything mode for the device-facing listener (see apfTrustStore.js doc comment).',
    );
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    throw new Error(`APF device allowlist isn't valid JSON: ${e.message}`);
  }
  if (!Array.isArray(parsed)) throw new Error('APF device allowlist must be a JSON array');
  return new ApfTrustStore(parsed);
}

module.exports = { ApfTrustStore, loadTrustStoreFromConfig, sha256Fingerprint, normalizeFingerprint };
