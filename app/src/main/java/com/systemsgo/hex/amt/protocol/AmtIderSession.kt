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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Intel AMT IDE-R (IDE Redirection) session — AMT-VPRO FEATURE phase 5, the
 * AMT analogue of Redfish's `VirtualMedia` endpoints (see
 * AMT_VPRO_ROADMAP.md phase 5). Mounts a local `.iso` file as a virtual
 * CD/DVD-ROM on the managed box, regardless of its power/OS state.
 *
 * ## Corrected protocol basis (this class was previously built on a wrong
 * assumption — see below for what changed and why)
 * A management console connecting *directly* to AMT's dedicated
 * redirection port (16994/16995) — this app's mode, same as MeshCommander's
 * and meshcmd's "direct" connection mode — does **not** use the Intel AMT
 * Port Forwarding Protocol (APF): APF's own reference manual only documents
 * two use-cases (local LMS/HECI forwarding, and CIRA relaying through a
 * remote Management Presence Server), both requiring *AMT* to be the one
 * initiating the underlying transport — never a console dialing AMT's
 * redirection port directly. A prior version of this class assumed APF
 * applied here anyway (an unconfirmed inference, flagged as such in that
 * version's doc comment); that assumption is now known to be wrong,
 * confirmed by two independent, unambiguous sources:
 *  - Intel's own AMT SDK docs ("Redirection Library Kerberos Support"):
 *    the actual client library functions for this (`IMR_SOLOpenTCPSession`,
 *    `IMR_IDEROpenTCPSession`) "open a socket ... and negotiate the
 *    protocol between them" — a separate negotiation, not APF.
 *  - MeshCentral's `meshcmd` tool — a real, direct-connect (non-CIRA)
 *    command-line AMT console — implements exactly that separate
 *    negotiation in `agents/modules_meshcmd/amt-redir-duk.js` /
 *    `amt-sol.js` (Ylian Saint-Hilaire, Intel Corp., Apache-2.0), with zero
 *    APF messages anywhere: a `StartRedirectionSession` (`0x10`) /
 *    `StartRedirectionSessionReply` (`0x11`) exchange carrying a 4-byte
 *    ASCII service tag (`"SOL "`, `"IDER"`, `"KVMR"`) — this **is** the
 *    real SOL/IDE-R sub-service selector this class previously guessed at
 *    via an APF channel field that turns out not to apply — followed by an
 *    `AuthenticateSession` (`0x13`/`0x14`) exchange that is itself a
 *    binary-framed HTTP Digest handshake (realm/nonce/qop, `POST
 *    /RedirectionService` as the digest URI). Only *after* that succeeds
 *    does AMT start accepting IDE-R envelope messages — directly on this
 *    same raw socket, with no further transport wrapper.
 * (This codebase's separate CIRA/agent-side APF client,
 * `agents/modules_meshcmd/amt-apfclient.js`, confirms APF is real and
 * correctly implemented elsewhere in that project — just for the
 * device-initiates-outward CIRA case, not this one.)
 *
 * [AmtSolSession] shared the same old, incorrect assumption and has since
 * been fixed the same way.
 *
 * ## What this class does, and where the remaining gap is
 * Once `AuthenticateSession` succeeds, real IDE-R diverges from SOL: AMT's
 * firmware drives a virtual IDE/ATAPI controller and expects the far end to
 * answer real ATA/ATAPI commands (IDENTIFY, PACKET, READ(10), INQUIRY,
 * MODE SENSE, READ CAPACITY, ...) with correctly-framed responses sourced
 * from the mounted image file — a small disk-emulation engine, not a byte
 * pipe. That splits into two pieces:
 *  - The command sets themselves are public standards, not Intel's: SCSI/MMC
 *    (T10) for the CD/DVD-ROM side — see [AmtIderDiskEmulator] — and plain
 *    ATA (T13) for the floppy side — see [AmtIderFloppyEmulator]. Both are
 *    complete and independently testable regardless of the envelope below.
 *  - The IDE-R *envelope* (how those CDB/task-file bytes and responses are
 *    wrapped in messages once redirection starts) is Intel-proprietary and
 *    undocumented outside the full AMT SDK's C headers, but is now
 *    confirmed byte-for-byte from MeshCentral's server-side
 *    `amt/amt-ider-module.js` (`CreateAmtRemoteIder`) — an isolated,
 *    unminified Node.js source file. Every multi-byte field *inside* this
 *    envelope is little-endian, unlike the big-endian `StartRedirectionSession`
 *    header fields above it. That confirmed transcription covers the
 *    envelope framing and the CD-ROM/SCSI field layout; exactly which of
 *    the envelope's bytes carry the floppy side's ATA task-file registers
 *    is inferred rather than transcribed — see [AmtIderFloppyEmulator]'s
 *    top doc comment for the reasoning and what would need to change if a
 *    real capture ever disagrees.
 *
 * One ambiguity carried over from that source, now resolved rather than
 * left open (see [sendCommandComplete] and [sendCommandError]'s doc
 * comments for the full reasoning): MeshCentral's `SendCommandEndResponse`
 * has two possible reply frames — the one used for essentially every
 * command-end response in that source, success and error alike, and a
 * second, sense-carrying frame used at only two call sites, one of which
 * that source's own author flagged as unverified (`TODO: Send proper
 * error!!!`). This class still declines to reproduce that flagged frame.
 * What changed: errors are no longer reported using the *success* frame
 * either — they now use the other real, already-in-use frame this source
 * sends at its "writes not supported" error path, generalized to carry
 * whatever sense/error info [AmtIderDiskEmulator]/[AmtIderFloppyEmulator]
 * actually computed instead of that one call site's hardcoded value.
 *
 * ## Write support (AMT_VPRO_ROADMAP.md phase 5 follow-up)
 * That "writes not supported" frame was previously sent unconditionally
 * for every `CMD_DATA_FROM_HOST` message, regardless of device — an
 * intentional read-only stub. That's still true for [AmtIderMediaType.CD_ROM]
 * (a real CD/DVD-ROM was never writable — see [AmtIderDiskEmulator]'s doc
 * comment), but [AmtIderMediaType.FLOPPY] now supports real, opt-in writes:
 * see [mountAndServe]'s `writable` parameter and [AmtIderFloppyEmulator]'s
 * top doc comment for the full architecture reasoning, and the
 * `pendingWrite` field/`CMD_COMMAND_WRITTEN`/`CMD_DATA_FROM_HOST` handling
 * below for how a write command's registers (one envelope message) and its
 * payload (a separate, possibly-multi-message envelope exchange) are
 * reassembled before actually being written.
 */
internal class AmtIderSession(
    private val host: String,
    private val redirectionPort: Int,
    private val useTls: Boolean,
    private val acceptSelfSignedCertificate: Boolean,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 8000,
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

    @Volatile var state: AmtIderSessionState = AmtIderSessionState.CONNECTING
        private set

    /** True once `StartRedirectionSession`/`AuthenticateSession` have
     *  succeeded and it hasn't been closed — covers both
     *  [AmtIderSessionState.CHANNEL_OPEN] (idle) and
     *  [AmtIderSessionState.MEDIA_ACTIVE] (a [mountAndServe] loop running),
     *  since both share the same underlying socket. */
    val established: Boolean get() = state == AmtIderSessionState.CHANNEL_OPEN || state == AmtIderSessionState.MEDIA_ACTIVE

    // ── public: session lifecycle ──────────────────────────────────────

    /** Opens the redirection socket and completes the `StartRedirectionSession`
     *  ("IDER") / `AuthenticateSession` (HTTP Digest) handshake — the real
     *  session-establishment protocol for a direct AMT connection (see this
     *  class's top doc comment). Throws [AmtException] on any failure. */
    fun open() {
        state = AmtIderSessionState.CONNECTING
        transport = externalTransport ?: DirectSocketTransport(openTransport())
        input = DataInputStream(transport.inputStream)
        output = DataOutputStream(transport.outputStream)
        transport.soTimeout = connectTimeoutMs

        sendRaw(byteArrayOf(0x10, 0x00, 0x00, 0x00) + SERVICE_TAG_IDER)
        readStartRedirectionSessionReply()
        sendRaw(byteArrayOf(0x13, 0, 0, 0, 0, 0, 0, 0, 0)) // AuthenticateSession: query supported methods
        authenticate()

        state = AmtIderSessionState.CHANNEL_OPEN
    }

    /** The two virtual devices [pumpCommands]/[handleEnvelopeMessage] can be
     *  serving — exactly one at a time, matching [mountAndServe] mounting
     *  exactly one image per call, same as real IDE-R usage (see
     *  [AmtIderMediaType]). Keeping the CD-ROM/floppy emulator behind this
     *  sealed type (rather than two nullable fields) makes it impossible to
     *  accidentally dispatch a CD-ROM CDB into the floppy emulator or vice
     *  versa. */
    private sealed class IderMedia : AutoCloseable {
        class Cdrom(val emulator: AmtIderDiskEmulator) : IderMedia() {
            override fun close() = emulator.close()
        }
        class Floppy(val emulator: AmtIderFloppyEmulator) : IderMedia() {
            override fun close() = emulator.close()
        }
    }

    /**
     * Mounts [imageFile] and answers AMT's ATA/ATAPI command stream for
     * [mediaType] until [requestStop] is called, AMT sends `CLOSE`, or the
     * connection drops. Runs the IDE-R envelope directly on the already-
     * authenticated redirection socket (no further transport wrapper — see
     * this class's top doc comment), decoding each incoming message into
     * either a SCSI CDB ([AmtIderMediaType.CD_ROM], via
     * [AmtIderDiskEmulator.process]) or an ATA task file
     * ([AmtIderMediaType.FLOPPY], via [AmtIderFloppyEmulator.process] — see
     * that class's doc comment for where those two command shapes come from
     * in the same envelope message), and re-encoding the result back into
     * AMT's envelope. Blocking — call from a background thread/coroutine.
     *
     * [writable] is passed through to [AmtIderFloppyEmulator] and only ever
     * matters for [AmtIderMediaType.FLOPPY] — [AmtIderMediaType.CD_ROM]
     * always mounts [AmtIderDiskEmulator] read-only regardless of this
     * argument, matching real optical media (see that class's doc comment
     * for the architecture reasoning). Defaults to `false` so existing
     * callers keep mounting read-only exactly as before this parameter
     * existed.
     */
    fun mountAndServe(imageFile: java.io.File, mediaType: AmtIderMediaType, writable: Boolean = false) {
        check(established) { "AMT IDE-R session is not open — call open() first" }
        stopRequested = false
        state = AmtIderSessionState.MEDIA_ACTIVE
        val media: IderMedia = when (mediaType) {
            AmtIderMediaType.CD_ROM -> IderMedia.Cdrom(AmtIderDiskEmulator(imageFile))
            AmtIderMediaType.FLOPPY -> IderMedia.Floppy(AmtIderFloppyEmulator(imageFile, writable = writable))
        }
        media.use {
            try {
                openIderEnvelopeSession()
                pumpCommands(media)
            } finally {
                if (state != AmtIderSessionState.CLOSED) state = AmtIderSessionState.CHANNEL_OPEN
            }
        }
    }

    /** Asks a running [mountAndServe] loop to stop after its current poll
     *  iteration. */
    fun requestStop() { stopRequested = true }

    override fun close() {
        state = AmtIderSessionState.CLOSED
        runCatching { transport.close() }
    }

    // ── StartRedirectionSession / AuthenticateSession handshake ─────────
    // Transcribed from meshcmd's amt-redir-duk.js (`xxOnSocketData`'s
    // 0x11/0x14 cases) — see this class's top doc comment for provenance.
    // Unlike the IDE-R envelope below, these header fields are read
    // byte-at-a-time / are ASCII, not little-endian multi-byte integers,
    // matching the reference exactly (no endianness claim needed here).

    private fun readStartRedirectionSessionReply() {
        val head = readFully(13)
        val cmd = head[0].toInt() and 0xFF
        if (cmd != CMD_START_REDIRECTION_SESSION_REPLY) {
            throw AmtException("Expected StartRedirectionSessionReply (0x11), got 0x${cmd.toString(16)}")
        }
        val status = head[1].toInt() and 0xFF
        if (status != 0) {
            throw AmtException("AMT refused the IDE-R redirection session (status=$status) — IDE-R may be disabled in MEBx/AMT_RedirectionService, the session may already be in use by another console, or the user lacks the Redirection realm")
        }
        val oemLen = head[12].toInt() and 0xFF
        if (oemLen > 0) readFully(oemLen) // OEM string — present but unused
    }

    /** Drives the `AuthenticateSession` state machine to completion: an
     *  initial "which methods do you support" reply, a digest challenge,
     *  and this client's computed digest response — exactly the 3-message
     *  HTTP-Digest-over-binary-framing exchange in the reference. */
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
                    // Initial reply: authData lists supported auth-type bytes.
                    val supported = authData.map { it.toInt() and 0xFF }
                    when {
                        4 in supported -> sendAuthTypeRequest(4) // Digest with QOP/cnonce
                        3 in supported -> sendAuthTypeRequest(3) // Digest without QOP
                        else -> throw AmtException("AMT IDE-R: AMT offered no supported authentication method (only Digest, types 3/4, is implemented)")
                    }
                }
                (authType == 3 || authType == 4) && status == 1 -> sendDigestResponse(authType, authData)
                else -> throw AmtException("AMT IDE-R authentication failed (status=$status) — check the profile's username/password and that this user has the Redirection realm")
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
     *  only ever performs one authenticated exchange, so a fixed nc is
     *  what the reference implementation (working against real firmware)
     *  actually sends. */
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

    // ── IDE-R envelope layer ─────────────────────────────────────────────
    // Sent/received directly on the same socket, with no further transport
    // wrapper (see this class's top doc comment). Transcribed from
    // MeshCentral's amt/amt-ider-module.js. Unlike the handshake above,
    // every multi-byte field here is little-endian.

    @Volatile private var stopRequested = false
    private var iderAcc = ByteArray(0)
    private var envInSeq = 0
    private var envOutSeq = 0
    private var readBufferSize = 512
    private var enabled = false

    /** IDE-R write support: state held between `CMD_COMMAND_WRITTEN` (which
     *  carries the write command's registers/addressing but, unlike every
     *  other command this class handles, none of the actual payload) and
     *  the one-or-more `CMD_DATA_FROM_HOST` messages that follow it with
     *  the bytes to write — two separate envelope messages for one logical
     *  write, per this class's `CMD_DATA_FROM_HOST` doc comment below. Only
     *  ever populated for [IderMedia.Floppy]: a CD-ROM write is rejected
     *  immediately inside [dispatchScsi] (real optical media has no
     *  data-out phase to reject after — see [AmtIderDiskEmulator]'s
     *  read-only architecture decision), so [IderMedia.Cdrom] never reaches
     *  this state. */
    private class PendingWrite(
        val device: Int,
        val taskFile: AmtIderFloppyEmulator.AtaTaskFile,
        val emulator: AmtIderFloppyEmulator,
        val expectedBytes: Int,
    ) {
        var collected: ByteArray = ByteArray(0)
    }
    private var pendingWrite: PendingWrite? = null

    /** IDE-R session open + enable — the two envelope messages that must
     *  happen before AMT starts sending SCSI commands. Mirrors
     *  `CreateAmtRemoteIder.Start()` + the `OPEN_SESSION`/`STATUS_DATA`
     *  handling in `ProcessDataEx`. */
    private fun openIderEnvelopeSession() {
        val body = leShort(RX_TIMEOUT_MS) + leShort(TX_TIMEOUT_MS) + leShort(HEARTBEAT_MS) + leInt(IDER_VERSION)
        sendEnvelope(CMD_OPEN_SESSION_REQUEST, body)

        while (true) {
            val chunk = readSome(pollTimeoutMs = 8000)
                ?: throw AmtException("AMT IDE-R: no OPEN_SESSION reply within timeout")
            iderAcc += chunk
            val (consumed, sawOpenReply) = drainOne(CMD_OPEN_SESSION_REPLY)
            if (consumed == 0) continue
            iderAcc = iderAcc.copyOfRange(consumed, iderAcc.size)
            if (sawOpenReply) break
        }

        sendEnableFeatures(IDER_START_NOW_VALUE)
    }

    private fun sendEnableFeatures(toggleValue: Int) {
        sendEnvelope(CMD_ENABLE_FEATURES, byteArrayOf(REGS_TOGGLE.toByte()) + leInt(toggleValue))
    }

    /** Main ATA/SCSI command loop. Returns normally on `CLOSE` or [requestStop]. */
    private fun pumpCommands(media: IderMedia) {
        while (!stopRequested) {
            val chunk = readSome(pollTimeoutMs = 4000) ?: continue
            iderAcc += chunk
            while (true) {
                val cmdId = if (iderAcc.isNotEmpty()) iderAcc[0].toInt() and 0xFF else -1
                val consumed = handleEnvelopeMessage(cmdId, media)
                if (consumed <= 0) break
                iderAcc = iderAcc.copyOfRange(consumed, iderAcc.size)
                if (cmdId == CMD_CLOSE) return
            }
        }
    }

    /** Parses and handles exactly one envelope message at the front of
     *  [iderAcc] if enough bytes are present; returns bytes consumed, or 0
     *  if more data is needed. Mirrors `ProcessDataEx`'s per-command
     *  switch, including its sequence-number check. */
    private fun handleEnvelopeMessage(cmdId: Int, media: IderMedia): Int {
        val acc = iderAcc
        if (acc.size < 8) return 0
        fun checkSeq(len: Int): Int {
            val seq = readLeInt(acc, 4)
            if (seq != envInSeq) throw AmtException("AMT IDE-R: out-of-sequence envelope message (expected $envInSeq, got $seq)")
            envInSeq++
            return len
        }
        return when (cmdId) {
            CMD_OPEN_SESSION_REPLY -> {
                if (acc.size < 30) return 0
                val len = acc[29].toInt() and 0xFF
                if (acc.size < 30 + len) return 0
                readBufferSize = readLeShort(acc, 16)
                enabled = false
                checkSeq(30 + len)
            }
            CMD_CLOSE -> { state = AmtIderSessionState.CLOSED; checkSeq(8) }
            CMD_KEEPALIVE_PING -> { sendEnvelope(CMD_KEEPALIVE_PONG); checkSeq(8) }
            CMD_KEEPALIVE_PONG -> checkSeq(8)
            CMD_RESET_OCCURRED -> {
                if (acc.size < 9) return 0
                sendEnvelope(CMD_RESET_OCCURRED_RESPONSE) // no queued async reads in this implementation, so respond immediately
                checkSeq(9)
            }
            CMD_STATUS_DATA -> {
                if (acc.size < 13) return 0
                val type = acc[8].toInt() and 0xFF
                val value = readLeInt(acc, 9)
                when (type) {
                    REGS_AVAIL -> if ((value and 1) != 0) sendEnableFeatures(IDER_START_NOW_VALUE)
                    REGS_STATUS -> enabled = (value and 2) != 0
                }
                checkSeq(13)
            }
            CMD_ERROR_OCCURRED -> { if (acc.size < 11) return 0; checkSeq(11) } // reference logs and continues rather than aborting
            CMD_HEARTBEAT -> checkSeq(8)
            CMD_COMMAND_WRITTEN -> {
                if (acc.size < 28) return 0
                // Offsets 9-15 are the 7-register IDE task file (Features,
                // Sector Count, LBA Low/Mid/High, Device/Head, Command) in
                // standard wire order — see AmtIderFloppyEmulator's top doc
                // comment for why, including which part of this is
                // transcribed-from-source vs. inferred from public T13 docs.
                val device = if ((acc[14].toInt() and 0x10) != 0) DEV_CDROM else DEV_FLOPPY
                val featureRegister = acc[9].toInt() and 0xFF
                when (media) {
                    is IderMedia.Cdrom -> {
                        // ATAPI PACKET command: the 12-byte SCSI CDB payload
                        // lives in its own field, offsets 16-27.
                        val cdb = acc.copyOfRange(16, 28)
                        dispatchScsi(device, cdb, featureRegister, media.emulator)
                    }
                    is IderMedia.Floppy -> {
                        val taskFile = AmtIderFloppyEmulator.AtaTaskFile(
                            features = featureRegister,
                            sectorCount = acc[10].toInt() and 0xFF,
                            lbaLow = acc[11].toInt() and 0xFF,
                            lbaMid = acc[12].toInt() and 0xFF,
                            lbaHigh = acc[13].toInt() and 0xFF,
                            deviceHead = acc[14].toInt() and 0xFF,
                            command = acc[15].toInt() and 0xFF,
                        )
                        if (media.emulator.isWriteCommand(taskFile.command)) {
                            // IDE-R write support: this message carries only
                            // the write command's registers, not its
                            // payload — the payload arrives in one or more
                            // CMD_DATA_FROM_HOST messages next (see this
                            // class's CMD_DATA_FROM_HOST doc comment). Stash
                            // the pending write and don't respond yet;
                            // dispatchAta/sendCommandComplete/sendCommandError
                            // only happen once the full payload is in.
                            pendingWrite = PendingWrite(
                                device = device,
                                taskFile = taskFile,
                                emulator = media.emulator,
                                expectedBytes = media.emulator.writeByteCount(taskFile),
                            )
                        } else {
                            dispatchAta(device, taskFile, media.emulator)
                        }
                    }
                }
                checkSeq(28)
            }
            CMD_DATA_FROM_HOST -> {
                // IDE-R write support: this is the write payload that
                // CMD_COMMAND_WRITTEN's floppy WRITE SECTORS/WRITE SECTORS
                // NO RETRY case above deferred — the two are separate
                // envelope messages (confirmed against MeshCentral's
                // amt/amt-ider-module.js SendCommandEndResponse/
                // data-from-host handling, per this class's top doc
                // comment). AMT may split one write's payload across more
                // than one CMD_DATA_FROM_HOST message, so this accumulates
                // into [pendingWrite] and only dispatches — via
                // AmtIderFloppyEmulator.writeSectors, mirroring
                // dispatchAta's real-result sendCommandComplete/
                // sendCommandError, not a hardcoded reply — once the full
                // sectorCount * sectorSize payload has arrived.
                if (acc.size < 14) return 0
                val len = readLeShort(acc, 9)
                if (acc.size < 14 + len) return 0
                val payload = acc.copyOfRange(14, 14 + len)
                val pending = pendingWrite
                if (pending != null) {
                    pending.collected += payload
                    if (pending.collected.size >= pending.expectedBytes) {
                        pendingWrite = null
                        val result = pending.emulator.writeSectors(pending.taskFile, pending.collected)
                        if (!result.statusGood) {
                            sendCommandError(pending.device, result.errorRegister, 0)
                        } else {
                            sendCommandComplete(pending.device)
                        }
                    }
                    // else: still waiting on further CMD_DATA_FROM_HOST
                    // messages for this same write — no response yet.
                } else {
                    // No write in progress: either the CD-ROM side (always
                    // read-only, see AmtIderDiskEmulator's architecture
                    // decision — a CD-ROM write is already rejected inside
                    // dispatchScsi with no data-out phase expected) or an
                    // out-of-band/unexpected data-from-host. Reference
                    // hardcodes DEV_FLOPPY here too; mirrored as-is for this
                    // fallback path.
                    sendEnvelope(
                        CMD_COMMAND_END_RESPONSE,
                        byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0, 0x87.toByte(), 0x70, 3,0,0,0, DEV_FLOPPY.toByte(), 0x51, 0x07, 0x27, 0x00),
                        completed = true,
                    )
                }
                checkSeq(14 + len)
            }
            else -> throw AmtException("AMT IDE-R: unknown envelope command 0x${cmdId.toString(16)}")
        }
    }

    private fun dispatchScsi(device: Int, cdb: ByteArray, featureRegister: Int, emulator: AmtIderDiskEmulator) {
        val result = emulator.process(cdb)
        val dma = (featureRegister and 1) != 0
        when {
            // SENSE FIX (item 12): a failed command used to fall through to
            // sendCommandComplete() exactly like success, silently discarding
            // the sense info AmtIderDiskEmulator already computed — see that
            // function's doc comment for why this is now reported for real.
            !result.statusGood -> sendCommandError(device, result.senseKey, result.senseAsc)
            result.data.isNotEmpty() -> sendDataToHost(device, result.data, dma)
            else -> sendCommandComplete(device)
        }
    }

    private fun dispatchAta(device: Int, taskFile: AmtIderFloppyEmulator.AtaTaskFile, emulator: AmtIderFloppyEmulator) {
        val result = emulator.process(taskFile)
        // FIX (item 8): this used to hardcode dma = false with a comment
        // claiming plain ATA PIO commands never use DMA — true back when
        // this emulator only implemented the PIO read opcodes. Now that
        // READ DMA/READ DMA NO RETRY are implemented (see
        // AmtIderFloppyEmulator.process's doc comment), the DMA bit AMT
        // expects in the envelope must match the opcode the guest actually
        // issued, unlike dispatchScsi's case where DMA-ness comes from a
        // separate feature-register bit.
        val dma = emulator.isDmaCommand(taskFile.command)
        when {
            // SENSE FIX (item 12): same fix as dispatchScsi above, reusing the
            // same error frame — the ATA side's errorRegister (IDNF/ABRT) goes
            // in the same single-byte slot the SCSI side's sense key uses; see
            // sendCommandError's doc comment for why one frame shape serves both.
            !result.statusGood -> sendCommandError(device, result.errorRegister, 0)
            result.data.isNotEmpty() -> sendDataToHost(device, result.data, dma)
            else -> sendCommandComplete(device)
        }
    }

    /** The "command complete, success" 25-byte envelope frame. Mirrors
     *  `SendCommandEndResponse(error=true, ...)` in the reference — despite
     *  that call taking sense/asc/asq parameters at every call site, this
     *  particular frame ignores them and is fixed; it's the frame used for
     *  essentially every *successful* command-end response in the
     *  reference (see this class's top doc comment). Only for success —
     *  see [sendCommandError] for the failure case. */
    private fun sendCommandComplete(device: Int) {
        sendEnvelope(
            CMD_COMMAND_END_RESPONSE,
            byteArrayOf(0,0,0,0,0,0,0,0,0,0,0,0, 0xc5.toByte(), 0, 3, 0, 0, 0, device.toByte(), 0x50, 0, 0, 0, 0, 0),
            completed = true,
        )
    }

    /** SENSE FIX (item 12) — the command-complete-with-error counterpart to
     *  [sendCommandComplete], now actually sent for failed commands instead
     *  of the class silently reusing the success frame for everything (this
     *  class's top doc comment covers the history of why that was the case
     *  for a while).
     *
     *  This class's top doc comment already noted the reference has *two*
     *  possible error frame shapes, and explicitly declined to reproduce the
     *  one its own author flagged as unverified ("TODO: Send proper
     *  error!!!"). That caution still stands — this function does not use
     *  that frame. Instead it generalizes the *other* shape: the 23-byte
     *  status-with-ERR-bit frame this class already sends for real, at the
     *  CMD_DATA_FROM_HOST "writes not supported" call site below (status
     *  byte 0x51 = 0x50 | ERR, vs. this frame's plain 0x50). That's a real,
     *  already-in-use frame from the reference — not a guess — so
     *  parameterizing the sense/error byte it already carries (instead of
     *  hardcoding "writes not supported"'s specific 0x07) resolves the
     *  ambiguity without enshrining the untested alternative. [device] is
     *  passed through properly here, unlike that call site's reference-
     *  mirrored DEV_FLOPPY hardcoding — see the comment there.
     *
     *  IDE-R write support: this is also now the function a *real* rejected
     *  floppy write (not writable, out-of-range LBA, ...) reports through —
     *  see the `pendingWrite` handling in this class's CMD_DATA_FROM_HOST
     *  case, which calls this with [AmtIderFloppyEmulator.AtaResult]'s
     *  actual `errorRegister` once a write's full payload has arrived,
     *  exactly like [dispatchAta] already does for read-side failures. */
    private fun sendCommandError(device: Int, senseOrErrorByte: Int, asc: Int) {
        sendEnvelope(
            CMD_COMMAND_END_RESPONSE,
            byteArrayOf(
                0,0,0,0,0,0,0,0,0,0,0,0,
                0x87.toByte(), 0x70, 3, 0, 0, 0,
                device.toByte(), 0x51,
                senseOrErrorByte.toByte(), asc.toByte(), 0x00,
            ),
            completed = true,
        )
    }

    /** DATA_TO_HOST — sends [data] to AMT for a completed SCSI data-in
     *  phase, chunked to [readBufferSize] (AMT's advertised read-buffer
     *  size from `OPEN_SESSION_REPLY`) exactly as `sendDiskDataEx` does,
     *  marking only the final chunk `completed`. */
    private fun sendDataToHost(device: Int, data: ByteArray, dma: Boolean) {
        val chunk = readBufferSize.coerceAtLeast(1)
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val completed = offset + len >= data.size
            sendDataToHostChunk(device, data, offset, len, completed, dma)
            offset += len
        }
    }

    private fun sendDataToHostChunk(device: Int, data: ByteArray, offset: Int, len: Int, completed: Boolean, dma: Boolean) {
        val payload = data.copyOfRange(offset, offset + len)
        val dmaLen = if (dma) 0 else len
        val header = if (completed) {
            byteArrayOf(
                0, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), 0,
                (if (dma) 0xb4 else 0xb5).toByte(), 0, 2, 0,
                (dmaLen and 0xFF).toByte(), ((dmaLen shr 8) and 0xFF).toByte(),
                device.toByte(), 0x58, 0x85.toByte(), 0, 3, 0, 0, 0, device.toByte(), 0x50, 0, 0, 0, 0, 0,
            )
        } else {
            byteArrayOf(
                0, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), 0,
                (if (dma) 0xb4 else 0xb5).toByte(), 0, 2, 0,
                (dmaLen and 0xFF).toByte(), ((dmaLen shr 8) and 0xFF).toByte(),
                device.toByte(), 0x58, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            )
        }
        sendEnvelope(CMD_DATA_TO_HOST, header + payload, completed = completed, dma = dma)
    }

    /** Builds and sends one IDE-R envelope message: 1-byte command id,
     *  2 reserved bytes, 1 attributes byte, 4-byte LE sequence, body.
     *  Mirrors `SendCommand`. */
    private fun sendEnvelope(cmdId: Int, body: ByteArray = ByteArray(0), completed: Boolean = false, dma: Boolean = false) {
        var attributes = 0
        if (cmdId > 50 && completed) attributes = attributes or 2
        if (dma) attributes = attributes or 1
        val header = byteArrayOf(cmdId.toByte(), 0, 0, attributes.toByte()) + leInt(envOutSeq)
        envOutSeq++
        sendRaw(header + body)
    }

    /** Consumes exactly one message of type [expectedCmd] from the *front*
     *  of [iderAcc] if present (used only during [openIderEnvelopeSession],
     *  before the general dispatch loop is running). */
    private fun drainOne(expectedCmd: Int): Pair<Int, Boolean> {
        if (iderAcc.size < 8) return 0 to false
        val cmdId = iderAcc[0].toInt() and 0xFF
        if (cmdId != expectedCmd) return 0 to false
        val len = if (iderAcc.size >= 30) (iderAcc[29].toInt() and 0xFF) else return 0 to false
        val total = 30 + len
        if (iderAcc.size < total) return 0 to false
        readBufferSize = readLeShort(iderAcc, 16)
        envInSeq++
        return total to true
    }

    // ── raw socket I/O (no transport-layer wrapper — see top doc comment) ──

    private fun sendRaw(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    /** Single non-blocking-ish read with a timeout, for the envelope pump
     *  loop (which needs to poll so [requestStop] can take effect). Returns
     *  whatever bytes are currently available, or null on timeout/close. */
    private fun readSome(pollTimeoutMs: Int): ByteArray? {
        transport.soTimeout = pollTimeoutMs
        val buf = ByteArray(4096)
        return try {
            val n = input.read(buf)
            if (n < 0) { state = AmtIderSessionState.CLOSED; null } else buf.copyOf(n)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    /** Blocking exact-length read, used only during the handshake (where
     *  message sizes are known upfront and there's no need to poll). */
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
                        "supplied to AmtIderSession — TOFU certificate pinning requires a Context " +
                        "to store the pinned fingerprint. Refusing to connect with a trust-all " +
                        "fallback. Pass appContext to AmtIderSession's constructor.",
                )
        } else null
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManager?.let { arrayOf<javax.net.ssl.TrustManager>(it) }, SecureRandom())
        val sslSocket = sslContext.socketFactory.createSocket(raw, host, redirectionPort, true) as SSLSocket
        sslSocket.soTimeout = connectTimeoutMs
        sslSocket.startHandshake()
        return sslSocket
    }

    // ── little-endian wire primitives (IDE-R envelope only — the
    // StartRedirectionSession/AuthenticateSession handshake above is parsed
    // byte-at-a-time / ASCII per the reference, not little-endian ints) ──
    private fun leShort(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun leInt(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun readLeShort(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun readLeInt(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        // StartRedirectionSession / AuthenticateSession command IDs —
        // transcribed from meshcmd's amt-redir-duk.js.
        private const val CMD_START_REDIRECTION_SESSION_REPLY = 0x11
        private const val CMD_AUTHENTICATE_SESSION_REPLY = 0x14
        private val SERVICE_TAG_IDER = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(), 'R'.code.toByte())
        private const val AUTH_URI = "/RedirectionService"

        // IDE-R envelope command IDs — transcribed from MeshCentral's
        // amt/amt-ider-module.js (`ProcessDataEx`/`SendCommand` call sites).
        // Little-endian fields throughout, unlike the handshake constants above.
        private const val CMD_OPEN_SESSION_REQUEST = 0x40
        private const val CMD_OPEN_SESSION_REPLY = 0x41
        private const val CMD_CLOSE = 0x43
        private const val CMD_KEEPALIVE_PING = 0x44
        private const val CMD_KEEPALIVE_PONG = 0x45
        private const val CMD_RESET_OCCURRED = 0x46
        private const val CMD_RESET_OCCURRED_RESPONSE = 0x47
        private const val CMD_ENABLE_FEATURES = 0x48
        private const val CMD_STATUS_DATA = 0x49
        private const val CMD_ERROR_OCCURRED = 0x4A
        private const val CMD_HEARTBEAT = 0x4B
        private const val CMD_COMMAND_WRITTEN = 0x50
        private const val CMD_COMMAND_END_RESPONSE = 0x51
        private const val CMD_DATA_TO_HOST = 0x54
        private const val CMD_DATA_FROM_HOST = 0x53

        // ENABLE_FEATURES ("DisableEnableFeatures") sub-types and the
        // REGS_TOGGLE value this app always requests: bit0 (enable) +
        // bit3/4/5 select OnReboot/Graceful/Now — this app always mounts
        // on-demand, so always "Now" (0x01 | 0x18).
        private const val REGS_AVAIL = 1
        private const val REGS_STATUS = 2
        private const val REGS_TOGGLE = 3
        private const val IDER_START_NOW_VALUE = 0x01 or 0x18

        private const val DEV_FLOPPY = 0xA0
        private const val DEV_CDROM = 0xB0

        // OPEN_SESSION request parameters — MeshCentral's own defaults.
        private const val RX_TIMEOUT_MS = 30000
        private const val TX_TIMEOUT_MS = 0
        private const val HEARTBEAT_MS = 20000
        private const val IDER_VERSION = 1
    }
}
