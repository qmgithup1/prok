package com.systemsgo.hex.ssh.protocol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.systemsgo.hex.security.openEncryptedPrefs

/**
 * TOFU (Trust-On-First-Use) host-key repository, backed by
 * EncryptedSharedPreferences, shared by every JSch [com.jcraft.jsch.Session]
 * in the app (interactive SSH, RDP/VNC-over-SSH tunnels, Mosh's SSH
 * bootstrap step, NETCONF/SSH, and SFTP file transfer).
 *
 * - First connection to a host:port: key is accepted and persisted.
 * - Subsequent connections: key is compared; a mismatch returns CHANGED and
 *   JSch aborts with a JSchException — this is the MITM-detection guarantee.
 *
 * REFACTOR NOTE: this used to be a `private inner class` of [SshClient], so
 * no other JSch-based client could reuse it and each fell back to JSch's
 * in-memory "accept-new" default (fingerprints not persisted across process
 * restarts). Hoisted out to a top-level class, parameterized by
 * [appContext] and the [defaultPort] to use when JSch's `host` string for a
 * session doesn't carry an explicit `[host]:port` (JSch only adds the
 * bracketed form for non-default ports), so every caller gets identical
 * persisted TOFU behavior.
 *
 * One instance per logical connection (mirrors the old inner-class
 * lifetime) — [pendingKeys] tracks an in-flight "first use" key between
 * [check] and [add] and must not be shared across concurrent sessions to
 * different hosts, hence no shared/static state here (see BUG-L1 FIX
 * history below).
 */
class TofuHostKeyRepository(
    private val appContext: Context,
    private val defaultPort: Int,
    /** SharedPreferences file name — kept distinct per logical client so, e.g., an
     *  SSH-terminal fingerprint and a Mosh-bootstrap fingerprint for the same host:port
     *  don't collide if the two ever legitimately differ (e.g. a host is re-keyed for
     *  one service only). */
    prefsName: String = PREFS_TOFU_DEFAULT,
) : com.jcraft.jsch.HostKeyRepository {

    companion object {
        private const val TAG = "TofuHostKeyRepository"
        const val PREFS_TOFU_DEFAULT = "systemsgo_tofu_ssh"
    }

    // BUG-L1 FIX (carried over from SshClient): per-instance pending-key map, not a
    // shared/static one — two simultaneous connections to the same host must not let
    // check() from one overwrite the other's pending entry before add() persists it.
    private val pendingKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    // MED-R1 FIX (carried over): EncryptedSharedPreferences so fingerprints are
    // AES-256-GCM authenticated on disk — a root-privileged attacker can't silently
    // swap a stored fingerprint to defeat MITM detection.
    private val cachedPrefs: SharedPreferences by lazy {
        appContext.openEncryptedPrefs(prefsName)
    }
    private fun prefs(): SharedPreferences = cachedPrefs

    private fun hostMapKey(host: String): String {
        // JSch encodes non-default ports as "[host]:port"; normalise to "host:port".
        val bare = host.removePrefix("[").substringBefore("]")
        return if (':' in host) "$bare:${host.substringAfterLast(']').removePrefix(":")}"
        else "$bare:$defaultPort"
    }

    override fun check(host: String, key: ByteArray): Int {
        val mapKey = hostMapKey(host)
        val incomingB64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val storedB64 = prefs().getString(mapKey, null)
        return if (storedB64 == null) {
            // First time — stash for add(), let accept-new mode proceed
            pendingKeys[mapKey] = incomingB64
            com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED
        } else if (storedB64 == incomingB64) {
            com.jcraft.jsch.HostKeyRepository.OK
        } else {
            // Key has changed — likely MITM; JSch will throw JSchException
            Log.w(TAG, "SSH host key CHANGED for $mapKey — possible MITM!")
            com.jcraft.jsch.HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: com.jcraft.jsch.HostKey, ui: com.jcraft.jsch.UserInfo?) {
        // Called by JSch after accept-new auto-accepts a NOT_INCLUDED key.
        val mapKey = hostMapKey(hostkey.host)
        pendingKeys.remove(mapKey)?.let { key ->
            // LIVE-HIGH-1 FIX (carried over): commit() (synchronous), not apply().
            // apply() enqueues to a background thread — an OOM kill or Force Stop
            // before it runs means the TOFU fingerprint is never saved, so the next
            // connection silently re-accepts the server as "new" with no MITM warning.
            prefs().edit().putString(mapKey, key).commit()
        }
    }

    override fun remove(host: String?, type: String?) {
        if (host != null) prefs().edit().remove(hostMapKey(host)).commit()
    }
    override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)
    override fun getKnownHostsRepositoryID() = "systemsgo-tofu-ssh-prefs"
    override fun getHostKey() = emptyArray<com.jcraft.jsch.HostKey>()
    override fun getHostKey(host: String?, type: String?) = emptyArray<com.jcraft.jsch.HostKey>()
}
