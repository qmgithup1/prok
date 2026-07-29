package com.systemsgo.hex.amt.protocol

import android.content.Context
import com.systemsgo.hex.security.TofuTrustManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Intel AMT SOL (Serial-over-LAN) console — the AMT counterpart to
 * [com.systemsgo.hex.ipmi.protocol.IpmiSession]/`IpmiSolChannel`. AMT-VPRO
 * FEATURE phase 3 (see AMT_VPRO_ROADMAP.md).
 *
 * ## Corrected protocol basis (this class was previously built on a wrong
 * assumption — see [AmtIderSession]'s doc comment for the full account;
 * summarized here)
 * A management console connecting *directly* to AMT's dedicated
 * redirection port (16994/16995) — this app's mode, same as MeshCommander's
 * and meshcmd's "direct" connection mode — does **not** use the Intel AMT
 * Port Forwarding Protocol (APF): a prior version of this class assumed it
 * did (an unconfirmed inference, flagged as such in that version's doc
 * comment), which is now known to be wrong. Confirmed by MeshCentral's
 * `meshcmd` tool — a real, direct-connect command-line AMT console — in
 * `agents/modules_meshcmd/amt-redir-duk.js`/`amt-sol.js` (Ylian
 * Saint-Hilaire, Intel Corp., Apache-2.0): the actual session-establishment
 * protocol is a `StartRedirectionSession` (`0x10`, carrying a 4-byte ASCII
 * service tag — `"SOL "` here, confirmed to be the *real* SOL/IDE-R/KVM
 * sub-service selector that the old APF-based version had guessed at via
 * an unrelated channel field) / `StartRedirectionSessionReply` (`0x11`)
 * exchange, followed by an `AuthenticateSession` (`0x13`/`0x14`) exchange
 * that is itself a binary-framed HTTP Digest handshake (realm/nonce/qop,
 * `POST /RedirectionService` as the digest URI) — no APF messages anywhere.
 * Once authenticated, SOL (unlike IDE-R/KVM) has one more step of its own:
 * a small "serial settings" negotiation (`0x20`/`0x21`/`0x27`) before the
 * link is live and this class's own periodic keepalive (`0x2B`) starts.
 * All multi-byte fields in this protocol are little-endian throughout —
 * unlike the (now removed) APF layer, which was big-endian.
 *
 * (This codebase's separate CIRA/agent-side APF client,
 * `agents/modules_meshcmd/amt-apfclient.js`, confirms APF is real and
 * correctly implemented elsewhere in that project — just for the
 * device-initiates-outward CIRA case, not this one.)
 *
 * One [AmtSolSession] = one authenticated SOL channel to one AMT device.
 * Not thread-safe — callers should serialize send()/receive() the same way
 * [com.systemsgo.hex.ipmi.protocol.IpmiSolChannel] callers do.
 */
internal class AmtSolSession(
    private val host: String,
    private val redirectionPort: Int,
    private val useTls: Boolean,
    private val acceptSelfSignedCertificate: Boolean,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 8000,
    /** How long [receive] blocks waiting for a data chunk before returning
     *  null so the caller can poll/cancel — mirrors
     *  [com.systemsgo.hex.ipmi.protocol.IpmiSession]'s per-call timeout. */
    private val pollTimeoutMs: Int = 4000,
    /** SECURITY FIX (TLS-TOFU-PARITY): see [AmtClient]'s `appContext` doc
     *  comment — passed through from there so this session's TLS transport
     *  gets the same TOFU pinning instead of the trust-all fallback. */
    private val appContext: Context? = null,
    /** AMT-VPRO FEATURE phase 6 (CIRA): when non-null, [open] hands this
     *  transport straight to [transport] instead of dialing
     *  [host]/[redirectionPort] itself via [openTransport] — see
     *  [AmtRedirectionTransport]'s doc comment. Everything from
     *  `StartRedirectionSession` onward is unchanged either way; a
     *  [CiraRelayTransport] is indistinguishable to this class from a
     *  [DirectSocketTransport] once `open()` has assigned it. Null (the
     *  default) preserves every existing direct-connect call site
     *  unchanged. */
    private val externalTransport: AmtRedirectionTransport? = null,
) : AutoCloseable {

    private lateinit var transport: AmtRedirectionTransport
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    private var acc = ByteArray(0)
    private var outSeq = 1
    private var keepAliveTimer: Timer? = null

    @Volatile var established: Boolean = false
        private set

    // ── public: session lifecycle ──────────────────────────────────────

    /** Opens the redirection socket (or, when [externalTransport] was
     *  supplied, adopts it directly — see that field's doc comment) and
     *  completes the `StartRedirectionSession`("SOL ")/`AuthenticateSession`
     *  (HTTP Digest)/serial-settings handshake — see this class's top doc
     *  comment. Blocks until the link is live (AMT's `0x21` settings reply
     *  received) or throws [AmtException]. */
    fun open() {
        transport = externalTransport ?: DirectSocketTransport(openTransport())
        input = DataInputStream(transport.inputStream)
        output = DataOutputStream(transport.outputStream)
        transport.soTimeout = connectTimeoutMs

        sendRaw(byteArrayOf(0x10, 0x00, 0x00, 0x00) + SERVICE_TAG_SOL)
        readStartRedirectionSessionReply()
        sendRaw(byteArrayOf(0x13, 0, 0, 0, 0, 0, 0, 0, 0)) // AuthenticateSession: query supported methods
        authenticate()

        sendSerialSettings()
        awaitSettingsReply()

        keepAliveTimer = Timer("amt-sol-keepalive", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() { runCatching { sendRaw(byteArrayOf(0x2B, 0, 0, 0) + leInt(outSeq++)) } }
            }, 2000L, 2000L)
        }
        established = true
    }

    override fun close() {
        established = false
        keepAliveTimer?.cancel()
        keepAliveTimer = null
        runCatching { transport.close() }
    }

    /** Sends console input. SOL frames each chunk as its own `0x28` message
     *  (2-byte length prefix), so unlike a generic byte pipe there's no
     *  flow-control window to wait on — just cap each frame's size. */
    fun send(data: ByteArray) {
        check(established) { "AMT SOL session not established" }
        var offset = 0
        while (offset < data.size) {
            val len = minOf(MAX_CHUNK, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + len)
            sendRaw(byteArrayOf(0x28, 0, 0, 0) + leInt(outSeq++) + leShort(len) + chunk)
            offset += len
        }
    }

    /** Suspends (via blocking read with [pollTimeoutMs]) until a chunk of
     *  console output arrives, or returns null on timeout/normal close so
     *  the caller can poll again — same contract as
     *  `IpmiSolChannel.receive()`. */
    fun receive(): ByteArray? {
        val deadline = System.currentTimeMillis() + pollTimeoutMs
        transport.soTimeout = pollTimeoutMs
        while (established && System.currentTimeMillis() < deadline) {
            val chunk = try {
                transport.soTimeout = maxOf(100, (deadline - System.currentTimeMillis()).toInt())
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n < 0) { established = false; return null }
                buf.copyOf(n)
            } catch (_: SocketTimeoutException) {
                return null
            }
            acc += chunk
            while (true) {
                val (consumed, data) = drainOneDataFrame()
                if (consumed == 0) break
                acc = acc.copyOfRange(consumed, acc.size)
                if (data != null) return data
            }
        }
        return null
    }

    /** Parses and consumes exactly one incoming message from the front of
     *  [acc] if present: `0x2A` (display data — returns its payload),
     *  `0x29`/`0x2B` (serial settings/keepalive — consumed, no payload),
     *  or throws on anything else (stream would otherwise desync). Returns
     *  `(bytesConsumed, payloadOrNull)`; `bytesConsumed == 0` means "need
     *  more data". Mirrors `xxOnSocketData`'s steady-state cases. */
    private fun drainOneDataFrame(): Pair<Int, ByteArray?> {
        if (acc.isEmpty()) return 0 to null
        return when (acc[0].toInt() and 0xFF) {
            0x2A -> {
                if (acc.size < 10) return 0 to null
                val len = readLeShort(acc, 8)
                val total = 10 + len
                if (acc.size < total) return 0 to null
                total to acc.copyOfRange(10, total)
            }
            0x29 -> { if (acc.size < 10) return 0 to null; 10 to null }
            0x2B -> { if (acc.size < 8) return 0 to null; 8 to null }
            else -> throw AmtException("AMT SOL: unexpected message 0x${(acc[0].toInt() and 0xFF).toString(16)}")
        }
    }

    // ── StartRedirectionSession / AuthenticateSession handshake ─────────
    // Identical in shape to AmtIderSession's — see its doc comment/copy for
    // provenance. Duplicated here rather than shared because the two
    // classes' post-handshake framing diverges completely (SOL keeps using
    // this accumulator-based message dispatch; IDE-R switches to its own
    // fully separate envelope). A shared base class may be worth doing
    // later if a third protocol (KVM) needs the same handshake again.

    private fun readStartRedirectionSessionReply() {
        val head = readFully(13)
        val cmd = head[0].toInt() and 0xFF
        if (cmd != CMD_START_REDIRECTION_SESSION_REPLY) {
            throw AmtException("Expected StartRedirectionSessionReply (0x11), got 0x${cmd.toString(16)}")
        }
        val status = head[1].toInt() and 0xFF
        if (status != 0) {
            throw AmtException("AMT refused the SOL redirection session (status=$status) — SOL may be disabled in MEBx/AMT_RedirectionService, the session may already be in use by another console, or the user lacks the Redirection realm")
        }
        val oemLen = head[12].toInt() and 0xFF
        if (oemLen > 0) readFully(oemLen) // OEM string — present but unused
    }

    private fun authenticate() {
        while (true) {
            val head = readFully(9)
            val cmd = head[0].toInt() and 0xFF
            if (cmd != CMD_AUTHENTICATE_SESSION_REPLY) throw AmtException("Expected AuthenticateSessionReply (0x14), got 0x${cmd.toString(16)}")
            val status = head[1].toInt() and 0xFF
            val authType = head[4].toInt() and 0xFF
            val authDataLen = readLeInt(head, 5)
            val authData = readFully(authDataLen)

            when {
                status == 0 -> return // Authenticated.
                authType == 0 -> {
                    val supported = authData.map { it.toInt() and 0xFF }
                    when {
                        4 in supported -> sendAuthTypeRequest(4)
                        3 in supported -> sendAuthTypeRequest(3)
                        else -> throw AmtException("AMT SOL: AMT offered no supported authentication method (only Digest, types 3/4, is implemented)")
                    }
                }
                (authType == 3 || authType == 4) && status == 1 -> sendDigestResponse(authType, authData)
                else -> throw AmtException("AMT SOL authentication failed (status=$status) — check the profile's username/password and that this user has the Redirection realm")
            }
        }
    }

    private fun sendAuthTypeRequest(authType: Int) {
        val userBytes = username.toByteArray(Charsets.US_ASCII)
        val uriBytes = AUTH_URI.toByteArray(Charsets.US_ASCII)
        val len = userBytes.size + uriBytes.size + 8
        val body = byteArrayOf(0x13, 0, 0, 0, authType.toByte()) + leInt(len) +
            byteArrayOf(userBytes.size.toByte()) + userBytes + byteArrayOf(0, 0) +
            byteArrayOf(uriBytes.size.toByte()) + uriBytes + byteArrayOf(0, 0, 0, 0)
        sendRaw(body)
    }

    /** Computes and sends the HTTP Digest response. `nc` (nonce count) is
     *  hardcoded to `"00000002"`, matching the reference — this session
     *  only ever performs one authenticated exchange. */
    private fun sendDigestResponse(authType: Int, authData: ByteArray) {
        var p = 0
        val realmLen = authData[p].toInt() and 0xFF; p += 1
        val realm = String(authData, p, realmLen, Charsets.US_ASCII); p += realmLen
        val nonceLen = authData[p].toInt() and 0xFF; p += 1
        val nonce = String(authData, p, nonceLen, Charsets.US_ASCII); p += nonceLen
        var qop: String? = null
        if (authType == 4) {
            val qopLen = authData[p].toInt() and 0xFF; p += 1
            qop = String(authData, p, qopLen, Charsets.US_ASCII); p += qopLen
        }

        val cnonce = randomHex(32)
        val nc = "00000002"
        val extra = if (qop != null) "$nc:$cnonce:$qop:" else ""
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("POST:$AUTH_URI")
        val digest = md5Hex("$ha1:$nonce:$extra$ha2")

        val userBytes = username.toByteArray(Charsets.US_ASCII)
        val realmBytes = realm.toByteArray(Charsets.US_ASCII)
        val nonceBytes = nonce.toByteArray(Charsets.US_ASCII)
        val uriBytes = AUTH_URI.toByteArray(Charsets.US_ASCII)
        val cnonceBytes = cnonce.toByteArray(Charsets.US_ASCII)
        val ncBytes = nc.toByteArray(Charsets.US_ASCII)
        val digestBytes = digest.toByteArray(Charsets.US_ASCII)
        val qopBytes = qop?.toByteArray(Charsets.US_ASCII)

        var totalLen = userBytes.size + realmBytes.size + nonceBytes.size + uriBytes.size +
            cnonceBytes.size + ncBytes.size + digestBytes.size + 7
        if (authType == 4) totalLen += (qopBytes!!.size + 1)

        var body = byteArrayOf(0x13, 0, 0, 0, authType.toByte()) +
            byteArrayOf((totalLen and 0xFF).toByte(), ((totalLen shr 8) and 0xFF).toByte(), 0, 0) +
            byteArrayOf(userBytes.size.toByte()) + userBytes +
            byteArrayOf(realmBytes.size.toByte()) + realmBytes +
            byteArrayOf(nonceBytes.size.toByte()) + nonceBytes +
            byteArrayOf(uriBytes.size.toByte()) + uriBytes +
            byteArrayOf(cnonceBytes.size.toByte()) + cnonceBytes +
            byteArrayOf(ncBytes.size.toByte()) + ncBytes +
            byteArrayOf(digestBytes.size.toByte()) + digestBytes
        if (authType == 4) body += byteArrayOf(qopBytes!!.size.toByte()) + qopBytes
        sendRaw(body)
    }

    private fun md5Hex(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.US_ASCII))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(hexLen: Int): String {
        val bytes = ByteArray(hexLen / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── SOL-specific serial settings negotiation ─────────────────────────
    // Transcribed from amt-redir-duk.js's `status == 0` (protocol==1) and
    // `0x21` cases — the one extra step SOL has beyond IDE-R/KVM before the
    // link is live.

    private fun sendSerialSettings() {
        val maxTxBuffer = 10000
        val txTimeout = 100
        val txOverflowTimeout = 0
        val rxTimeout = 10000
        val rxFlushTimeout = 100
        val heartbeat = 0
        val body = leInt(outSeq++) + leShort(maxTxBuffer) + leShort(txTimeout) + leShort(txOverflowTimeout) +
            leShort(rxTimeout) + leShort(rxFlushTimeout) + leShort(heartbeat) + leInt(0)
        sendRaw(byteArrayOf(0x20, 0, 0, 0) + body)
    }

    /** Waits for `0x21` (fixed 23-byte reply, contents unused by any known
     *  client), then sends the `0x27` ack that the reference always sends
     *  verbatim. */
    private fun awaitSettingsReply() {
        while (true) {
            val chunk = readSome(pollTimeoutMs = 8000) ?: throw AmtException("AMT SOL: no settings reply within timeout")
            acc += chunk
            if (acc.isEmpty()) continue
            if ((acc[0].toInt() and 0xFF) != 0x21) throw AmtException("Expected SOL settings reply (0x21), got 0x${(acc[0].toInt() and 0xFF).toString(16)}")
            if (acc.size < 23) continue
            acc = acc.copyOfRange(23, acc.size)
            sendRaw(byteArrayOf(0x27, 0, 0, 0) + leInt(outSeq++) + byteArrayOf(0x00, 0x00, 0x1B, 0x00, 0x00, 0x00))
            return
        }
    }

    // ── raw socket I/O (no transport-layer wrapper — see top doc comment) ──

    private fun sendRaw(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    private fun readSome(pollTimeoutMs: Int): ByteArray? {
        transport.soTimeout = pollTimeoutMs
        val buf = ByteArray(4096)
        return try {
            val n = input.read(buf)
            if (n < 0) null else buf.copyOf(n)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun readFully(n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        input.readFully(buf)
        return buf
    }

    // ── transport setup (unchanged: plain TCP or TLS to the redirection port) ──

    private fun openTransport(): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, redirectionPort), connectTimeoutMs)
        if (!useTls) return raw
        // SECURITY FIX (TLS-TOFU-PARITY): this used to be a blind trust-all
        // X509TrustManager — see AmtClient's httpClient doc comment for the
        // full reasoning, which applies identically here since this is the
        // same "accept self-signed certificate" opt-in, just for the
        // redirection-port socket instead of the WS-Man HTTP client.
        // SECURITY FIX (TLS-TOFU-NO-FALLBACK): a missing appContext used to
        // fall back to a trust-all X509TrustManager (every certificate
        // accepted, no pinning, no MITM detection) — exactly the
        // vulnerability TofuTrustManager exists to close. Fail closed
        // instead of silently downgrading to an insecure connection.
        val trustManager = if (acceptSelfSignedCertificate) {
            val identity = "$host:$redirectionPort"
            appContext?.let { TofuTrustManager(it, identity) }
                ?: throw IllegalStateException(
                    "acceptSelfSignedCertificate is on for '$identity' but no appContext was " +
                        "supplied to AmtSolSession — TOFU certificate pinning requires a Context " +
                        "to store the pinned fingerprint. Refusing to connect with a trust-all " +
                        "fallback. Pass appContext to AmtSolSession's constructor.",
                )
        } else null
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManager?.let { arrayOf<javax.net.ssl.TrustManager>(it) }, SecureRandom())
        val sslSocket = sslContext.socketFactory.createSocket(raw, host, redirectionPort, true) as SSLSocket
        sslSocket.soTimeout = connectTimeoutMs
        sslSocket.startHandshake()
        return sslSocket
    }

    // ── little-endian wire primitives (this whole protocol is LE, unlike the removed APF layer) ──
    private fun leShort(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun leInt(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun readLeShort(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun readLeInt(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val CMD_START_REDIRECTION_SESSION_REPLY = 0x11
        private const val CMD_AUTHENTICATE_SESSION_REPLY = 0x14
        private val SERVICE_TAG_SOL = byteArrayOf('S'.code.toByte(), 'O'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte())
        private const val AUTH_URI = "/RedirectionService"

        private const val MAX_CHUNK = 4096
    }
}
