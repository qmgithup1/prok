package com.undatech.opaque

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.interfaces.DHPublicKey
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * ULTRAVNC-REPEATER FEATURE: thrown by [RfbConnectable.connect] specifically
 * when a Mode II repeater connection's ID frame was sent but the repeater
 * then closed (or never advanced) the socket before sending the RFB version
 * greeting — i.e. it did not recognize/relay the ID. This is the shape a
 * real `uvnc_repeater`/`repeater.exe` produces for both an unregistered ID
 * (typo) and a valid-but-not-yet-registered one (target server not
 * currently connected to that repeater with a matching `-id:`). The two
 * cases aren't distinguishable from the client side, so the message covers
 * both. Callers (see [com.systemsgo.hex.vnc.protocol.VncClient]) catch this
 * specifically to show an actionable message instead of a generic
 * connect-failed one.
 */
class RepeaterRejectedException(message: String) : IOException(message)

/**
 * Real, hand-written RFB (Remote Framebuffer / VNC) client implemented in
 * pure Kotlin — no native library, no third-party AAR, works on every ABI
 * and every Android device the rest of the app supports (minSdk 26+).
 *
 * Supports:
 *  - RFB protocol versions 3.3 / 3.7 / 3.8 handshake negotiation.
 *  - Security types: None (1) and VNC Authentication (2, DES challenge/response).
 *  - A fixed client pixel format (32bpp, true-colour, little-endian, R/G/B
 *    shifts 16/8/0) requested via SetPixelFormat — this means the server's
 *    own native pixel format never has to be parsed/converted, which keeps
 *    the decoder simple and fast on every device.
 *  - Encodings: ZRLE (zlib + tile-level RLE/palette compression — the
 *    bandwidth-efficient default, negotiated first whenever the server
 *    supports it), plus Raw and CopyRect as universal fallbacks. Raw is
 *    mandatory for every RFB-compliant server, so a connection never fails
 *    due to "no common encoding" even against a minimal/legacy server that
 *    doesn't implement ZRLE — it just falls back to uncompressed updates.
 *    PERF FIX: this used to advertise Raw/CopyRect only, which meant every
 *    screen update was sent as near-raw pixel data — fine on a fast LAN, but
 *    slow and choppy over 4G or a weak Wi-Fi link. ZRLE typically cuts
 *    on-the-wire bytes for normal desktop content by an order of magnitude.
 *  - TCP keep-alive on the underlying socket so idle sessions are not
 *    silently dropped by carrier-grade NAT / aggressive OEM power management.
 *
 * Also supports **VeNCrypt** (RFB security-type 19, RFB Protocol Extension
 * §3), the widely-deployed TLS extension used by TigerVNC/libvncserver-based
 * servers hardened to require encryption. See [negotiateVeNCrypt] for the
 * exact wire protocol. Sub-type preference (when the server offers several)
 * is certificate-based TLS first, then anonymous TLS, then cleartext
 * VeNCrypt-Plain last — see [VENCRYPT_SUBTYPE_PREFERENCE]. Certificate trust
 * for the X509* sub-types is TOFU (trust-on-first-use): [certificateVerifier]
 * is invoked with the real negotiated peer certificate and decides
 * accept/reject, mirroring [com.systemsgo.hex.ssh.protocol.SshClient]'s
 * TofuHostKeyRepository for SSH host keys.
 *
 * Also supports connecting through an **UltraVNC Repeater**, either mode
 * (see [Connection.RepeaterMode]): when [Connection.useRepeater] is set and
 * [Connection.repeaterMode] is MODE_II (ID-based routing — see
 * [sendRepeaterIdFrame]), [Connection.address]/[Connection.port] point at
 * the repeater itself rather than the real server, and a fixed 250-byte
 * `"ID:<repeaterId>"` frame is sent immediately after TCP connect, before
 * anything else — the repeater relays every byte from that point on, so the
 * rest of the handshake (version/security/init) is completely unaware a
 * repeater is even involved. If the repeater doesn't recognize the ID (typo,
 * or the target server hasn't registered it yet), it drops the socket
 * before ever relaying an RFB version greeting; [connect] detects that
 * specific failure shape and throws [RepeaterRejectedException] instead of
 * a generic [IOException], so the caller can show an actionable message
 * instead of a plain "connection failed". MODE_I (port-mapped, non-ID
 * repeater access) is not distinguished from a direct connection at the
 * protocol level — no ID frame is sent, since it's just a repeater exposing
 * a fixed local port that forwards straight to one server.
 *
 * ARD/Screen Sharing support: security-type 30 ("RA2", Apple's
 * Diffie-Hellman + AES scheme — see [negotiateAppleRA2]) is implemented,
 * which covers authenticating and controlling the screen exactly like the
 * macOS "Screen Sharing.app" / Apple Remote Desktop viewer does. The
 * broader ARD *administration* suite (Remote Install, Copy Items, Install
 * Packages, Reports, Task Server, Spotlight search across machines, etc.)
 * is a **separate, proprietary protocol** with no public specification —
 * unlike RA2, no security researcher write-up or open-source
 * implementation of it is available to verify against, and it depends on
 * the "Remote Desktop" management agent/database on the admin side, not
 * just a socket-level protocol a client can speak. It is not implemented
 * here, and attempting to guess at its wire format would risk producing
 * code that looks functional but silently does the wrong thing. Vendor
 * security types 33/35/36 that some macOS versions offer alongside 30 are
 * likewise undocumented and not implemented — see [negotiateAppleRA2]'s
 * doc comment.
 *
 * Not supported in this version (documented limitation, not silently
 * dropped): File Transfer extensions. Plain RFB (None/VNC-Auth) plus
 * VeNCrypt plus Apple RA2 now covers effectively all real-world VNC/ARD
 * servers (TigerVNC, TightVNC, RealVNC Server, x11vnc, Vino, UltraVNC,
 * macOS Screen Sharing — with or without TLS).
 */
class RfbConnectable(
    private val connection: Connection,
    @Suppress("UNUSED_PARAMETER") private val context: Context,
) {

    companion object {
        private const val TAG = "RfbConnectable"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_READ_TIMEOUT_MS = 20_000
        // LISTEN-MODE FEATURE: how long the ServerSocket.accept() call below
        // blocks waiting for the remote VNC server to dial in, before giving
        // up. Deliberately much longer than SOCKET_CONNECT_TIMEOUT_MS — in
        // listen mode there is no outbound dial to time out, and the whole
        // point of the feature is that the *user* decides when to trigger
        // the incoming connection on the server side (which can be minutes
        // after the viewer starts listening), not the app.
        private const val LISTEN_ACCEPT_TIMEOUT_MS = 5 * 60_000

        // ── ULTRAVNC-REPEATER FEATURE ─────────────────────────────────────
        // UltraVNC repeater's "Mode II" ID frame is always exactly 250 bytes:
        // the ASCII text "ID:<repeaterId>" followed by NUL padding out to
        // this fixed size. This is UltraVNC's own wire format (not part of
        // base RFB), sent once, immediately after TCP connect and *before*
        // the "RFB 003.0XX\n" version greeting that would normally be the
        // very first thing exchanged — see negotiateVersion(). The repeater
        // reads exactly these 250 bytes, matches the ID against a pending
        // server-side registration, and from then on transparently pipes
        // raw bytes between the two sockets, so every later step in
        // connect() (version/security/init) proceeds completely unchanged.
        private const val REPEATER_ID_FRAME_SIZE = 250
        private const val REPEATER_ID_MAX_LEN = REPEATER_ID_FRAME_SIZE - 3 // "ID:" prefix

        // ── RFB security types (RFB Protocol §7.2.1) ─────────────────────
        // Named so the selection/dispatch logic in negotiateSecurity() reads
        // clearly, and so a future security type has an obvious constant to
        // add alongside these two.
        private const val SECURITY_TYPE_NONE     = 1
        private const val SECURITY_TYPE_VNC_AUTH = 2
        // VeNCrypt is a widely-deployed *extension*, not part of the base
        // RFB spec — see [negotiateVeNCrypt] for what supporting it requires.
        private const val SECURITY_TYPE_VENCRYPT = 19
        // ── Apple Remote Desktop / Screen Sharing (ARD) ───────────────────
        // "RA2" — Apple's own Diffie-Hellman based auth, used whenever a Mac
        // is configured for "Apple Remote Desktop" / local-account access
        // rather than a legacy VNC password (in which case the server won't
        // offer SECURITY_TYPE_VNC_AUTH at all, only this one — see
        // negotiateApple() doc comment for the wire format and sources).
        private const val SECURITY_TYPE_APPLE_RA2 = 30

        // ── VeNCrypt sub-types (RFB Protocol Extension §3, TigerVNC vencrypt.h) ──
        private const val VENCRYPT_PLAIN      = 256 // no TLS; cleartext username/password
        private const val VENCRYPT_TLS_NONE   = 257 // anonymous TLS, no further auth
        private const val VENCRYPT_TLS_VNC    = 258 // anonymous TLS, then VNC Authentication
        private const val VENCRYPT_TLS_PLAIN  = 259 // anonymous TLS, then username/password
        private const val VENCRYPT_X509_NONE  = 260 // certificate-based TLS, no further auth
        private const val VENCRYPT_X509_VNC   = 261 // certificate-based TLS, then VNC Authentication
        private const val VENCRYPT_X509_PLAIN = 262 // certificate-based TLS, then username/password

        // Preference order when the server offers more than one sub-type:
        // certificate-based TLS first (client can TOFU-pin an actual identity),
        // then anonymous TLS (still encrypted, just no certificate to pin),
        // then cleartext Plain last (only reached if nothing else was offered).
        // Within each tier, prefer "no further auth" > VNC-Auth > Plain — a
        // simpler post-handshake path has fewer things that can go wrong.
        private val VENCRYPT_SUBTYPE_PREFERENCE = intArrayOf(
            VENCRYPT_X509_NONE, VENCRYPT_X509_VNC, VENCRYPT_X509_PLAIN,
            VENCRYPT_TLS_NONE, VENCRYPT_TLS_VNC, VENCRYPT_TLS_PLAIN,
            VENCRYPT_PLAIN,
        )

        /** RFB §7.2.2 — VNC Authentication key prep: reverse the bits of every byte. */
        private fun reverseBits(b: Byte): Byte {
            var v = b.toInt() and 0xFF
            var r = 0
            for (i in 0 until 8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            return r.toByte()
        }

        /** Builds the 8-byte DES key bVNC/RFB servers expect from a plaintext password. */
        private fun vncAuthKey(password: String): ByteArray {
            val raw = password.toByteArray(Charsets.ISO_8859_1)
            val key = ByteArray(8)
            for (i in 0 until 8) {
                key[i] = if (i < raw.size) reverseBits(raw[i]) else 0
            }
            return key
        }

        private fun desEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(block)
        }
    }

    /** Last Bitmap received from the server. Becomes null again if the connection drops. */
    var framebuffer: Bitmap? = null
        private set

    /**
     * True once [connect] has completed a VeNCrypt/TLS handshake for this
     * session (any sub-type except [VENCRYPT_PLAIN]). False for a plain
     * (unencrypted) RFB connection — including one where the server offered
     * VeNCrypt but the negotiated sub-type was cleartext Plain. Only
     * meaningful after [connect] returns successfully.
     */
    var isEncrypted: Boolean = false
        private set

    /**
     * The server's X.509 certificate from the VeNCrypt handshake, populated
     * only when the negotiated sub-type was certificate-based (X509None/
     * X509Vnc/X509Plain). Null for anonymous-TLS sub-types, Plain, or a
     * non-VeNCrypt connection — there is simply no certificate to inspect
     * in those cases.
     */
    var serverCertificate: X509Certificate? = null
        private set

    /**
     * Optional callback invoked synchronously from inside [connect],
     * immediately after a certificate-based (X509*) VeNCrypt TLS handshake
     * completes and *before* any further security negotiation or
     * framebuffer data is exchanged over the tunnel. Return `true` to
     * accept this certificate and continue connecting, or `false` to abort
     * — [connect] then throws [IOException] and no session data is ever
     * exchanged with the unverified server.
     *
     * Left `null` (the default) accepts unconditionally. The caller is
     * expected to set this to run its own trust-on-first-use (TOFU)
     * pinning check against the *real* negotiated certificate, mirroring
     * how [com.systemsgo.hex.ssh.protocol.SshClient]'s
     * TofuHostKeyRepository gates SSH host keys before a session proceeds.
     * See [com.systemsgo.hex.vnc.protocol.VncClient]'s wiring of this
     * property for the concrete TOFU store used.
     */
    var certificateVerifier: ((X509Certificate) -> Boolean)? = null

    /**
     * CLIPBOARD FIX: invoked on the reader thread whenever the server sends
     * ServerCutText (RFB message-type 3). The caller ([com.systemsgo.hex.vnc.protocol.VncClient])
     * is responsible for hopping onto whatever thread it needs (e.g. before
     * touching Android's ClipboardManager) — this class stays transport-only.
     */
    var onServerCutText: ((String) -> Unit)? = null

    private var socket: Socket? = null
    // LISTEN-MODE FEATURE: only non-null while [connect] is blocked inside
    // acceptListenModeSocket() below; kept as a field (rather than a local)
    // solely so [close] can call serverSocket?.close() to unblock a pending
    // accept() immediately if the user cancels while still waiting for an
    // incoming connection — closing a ServerSocket makes a thread parked in
    // accept() throw SocketException right away instead of waiting out the
    // full LISTEN_ACCEPT_TIMEOUT_MS.
    @Volatile private var serverSocket: java.net.ServerSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    @Volatile private var running = false
    private var readerThread: Thread? = null

    private var fbWidth = 0
    private var fbHeight = 0

    // ZRLE uses a single continuous zlib stream for the lifetime of the RFB
    // connection (rectangles are NOT independently zlib-framed) — this must
    // be created once per connection and never reset between rectangles, or
    // the decompressor desyncs from the server's encoder state.
    private var zrleInflater: Inflater? = null
    private val zrleByteBuf = ByteArray(1)

    /**
     * Performs the full RFB handshake synchronously (version → security →
     * auth → init) then starts a background thread that keeps pulling
     * FramebufferUpdate messages and applying them to [framebuffer].
     *
     * @throws AuthenticationException if the server rejects the password.
     * @throws java.io.IOException on any network / protocol failure.
     */
    fun connect() {
        val host = connection.address
        val port = if (connection.port > 0) connection.port else 5900
        // Fresh zlib stream per connection — a leftover Inflater from a prior
        // session on this same RfbConnectable instance would desync ZRLE
        // decoding immediately (see field doc on zrleInflater).
        zrleInflater?.end()
        zrleInflater = null
        try {
            val sock: Socket = if (connection.useListenMode) {
                acceptListenModeSocket()
            } else {
                val outbound = Socket()
                outbound.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
                outbound
            }
            socket = sock
            sock.tcpNoDelay = true
            // Keep idle RFB connections alive across carrier NAT timeouts and
            // OEM Doze-style network suspension (Xiaomi/Honor/Oppo/etc.) —
            // the OS sends periodic empty TCP keep-alive probes so the link
            // is not silently reclaimed while the user isn't touching the
            // screen.
            sock.keepAlive = true
            sock.soTimeout = SOCKET_READ_TIMEOUT_MS

            val rawIn = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val rawOut = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
            input = rawIn
            output = rawOut

            // ULTRAVNC-REPEATER FEATURE: must be the very first bytes on the
            // wire, ahead of the RFB version greeting the repeater itself
            // never sends — the *target* VNC server does, once the repeater
            // has spliced the two sockets together. Mode I sends no frame:
            // the repeater's own config already maps this address/port
            // straight to one server, so it looks like a direct connection.
            // LISTEN-MODE FEATURE: a repeater is something *this app* dials
            // out through; it has no meaning for a socket the remote server
            // just dialed in on, so the ID frame is skipped defensively here
            // even though the UI already treats the two features as
            // mutually exclusive (see Components.kt VNC settings section).
            val isRepeaterModeII = !connection.useListenMode && connection.useRepeater &&
                connection.repeaterMode == Connection.RepeaterMode.MODE_II
            if (isRepeaterModeII) {
                sendRepeaterIdFrame(rawOut, connection.repeaterId)
            }

            // ULTRAVNC-REPEATER FEATURE: a Mode II repeater that doesn't
            // recognize the ID drops (or simply never advances) the socket
            // instead of ever relaying the target server's RFB version
            // greeting — negotiateVersion below would then fail with a bare
            // EOFException/SocketTimeoutException/"bad version greeting"
            // IOException, indistinguishable from any other network error.
            // Recognize that specific shape here, right after the frame we
            // just sent, and re-throw as RepeaterRejectedException so the
            // caller can show an actionable message instead of a generic one.
            if (isRepeaterModeII) {
                try {
                    negotiateVersion(rawIn, rawOut)
                } catch (e: EOFException) {
                    throw RepeaterRejectedException(
                        "UltraVNC Repeater closed the connection after the ID frame — " +
                            "the ID '${connection.repeaterId}' was not recognized, or the " +
                            "target server is not currently registered with this repeater."
                    )
                } catch (e: SocketTimeoutException) {
                    throw RepeaterRejectedException(
                        "UltraVNC Repeater accepted the ID frame but no target server " +
                            "responded in time — the ID '${connection.repeaterId}' may be " +
                            "correct but the target server isn't connected to this repeater."
                    )
                }
            } else {
                negotiateVersion(rawIn, rawOut)
            }
            // VENCRYPT FIX: negotiateSecurity may upgrade to TLS partway through
            // (VeNCrypt, security-type 19) — it returns whichever streams the
            // rest of the handshake (and the ongoing session) must use from
            // this point on. Reassign the `input`/`output` fields too, since
            // readLoop()/sendPointerEvent()/sendKeyEvent()/etc. all read those
            // fields directly rather than taking a stream parameter.
            val (activeIn, activeOut) = negotiateSecurity(rawIn, rawOut)
            input = activeIn
            output = activeOut
            clientAndServerInit(activeIn, activeOut)
            setPixelFormatAndEncodings(activeOut)

            framebuffer = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)

            // Initial full-screen, non-incremental request.
            sendFramebufferUpdateRequest(incremental = false, x = 0, y = 0, w = fbWidth, h = fbHeight)

            running = true
            readerThread = Thread({ readLoop() }, "rfb-reader").apply {
                isDaemon = true
                start()
            }
        } catch (e: AuthenticationException) {
            closeQuietly()
            throw e
        } catch (e: IOException) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw IOException("VNC connection failed: ${e.message}", e)
        }
    }

    // ── Handshake ────────────────────────────────────────────────────────

    /**
     * ULTRAVNC-REPEATER FEATURE: writes the fixed-size 250-byte Mode II ID
     * frame UltraVNC's `repeater.exe`/`uvnc_repeater` expects as the first
     * thing on a freshly-connected socket: the ASCII text `"ID:<id>"`,
     * NUL-padded to exactly [REPEATER_ID_FRAME_SIZE] bytes. The repeater
     * reads exactly this many bytes before doing anything else, so an
     * over-length ID is truncated (never sent partially/split across two
     * writes) rather than corrupting the frame boundary.
     */
    private fun sendRepeaterIdFrame(output: DataOutputStream, repeaterId: String) {
        val id = repeaterId.trim().take(REPEATER_ID_MAX_LEN)
        val idBytes = "ID:$id".toByteArray(Charsets.US_ASCII)
        val frame = ByteArray(REPEATER_ID_FRAME_SIZE) // zero-initialised = NUL padding
        System.arraycopy(idBytes, 0, frame, 0, idBytes.size.coerceAtMost(REPEATER_ID_FRAME_SIZE))
        output.write(frame)
        output.flush()
    }

    /**
     * LISTEN-MODE FEATURE: opens a ServerSocket on [Connection.listenPort]
     * and blocks until the remote VNC server dials in, returning the
     * accepted [Socket]. Everything downstream of this call (version/
     * security/init negotiation, the reader thread, sendPointerEvent, etc.)
     * is completely unaware whether the underlying socket came from dialing
     * out or from accepting an incoming connection — the RFB wire protocol
     * itself is identical either way (the *server* always sends the version
     * greeting first regardless of who opened the TCP connection), which is
     * exactly what makes listen mode possible without touching any of the
     * handshake code below.
     *
     * @throws IOException if the port is already in use, or if no server
     *   connects within [LISTEN_ACCEPT_TIMEOUT_MS].
     */
    private fun acceptListenModeSocket(): Socket {
        val port = if (connection.listenPort > 0) connection.listenPort else 5500
        val server = java.net.ServerSocket()
        // SO_REUSEADDR: a previous session's listen socket on this same port
        // can linger briefly in TIME_WAIT after close(); without this, a
        // quick reconnect attempt on the same profile can fail to bind with
        // "Address already in use" even though nothing is really listening.
        server.reuseAddress = true
        serverSocket = server
        try {
            server.bind(InetSocketAddress(port))
            server.soTimeout = LISTEN_ACCEPT_TIMEOUT_MS
            Log.i(TAG, "VNC listen mode: waiting for incoming connection on port $port")
            return server.accept()
        } finally {
            // Whether accept() succeeded, timed out, or was interrupted by
            // close() cancelling the session, the listening socket itself
            // (as opposed to the *accepted* connection socket) has no
            // further use — only one incoming connection is ever expected
            // per session.
            try { server.close() } catch (e: Exception) { android.util.Log.d("RfbConnectable", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            serverSocket = null
        }
    }

    private fun negotiateVersion(input: DataInputStream, output: DataOutputStream) {
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val versionStr = String(versionBytes, Charsets.US_ASCII)
        // Expected form: "RFB 003.0XX\n"
        val match = Regex("""RFB (\d{3})\.(\d{3})""").find(versionStr)
            ?: throw IOException("Not an RFB server (bad version greeting)")
        val serverMajor = match.groupValues[1].toInt()
        val serverMinor = match.groupValues[2].toInt()

        // We support 3.3 through 3.8. Reply with whichever is lower so we
        // never claim to support handshake semantics the server doesn't.
        negotiatedMinor = when {
            serverMajor < 3 -> throw IOException("Unsupported RFB protocol version: $versionStr")
            serverMinor >= 8 -> 8
            else -> serverMinor.coerceIn(3, 8)
        }
        val reply = "RFB 003.%03d\n".format(negotiatedMinor)
        output.write(reply.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private var negotiatedMinor = 8

    /**
     * @return the streams the rest of the handshake (and the ongoing
     * session) must use — the same `input`/`output` passed in, unless
     * VeNCrypt negotiated a TLS upgrade, in which case the TLS-wrapped
     * streams are returned instead.
     */
    private fun negotiateSecurity(
        input: DataInputStream,
        output: DataOutputStream,
    ): Pair<DataInputStream, DataOutputStream> {
        var activeIn = input
        var activeOut = output

        val chosenType: Int
        if (negotiatedMinor <= 3) {
            // RFB 3.3: server dictates a single security type directly.
            val type = activeIn.readInt()
            if (type == 0) {
                throw IOException("Server refused connection: ${readFailureReason(activeIn)}")
            }
            chosenType = type
        } else {
            // RFB 3.7+: server offers a list, client picks one.
            val count = activeIn.readUnsignedByte()
            if (count == 0) {
                throw IOException("Server refused connection: ${readFailureReason(activeIn)}")
            }
            val types = IntArray(count) { activeIn.readUnsignedByte() }
            chosenType = when {
                types.contains(SECURITY_TYPE_VNC_AUTH) -> SECURITY_TYPE_VNC_AUTH // prefer VNC Authentication when offered
                types.contains(SECURITY_TYPE_NONE) -> SECURITY_TYPE_NONE // fall back to None
                types.contains(SECURITY_TYPE_VENCRYPT) -> SECURITY_TYPE_VENCRYPT
                // ARD/Screen Sharing servers set to "Apple Remote Desktop" or
                // local-account access (rather than a legacy VNC password)
                // offer *only* RA2-family types (30 plus vendor variants like
                // 33/35/36) — no SECURITY_TYPE_VNC_AUTH at all. 30 is the one
                // with a publicly documented wire format; the others are
                // undocumented vendor variants we don't implement.
                types.contains(SECURITY_TYPE_APPLE_RA2) -> SECURITY_TYPE_APPLE_RA2
                else -> throw IOException(
                    "No supported VNC security type offered by server " +
                        "(server offered: ${types.joinToString()}); only None/VNC-Auth/VeNCrypt/Apple-RA2 are supported"
                )
            }
            activeOut.writeByte(chosenType)
            activeOut.flush()
        }

        when (chosenType) {
            SECURITY_TYPE_NONE -> {
                // None. RFB 3.3/3.7 send no SecurityResult for type None;
                // 3.8 does — read it defensively without blocking forever.
                if (negotiatedMinor >= 8) readSecurityResult(activeIn)
            }
            SECURITY_TYPE_VNC_AUTH -> {
                val challenge = ByteArray(16)
                activeIn.readFully(challenge)
                val key = vncAuthKey(connection.password)
                val response = ByteArray(16)
                desEncryptBlock(key, challenge.copyOfRange(0, 8)).copyInto(response, 0)
                desEncryptBlock(key, challenge.copyOfRange(8, 16)).copyInto(response, 8)
                activeOut.write(response)
                activeOut.flush()
                readSecurityResult(activeIn)
            }
            // Also covers the (rare) RFB-3.3 path above, where the server
            // dictates security-type 19 directly rather than offering a list.
            SECURITY_TYPE_VENCRYPT -> {
                val (tlsIn, tlsOut) = negotiateVeNCrypt(activeIn, activeOut)
                activeIn = tlsIn
                activeOut = tlsOut
            }
            SECURITY_TYPE_APPLE_RA2 -> {
                negotiateAppleRA2(activeIn, activeOut)
                readSecurityResult(activeIn)
            }
            else -> throw IOException("Unsupported VNC security type: $chosenType")
        }
        return activeIn to activeOut
    }

    /**
     * VeNCrypt (RFB security-type 19) — see the class doc comment and the
     * VENCRYPT_* sub-type constants above. Implements VeNCrypt version 0.2,
     * which is what every modern server (TigerVNC, x11vnc/libvncserver)
     * speaks. Returns the streams to use for the rest of the session: the
     * original plaintext ones for sub-type [VENCRYPT_PLAIN], or new streams
     * wrapping a freshly-negotiated [SSLSocket] for every other sub-type.
     */
    private fun negotiateVeNCrypt(
        rawIn: DataInputStream,
        rawOut: DataOutputStream,
    ): Pair<DataInputStream, DataOutputStream> {
        // 1. Version handshake — we only implement 0.2 (near-universal).
        val serverMajor = rawIn.readUnsignedByte()
        val serverMinor = rawIn.readUnsignedByte()
        if (serverMajor != 0) {
            throw IOException("Unsupported VeNCrypt major version: $serverMajor.$serverMinor")
        }
        rawOut.writeByte(0)
        rawOut.writeByte(2)
        rawOut.flush()
        val versionAck = rawIn.readUnsignedByte()
        if (versionAck != 0) {
            throw IOException(
                "Server rejected VeNCrypt version 0.2 (server offered $serverMajor.$serverMinor)"
            )
        }

        // 2. Sub-type negotiation.
        val subtypeCount = rawIn.readUnsignedByte()
        if (subtypeCount == 0) throw IOException("Server offered no VeNCrypt sub-types")
        val subtypes = IntArray(subtypeCount) { rawIn.readInt() }
        val chosenSubtype = VENCRYPT_SUBTYPE_PREFERENCE.firstOrNull { subtypes.contains(it) }
            ?: throw IOException(
                "No supported VeNCrypt sub-type offered by server " +
                    "(server offered: ${subtypes.joinToString()})"
            )
        rawOut.writeInt(chosenSubtype)
        rawOut.flush()

        // 3. Server ACK for the chosen sub-type.
        val subtypeAck = rawIn.readUnsignedByte()
        if (subtypeAck != 1) {
            throw IOException("Server rejected VeNCrypt sub-type $chosenSubtype")
        }

        if (chosenSubtype == VENCRYPT_PLAIN) {
            // Cleartext username/password directly on the existing plain
            // socket — no TLS involved despite going through VeNCrypt.
            sendVeNCryptPlainCredentials(rawOut)
            readSecurityResult(rawIn)
            isEncrypted = false
            return rawIn to rawOut
        }

        // 4. Wrap the existing plaintext socket in TLS. A permissive trust
        // manager is used deliberately — self-signed certificates are the
        // norm for VNC servers, same as this app's RDP/SSH paths. Trust is
        // established afterward via TOFU ([certificateVerifier]), not via
        // CA chain validation.
        val plainSocket = socket ?: throw IOException("Socket closed during VeNCrypt negotiation")
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        val targetPort = if (connection.port > 0) connection.port else 5900
        val sslSocket = sslContext.socketFactory.createSocket(
            plainSocket, connection.address, targetPort, /* autoClose = */ true
        ) as SSLSocket
        sslSocket.useClientMode = true
        sslSocket.soTimeout = SOCKET_READ_TIMEOUT_MS
        sslSocket.startHandshake()
        // Reassign so close()/closeQuietly() tear down the TLS layer (and,
        // via autoClose, the underlying TCP socket) together.
        socket = sslSocket

        val isX509 = chosenSubtype == VENCRYPT_X509_NONE ||
            chosenSubtype == VENCRYPT_X509_VNC ||
            chosenSubtype == VENCRYPT_X509_PLAIN
        if (isX509) {
            val cert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IOException("VeNCrypt X.509 sub-type negotiated but server presented no certificate")
            serverCertificate = cert
            // SECURITY FIX (VNC-TLS-TOFU-NO-FALLBACK): a missing certificateVerifier
            // must REJECT, not silently accept, the connection. The previous
            // `?: true` fallback meant that any caller which forgot to wire up
            // TOFU verification (a future code path, a test, a refactor) would
            // trust *any* server certificate with zero verification — same class
            // of bug already fixed for Guacamole (see GuacamoleTunnelClient's
            // TLS-TOFU-NO-FALLBACK fix) and RDP (see AFreeRdpBridge's
            // onNativeCertificateCheck). There is exactly one production caller
            // (VncClient.connect()) and it always sets certificateVerifier before
            // calling connect(), so this is defense-in-depth, not a behavior change
            // for any real session today.
            val verifier = certificateVerifier
            val accepted = if (verifier != null) {
                verifier.invoke(cert)
            } else {
                Log.w(TAG, "VeNCrypt X.509 handshake with no certificateVerifier set — rejecting")
                false
            }
            if (!accepted) {
                throw IOException(
                    "VNC server certificate rejected (TOFU fingerprint mismatch — possible MITM attack)"
                )
            }
        }
        isEncrypted = true

        val tlsIn = DataInputStream(BufferedInputStream(sslSocket.inputStream, 64 * 1024))
        val tlsOut = DataOutputStream(BufferedOutputStream(sslSocket.outputStream, 64 * 1024))

        // 6. Continue security negotiation *inside* the tunnel for the
        // Vnc/Plain sub-types; the None sub-types have nothing further to
        // negotiate here.
        when (chosenSubtype) {
            VENCRYPT_X509_VNC, VENCRYPT_TLS_VNC -> {
                val challenge = ByteArray(16)
                tlsIn.readFully(challenge)
                val key = vncAuthKey(connection.password)
                val response = ByteArray(16)
                desEncryptBlock(key, challenge.copyOfRange(0, 8)).copyInto(response, 0)
                desEncryptBlock(key, challenge.copyOfRange(8, 16)).copyInto(response, 8)
                tlsOut.write(response)
                tlsOut.flush()
                readSecurityResult(tlsIn)
            }
            VENCRYPT_X509_PLAIN, VENCRYPT_TLS_PLAIN -> {
                sendVeNCryptPlainCredentials(tlsOut)
                readSecurityResult(tlsIn)
            }
            VENCRYPT_X509_NONE, VENCRYPT_TLS_NONE -> {
                // No further auth required. Mirrors the plaintext
                // SECURITY_TYPE_NONE branch above: 3.8 still sends a
                // SecurityResult, 3.3/3.7 don't.
                if (negotiatedMinor >= 8) readSecurityResult(tlsIn)
            }
        }

        return tlsIn to tlsOut
    }

    /**
     * Sends VeNCrypt-Plain credentials (RFB Protocol Extension §3.2): a
     * 4-byte big-endian username length, a 4-byte big-endian password
     * length, then the raw username and password bytes (no encryption of
     * the bytes themselves — this sub-type's confidentiality comes only
     * from the surrounding TLS tunnel, or from nothing at all for the
     * cleartext [VENCRYPT_PLAIN] sub-type).
     */
    private fun sendVeNCryptPlainCredentials(out: DataOutputStream) {
        val username = connection.userName.toByteArray(Charsets.UTF_8)
        val password = connection.password.toByteArray(Charsets.UTF_8)
        out.writeInt(username.size)
        out.writeInt(password.size)
        out.write(username)
        out.write(password)
        out.flush()
    }

    /**
     * Apple's "RA2" authentication (RFB security-type 30) — the scheme used
     * by macOS Screen Sharing / ARD whenever the Mac is set to
     * "VNC users may control screen with ARD" *off* and only ARD/local
     * accounts are allowed (the common case; the server then does not offer
     * [SECURITY_TYPE_VNC_AUTH] at all).
     *
     * There is no public Apple spec for this — it was reverse-engineered by
     * security researchers from Wireshark captures against real macOS
     * Screen Sharing servers. This implementation follows the two published,
     * mutually-consistent write-ups: Tenable's 2018 analysis of the wire
     * format ("Detecting macOS High Sierra Root Account Without
     * Authentication") and David Simmons' 2011 "Apple Remote Desktop
     * quirks" (cafbit.com), plus Apple's own confirmation that Remote
     * Desktop authentication is Diffie-Hellman with a 512-bit prime and a
     * 128-bit AES shared key. It has additionally been cross-checked,
     * field-for-field, against Simmons' own working Java client
     * (RFBSecurityARD.java, Apache-2.0, github.com/simmons/valence) — the
     * key derivation below deliberately uses the platform's own
     * javax.crypto DH KeyAgreement the same way that reference does,
     * rather than hand-rolled BigInteger.modPow arithmetic, for the same
     * reason: less surface area for a subtle, hard-to-notice crypto bug.
     *
     * Wire format, after the server receives security-type byte 30:
     *  - Server -> client: generator g (2 bytes, big-endian unsigned),
     *    key length n (2 bytes, big-endian unsigned), prime modulus p
     *    (n bytes, big-endian unsigned), server public key (n bytes,
     *    big-endian unsigned).
     *  - Client generates its own DH keypair over the same (g, p), derives
     *    the shared secret, MD5-hashes it to a 128-bit AES key, and
     *    encrypts a fixed 128-byte credentials block — username, NUL
     *    terminator, then random padding to 64 bytes; same for password —
     *    with AES-128/ECB/NoPadding.
     *  - Client -> server: the 128-byte ciphertext, then the client's own
     *    public key (n bytes, big-endian unsigned, zero-padded on the left
     *    to exactly n bytes if the numeric value happens to be shorter).
     *  - Server replies with the usual 4-byte SecurityResult, read by the
     *    caller after this function returns.
     *
     * Only credential-based RA2 (type 30) is implemented. The related
     * vendor security types 33/35/36 that some macOS versions also offer
     * alongside 30 have no published wire format we could verify, so they
     * are deliberately not attempted — guessing at undocumented crypto
     * framing would silently produce connections that look like they work
     * but aren't actually authenticating correctly.
     */
    private fun negotiateAppleRA2(input: DataInputStream, output: DataOutputStream) {
        val generator = input.readUnsignedShort()
        val keyLength = input.readUnsignedShort()

        val primeBytes = ByteArray(keyLength)
        input.readFully(primeBytes)
        val serverPublicBytes = ByteArray(keyLength)
        input.readFully(serverPublicBytes)

        val prime = BigInteger(1, primeBytes)
        val serverPublic = BigInteger(1, serverPublicBytes)
        val g = BigInteger.valueOf(generator.toLong())

        // Client's own DH keypair over the server-supplied (g, p), computed
        // with the platform's own DH implementation (javax.crypto) rather
        // than hand-rolled modPow arithmetic — this is deliberately the same
        // approach David Simmons' verified, working reference client uses
        // (see RfbConnectable's class doc for the source), so we inherit its
        // battle-tested key-generation behavior instead of a bespoke one.
        val dhParams = DHParameterSpec(prime, g)
        val keyPairGenerator = KeyPairGenerator.getInstance("DH")
        keyPairGenerator.initialize(dhParams)
        val keyPair = keyPairGenerator.generateKeyPair()

        val keyFactory = KeyFactory.getInstance("DH")
        val serverPublicKey = keyFactory.generatePublic(DHPublicKeySpec(serverPublic, prime, g)) as DHPublicKey

        val keyAgreement = KeyAgreement.getInstance("DH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(serverPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()
        val clientPublic = (keyPair.public as DHPublicKey).y

        // AES key = MD5(shared secret) — the JCE DH provider already
        // returns generateSecret() zero-padded to the modulus length, so no
        // extra fixed-length encoding is needed here (unlike the public/
        // private key bytes below, which we do encode ourselves since we
        // read/write those directly on the wire, bypassing JCE's own X.509
        // key encoding).
        val aesKey = MessageDigest.getInstance("MD5").digest(sharedSecret)

        // 128-byte credentials block: username[64] + password[64], each
        // NUL-terminated with the remainder filled with random bytes.
        val credentials = ByteArray(128)
        SecureRandom().nextBytes(credentials)
        writeNulTerminatedField(credentials, 0, connection.userName)
        writeNulTerminatedField(credentials, 64, connection.password)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val ciphertext = cipher.doFinal(credentials)

        output.write(ciphertext)
        output.write(toFixedLengthBytes(clientPublic, keyLength))
        output.flush()
    }

    /** Encodes [value] as exactly [length] big-endian unsigned bytes. */
    private fun toFixedLengthBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray() // may have a leading zero sign byte, or be shorter than `length`
        val out = ByteArray(length)
        val srcStart = maxOf(0, raw.size - length)
        val copyLen = minOf(raw.size - srcStart, length)
        System.arraycopy(raw, srcStart, out, length - copyLen, copyLen)
        return out
    }

    /**
     * Writes [text] into `dest[offset, offset+64)` UTF-8 encoded and
     * NUL-terminated, truncating if it doesn't fit in the 64-byte field
     * (the pre-filled random padding from the caller is left untouched
     * beyond the terminator, matching Apple's own client behavior).
     */
    private fun writeNulTerminatedField(dest: ByteArray, offset: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val copyLen = minOf(bytes.size, 63) // leave room for the NUL terminator
        System.arraycopy(bytes, 0, dest, offset, copyLen)
        dest[offset + copyLen] = 0
    }

    private fun readSecurityResult(input: DataInputStream) {
        val result = input.readInt()
        if (result != 0) {
            val reason = if (negotiatedMinor >= 8) readFailureReason(input) else "authentication failed"
            throw AuthenticationException(reason)
        }
    }

    private fun readFailureReason(input: DataInputStream): String = try {
        val len = input.readInt()
        if (len in 0..4096) {
            val bytes = ByteArray(len)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        } else {
            "unknown reason"
        }
    } catch (_: Exception) {
        "unknown reason"
    }

    private fun clientAndServerInit(input: DataInputStream, output: DataOutputStream) {
        // ClientInit: share the desktop with other viewers (1 = shared).
        output.writeByte(1)
        output.flush()

        // ServerInit
        fbWidth = input.readUnsignedShort()
        fbHeight = input.readUnsignedShort()
        // Server pixel format (16 bytes) — discarded; we override it below.
        val serverPixelFormat = ByteArray(16)
        input.readFully(serverPixelFormat)
        val nameLength = input.readInt()
        if (nameLength in 0..(1 shl 20)) {
            val nameBytes = ByteArray(nameLength)
            input.readFully(nameBytes)
        }
        if (fbWidth <= 0 || fbHeight <= 0 || fbWidth > 16384 || fbHeight > 16384) {
            throw IOException("Server reported an invalid framebuffer size: ${fbWidth}x$fbHeight")
        }
    }

    private fun setPixelFormatAndEncodings(output: DataOutputStream) {
        // SetPixelFormat (message-type 0): force 32bpp little-endian
        // true-colour with R/G/B shifts 16/8/0 so every received pixel can
        // be decoded with a fixed, branch-free formula regardless of what
        // the server's native format is.
        output.writeByte(0)
        output.write(byteArrayOf(0, 0, 0)) // padding
        output.writeByte(32) // bits-per-pixel
        output.writeByte(24) // depth
        output.writeByte(0)  // big-endian-flag = false
        output.writeByte(1)  // true-colour-flag = true
        output.writeShort(255) // red-max
        output.writeShort(255) // green-max
        output.writeShort(255) // blue-max
        output.writeByte(16) // red-shift
        output.writeByte(8)  // green-shift
        output.writeByte(0)  // blue-shift
        output.write(byteArrayOf(0, 0, 0)) // padding
        output.flush()

        // SetEncodings (message-type 2): listed in order of preference,
        // most-preferred first. ZRLE (16) is offered first for its much
        // better bandwidth efficiency over non-LAN links (PERF FIX); Raw (0)
        // and CopyRect (1) are always included too — every compliant RFB
        // server supports Raw, and CopyRect is cheap for the server to use
        // opportunistically (e.g. window drags/scrolling) regardless of
        // where it sits in this list. If the server doesn't understand ZRLE
        // it simply never chooses it and always sends Raw/CopyRect rectangles
        // instead — the connection itself never fails over this.
        // LIVE-RESIZE FIX: -223 (DesktopSize) and -308 (ExtendedDesktopSize) are
        // pseudo-encodings, not pixel data — advertising them tells the server
        // it may notify us (via a rectangle using one of these "encodings" in a
        // FramebufferUpdate) whenever the desktop size changes, and -308
        // additionally lets *us* request a resize with SetDesktopSize (message
        // 251, sent from [requestResize]) and get back a proper accept/reject
        // reply instead of guessing. Servers that don't implement either simply
        // never send them — this list only ever grows what the server is
        // permitted to do, it can't break a server that ignores it.
        val encodings = intArrayOf(16, 1, 0, -223, -308) // ZRLE, CopyRect, Raw, DesktopSize, ExtendedDesktopSize
        output.writeByte(2)
        output.writeByte(0) // padding
        output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        output.flush()
    }

    // ── Ongoing message loop ────────────────────────────────────────────

    private fun readLoop() {
        val inp = input ?: return
        try {
            while (running) {
                val messageType = try {
                    inp.readUnsignedByte()
                } catch (e: SocketTimeoutException) {
                    // No update in the read-timeout window — request again
                    // (covers servers that don't push spontaneously) and keep
                    // the loop alive instead of treating this as fatal.
                    sendFramebufferUpdateRequest(incremental = true, x = 0, y = 0, w = fbWidth, h = fbHeight)
                    continue
                }
                when (messageType) {
                    0 -> handleFramebufferUpdate(inp)
                    1 -> skipSetColourMapEntries(inp)
                    2 -> { /* Bell — no payload, nothing to render */ }
                    3 -> handleServerCutText(inp)
                    else -> throw IOException("Unknown RFB server message type: $messageType")
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "RFB read loop ended: ${e.message}")
            }
        } finally {
            running = false
            framebuffer = null // signals VncClient's frame loop that the session is gone
            closeQuietly()
        }
    }

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.readUnsignedByte() // padding
        val numRects = inp.readUnsignedShort()
        repeat(numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            val encoding = inp.readInt()
            // LIVE-RESIZE FIX: re-read framebuffer on every rectangle (instead
            // of once before the loop) so that if one of the resize
            // pseudo-encodings below reallocates it mid-update, the remaining
            // rectangles in this same FramebufferUpdate draw into the new
            // bitmap instead of a stale reference of the old size.
            when (encoding) {
                0 -> applyRawRect(inp, framebuffer ?: return@repeat, x, y, w, h)
                1 -> applyCopyRect(inp, framebuffer ?: return@repeat, x, y, w, h)
                16 -> applyZrleRect(inp, framebuffer ?: return@repeat, x, y, w, h)
                -223 -> handleDesktopSizeRect(w, h)
                -308 -> handleExtendedDesktopSizeRect(inp, reason = x, status = y, w = w, h = h)
                else -> throw IOException("Unsupported RFB encoding from server: $encoding")
            }
        }
        // Ask for the next incremental update — keeps the session live
        // without us having to poll on a fixed timer.
        sendFramebufferUpdateRequest(incremental = true, x = 0, y = 0, w = fbWidth, h = fbHeight)
    }

    // ── LIVE-RESIZE FIX: server-driven / server-confirmed desktop resize ────

    /**
     * Simple "DesktopSize" pseudo-encoding (-223): no payload beyond the
     * rectangle header itself — [w]/[h] in the header ARE the new desktop
     * size. Older servers that predate the "Extended" variant send this
     * whenever they change the framebuffer size for any reason.
     */
    private fun handleDesktopSizeRect(w: Int, h: Int) {
        resizeFramebuffer(w, h)
    }

    /**
     * "ExtendedDesktopSize" pseudo-encoding (-308): the richer, negotiable
     * variant. The rectangle header's x/y fields are repurposed as
     * (reason, status) rather than a real position:
     *   reason: 0 = server-initiated, 1 = this client's request, 2 = another
     *           client's request.
     *   status: 0 = success, non-zero = the request was rejected (e.g.
     *           administratively prohibited / out of resources) — in that
     *           case the desktop size is UNCHANGED and must not be applied.
     * The header's w/h are the (possibly unchanged) desktop size. The rect
     * body lists the resulting per-screen layout, which this client doesn't
     * need to render (single-framebuffer view) but must still consume so the
     * stream stays in sync.
     */
    private fun handleExtendedDesktopSizeRect(inp: DataInputStream, reason: Int, status: Int, w: Int, h: Int) {
        val numScreens = inp.readUnsignedByte()
        inp.skipBytesFully(3) // padding
        inp.skipBytesFully(numScreens * 16) // per-screen: id(4)+x(2)+y(2)+w(2)+h(2)+flags(4)
        if (status == 0) resizeFramebuffer(w, h)
        // status != 0 -> our (or another client's) resize request was refused;
        // keep the current framebuffer as-is, nothing to reconcile.
    }

    /** Reallocates [framebuffer] at the new size and requests a full repaint of it. */
    private fun resizeFramebuffer(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return
        if (w == fbWidth && h == fbHeight && framebuffer != null) return
        fbWidth = w
        fbHeight = h
        framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Non-incremental: the freshly allocated bitmap starts blank, so ask
        // for real pixel content immediately rather than waiting for the
        // server's next spontaneous incremental update.
        sendFramebufferUpdateRequest(incremental = false, x = 0, y = 0, w = fbWidth, h = fbHeight)
    }

    /**
     * Asks the server to resize the desktop (RFB SetDesktopSize, message-type
     * 251 — the client-to-server counterpart of the ExtendedDesktopSize
     * pseudo-encoding advertised in [setPixelFormatAndEncodings]). RFB has no
     * capability handshake for this message, so we simply send it; a server
     * that doesn't support it either ignores the message outright or replies
     * with an ExtendedDesktopSize rectangle carrying a non-zero status, both
     * of which leave the session at its current resolution with no error to
     * the caller — resizing the remote desktop is inherently best-effort.
     */
    /**
     * HIRES-ZOOM FEATURE: forces a full, non-incremental
     * FramebufferUpdateRequest for the entire desktop. Used by
     * [com.systemsgo.hex.vnc.protocol.VncClient.refresh] once a client-side
     * pinch/pan gesture settles, so the viewport is guaranteed a complete
     * repaint rather than relying on whatever incremental rectangles
     * happened to arrive mid-gesture. Safe to call at any time the
     * connection is up; a no-op before the initial ServerInit has set
     * [fbWidth]/[fbHeight].
     */
    fun requestFullRefresh() {
        if (fbWidth <= 0 || fbHeight <= 0) return
        sendFramebufferUpdateRequest(incremental = false, x = 0, y = 0, w = fbWidth, h = fbHeight)
    }

    fun requestResize(width: Int, height: Int) {
        val out = output ?: return
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return
        synchronized(writeLock) {
            try {
                out.writeByte(251)     // message-type: SetDesktopSize
                out.writeByte(0)       // padding
                out.writeShort(width)
                out.writeShort(height)
                out.writeByte(1)       // number-of-screens
                out.writeByte(0)       // padding
                out.writeInt(0)        // screen id
                out.writeShort(0)      // screen x-position
                out.writeShort(0)      // screen y-position
                out.writeShort(width)  // screen width
                out.writeShort(height) // screen height
                out.writeInt(0)        // flags
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    private fun applyRawRect(inp: DataInputStream, fb: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val byteCount = w * h * 4
        val raw = ByteArray(byteCount)
        inp.readFully(raw)
        val pixels = IntArray(w * h)
        var p = 0
        var i = 0
        while (i < byteCount) {
            // Bytes on the wire are little-endian B,G,R,pad (per the fixed
            // SetPixelFormat we requested above).
            val b = raw[i].toInt() and 0xFF
            val g = raw[i + 1].toInt() and 0xFF
            val r = raw[i + 2].toInt() and 0xFF
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            p++
            i += 4
        }
        fb.setPixels(pixels, 0, w, x, y, w, h)
    }

    private fun applyCopyRect(inp: DataInputStream, fb: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        if (w <= 0 || h <= 0) return
        val pixels = IntArray(w * h)
        fb.getPixels(pixels, 0, w, srcX, srcY, w, h)
        fb.setPixels(pixels, 0, w, x, y, w, h)
    }

    // ── ZRLE (RFB encoding 16) ──────────────────────────────────────────
    // One zlib-compressed chunk per rectangle, decompressing to a grid of
    // 64x64 tiles (edge tiles are smaller), each independently
    // sub-encoded. See RFC 6143 §7.7.5. The zlib *stream* itself spans the
    // whole connection (see zrleInflater doc above) — only the compressed
    // byte length is per-rectangle.

    private fun applyZrleRect(inp: DataInputStream, fb: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val length = inp.readInt()
        if (length < 0 || length > 32 * 1024 * 1024) {
            throw IOException("Implausible ZRLE rectangle length: $length")
        }
        val compressed = ByteArray(length)
        inp.readFully(compressed)
        if (w <= 0 || h <= 0) return // nothing to decode, but bytes above were still consumed

        val inflater = zrleInflater ?: Inflater().also { zrleInflater = it }
        inflater.setInput(compressed, 0, length)

        val tilePixels = IntArray(64 * 64)
        var ty = 0
        while (ty < h) {
            val tileH = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tileW = minOf(64, w - tx)
                decodeZrleTile(inflater, tilePixels, tileW, tileH)
                fb.setPixels(tilePixels, 0, tileW, x + tx, y + ty, tileW, tileH)
                tx += 64
            }
            ty += 64
        }
    }

    private fun decodeZrleTile(inflater: Inflater, tilePixels: IntArray, tileW: Int, tileH: Int) {
        val pixelCount = tileW * tileH
        when (val sub = zrleReadByte(inflater)) {
            0 -> { // Raw: one CPIXEL per pixel, row-major
                for (i in 0 until pixelCount) tilePixels[i] = zrleReadCPixel(inflater)
            }
            1 -> { // Solid: single CPIXEL fills the whole tile
                val color = zrleReadCPixel(inflater)
                for (i in 0 until pixelCount) tilePixels[i] = color
            }
            in 2..16 -> { // Packed Palette: paletteSize == sub
                val paletteSize = sub
                val palette = IntArray(paletteSize) { zrleReadCPixel(inflater) }
                val bitsPerPixel = when {
                    paletteSize == 2 -> 1
                    paletteSize <= 4 -> 2
                    else -> 4 // 5..16
                }
                val rowBytes = (tileW * bitsPerPixel + 7) / 8
                var idx = 0
                for (row in 0 until tileH) {
                    // Each row is padded to a byte boundary — reset bit
                    // position per row rather than packing continuously.
                    val rowData = zrleReadBytes(inflater, rowBytes)
                    var bitPos = 0
                    for (col in 0 until tileW) {
                        val byteIdx = bitPos ushr 3
                        val shift = 8 - bitsPerPixel - (bitPos and 7)
                        val mask = (1 shl bitsPerPixel) - 1
                        val paletteIdx = (rowData[byteIdx].toInt() ushr shift) and mask
                        tilePixels[idx++] = palette[paletteIdx]
                        bitPos += bitsPerPixel
                    }
                }
            }
            128 -> { // Plain RLE: repeating (CPIXEL, run-length) pairs
                var idx = 0
                while (idx < pixelCount) {
                    val color = zrleReadCPixel(inflater)
                    val runLen = zrleReadRunLength(inflater)
                    val fill = minOf(runLen, pixelCount - idx)
                    for (i in 0 until fill) tilePixels[idx + i] = color
                    idx += fill
                }
            }
            in 130..255 -> { // Palette RLE: paletteSize == sub - 128
                val paletteSize = sub - 128
                val palette = IntArray(paletteSize) { zrleReadCPixel(inflater) }
                var idx = 0
                while (idx < pixelCount) {
                    val rawIndex = zrleReadByte(inflater)
                    val isRun = (rawIndex and 0x80) != 0
                    val color = palette[rawIndex and 0x7F]
                    val runLen = if (isRun) zrleReadRunLength(inflater) else 1
                    val fill = minOf(runLen, pixelCount - idx)
                    for (i in 0 until fill) tilePixels[idx + i] = color
                    idx += fill
                }
            }
            else -> throw IOException("Unsupported ZRLE tile sub-encoding: $sub")
        }
    }

    /** Reads a single VNC "CPIXEL" (3 bytes: B,G,R — matches our fixed 32bpp/depth24 SetPixelFormat, minus its padding byte) from the ZRLE zlib stream. */
    private fun zrleReadCPixel(inflater: Inflater): Int {
        val buf = zrleReadBytes(inflater, 3)
        val b = buf[0].toInt() and 0xFF
        val g = buf[1].toInt() and 0xFF
        val r = buf[2].toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** RFB run-length encoding: sum bytes while == 255, add the final (<255) byte, plus 1. */
    private fun zrleReadRunLength(inflater: Inflater): Int {
        var len = 0
        while (true) {
            val b = zrleReadByte(inflater)
            len += b
            if (b < 255) break
        }
        return len + 1
    }

    private fun zrleReadByte(inflater: Inflater): Int {
        zrleReadBytesInto(inflater, zrleByteBuf, 0, 1)
        return zrleByteBuf[0].toInt() and 0xFF
    }

    private fun zrleReadBytes(inflater: Inflater, count: Int): ByteArray {
        val out = ByteArray(count)
        zrleReadBytesInto(inflater, out, 0, count)
        return out
    }

    private fun zrleReadBytesInto(inflater: Inflater, dest: ByteArray, offset: Int, count: Int) {
        var off = offset
        val end = offset + count
        while (off < end) {
            val n = try {
                inflater.inflate(dest, off, end - off)
            } catch (e: java.util.zip.DataFormatException) {
                throw IOException("Corrupt ZRLE zlib stream: ${e.message}", e)
            }
            if (n == 0) {
                if (inflater.needsInput()) {
                    throw IOException("ZRLE stream truncated: rectangle ended mid-tile")
                }
                if (inflater.finished()) {
                    throw IOException("ZRLE zlib stream ended unexpectedly")
                }
            }
            off += n
        }
    }

    private fun skipSetColourMapEntries(inp: DataInputStream) {
        inp.readUnsignedByte() // padding
        inp.readUnsignedShort() // first-colour
        val n = inp.readUnsignedShort()
        inp.skipBytesFully(n * 6)
    }

    /**
     * CLIPBOARD FIX: ServerCutText (RFB Protocol §7.5.4) previously had its
     * payload skipped unread. Now decoded and handed to [onServerCutText] so
     * VncClient can mirror it into the Android system clipboard. Text is
     * ISO-8859-1 (Latin-1) — the encoding the RFB base protocol's clipboard
     * messages use; there is no capability negotiation for this, so a
     * malformed/oversized length is simply treated as "no clipboard update"
     * rather than tearing down the whole connection.
     */
    private fun handleServerCutText(inp: DataInputStream) {
        inp.skipBytesFully(3) // padding
        val len = inp.readInt()
        if (len <= 0 || len > (1 shl 20)) {
            if (len > 0) inp.skipBytesFully(len)
            return
        }
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        onServerCutText?.invoke(String(bytes, Charsets.ISO_8859_1))
    }

    private fun DataInputStream.skipBytesFully(count: Int) {
        var remaining = count
        val buf = ByteArray(minOf(remaining, 8192).coerceAtLeast(1))
        while (remaining > 0) {
            val n = read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) throw EOFException()
            remaining -= n
        }
    }

    // ── Outgoing client messages ────────────────────────────────────────

    private fun sendFramebufferUpdateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(3)
                out.writeByte(if (incremental) 1 else 0)
                out.writeShort(x)
                out.writeShort(y)
                out.writeShort(w)
                out.writeShort(h)
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /** Sends a pointer (mouse) event. mask: 1=left, 2=middle, 4=right, 8/16=wheel. */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(5)
                out.writeByte(mask and 0xFF)
                out.writeShort(x.coerceIn(0, 65535))
                out.writeShort(y.coerceIn(0, 65535))
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /** Sends a keyboard event. keysym: X11 keysym code. down: true=press, false=release. */
    fun sendKeyEvent(keysym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keysym)
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /**
     * CLIPBOARD FIX: sends ClientCutText (RFB Protocol §7.5.6, message-type
     * 6) — the client-to-server counterpart of ServerCutText — so local
     * clipboard changes reach the remote desktop. Best-effort like the other
     * outgoing messages in this class (sendPointerEvent/sendKeyEvent/
     * requestResize): a server that ignores clipboard sync simply never
     * reacts to it, and any write failure is swallowed as "connection
     * already closing" rather than surfaced as an error.
     *
     * Text is encoded as ISO-8859-1 (Latin-1), matching the encoding used
     * for ServerCutText — the RFB base protocol has no extension for wider
     * charsets. Characters outside Latin-1 are replaced by the charset's
     * standard substitute character rather than failing the whole send.
     */
    fun sendClientCutText(text: String) {
        if (text.isEmpty()) return
        val out = output ?: return
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        synchronized(writeLock) {
            try {
                out.writeByte(6)
                out.write(byteArrayOf(0, 0, 0)) // padding
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            } catch (_: IOException) { /* connection already closing */ }
        }
    }

    /** Closes the connection and releases all resources. */
    fun close() {
        running = false
        // LISTEN-MODE FEATURE: if connect() is still parked inside
        // acceptListenModeSocket()'s server.accept() call (waiting for the
        // remote server to dial in), closing the ServerSocket here makes
        // that call throw immediately instead of leaving the caller blocked
        // for up to LISTEN_ACCEPT_TIMEOUT_MS after the user cancels.
        try { serverSocket?.close() } catch (e: Exception) { android.util.Log.d("RfbConnectable", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        closeQuietly()
        readerThread?.let {
            if (it !== Thread.currentThread()) {
                try { it.join(1000) } catch (e: InterruptedException) { android.util.Log.d("RfbConnectable", "interrupted, restoring interrupt status"); Thread.currentThread().interrupt() }
            }
        }
        framebuffer = null
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (e: Exception) { android.util.Log.d("RfbConnectable", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socket = null
        input = null
        output = null
        // Inflater wraps native zlib memory that isn't GC-managed — must be
        // released explicitly or every reconnect leaks it.
        zrleInflater?.end()
        zrleInflater = null
    }
}
