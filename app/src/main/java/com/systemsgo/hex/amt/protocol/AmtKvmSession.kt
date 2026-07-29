package com.systemsgo.hex.amt.protocol

import android.content.Context
import android.graphics.BitmapFactory
import com.systemsgo.hex.security.TofuTrustManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Intel AMT KVM remote-desktop session — the AMT counterpart to
 * [com.systemsgo.hex.vnc.protocol.VncClient], and AMT-VPRO FEATURE phase 4
 * (see AMT_VPRO_ROADMAP.md).
 *
 * ## Corrected protocol basis (this class was previously built on a wrong
 * assumption — see [AmtIderSession]'s doc comment for the full account of
 * how this was discovered; summarized here)
 * A prior version of this class assumed AMT's redirection port "sniffs" the
 * first bytes of a new connection to decide between APF and raw RFB, and
 * that KVM traffic is therefore *plain* RFB (RFC 6143) from the first byte
 * — an unconfirmed inference, flagged as such in that version's doc
 * comment. That's wrong, the same way the same guess was wrong for SOL/
 * IDE-R: confirmed against MeshCentral's `meshcmd` tool
 * (`agents/modules_meshcmd/amt-redir-duk.js`, Ylian Saint-Hilaire, Intel
 * Corp., Apache-2.0), a direct-connect (non-CIRA) command-line AMT console
 * that implements KVM the same way it implements SOL/IDE-R: a
 * `StartRedirectionSession` (`0x10`, 4-byte ASCII tag `"KVMR"`) /
 * `StartRedirectionSessionReply` (`0x11`) exchange, then `AuthenticateSession`
 * (`0x13`/`0x14`, a binary-framed HTTP Digest handshake against `POST
 * /RedirectionService`, using the AMT device's normal Digest credentials —
 * see [digestUsername]/[digestPassword] — *not* the KVM redirection
 * password), then one more KVM-specific step: an 8-byte fixed
 * "start redirection" message (`0x40`) and its 8-byte fixed reply (`0x41`).
 * Only *after* that does genuine RFB begin on this same socket — negotiated
 * with the KVM redirection password ([kvmPassword]) as RFB's own
 * "VNC Authentication" security-type secret, which really is a separate
 * credential from the Digest one, same as this class's doc always said.
 * See [openTransport]/[openRedirectionSession] below; everything from
 * [negotiateVersion] onward is unchanged, genuine RFC 6143 RFB.
 *
 * (This codebase's separate CIRA/agent-side APF client,
 * `agents/modules_meshcmd/amt-apfclient.js`, confirms APF is real and
 * correctly implemented elsewhere in that project — just for the
 * device-initiates-outward CIRA case, never for any of SOL/IDE-R/KVM here.)
 *
 * The two things this class does that a stock RFB client wouldn't, beyond
 * the corrected preamble above:
 *  1. Enable the KVM SAP first via WS-Man ([AmtClient.enableKvmRedirection])
 *     before ever opening this session — KVM has its own enable/SAP-state
 *     path, `IPS_KVMRedirectionSettingData` + `CIM_KVMRedirectionSAP`,
 *     separate from `AMT_RedirectionService`'s SOL/IDE-R listener toggle.
 *  2. Speak RFB over AMT's TLS/plain redirection socket (port 16994/16995
 *     — the same port [AmtSolSession] uses for SOL, just prefixed with the
 *     preamble above) rather than a plain TCP 5900.
 *
 * ## Auth
 * Two independent secrets are involved, at two different layers: the AMT
 * device's normal Digest username/password ([digestUsername]/
 * [digestPassword]) authenticates the `AuthenticateSession` preamble above
 * (same credentials [AmtSolSession]/[AmtIderSession] use), while
 * [kvmPassword] — commonly provisioned equal to the AMT admin password but
 * logically separate (`IPS_KVMRedirectionSettingData.RFBPassword`) — is
 * used only for RFB's own Security Type 2 ("VNC Authentication", a DES
 * challenge-response — see [vncAuthResponse]) once genuine RFB starts.
 * RFB Security Type 1 ("None") is also handled, for SMB/lab-mode devices
 * with no KVM password set.
 *
 * Frames are exposed as raw decoded rectangles via [frames] rather than
 * assembled into a `Bitmap` here, so this class stays platform-agnostic —
 * the caller (`BmcManagementViewModel`) owns the `Bitmap`/canvas.
 *
 * One [AmtKvmSession] = one RFB connection to one AMT device. Not
 * thread-safe: [receiveFrame] must only be called from one
 * coroutine/loop at a time, same contract as [AmtSolSession.receive].
 */
internal class AmtKvmSession(
    private val host: String,
    private val redirectionPort: Int,
    private val useTls: Boolean,
    private val acceptSelfSignedCertificate: Boolean,
    private val kvmPassword: String,
    /** The AMT device's normal Digest username/password — authenticates
     *  the `AuthenticateSession` preamble, *not* RFB itself. See this
     *  class's top doc comment for why this is a separate secret from
     *  [kvmPassword]. */
    private val digestUsername: String,
    private val digestPassword: String,
    private val connectTimeoutMs: Int = 8000,
    private val pollTimeoutMs: Int = 4000,
    /** SECURITY FIX (TLS-TOFU-PARITY): see [AmtClient]'s `appContext` doc
     *  comment — passed through from there so this session's TLS transport
     *  gets the same TOFU pinning instead of the trust-all fallback. */
    private val appContext: Context? = null,
    /** AMT-VPRO FEATURE phase 6 (CIRA): see [AmtSolSession]'s identical
     *  parameter for the full reasoning — when non-null, [open] adopts this
     *  transport instead of dialing [host]/[redirectionPort] via
     *  [openTransport]. Null (the default) preserves every existing
     *  direct-connect call site unchanged. */
    private val externalTransport: AmtRedirectionTransport? = null,
) : AutoCloseable {

    private lateinit var transport: AmtRedirectionTransport
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    @Volatile var established: Boolean = false
        private set

    var framebufferWidth: Int = 0
        private set
    var framebufferHeight: Int = 0
        private set
    var desktopName: String = ""
        private set

    // Negotiated once in ClientInit/ServerInit and re-asserted via
    // SetPixelFormat below so every FramebufferUpdate rectangle this class
    // parses is always the one fixed 32bpp layout regardless of what the
    // server's ServerInit originally advertised — matches the "just pin a
    // known-good format" strategy most minimal RFB clients use instead of
    // handling all of RFB's pixel-format matrix.
    private val bytesPerPixel = 4

    // RFB-ENCODING FIX (AMT_VPRO_ROADMAP phase 4 follow-up, part 2): ZRLE
    // and Tight both mandate a *single persistent* zlib stream object per
    // RFB connection (RFC 6143 §7.6.9/§7.6.7 — "a single zlib stream object
    // is used for a given RFB connection, so rectangles must be encoded and
    // decoded strictly in order"). Tight additionally has *four* independent
    // streams (selected per-rectangle by the compression-control byte), not
    // one. These therefore live for the lifetime of this session object,
    // never reset except when the server's compression-control byte
    // explicitly asks for it (see readTightRect's per-bit reset handling) —
    // resetting them at the wrong point desyncs every subsequent rectangle's
    // decompression, not just the current one.
    private val zrleInflater = Inflater()
    private var zrleOutBuf = ByteArray(0)
    private var zrleOutPos = 0
    private val tightInflaters = Array(4) { Inflater() }
    private val tightOutBuf = arrayOfNulls<ByteArray>(4)
    private val tightOutPos = IntArray(4)

    // ── public: session lifecycle ──────────────────────────────────────

    fun open() {
        transport = externalTransport ?: DirectSocketTransport(openTransport())
        input = DataInputStream(transport.inputStream)
        output = DataOutputStream(transport.outputStream)
        transport.soTimeout = connectTimeoutMs

        openRedirectionSession() // StartRedirectionSession("KVMR") + AuthenticateSession + 0x40/0x41 — see top doc comment

        negotiateVersion()
        negotiateSecurity()
        readSecurityResult()

        writeByte(1) // ClientInit's one field, shared-flag=1: don't kick other viewers off — matches how the SOL/console tabs coexist with e.g. a concurrent MeshCommander session
        output.flush()
        readServerInit()

        sendSetPixelFormat()
        sendSetEncodings()
        requestFrame(incremental = false) // full frame to start

        established = true
    }

    override fun close() {
        established = false
        runCatching { transport.close() }
        // Inflater holds a native zlib context each — end() releases it
        // deterministically instead of waiting on GC/finalize.
        runCatching { zrleInflater.end() }
        tightInflaters.forEach { runCatching { it.end() } }
    }

    /** Requests the next screen update. [incremental] = true asks the
     *  server to send only changed rectangles (the steady-state case);
     *  false forces a full-framebuffer resend (used once at [open] and
     *  after anything that could have desynced the client's picture). */
    fun requestFrame(incremental: Boolean = true) {
        check(established || !incremental) { "AMT KVM session not established" }
        writeByte(FRAMEBUFFER_UPDATE_REQUEST)
        writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0)
        writeShort(framebufferWidth.takeIf { it > 0 } ?: 0xFFFF)
        writeShort(framebufferHeight.takeIf { it > 0 } ?: 0xFFFF)
        output.flush()
    }

    /** Blocks (bounded by [pollTimeoutMs]) for the next server message and
     *  returns the rectangles from one `FramebufferUpdate`, or an empty
     *  list on timeout/a non-framebuffer message (bell, server-cut-text,
     *  colour-map — all just drained and ignored, this being a
     *  KVM-for-admin-tasks client, not a general-purpose viewer) so the
     *  caller's poll loop keeps spinning — same "null/empty means keep
     *  polling" contract as [AmtSolSession.receive]. */
    fun receiveFrame(): List<AmtKvmRect> {
        transport.soTimeout = pollTimeoutMs
        val type = try {
            readByte()
        } catch (_: SocketTimeoutException) {
            return emptyList()
        }
        return when (type) {
            FRAMEBUFFER_UPDATE -> {
                readByte() // padding
                val numRects = readUShort()
                (0 until numRects).mapNotNull { readRectangle() }
            }
            SET_COLOUR_MAP_ENTRIES -> {
                readByte(); val firstColor = readUShort(); val n = readUShort()
                skip(n * 6); emptyList() // shouldn't occur with our fixed TrueColor SetPixelFormat, but drain rather than desync if a server sends it anyway
            }
            BELL -> emptyList()
            SERVER_CUT_TEXT -> { skip(3); val len = readInt(); skip(len); emptyList() }
            else -> emptyList() // unknown message type — nothing safe to do but stop trusting the stream this poll
        }
    }

    /** Sends a pointer (mouse) event — absolute position + button mask
     *  (bit0=left, bit1=middle, bit2=right, bit3/4=scroll up/down), exactly
     *  RFB's `PointerEvent` (§7.5.5). */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (!established) return
        writeByte(POINTER_EVENT)
        writeByte(buttonMask)
        writeShort(x.coerceIn(0, 0xFFFF))
        writeShort(y.coerceIn(0, 0xFFFF))
        output.flush()
    }

    /** Sends a key event. [keysym] is an X11 keysym, not a raw scancode —
     *  RFB's `KeyEvent` (§7.5.4) always speaks keysyms, same as
     *  [com.systemsgo.hex.vnc.protocol.VncClient]'s bVNC backend expects
     *  from its own input layer. */
    fun sendKeyEvent(keysym: Int, down: Boolean) {
        if (!established) return
        writeByte(KEY_EVENT)
        writeByte(if (down) 1 else 0)
        writeShort(0) // padding
        writeInt(keysym)
        output.flush()
    }

    // ── StartRedirectionSession / AuthenticateSession preamble, then KVM's
    // own 0x40/0x41 "start redirection" exchange — see this class's top doc
    // comment for provenance. Transcribed from meshcmd's amt-redir-duk.js.
    // Identical in shape to AmtSolSession's/AmtIderSession's handshake code;
    // duplicated rather than shared for the same reason noted in those
    // classes (each protocol's post-handshake framing diverges completely).

    private fun openRedirectionSession() {
        sendRaw(byteArrayOf(0x10, 0x01, 0x00, 0x00) + SERVICE_TAG_KVM)
        readStartRedirectionSessionReply()
        sendRaw(byteArrayOf(0x13, 0, 0, 0, 0, 0, 0, 0, 0)) // AuthenticateSession: query supported methods
        authenticate()

        // KVM-specific: "start redirection" (0x40) and its fixed 8-byte
        // reply (0x41) — the one extra step KVM has beyond the shared
        // preamble, before genuine RFB begins on this same socket.
        sendRaw(byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 0))
        val reply = readFully(8)
        if ((reply[0].toInt() and 0xFF) != 0x41) {
            throw AmtException("Expected KVM start-redirection reply (0x41), got 0x${(reply[0].toInt() and 0xFF).toString(16)}")
        }
    }

    private fun readStartRedirectionSessionReply() {
        val head = readFully(13)
        val cmd = head[0].toInt() and 0xFF
        if (cmd != CMD_START_REDIRECTION_SESSION_REPLY) {
            throw AmtException("Expected StartRedirectionSessionReply (0x11), got 0x${cmd.toString(16)}")
        }
        val status = head[1].toInt() and 0xFF
        if (status != 0) {
            throw AmtException("AMT refused the KVM redirection session (status=$status) — KVM may be disabled/not enabled (AmtClient.enableKvmRedirection), the session may already be in use by another console, or the user lacks the Redirection realm")
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
                        else -> throw AmtException("AMT KVM: AMT offered no supported authentication method (only Digest, types 3/4, is implemented)")
                    }
                }
                (authType == 3 || authType == 4) && status == 1 -> sendDigestResponse(authType, authData)
                else -> throw AmtException("AMT KVM redirection-session authentication failed (status=$status) — check the profile's Digest username/password and that this user has the Redirection realm")
            }
        }
    }

    private fun sendAuthTypeRequest(authType: Int) {
        val userBytes = digestUsername.toByteArray(Charsets.US_ASCII)
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
        val ha1 = md5Hex("$digestUsername:$realm:$digestPassword")
        val ha2 = md5Hex("POST:$AUTH_URI")
        val digest = md5Hex("$ha1:$nonce:$extra$ha2")

        val userBytes = digestUsername.toByteArray(Charsets.US_ASCII)
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

    private fun sendRaw(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    private fun readFully(n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        input.readFully(buf)
        return buf
    }

    private fun leInt(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun readLeInt(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // ── transport setup — same shape as AmtSolSession.openTransport ────

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
                        "supplied to AmtKvmSession — TOFU certificate pinning requires a Context " +
                        "to store the pinned fingerprint. Refusing to connect with a trust-all " +
                        "fallback. Pass appContext to AmtKvmSession's constructor.",
                )
        } else null
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManager?.let { arrayOf<javax.net.ssl.TrustManager>(it) }, SecureRandom())
        val sslSocket = sslContext.socketFactory.createSocket(raw, host, redirectionPort, true) as SSLSocket
        sslSocket.soTimeout = connectTimeoutMs
        sslSocket.startHandshake()
        return sslSocket
    }

    // ── RFB handshake (RFC 6143 §7.1-7.3) ───────────────────────────────

    private fun negotiateVersion() {
        val serverVersion = ByteArray(12)
        input.readFully(serverVersion)
        // Always reply with 3.8 regardless of what the server advertised —
        // every AMT firmware generation this app targets (Release 6.0+)
        // supports 3.8, and 3.8 is the version that gained the
        // multi-security-type negotiation [negotiateSecurity] relies on.
        output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun negotiateSecurity() {
        val count = readByte()
        if (count == 0) {
            // RFB 3.8: a zero count means the connection is being refused —
            // the body is a reason string, not a security-type list.
            val len = readInt()
            val reason = ByteArray(len).also { input.readFully(it) }
            throw AmtException("AMT refused the KVM connection: ${String(reason, Charsets.US_ASCII)}")
        }
        val types = ByteArray(count).also { input.readFully(it) }.map { it.toInt() and 0xFF }
        val chosen = when {
            SECURITY_VNC_AUTH in types && kvmPassword.isNotEmpty() -> SECURITY_VNC_AUTH
            SECURITY_NONE in types -> SECURITY_NONE
            SECURITY_VNC_AUTH in types -> SECURITY_VNC_AUTH // last resort even with an empty password — let AMT reject it with a clear SecurityResult failure rather than fail locally
            else -> throw AmtException("AMT KVM offered no supported RFB security type (got: $types)")
        }
        writeByte(chosen); output.flush()
        if (chosen == SECURITY_VNC_AUTH) {
            val challenge = ByteArray(16).also { input.readFully(it) }
            output.write(vncAuthResponse(challenge, kvmPassword))
            output.flush()
        }
    }

    private fun readSecurityResult() {
        val result = readInt()
        if (result != 0) {
            // RFB 3.8 SecurityResult failure includes a reason string.
            val len = readInt()
            val reason = ByteArray(len).also { input.readFully(it) }
            throw AmtException(
                "AMT KVM authentication failed: ${String(reason, Charsets.US_ASCII)} " +
                    "— check the KVM redirection password and that KVM is enabled (AmtClient.enableKvmRedirection)"
            )
        }
    }

    private fun readServerInit() {
        framebufferWidth = readUShort()
        framebufferHeight = readUShort()
        skip(16) // server's own PIXEL_FORMAT (16 bytes) — ignored, we override via SetPixelFormat below rather than adapting to whatever the server proposed
        val nameLen = readInt()
        val nameBytes = ByteArray(nameLen).also { input.readFully(it) }
        desktopName = String(nameBytes, Charsets.US_ASCII)
    }

    /** Pins a fixed 32bpp little-endian TrueColor pixel format so
     *  [readRectangle]'s Raw-encoding decode below never has to branch on
     *  bits-per-pixel/byte order — the same "ask the server to conform to
     *  us" approach [com.systemsgo.hex.vnc.protocol.VncClient]'s bVNC
     *  backend takes internally, just done by hand here. */
    private fun sendSetPixelFormat() {
        writeByte(SET_PIXEL_FORMAT)
        writeByte(0); writeShort(0) // 3 bytes padding
        writeByte(32) // bits-per-pixel
        writeByte(24) // depth
        writeByte(0) // big-endian-flag = 0 (little-endian)
        writeByte(1) // true-colour-flag
        writeShort(255); writeShort(255); writeShort(255) // red/green/blue max
        writeByte(16); writeByte(8); writeByte(0) // red/green/blue shift
        output.write(ByteArray(3)) // padding
        output.flush()
    }

    /** RFB-ENCODING FIX (AMT_VPRO_ROADMAP phase 4 follow-up, part 2): Tight
     *  (RFC 6143 §7.6.7) and ZRLE (§7.6.9) added alongside Raw/CopyRect/
     *  Hextile — closing the gap the previous revision of this class left
     *  open (see git history / the old version of this comment for why
     *  Hextile alone shipped first). Both need a zlib decompressor, which
     *  `java.util.zip.Inflater` supplies natively, so no external
     *  dependency was needed; Tight's optional JPEG subrects are decoded
     *  via `android.graphics.BitmapFactory`, likewise already part of the
     *  platform. See [readTightRect]/[readZrleRect] for the decode logic
     *  and exactly which corners of each spec were left as a documented
     *  gap rather than guessed at: the rare explicit-no-zlib subencoding
     *  (never sent by any server this class's own capability advertisement
     *  provokes) and JPEG chroma subsampling nuances (already handled by
     *  the platform decoder regardless). Tight's "gradient" filter -- the
     *  one gap this doc comment used to also list -- is now implemented;
     *  see [readTightFilteredPixels]'s doc comment for its exact predictor
     *  semantics, confirmed against the RFB/TightVNC spec's own
     *  reconstruction pseudo-code.
     *
     *  Listed in preference order, most-preferred first (per §7.6's "the
     *  order... indicates a preference"): Tight and ZRLE are both far more
     *  bandwidth-efficient than Hextile for the mixed UI/text content a
     *  KVM redirection session typically shows, which matters most on the
     *  slow out-of-band management link IDE-R/KVM run over. RAW is still
     *  listed last since every RFB server must support it as an
     *  unconditional fallback regardless of what else it can do. */
    private fun sendSetEncodings() {
        writeByte(SET_ENCODINGS)
        writeByte(0) // padding
        writeShort(5)
        writeInt(ENCODING_TIGHT)
        writeInt(ENCODING_ZRLE)
        writeInt(ENCODING_HEXTILE)
        writeInt(ENCODING_COPY_RECT)
        writeInt(ENCODING_RAW)
        output.flush()
    }

    private fun readRectangle(): AmtKvmRect? {
        val x = readUShort(); val y = readUShort()
        val w = readUShort(); val h = readUShort()
        val encoding = readInt()
        return when (encoding) {
            ENCODING_RAW -> {
                val pixels = IntArray(w * h)
                val row = ByteArray(w * bytesPerPixel)
                for (r in 0 until h) {
                    input.readFully(row)
                    for (c in 0 until w) {
                        val o = c * bytesPerPixel
                        // little-endian per sendSetPixelFormat: byte0=B,1=G,2=R,3=unused
                        val b = row[o].toInt() and 0xFF
                        val g = row[o + 1].toInt() and 0xFF
                        val rr = row[o + 2].toInt() and 0xFF
                        pixels[r * w + c] = (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                    }
                }
                AmtKvmRect(x, y, w, h, pixels)
            }
            ENCODING_COPY_RECT -> {
                val srcX = readUShort(); val srcY = readUShort()
                AmtKvmRect(x, y, w, h, pixels = null, copySrcX = srcX, copySrcY = srcY)
            }
            ENCODING_HEXTILE -> AmtKvmRect(x, y, w, h, pixels = readHextileRect(w, h))
            ENCODING_ZRLE -> AmtKvmRect(x, y, w, h, pixels = readZrleRect(w, h))
            ENCODING_TIGHT -> AmtKvmRect(x, y, w, h, pixels = readTightRect(w, h))
            else -> null // shouldn't happen — SetEncodings above only advertised Raw/CopyRect/Hextile/ZRLE/Tight
        }
    }

    /** Decodes one Hextile-encoded rectangle into the same row-major
     *  0xAARRGGBB pixel layout [readRectangle]'s Raw branch produces, so
     *  callers never need to know which encoding a given rect arrived as.
     *
     *  Per RFC 6143 §7.7.3: the rectangle is split into 16x16 tiles (the
     *  last row/column of tiles may be smaller if [w]/[h] isn't a multiple
     *  of 16), read left-to-right, top-to-bottom. Each tile starts with a
     *  1-byte subencoding mask:
     *   - bit 0 (Raw): the tile is [tileW]*[tileH] raw pixels, in which
     *     case none of the other bits apply.
     *   - otherwise, the tile is background colour + 0 or more solid-colour
     *     subrectangles:
     *     - bit 1 (BackgroundSpecified): a new background pixel value
     *       follows; else the previous tile's background carries over
     *       (undefined for the very first tile of a rect if unset, but
     *       every real server always sets it there — matches other RFB
     *       client implementations' behaviour of just keeping whatever
     *       default, 0, that leaves).
     *     - bit 2 (ForegroundSpecified): likewise for the foreground
     *       colour, used by uncoloured subrects below.
     *     - bit 3 (AnySubrects): a 1-byte subrect count follows, then that
     *       many subrects.
     *     - bit 4 (SubrectsColoured): each subrect carries its own pixel
     *       value instead of using the shared foreground colour.
     *     Each subrect is 2 bytes: packed x/y (4 bits each, tile-relative)
     *     then packed (width-1)/(height-1) (4 bits each).
     *  Background/foreground persist across tiles *within this rectangle
     *  only* — reset to 0 at the start of every new rectangle, matching
     *  the spec's silence on any cross-rectangle persistence. */
    private fun readHextileRect(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        var background = 0
        var foreground = 0
        var tileY = 0
        while (tileY < h) {
            val tileH = minOf(HEXTILE_SIZE, h - tileY)
            var tileX = 0
            while (tileX < w) {
                val tileW = minOf(HEXTILE_SIZE, w - tileX)
                val mask = readByte()
                if (mask and HEXTILE_RAW != 0) {
                    val row = ByteArray(tileW * bytesPerPixel)
                    for (ry in 0 until tileH) {
                        input.readFully(row)
                        val rowBase = (tileY + ry) * w + tileX
                        for (rx in 0 until tileW) {
                            val o = rx * bytesPerPixel
                            val b = row[o].toInt() and 0xFF
                            val g = row[o + 1].toInt() and 0xFF
                            val r = row[o + 2].toInt() and 0xFF
                            pixels[rowBase + rx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                } else {
                    if (mask and HEXTILE_BACKGROUND_SPECIFIED != 0) background = readHextilePixelValue()
                    for (ry in 0 until tileH) {
                        val rowBase = (tileY + ry) * w + tileX
                        for (rx in 0 until tileW) pixels[rowBase + rx] = background
                    }
                    if (mask and HEXTILE_FOREGROUND_SPECIFIED != 0) foreground = readHextilePixelValue()
                    if (mask and HEXTILE_ANY_SUBRECTS != 0) {
                        val subrectCount = readByte()
                        val coloured = mask and HEXTILE_SUBRECTS_COLOURED != 0
                        repeat(subrectCount) {
                            val color = if (coloured) readHextilePixelValue() else foreground
                            val xy = readByte()
                            val wh = readByte()
                            val subX = (xy shr 4) and 0x0F
                            val subY = xy and 0x0F
                            val subW = ((wh shr 4) and 0x0F) + 1
                            val subH = (wh and 0x0F) + 1
                            for (ry in 0 until subH) {
                                val py = tileY + subY + ry
                                if (py >= tileY + tileH) continue // clamp: a malformed/edge subrect must never overrun into the next tile row
                                val rowBase = py * w + tileX + subX
                                for (rx in 0 until subW) {
                                    if (subX + rx >= tileW) continue // same clamp, column-wise
                                    pixels[rowBase + rx] = color
                                }
                            }
                        }
                    }
                }
                tileX += HEXTILE_SIZE
            }
            tileY += HEXTILE_SIZE
        }
        return pixels
    }

    /** One [bytesPerPixel]-sized pixel value in the fixed 32bpp
     *  little-endian TrueColor format [sendSetPixelFormat] negotiated —
     *  same byte order as the Raw decode above (B, G, R, unused). Hextile
     *  background/foreground/subrect colours are always exactly one pixel
     *  value in this format, regardless of tile size. */
    private fun readHextilePixelValue(): Int {
        val b = readByte(); val g = readByte(); val r = readByte(); readByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ── ZRLE (RFC 6143 §7.6.9) ───────────────────────────────────────────
    //
    // Wire shape: 4-byte length, then that many bytes of zlib-compressed
    // data (fed into the single persistent [zrleInflater] — see its field
    // doc comment for why it must never be reset per-rectangle). Decompressed,
    // that data is a sequence of up to-64x64 tiles in left-to-right,
    // top-to-bottom order (edge tiles clipped, same as Hextile's 16x16
    // tiling above). Each tile starts with a 1-byte subencoding:
    //   0        Raw            — tileW*tileH CPIXELs, row-major.
    //   1        Solid          — one CPIXEL fills the whole tile.
    //   2..16    Packed palette — N=subencoding CPIXELs, then indices
    //            packed at 1/2/4 bits-per-pixel (N<=2/4/16), each row
    //            byte-aligned (a fresh byte is started at the left edge
    //            of every row, per RFC 6143's palette packing rule).
    //   128      Plain RLE      — repeated (CPIXEL, run-length) pairs
    //            covering the tile in raster order.
    //   130..255 Palette RLE    — N=(subencoding-128) CPIXELs, then
    //            (index [+run-length if index's top bit is set]) pairs.
    // 17-127/129 are unused by any encoder and never sent.
    //
    // CPIXEL (RFC 6143 §7.6.9): identical to our fixed pixel format's PIXEL
    // except the padding byte is dropped whenever bpp=32 and depth<=24 —
    // [sendSetPixelFormat] always pins bpp=32/depth=24, so CPIXEL is always
    // exactly 3 bytes here (B,G,R — matching this class's other decoders'
    // little-endian byte order, since ZRLE's CPIXEL is just a truncated
    // PIXEL in whatever byte order SetPixelFormat negotiated, not a
    // separately-specified R,G,B order the way Tight's CPIXEL is below).

    private fun readZrleRect(w: Int, h: Int): IntArray {
        val compressedLen = readInt()
        val compressed = ByteArray(compressedLen).also { input.readFully(it) }
        zrleInflater.setInput(compressed)
        val pixels = IntArray(w * h)
        var tileY = 0
        while (tileY < h) {
            val tileH = minOf(ZRLE_TILE_SIZE, h - tileY)
            var tileX = 0
            while (tileX < w) {
                val tileW = minOf(ZRLE_TILE_SIZE, w - tileX)
                readZrleTile(pixels, w, tileX, tileY, tileW, tileH)
                tileX += ZRLE_TILE_SIZE
            }
            tileY += ZRLE_TILE_SIZE
        }
        return pixels
    }

    private fun readZrleTile(pixels: IntArray, stride: Int, tileX: Int, tileY: Int, tileW: Int, tileH: Int) {
        val total = tileW * tileH
        when (val subencoding = zrleReadByte()) {
            0 -> { // Raw
                for (ry in 0 until tileH) {
                    val rowBase = (tileY + ry) * stride + tileX
                    for (rx in 0 until tileW) pixels[rowBase + rx] = zrleReadCPixel()
                }
            }
            1 -> { // Solid
                val color = zrleReadCPixel()
                for (ry in 0 until tileH) {
                    val rowBase = (tileY + ry) * stride + tileX
                    for (rx in 0 until tileW) pixels[rowBase + rx] = color
                }
            }
            in 2..16 -> { // Packed palette
                val palette = IntArray(subencoding) { zrleReadCPixel() }
                val bitsPerIndex = when {
                    subencoding <= 2 -> 1
                    subencoding <= 4 -> 2
                    else -> 4
                }
                val mask = (1 shl bitsPerIndex) - 1
                for (ry in 0 until tileH) {
                    val rowBase = (tileY + ry) * stride + tileX
                    var bitBuf = 0
                    var bitsLeft = 0
                    for (rx in 0 until tileW) {
                        if (bitsLeft == 0) { bitBuf = zrleReadByte(); bitsLeft = 8 }
                        val idx = (bitBuf shr (bitsLeft - bitsPerIndex)) and mask
                        bitsLeft -= bitsPerIndex
                        pixels[rowBase + rx] = palette[idx]
                    }
                    // Rows are byte-aligned — any unused low bits left in bitBuf
                    // are simply dropped; the next row starts a fresh byte via
                    // the bitsLeft==0 check above.
                }
            }
            128 -> { // Plain RLE
                var count = 0
                while (count < total) {
                    val color = zrleReadCPixel()
                    var runLength = 1
                    var b: Int
                    do { b = zrleReadByte(); runLength += b } while (b == 255)
                    repeat(minOf(runLength, total - count)) {
                        pixels[(tileY + count / tileW) * stride + tileX + count % tileW] = color
                        count++
                    }
                }
            }
            in 130..255 -> { // Palette RLE
                val palette = IntArray(subencoding - 128) { zrleReadCPixel() }
                var count = 0
                while (count < total) {
                    val indexByte = zrleReadByte()
                    val color = palette[indexByte and 0x7F]
                    val runLength = if (indexByte and 0x80 != 0) {
                        var run = 1
                        var b: Int
                        do { b = zrleReadByte(); run += b } while (b == 255)
                        run
                    } else 1
                    repeat(minOf(runLength, total - count)) {
                        pixels[(tileY + count / tileW) * stride + tileX + count % tileW] = color
                        count++
                    }
                }
            }
            else -> throw AmtException("AMT KVM: unsupported ZRLE tile subencoding $subencoding")
        }
    }

    /** Pulls one decompressed byte from [zrleInflater], refilling
     *  [zrleOutBuf] from the compressed data already handed to the
     *  inflater by [readZrleRect] as needed. */
    private fun zrleReadByte(): Int {
        if (zrleOutPos >= zrleOutBuf.size) {
            val fresh = ByteArray(4096)
            val n = zrleInflater.inflate(fresh)
            if (n == 0) {
                throw AmtException("AMT KVM: ZRLE stream produced no data — compressed block truncated or corrupt")
            }
            zrleOutBuf = fresh
            zrleOutPos = 0
            // n can be less than fresh.size; only the first n bytes are valid.
            if (n < fresh.size) zrleOutBuf = fresh.copyOf(n)
        }
        return zrleOutBuf[zrleOutPos++].toInt() and 0xFF
    }

    private fun zrleReadCPixel(): Int {
        val b = zrleReadByte(); val g = zrleReadByte(); val r = zrleReadByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ── Tight (RFC 6143 §7.6.7) ───────────────────────────────────────────
    //
    // Every Tight rectangle starts with a 1-byte compression-control byte:
    //   bits 0-3: "reset zlib stream N before use" flags, one bit per
    //             stream (Tight keeps 4 independent zlib streams, unlike
    //             ZRLE's single one — see [tightInflaters]) — must be
    //             honoured even for the fill/JPEG compression types below,
    //             which don't themselves use zlib, per RFC 6143 NOTE 2.
    //   bits 4-7: compression type — 0x8=fill, 0x9=jpeg, 0x0-0x7=basic
    //             (zlib-compressed; bits 5-4 *of that nibble* select which
    //             of the 4 streams, bit 6 flags an explicit filter-id byte).
    // Left as a documented gap rather than guessed at: the rare explicit
    // "basic, no zlib" subencodings (control nibble 0x0A/0x0E — only ever
    // sent for tiny/incompressible tiles, and reserved/undefined by RFC
    // 6143 §7.6.7 in the first place — bit 7 set means fill/JPEG (0x8/0x9),
    // bit 7 clear means basic, so 0xA-0xF simply aren't valid control
    // nibbles any compliant server can emit). All three real Tight filters
    // — Copy, Palette, and Gradient — are implemented; see
    // [readTightFilteredPixels]'s doc comment for the Gradient predictor.
    private fun readTightRect(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        val ctrl = readByte()
        for (i in 0 until 4) {
            if (ctrl and (1 shl i) != 0) {
                tightInflaters[i].reset()
                tightOutBuf[i] = null
                tightOutPos[i] = 0
            }
        }
        when (val compType = (ctrl shr 4) and 0x0F) {
            0x08 -> { // Fill — one CPIXEL, uncompressed, fills the whole rectangle
                val color = readTightCPixelRaw()
                pixels.fill(color)
            }
            0x09 -> { // JPEG — compact-length-prefixed JPEG stream, uncompressed (no zlib)
                val data = readTightCompactData()
                decodeTightJpegInto(pixels, w, h, data)
            }
            in 0x00..0x07 -> { // Basic, zlib-compressed
                val streamId = (ctrl shr 4) and 0x03
                val hasFilterId = (ctrl and 0x40) != 0
                val filterId = if (hasFilterId) readByte() else TIGHT_FILTER_COPY
                val compressed = readTightCompactData()
                tightInflaters[streamId].setInput(compressed)
                readTightFilteredPixels(pixels, w, h, streamId, filterId)
            }
            else -> throw AmtException("AMT KVM: unsupported Tight compression-control nibble 0x${compType.toString(16)}")
        }
        return pixels
    }

    /** Dispatches to the filter this compType's compressed payload was pre-processed with — see the class doc comment above for which filter this client supports.
     *
     *  **Gradient filter** (RFC 6143 §7.6.7 / TightVNC spec): the encoder
     *  pre-processes each colour component (R, G, B independently) with a
     *  simple two-neighbour-plus-corner predictor before zlib compression,
     *  which improves compression of photo-like content without changing
     *  the uncompressed size. Per component, with V[i,j] the true 0-255
     *  intensity at row i, column j (V is taken as 0 for any coordinate
     *  outside the rectangle, including i=-1/j=-1):
     *
     *      P[i,j] = clamp(V[i-1,j] + V[i,j-1] - V[i-1,j-1], 0, 255)
     *      D[i,j] = (V[i,j] - P[i,j]) mod 256      // what's on the wire
     *
     *  Decoding reverses this by reconstructing V left-to-right, top-to-
     *  bottom (so V[i-1,j], V[i,j-1], V[i-1,j-1] are always already known)
     *  and adding the predictor back, again mod 256 — byte-wise unsigned
     *  arithmetic, which is what "mod 256" reduces to for `Int and 0xFF`:
     *
     *      V[i,j] = (D[i,j] + P[i,j]) and 0xFF
     *
     *  This is the RFB/TightVNC spec's own reconstruction pseudo-code
     *  (RGB byte order, since Tight's CPIXEL is always R,G,B regardless of
     *  negotiated pixel format — see [tightReadCPixel]'s doc comment) —
     *  not reverse-engineered from any particular server or client
     *  implementation.
     */
    private fun readTightFilteredPixels(pixels: IntArray, w: Int, h: Int, streamId: Int, filterId: Int) {
        when (filterId) {
            TIGHT_FILTER_COPY -> {
                for (i in 0 until w * h) pixels[i] = tightReadCPixel(streamId)
            }
            TIGHT_FILTER_PALETTE -> {
                val paletteSize = tightReadByte(streamId) + 1 // byte holds count-1
                val palette = IntArray(paletteSize) { tightReadCPixel(streamId) }
                if (paletteSize == 2) {
                    // 1 bit per pixel, MSB-first, each row byte-aligned.
                    for (ry in 0 until h) {
                        var bitBuf = 0; var bitsLeft = 0
                        val rowBase = ry * w
                        for (rx in 0 until w) {
                            if (bitsLeft == 0) { bitBuf = tightReadByte(streamId); bitsLeft = 8 }
                            val idx = (bitBuf shr (bitsLeft - 1)) and 0x01
                            bitsLeft--
                            pixels[rowBase + rx] = palette[idx]
                        }
                    }
                } else {
                    for (i in 0 until w * h) pixels[i] = palette[tightReadByte(streamId)]
                }
            }
            TIGHT_FILTER_GRADIENT -> readTightGradientPixels(pixels, w, h, streamId)
            else -> throw AmtException("AMT KVM: unsupported Tight filter id $filterId")
        }
    }

    /** Reconstructs a Gradient-filtered rectangle — see
     *  [readTightFilteredPixels]'s doc comment for the predictor formula.
     *  Keeps only the previous row of true (unfiltered) component values
     *  in memory (`aboveR/G/B`), since the predictor never looks further
     *  back than one row or one column. */
    private fun readTightGradientPixels(pixels: IntArray, w: Int, h: Int, streamId: Int) {
        var aboveR = IntArray(w) // V[i-1, j] per column, from the row just decoded
        var aboveG = IntArray(w)
        var aboveB = IntArray(w)
        for (ry in 0 until h) {
            var leftR = 0; var leftG = 0; var leftB = 0 // V[i, j-1]; 0 at the left edge
            var aboveLeftR = 0; var aboveLeftG = 0; var aboveLeftB = 0 // V[i-1, j-1]
            val curR = IntArray(w); val curG = IntArray(w); val curB = IntArray(w)
            val rowBase = ry * w
            for (rx in 0 until w) {
                val upR = aboveR[rx]; val upG = aboveG[rx]; val upB = aboveB[rx]

                val predR = (leftR + upR - aboveLeftR).coerceIn(0, 255)
                val predG = (leftG + upG - aboveLeftG).coerceIn(0, 255)
                val predB = (leftB + upB - aboveLeftB).coerceIn(0, 255)

                val dR = tightReadByte(streamId)
                val dG = tightReadByte(streamId)
                val dB = tightReadByte(streamId)

                val vR = (dR + predR) and 0xFF
                val vG = (dG + predG) and 0xFF
                val vB = (dB + predB) and 0xFF

                curR[rx] = vR; curG[rx] = vG; curB[rx] = vB
                pixels[rowBase + rx] = (0xFF shl 24) or (vR shl 16) or (vG shl 8) or vB

                aboveLeftR = upR; aboveLeftG = upG; aboveLeftB = upB
                leftR = vR; leftG = vG; leftB = vB
            }
            aboveR = curR; aboveG = curG; aboveB = curB
        }
    }

    /** Pulls one decompressed byte from [tightInflaters]`[streamId]`,
     *  refilling that stream's output buffer as needed — same pull-based
     *  pattern as [zrleReadByte], just per-stream instead of singular. */
    private fun tightReadByte(streamId: Int): Int {
        var buf = tightOutBuf[streamId]
        if (buf == null || tightOutPos[streamId] >= buf.size) {
            val fresh = ByteArray(4096)
            val n = tightInflaters[streamId].inflate(fresh)
            if (n == 0) {
                throw AmtException("AMT KVM: Tight zlib stream $streamId produced no data — compressed block truncated or corrupt")
            }
            buf = if (n < fresh.size) fresh.copyOf(n) else fresh
            tightOutBuf[streamId] = buf
            tightOutPos[streamId] = 0
        }
        return buf[tightOutPos[streamId]++].toInt() and 0xFF
    }

    /** Tight's CPIXEL byte order is R,G,B (RFC 6143 §7.6.7 NOTE 1) — the
     *  *opposite* of this class's other decoders' little-endian B,G,R,
     *  because unlike ZRLE's CPIXEL (a truncated copy of whatever
     *  SetPixelFormat negotiated), Tight always specifies R,G,B
     *  explicitly regardless of pixel format. */
    private fun tightReadCPixel(streamId: Int): Int {
        val r = tightReadByte(streamId); val g = tightReadByte(streamId); val b = tightReadByte(streamId)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun readTightCPixelRaw(): Int {
        val r = readByte(); val g = readByte(); val b = readByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Reads a Tight "compact length" (1-3 bytes, base-128 varint, RFC
     *  6143 §7.6.7) followed by that many raw bytes — used for both the
     *  zlib-compressed "basic" payload and the JPEG stream. */
    private fun readTightCompactData(): ByteArray {
        var value = 0
        var shift = 0
        while (true) {
            val b = readByte()
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return ByteArray(value).also { input.readFully(it) }
    }

    /** JPEG subrects are a normal JPEG stream covering exactly this
     *  rectangle's pixels — decoded via the platform's own decoder
     *  (Android has no OS-level libjpeg-turbo/system JPEG library exposed
     *  any other way) rather than a hand-rolled JPEG decoder, which would
     *  be a large, error-prone undertaking of its own and buys nothing
     *  over the platform decoder's already-hardware-accelerated path. */
    private fun decodeTightJpegInto(pixels: IntArray, w: Int, h: Int, jpegData: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            ?: throw AmtException("AMT KVM: server sent an unparseable JPEG tile")
        try {
            if (bmp.width != w || bmp.height != h) {
                throw AmtException("AMT KVM: JPEG tile size ${bmp.width}x${bmp.height} doesn't match rectangle ${w}x$h")
            }
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        } finally {
            bmp.recycle()
        }
    }

    // ── VNC DES challenge-response (RFC 6143 §7.2.2) ────────────────────
    // Classic VNC auth: the 8-byte password (truncated/null-padded) is used
    // as a DES key, but with every key byte's *bits* reversed first — an
    // original RealVNC quirk (the DES spec's bit numbering is MSB-first;
    // VNC's password-to-key step was implemented against a LSB-first
    // convention and the mismatch shipped as a de-facto standard everyone,
    // including this Intel-licensed KVM core, still has to replicate).
    // ECB mode, no padding, encrypting (not decrypting) the challenge.

    private fun vncAuthResponse(challenge: ByteArray, password: String): ByteArray {
        val keyBytes = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 8) keyBytes[i] = if (i < pwBytes.size) reverseBits(pwBytes[i]) else 0
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var r = 0
        for (i in 0 until 8) { r = (r shl 1) or (v and 1); v = v shr 1 }
        return r.toByte()
    }

    // ── wire primitives (RFB: all multi-byte fields big-endian) ────────

    private fun writeByte(v: Int) = output.writeByte(v)
    private fun writeShort(v: Int) = output.writeShort(v)
    private fun writeInt(v: Int) = output.writeInt(v)

    private fun readByte(): Int = input.readUnsignedByte()
    private fun readUShort(): Int = input.readUnsignedShort()
    private fun readInt(): Int = input.readInt()

    private fun skip(bytes: Int) {
        var remaining = bytes
        val buf = ByteArray(minOf(remaining, 4096))
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) throw EOFException("AMT KVM: connection closed mid-message")
            remaining -= n
        }
    }

    companion object {
        private const val CMD_START_REDIRECTION_SESSION_REPLY = 0x11
        private const val CMD_AUTHENTICATE_SESSION_REPLY = 0x14
        private val SERVICE_TAG_KVM = byteArrayOf('K'.code.toByte(), 'V'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte())
        private const val AUTH_URI = "/RedirectionService"

        // RFB security types (RFC 6143 §7.1.2)
        private const val SECURITY_NONE = 1
        private const val SECURITY_VNC_AUTH = 2

        // RFB client-to-server message types (§7.5)
        private const val SET_PIXEL_FORMAT = 0
        private const val SET_ENCODINGS = 2
        private const val FRAMEBUFFER_UPDATE_REQUEST = 3
        private const val KEY_EVENT = 4
        private const val POINTER_EVENT = 5

        // RFB server-to-client message types (§7.6)
        private const val FRAMEBUFFER_UPDATE = 0
        private const val SET_COLOUR_MAP_ENTRIES = 1
        private const val BELL = 2
        private const val SERVER_CUT_TEXT = 3

        private const val ENCODING_RAW = 0
        private const val ENCODING_COPY_RECT = 1
        private const val ENCODING_HEXTILE = 5
        private const val ENCODING_TIGHT = 7
        private const val ENCODING_ZRLE = 16

        // Hextile (RFC 6143 §7.7.3) tile size and subencoding-mask bits.
        private const val HEXTILE_SIZE = 16
        private const val HEXTILE_RAW = 0x01
        private const val HEXTILE_BACKGROUND_SPECIFIED = 0x02
        private const val HEXTILE_FOREGROUND_SPECIFIED = 0x04
        private const val HEXTILE_ANY_SUBRECTS = 0x08
        private const val HEXTILE_SUBRECTS_COLOURED = 0x10

        // ZRLE (RFC 6143 §7.6.9) tile size.
        private const val ZRLE_TILE_SIZE = 64

        // Tight (RFC 6143 §7.6.7) filter ids. All three the spec defines
        // are implemented — see readTightFilteredPixels's doc comment for
        // the gradient predictor's exact semantics.
        private const val TIGHT_FILTER_COPY = 0x00
        private const val TIGHT_FILTER_PALETTE = 0x01
        private const val TIGHT_FILTER_GRADIENT = 0x02
    }
}

/**
 * One decoded `FramebufferUpdate` rectangle. Either [pixels] is populated
 * (Raw encoding — 0xAARRGGBB values, one per pixel, row-major) or
 * [copySrcX]/[copySrcY] are (CopyRect — "blit [w]x[h] from this source
 * point in the *client's own already-drawn* framebuffer to [x],[y]"; the
 * caller, not this class, owns that framebuffer so it's the one that has
 * to perform the copy).
 */
internal data class AmtKvmRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val pixels: IntArray?,
    val copySrcX: Int = 0,
    val copySrcY: Int = 0,
)
