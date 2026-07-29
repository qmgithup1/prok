package com.systemsgo.hex.vnc.ard

import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * ARD FEATURE: client for Apple Remote Desktop's *administration* channel
 * — UDP port 3283, sometimes called "ARMS" (Apple Remote Management
 * Service) — as opposed to the Screen Sharing/RFB channel on TCP port
 * 5900 (see [com.undatech.opaque.RfbConnectable.negotiateAppleRA2]). This
 * is the channel behind Remote Desktop.app features like "Get Info",
 * "Send UNIX Command", "Send Message", remote shutdown/restart, and — via
 * [copyItem]/[installPackage], composed on top of "Send UNIX Command"
 * rather than a ported wire format (see those functions' doc comments for
 * why and how) — "Copy Items" and "Install Packages".
 *
 * ## Provenance and confidence level — please read before trusting this
 *
 * There is no public Apple specification for this protocol. This
 * implementation is a Kotlin port of the wire-format handling in
 * `ardclient` (Dan Keder, GPL-2, originally hosted at
 * code.google.com/p/ardclient, mirrored at github.com/foxlet/ardclient).
 * That project reimplements the *target* (managed-Mac) side of this
 * protocol well enough that Apple's real Remote Desktop.app successfully
 * drives it — which is strong evidence that the fields ardclient actually
 * *parses and validates* are correct. This port flips those roles: we
 * play the *admin* (Remote Desktop.app's) side, talking to a real Mac's
 * built-in ARDAgent, which we have never been able to test against here
 * (no network access in this environment).
 *
 * Confidence is not uniform across every field:
 *  - The Diffie-Hellman handshake (fixed 512-bit prime, MD5+AES128-ECB key
 *    derivation) is the same well-verified crypto family as the Screen
 *    Sharing RA2 scheme, and ardclient's server-side code both produces
 *    and validates these fields against the real Remote Desktop.app, so
 *    this is our highest-confidence part.
 *  - The [addComputer] "packet 1" we send to *initiate* the handshake is
 *    a genuine unknown: ardclient's server received this field from the
 *    real Remote Desktop.app but never needed to inspect its content (see
 *    `parse_packet_1` in ardclient's `add_computer.py`, which parses 24
 *    bytes and never reads them back). We send 24 zero bytes as a
 *    best-effort placeholder. If a real Mac's ARDAgent validates this
 *    field strictly, the handshake may be rejected — this is the single
 *    most likely point of failure in this whole implementation.
 *  - [sendUnixCommand]'s outgoing ACK packets (packets 4/6/8/10 in
 *    ardclient's naming) are inferred by symmetry with the one clearly
 *    self-consistent example (`make_packet_2`, which echoes the request's
 *    packet code plus a zero status byte) rather than observed directly,
 *    since ardclient only needed to *produce* the packets we now need to
 *    *consume*, and vice versa.
 *  - Several fields we only need to skip over (short "marker" byte
 *    sequences ardclient hardcodes when playing the target role, e.g.
 *    `"\x00\x40"` before a MAC address) have no documented meaning even in
 *    the reference source — we don't attempt to interpret them, only skip
 *    past them positionally.
 *
 * Given that, this should be treated as an untested best-effort
 * implementation, not a verified one — test against a real Mac with
 * "Remote Management" enabled (System Settings > General > Sharing)
 * before relying on it.
 *
 * ## What is *not* implemented
 *
 * "Get Info" (device status/idle query) is deliberately left out: the
 * reference implementation's outgoing reply for that request is built
 * from long runs of unexplained, seemingly capture-derived magic bytes
 * (raw pointer-looking values, no field labels) rather than a clean
 * struct — a strong signal that even the reference author was relying on
 * copying bytes from a packet capture rather than a understood format.
 * Reusing that as a *parser* for a real Mac's reply would mean trusting
 * byte offsets nobody has actually explained; skipped rather than ported.
 *
 * Copy Items and Install Packages *are* implemented — see [copyItem]/
 * [installPackage] — but as a composition on top of [sendUnixCommand]
 * rather than a native wire format, since no such format was found
 * documented or reverse-engineered anywhere (same "genuinely undocumented"
 * situation as "Get Info" above). Reports and Task Server remain
 * unimplemented for that same reason, with no equivalent
 * compose-from-something-that-works path available for either.
 */
object ArdAdminClient {

    private const val DEFAULT_PORT = 3283
    private const val DEFAULT_TIMEOUT_MS = 6000

    // Fixed 512-bit DH prime modulus, hardcoded on both sides of this
    // protocol (unlike Screen Sharing's RA2, which sends a fresh prime
    // per-session — see ardclient's utils.py: generate_key_pair /
    // make_shared_key). Copied verbatim from that source.
    private val FIXED_PRIME = BigInteger(
        "F8283BEFD6E0C7B39A79E8031F0B6CDC5C5C412DB8B8C10CD554DFF0E161DAF" +
            "4F57734EA0CABF2C50B77C1946D2B387E41D6737A5D4956EA3D370FBB36A828D7",
        16
    )

    private const val CODE_ACK_PACKET = 0x0000
    private const val CODE_ADD_COMPUTER = 0x7d01
    private const val CODE_ADD_COMPUTER_2 = 0x7d00
    private const val CODE_SEND_CMD = 0x006b
    private const val CODE_CMD_RESULTS = 0x005f
    private const val CODE_CMD_RESULTS_2 = 0x006c

    class ArdProtocolException(message: String) : IOException(message)
    class ArdAuthDeniedException : IOException("Apple Remote Desktop denied the admin credentials (wrong username/password, or Remote Management not enabled for this account)")

    /** An authenticated session, ready for [sendUnixCommand] calls. */
    class ArdAdminSession internal constructor(
        internal val socket: DatagramSocket,
        internal val address: InetAddress,
        internal val port: Int
    ) {
        fun close() {
            socket.close()
        }
    }

    data class ArdCommandResult(
        val exitStatus: Int,
        /** Combined output text collected across the exchange (see [sendUnixCommand]). */
        val output: String
    )

    // ── Wire framing ──────────────────────────────────────────────────────
    // Every packet: 2-byte big-endian code, 2-byte big-endian data length,
    // then that many bytes of data — one per UDP datagram (see ardclient's
    // packet.py: Packet.get_bytes() / parse()).

    private data class RawPacket(val code: Int, val data: ByteArray)

    private fun sendPacket(socket: DatagramSocket, address: InetAddress, port: Int, code: Int, data: ByteArray) {
        val buf = ByteBuffer.allocate(4 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(code.toShort())
        buf.putShort(data.size.toShort())
        buf.put(data)
        val dp = DatagramPacket(buf.array(), buf.array().size, address, port)
        socket.send(dp)
    }

    private fun receivePacket(socket: DatagramSocket, timeoutMs: Int): RawPacket {
        socket.soTimeout = timeoutMs
        val buf = ByteArray(65535)
        val dp = DatagramPacket(buf, buf.size)
        try {
            socket.receive(dp)
        } catch (e: SocketTimeoutException) {
            throw ArdProtocolException("No reply from ARD admin service within ${timeoutMs}ms (is Remote Management enabled on the target Mac?)")
        }
        val bb = ByteBuffer.wrap(dp.data, dp.offset, dp.length).order(ByteOrder.BIG_ENDIAN)
        if (dp.length < 4) throw ArdProtocolException("Reply too short to be a valid ARD packet (${dp.length} bytes)")
        val code = bb.short.toInt() and 0xFFFF
        val len = bb.short.toInt() and 0xFFFF
        val data = ByteArray(len)
        bb.get(data, 0, minOf(len, bb.remaining()))
        return RawPacket(code, data)
    }

    // ── Shared DH/AES crypto helpers (see class doc: same family as RA2) ──

    private fun toFixedLengthBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        val srcStart = maxOf(0, raw.size - length)
        val copyLen = minOf(raw.size - srcStart, length)
        System.arraycopy(raw, srcStart, out, length - copyLen, copyLen)
        return out
    }

    private fun deriveSharedKey(peerPublic: BigInteger, ourPrivate: BigInteger): ByteArray {
        val secret = peerPublic.modPow(ourPrivate, FIXED_PRIME)
        // 64-byte zero-padded, then MD5'd — matches utils.make_shared_key.
        return MessageDigest.getInstance("MD5").digest(toFixedLengthBytes(secret, 64))
    }

    /** Writes [text] NUL-terminated into a [fieldWidth]-byte region starting at [offset], truncating if needed. */
    private fun writeField(dest: ByteArray, offset: Int, fieldWidth: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val copyLen = minOf(bytes.size, fieldWidth - 1)
        System.arraycopy(bytes, 0, dest, offset, copyLen)
        dest[offset + copyLen] = 0
    }

    private fun pascalString(text: String, totalWidth: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(totalWidth)
        val copyLen = minOf(bytes.size, totalWidth - 1)
        out[0] = copyLen.toByte()
        System.arraycopy(bytes, 0, out, 1, copyLen)
        return out
    }

    // ── Add Computer (auth handshake) ───────────────────────────────────

    /**
     * Registers this app as an authorized "admin" with the target Mac's
     * ARDAgent, using [adminUsername]/[adminPassword] — the same
     * credentials Apple Remote Desktop / System Settings > Sharing >
     * Remote Management would prompt for. Must succeed before
     * [sendUnixCommand] can be used. See class doc for the confidence
     * caveats, especially around the initial request's content.
     *
     * @throws ArdAuthDeniedException if the target rejected the credentials.
     * @throws ArdProtocolException on any other unexpected reply.
     */
    fun addComputer(
        host: String,
        adminUsername: String,
        adminPassword: String,
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): ArdAdminSession {
        val address = InetAddress.getByName(host)
        val socket = DatagramSocket()
        try {
            // Packet 1: initiate. Content unverified — see class doc.
            sendPacket(socket, address, port, CODE_ADD_COMPUTER, ByteArray(24))

            // Packet 2: server's DH generator + public key.
            // Layout ("!H 2s I 64s 18s"): echoed code(2) + pad(2) + g(4) + serverPub(64) + pad(18)
            val packet2 = receivePacket(socket, timeoutMs)
            if (packet2.code != CODE_ACK_PACKET || packet2.data.size < 2 + 2 + 4 + 64) {
                throw ArdProtocolException("Unexpected reply to Add Computer request (code=0x${packet2.code.toString(16)}, ${packet2.data.size} bytes)")
            }
            val bb2 = ByteBuffer.wrap(packet2.data).order(ByteOrder.BIG_ENDIAN)
            bb2.position(2 + 2) // skip echoed code + 2 zero pad bytes
            val g = BigInteger.valueOf(bb2.int.toLong() and 0xFFFFFFFFL)
            val serverPubBytes = ByteArray(64)
            bb2.get(serverPubBytes)
            val serverPublic = BigInteger(1, serverPubBytes)

            // Our own DH keypair over the fixed prime + server-chosen generator.
            val random = SecureRandom()
            val ourPrivate = BigInteger(512, random).mod(FIXED_PRIME)
            val ourPublic = g.modPow(ourPrivate, FIXED_PRIME)
            val sharedKey = deriveSharedKey(serverPublic, ourPrivate)

            // 128-byte credentials block: username in [0,32), password in
            // [64,96), rest random padding — see utils.decrypt callers in
            // ardclient's client.py (handle_add_computer).
            val credentials = ByteArray(128)
            random.nextBytes(credentials)
            writeField(credentials, 0, 32, adminUsername)
            writeField(credentials, 64, 32, adminPassword)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedKey, "AES"))
            val encryptedCredentials = cipher.doFinal(credentials)

            // Packet 3: our public key + encrypted credentials + identity fields.
            // Layout: pubkey(64) + pad(2) + credentials(128) + hostname pascal(128)
            //         + mac(6) + pad(16) + id(12) + serial(42) + pad(64)
            val ourPublicBytes = toFixedLengthBytes(ourPublic, 64)
            val hostnameField = pascalString(android.os.Build.MODEL ?: "Android", 128)
            val packet3Data = ourPublicBytes + ByteArray(2) + encryptedCredentials +
                hostnameField + ByteArray(6) + ByteArray(16) + ByteArray(12) + ByteArray(42) + ByteArray(64)
            sendPacket(socket, address, port, CODE_ADD_COMPUTER_2, packet3Data)

            // Packet 4: grant or deny.
            // Denied ("!HH"): echoed code(2) + errorCode(2) = 4 bytes total.
            // Granted ("!H 10s 4s 6s 34s H"): much longer (58 bytes).
            val packet4 = receivePacket(socket, timeoutMs)
            if (packet4.data.size <= 4) {
                throw ArdAuthDeniedException()
            }
            // Granted — we don't currently need the returned IP/MAC/flags.
            return ArdAdminSession(socket, address, port)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    // ── Send UNIX Command ───────────────────────────────────────────────

    /**
     * Runs [command] as [user] on the Mac behind [session] (already
     * authenticated via [addComputer]), and returns its output and exit
     * status. This is the same capability as Remote Desktop.app's "Send
     * UNIX Command" task — full shell access as the given user, so treat
     * credentials and command input with the same care you would for SSH.
     *
     * Blocks until the remote command finishes or [timeoutMs] elapses
     * with no reply at any step.
     */
    fun sendUnixCommand(
        session: ArdAdminSession,
        user: String,
        command: String,
        timeoutMs: Int = 15000
    ): ArdCommandResult {
        val socket = session.socket
        val address = session.address
        val port = session.port
        val taskId = SecureRandom().nextInt(Int.MAX_VALUE / 2)

        // Packet 1 (ours): task_id(4) + user(41, NUL-padded) + pascal(cmd) + pad(2)
        val userField = ByteArray(41)
        writeField(userField, 0, 41, user)
        val cmdBytes = command.toByteArray(Charsets.UTF_8)
        // BUG FIX: cmdBytes.size.toByte() below silently truncates to the low
        // 8 bits for any command over 255 bytes (Kotlin Int.toByte() wraps,
        // it doesn't clamp) instead of failing loudly — a real latent bug,
        // not a hypothetical one: [copyItem]'s chunked transfer generates
        // exactly this kind of programmatic command and must never exceed
        // this limit, so this now fails fast with a clear message instead of
        // silently sending a corrupted length byte if it ever did.
        require(cmdBytes.size <= 255) {
            "ARD Send UNIX Command's wire format uses a 1-byte command length " +
                "(max 255 bytes) — got ${cmdBytes.size} bytes: \"$command\""
        }
        val p1 = ByteBuffer.allocate(4 + 41 + 1 + cmdBytes.size + 2).order(ByteOrder.BIG_ENDIAN)
        p1.putInt(taskId)
        p1.put(userField)
        p1.put(cmdBytes.size.toByte())
        p1.put(cmdBytes)
        p1.put(ByteArray(2))
        sendPacket(socket, address, port, CODE_SEND_CMD, p1.array())

        // Packet 2 (server ack of the request) — just validate it arrived.
        val packet2 = receivePacket(socket, timeoutMs)
        if (packet2.code != CODE_ACK_PACKET) {
            throw ArdProtocolException("Unexpected reply after Send Command (code=0x${packet2.code.toString(16)})")
        }

        // Packet 3 (server: task started) — task_id(4) + 10 unexplained bytes.
        val packet3 = receivePacket(socket, timeoutMs)
        if (packet3.code != CODE_CMD_RESULTS) {
            throw ArdProtocolException("Expected task-started notice, got code=0x${packet3.code.toString(16)}")
        }
        ackCmdResults(socket, address, port)

        // Packet 5 (server: output ready) — task_id(4) + marker(2) + mac(6) + output(rest).
        val packet5 = receivePacket(socket, timeoutMs)
        var output = ""
        if (packet5.code == CODE_CMD_RESULTS && packet5.data.size > 12) {
            output = String(packet5.data, 12, packet5.data.size - 12, Charsets.UTF_8)
        }
        ackCmdResults(socket, address, port)

        // Packet 7 (server: final status + last output line) — status(1) + marker(3) + text.
        val packet7 = receivePacket(socket, timeoutMs)
        var status = -1
        if (packet7.code == CODE_CMD_RESULTS_2 && packet7.data.isNotEmpty()) {
            status = packet7.data[0].toInt() and 0xFF
            if (packet7.data.size > 4) {
                val lastLine = String(packet7.data, 4, packet7.data.size - 4, Charsets.UTF_8).trimEnd('\u0000')
                if (lastLine.isNotBlank() && !output.contains(lastLine)) {
                    output = if (output.isEmpty()) lastLine else "$output\n$lastLine"
                }
            }
        }
        ackCmdResultsFinal(socket, address, port)

        // Packet 9 (server: task fully finished) — task_id(4) + marker(2) + mac(6).
        val packet9 = receivePacket(socket, timeoutMs)
        if (packet9.code == CODE_CMD_RESULTS) {
            ackCmdResults(socket, address, port)
        }

        return ArdCommandResult(status, output)
    }

    /** Sends the "!HB" ack (echoed code + zero status byte) ardclient expects after a CMD_RESULTS packet. */
    private fun ackCmdResults(socket: DatagramSocket, address: InetAddress, port: Int) {
        val data = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
        data.putShort(CODE_CMD_RESULTS.toShort())
        data.put(0.toByte())
        sendPacket(socket, address, port, CODE_ACK_PACKET, data.array())
    }

    private fun ackCmdResultsFinal(socket: DatagramSocket, address: InetAddress, port: Int) {
        val data = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
        data.putShort(CODE_CMD_RESULTS_2.toShort())
        data.put(0.toByte())
        sendPacket(socket, address, port, CODE_ACK_PACKET, data.array())
    }

    // ── Copy Items / Install Packages ───────────────────────────────────
    // ARD-COPY-INSTALL FEATURE: see [copyItem]'s doc comment for the full
    // reasoning — this is a real, working feature, but built by composing
    // [sendUnixCommand] rather than porting a Copy-Items/Install-Packages
    // wire format that (per extensive searching — ardclient, Wireshark's
    // dissector list, general "ARD protocol reverse engineered" searches)
    // does not exist anywhere publicly.

    /** Safe single-quoting for a path/string dropped into a shell command sent via [sendUnixCommand]. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * ARD's real "Copy Items" and "Install Packages" tasks have no public
     * wire-format specification — confirmed by extensive searching (no
     * public Apple spec, `ardclient`'s server-side reference implementation
     * explicitly never received these commands over its lifetime and does
     * not model them, and no Wireshark dissector or other reverse-engineered
     * reference for them was found anywhere). That's *not* the end of the
     * story the way it is for e.g. VNC AppleRA2's undocumented bytes,
     * though: unlike those, this feature doesn't need its own wire format
     * at all, because [sendUnixCommand] — the "Send UNIX Command" task,
     * which *is* real and reasonably well-verified (see [ArdAdminClient]'s
     * class doc) — already gives full shell access to the target Mac. Any
     * file can be materialized on the far end by shipping it through that
     * channel as base64 text and decoding it there; no new packet types
     * needed, only composition of an already-working primitive.
     *
     * Mechanics: base64-encodes [data], then issues a sequence of
     * `printf %s '<chunk>' >>'<tmp file>'` commands (chunked to fit
     * [sendUnixCommand]'s real 255-byte wire limit — see that function's
     * size-guard comment) appending into a temp file on the target, then a
     * final `base64 -D -o '<remotePath>' '<tmp file>' && rm -f '<tmp
     * file>'` (macOS's BSD `base64 -D` flag, confirmed via `man base64` on
     * macOS — *not* GNU coreutils' `-d`) to decode it into place and clean
     * up the temp file.
     *
     * Performance/size reality check, stated plainly rather than left to
     * be discovered the hard way: each chunk is a full ARMS round-trip
     * (request → ack → task-started → output → final-status → task-done,
     * same exchange [sendUnixCommand] always does) over UDP, so this is
     * genuinely slow for large files — nothing like a real file-transfer
     * protocol's throughput. [maxBytes] defaults to 2 MiB specifically to
     * keep a worst-case transfer (thousands of round-trips) from silently
     * hanging for many minutes; callers moving larger files should raise
     * it deliberately and expect it to take a while, or use this app's SSH
     * client's SFTP support instead if the target also has Remote Login
     * enabled (Apple's own port docs list port 22 as this feature's
     * alternate "Encrypted file transfer" path — see
     * `support.apple.com/guide/remote-desktop`'s port reference — a much
     * faster route when available, but not always: Remote Management and
     * Remote Login are independent toggles in System Settings > Sharing,
     * and plenty of real deployments only enable the former).
     *
     * @throws ArdProtocolException if [data] exceeds [maxBytes].
     */
    fun copyItem(
        session: ArdAdminSession,
        user: String,
        data: ByteArray,
        remotePath: String,
        timeoutMs: Int = 15000,
        maxBytes: Int = 2 * 1024 * 1024,
        onProgress: ((bytesSent: Int, totalBytes: Int) -> Unit)? = null
    ): ArdCommandResult {
        if (data.size > maxBytes) {
            throw ArdProtocolException(
                "File is ${data.size} bytes, over the ${maxBytes}-byte cap for " +
                    "command-channel transfer — see copyItem's doc comment for why " +
                    "this has a cap and what to use instead for larger files"
            )
        }
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        val tmpPath = "/tmp/.sgohex_${SecureRandom().nextInt(Int.MAX_VALUE)}.b64"
        val quotedTmp = shellQuote(tmpPath)
        val quotedDest = shellQuote(remotePath)

        // Fresh temp file — clears any leftover from a prior failed attempt
        // that reused the same random suffix (astronomically unlikely, but
        // free to guard against).
        sendUnixCommand(session, user, "rm -f $quotedTmp", timeoutMs)

        // Chunk size: fit "printf %s '<chunk>'>>$quotedTmp" under the real
        // 255-byte wire limit with margin, computed from the actual
        // template overhead (which varies with tmpPath's length) rather
        // than a guessed constant.
        val templateOverhead = "printf %s ''>>$quotedTmp".toByteArray(Charsets.UTF_8).size
        val chunkSize = (255 - templateOverhead - 8).coerceAtLeast(16) // 8 bytes safety margin
        var offset = 0
        while (offset < b64.length) {
            val end = minOf(offset + chunkSize, b64.length)
            val chunk = b64.substring(offset, end)
            sendUnixCommand(session, user, "printf %s '$chunk'>>$quotedTmp", timeoutMs)
            offset = end
            onProgress?.invoke(offset, b64.length)
        }

        // Decode into place, then remove the temp file. `base64 -D` is
        // macOS's BSD decode flag (verified via `man base64` on macOS —
        // GNU coreutils uses lowercase `-d` instead, which would silently
        // do nothing useful here since this target is always macOS).
        return sendUnixCommand(
            session, user,
            "base64 -D -o $quotedDest $quotedTmp && rm -f $quotedTmp",
            timeoutMs
        )
    }

    /**
     * ARD's "Install Packages" task, composed from [copyItem] + a silent
     * `installer` invocation — matches Apple's own documented description
     * of what this task actually does under the hood (Apple Support:
     * "Remote Desktop copies the package to the remote clients, runs the
     * installer with no visible window or user interaction required, and
     * then deletes the installer packages on completion" —
     * support.apple.com/guide/remote-desktop/install-files-apdda2d11c4).
     * See [copyItem]'s doc comment for the transfer mechanics and its
     * size/performance caveats, which apply here too — a real multi-MB
     * `.pkg` will be slow; consider raising [maxBytes] deliberately and
     * expecting the call to take a while, or pre-staging the package via
     * SFTP over SSH (Remote Login) if that's enabled on the target and
     * copying only the trigger command through here instead.
     *
     * [user] should normally be `"root"` (or another account with
     * administrator rights) — `/usr/sbin/installer` requires elevated
     * privileges to write into `/`, same as it does interactively.
     *
     * @param restartAfter if true, appends a `shutdown -r now` after a
     *   successful install — mirrors ARD's "restart after installation"
     *   option. Fire-and-forget: the reply to this command may never
     *   arrive since the machine is rebooting, so a timeout here after a
     *   nonzero [ArdCommandResult.exitStatus] from the install step itself
     *   isn't necessarily a failure.
     */
    fun installPackage(
        session: ArdAdminSession,
        user: String,
        packageBytes: ByteArray,
        packageFileName: String,
        timeoutMs: Int = 15000,
        maxBytes: Int = 2 * 1024 * 1024,
        restartAfter: Boolean = false,
        onProgress: ((bytesSent: Int, totalBytes: Int) -> Unit)? = null
    ): ArdCommandResult {
        val remotePkgPath = "/tmp/${packageFileName.substringAfterLast('/')}"
        copyItem(session, user, packageBytes, remotePkgPath, timeoutMs, maxBytes, onProgress)

        val quotedPkg = shellQuote(remotePkgPath)
        val installResult = sendUnixCommand(
            session, user,
            "installer -pkg $quotedPkg -target / >/dev/null 2>&1; " +
                "s=\$?; rm -f $quotedPkg; exit \$s",
            timeoutMs
        )

        if (restartAfter && installResult.exitStatus == 0) {
            try {
                sendUnixCommand(session, user, "shutdown -r now", timeoutMs = 5000)
            } catch (_: ArdProtocolException) {
                // Expected: the machine is rebooting and may never reply — see doc comment.
            }
        }
        return installResult
    }
}
