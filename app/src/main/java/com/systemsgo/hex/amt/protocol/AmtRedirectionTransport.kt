package com.systemsgo.hex.amt.protocol

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * PREP FOR AMT-VPRO FEATURE phase 6 (CIRA): the byte-stream surface that
 * [AmtSolSession], [AmtKvmSession], and [AmtIderSession] actually need from
 * their transport — nothing more. Extracted from what used to be a direct
 * `lateinit var socket: Socket` field in all three classes (grep confirms
 * that's genuinely all they touched: `getInputStream()`/`getOutputStream()`/
 * `soTimeout`/`close()` — never anything Socket-specific like the remote
 * address), so this is a pure mechanical rename, not a behaviour change.
 *
 * The point: today [DirectSocketTransport] is the only implementation (a
 * plain or TLS `Socket` straight to the AMT device's own IP, exactly as
 * before). CIRA support means the device isn't reachable by IP at all — the
 * *device* dials out to a Management Presence Server (MPS), and reaching it
 * means relaying `APF_CHANNEL_DATA` frames for a `forwarded-tcpip` channel
 * (on that MPS's already-open, device-initiated APF connection) through
 * whichever front-end protocol this app's chosen MPS/relay exposes to
 * consumer clients. Once that relay client exists, it only needs to
 * implement this same three-member interface — a
 * `CiraRelayTransport(session-handle-or-socket-to-relay)` — and
 * [AmtSolSession]/[AmtKvmSession]/[AmtIderSession] need no further changes
 * at all: their `open()` just assigns whichever transport it was given.
 *
 * Deliberately NOT done here (left for that future work, since it depends
 * on which relay protocol gets chosen — see AMT_VPRO_ROADMAP.md's CIRA
 * section): the actual CIRA/APF client, the relay-selection UI, and how a
 * session picks direct vs. relayed. This file only removes the busywork of
 * re-threading three already-tested classes once that decision is made.
 */
internal interface AmtRedirectionTransport {
    val inputStream: InputStream
    val outputStream: OutputStream

    /** Read timeout in milliseconds, same semantics as [Socket.setSoTimeout]
     *  — settable more than once over the transport's lifetime (these
     *  sessions tighten it after the initial connect-timeout window). */
    var soTimeout: Int

    fun close()
}

/** Wraps a plain or already-TLS-wrapped [Socket] — the only transport that
 *  exists today, and exactly the behaviour all three sessions' `open()`
 *  methods had inline before this file existed. */
internal class DirectSocketTransport(private val socket: Socket) : AmtRedirectionTransport {
    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()
    override var soTimeout: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    override fun close() = socket.close()
}
