package com.systemsgo.hex.ssh.protocol

import java.security.SecureRandom

/**
 * X11 FORWARDING FEATURE: MIT-MAGIC-COOKIE-1 handling for SSH X11 forwarding
 * (the equivalent of OpenSSH's `ssh -X`/`-Y`).
 *
 * Background — why a "cookie" is involved at all:
 *
 * X11 forwarding works by the SSH server allocating a *virtual* display
 * (e.g. `localhost:10.0`) on the remote host and pointing the remote shell's
 * `$DISPLAY` at it. Any GUI program the user runs remotely connects to that
 * virtual display exactly like it would to a real one, including the normal
 * X11 authentication handshake: it presents an MIT-MAGIC-COOKIE-1 (16 raw
 * bytes) that it read from the remote `~/.Xauthority`, and the party on the
 * other end has to recognise it.
 *
 * The SSH server can't just forward that authentication straight through,
 * though — the "other end" here is really the SSH *client* (this app),
 * relaying to the real local X server. If the client simply told the server
 * to accept the client's real local cookie, that cookie would then sit in
 * the remote host's `~/.Xauthority` in the clear, readable by anyone with
 * shell access there. So OpenSSH — and JSch, which this class supports —
 * do the standard trick instead:
 *
 *  1. The client generates a random, single-use "fake" cookie and hands it
 *     to the server as part of the `x11-req` request. That's the cookie
 *     that ends up in the remote `~/.Xauthority`, visible to anyone with
 *     shell access there, but it authenticates nothing else and is
 *     worthless once the session ends.
 *  2. The client separately configures the *real* cookie — the one its
 *     actual local X server expects — via [com.jcraft.jsch.Session]
 *     `setX11Cookie(...)`.
 *  3. When a forwarded X11 channel opens (a GUI program on the remote host
 *     connected to its virtual display), JSch checks the fake cookie the
 *     remote client presents, and if it matches the one issued in step 1,
 *     substitutes the *real* cookie before relaying the connection's first
 *     bytes to the local X server — so the local server's own auth check
 *     passes transparently, with the fake cookie having done nothing more
 *     than prove the connection actually came through this SSH session.
 *
 * This class only deals with the hex encoding/decoding and generation of
 * these 16-byte cookies; JSch performs the fake/real substitution itself
 * once given a real cookie via `setX11Cookie`.
 */
object X11AuthCookie {

    /** MIT-MAGIC-COOKIE-1 is always exactly 16 raw bytes (32 hex characters). */
    const val COOKIE_BYTES = 16

    /**
     * Generates a fresh random 16-byte cookie, hex-encoded (lowercase, no
     * separators) — the form JSch's `Session#setX11Cookie(String)` expects.
     *
     * Used when [com.systemsgo.hex.data.model.RdpProfile.x11AuthCookie] is
     * left blank: many mobile X server apps (Termux:X11, XSDL-style
     * servers) don't enforce a fixed cookie for local/loopback connections
     * in their default configuration, so a random value is accepted
     * regardless of its content — it just needs to *be* a well-formed
     * 16-byte MIT-MAGIC-COOKIE-1.
     */
    fun generateRandom(): String {
        val bytes = ByteArray(COOKIE_BYTES)
        SecureRandom().nextBytes(bytes)
        return toHex(bytes)
    }

    /**
     * Validates a user-supplied cookie string (e.g. pasted from the local
     * X server's `xauth list` output) is well-formed: exactly 32 hex
     * characters. Returns null (not a fallback to random — a malformed
     * cookie the user explicitly typed is a mistake worth surfacing) if
     * invalid, otherwise the normalised lowercase hex string.
     */
    fun validate(hex: String): String? {
        val trimmed = hex.trim()
        if (trimmed.length != COOKIE_BYTES * 2) return null
        if (!trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return trimmed.lowercase()
    }

    /**
     * Resolves the cookie to actually pass to JSch for a given profile
     * configuration: the validated user-supplied cookie if present and
     * well-formed, otherwise a freshly generated random one.
     */
    fun resolve(configuredHex: String): String {
        if (configuredHex.isNotBlank()) {
            validate(configuredHex)?.let { return it }
        }
        return generateRandom()
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
