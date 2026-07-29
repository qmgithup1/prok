package com.systemsgo.hex.ipmi.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** One cipher suite's algorithm IDs, per IPMI 2.0 table 22-19. */
private data class CipherSuiteSpec(
    val id: Int,
    val authAlgo: Int,
    val integrityAlgo: Int,
    val useSha256: Boolean,
)

/**
 * Low-level IPMI v2.0 / RMCP+ session over UDP (port 623 by default).
 *
 * [open] negotiates Cipher Suite 17 (RAKP-HMAC-SHA256 / HMAC-SHA256-128 /
 * AES-CBC-128) first, falling back to Cipher Suite 3 (RAKP-HMAC-SHA1 /
 * HMAC-SHA1-96 / AES-CBC-128) if the BMC rejects it — 17 is the modern
 * default on firmware that has SHA-1 disabled (iDRAC9 FIPS mode, iLO5+,
 * some AST2600 boards), 3 is still what the broadest range of older/generic
 * BMCs (and `ipmitool -I lanplus` by default) actually support, so trying
 * both covers both worlds without needing a per-profile setting. Whichever
 * suite wins stays fixed for the rest of the session.
 *
 * One IpmiSession = one authenticated connection to one BMC. Not thread-safe
 * — callers should serialize requests (IpmiClient does this).
 *
 * Supports both "one-key" (the default on essentially every BMC — no
 * separate BMC key configured) and "two-key"/Kg logins via the optional
 * [kgKey] constructor parameter — see its doc comment and
 * [IpmiCrypto.deriveSik] for what Kg actually changes.
 */
internal class IpmiSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val requestedPrivilege: Int = 0x04, // ADMINISTRATOR
    private val timeoutMs: Int = 4000,
    /**
     * The BMC's "Kg"/"BMC key" for a two-key login, or null/blank for the
     * far more common one-key login (no BMC key configured). Accepts the
     * same two input forms `ipmitool -y`/most vendor BMC UIs do: a `0x`-
     * prefixed even-length hex string (the raw key bytes), or — if it
     * doesn't start with `0x` — a plain ASCII passphrase, used as-is. See
     * [IpmiCrypto.deriveSik]'s doc comment for what this actually changes.
     */
    private val kgKey: String? = null,
) : AutoCloseable {

    private val socket = DatagramSocket().apply { soTimeout = timeoutMs }
    private val address = InetAddress.getByName(host)
    private val random = SecureRandom()

    /** [kgKey] decoded to raw bytes once, per the two accepted input forms — see the constructor param's doc comment. */
    private val bmcKeyBytes: ByteArray? = kgKey?.trim()?.takeIf { it.isNotEmpty() }?.let { k ->
        if (k.startsWith("0x", ignoreCase = true)) {
            val hex = k.substring(2)
            require(hex.length % 2 == 0) { "Kg key hex string must have an even number of digits" }
            ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }
        } else {
            k.toByteArray(Charsets.UTF_8)
        }
    }

    private var managedSystemSessionId: Int = 0
    private var remoteConsoleSessionId: Int = 0
    private var sik: ByteArray = ByteArray(0)
    private var k1: ByteArray = ByteArray(0)
    private var k2: ByteArray = ByteArray(0)
    private var outSeq: Int = 1
    private var inSeq: Int = 0
    private var useSha256: Boolean = false
    var established: Boolean = false
        private set

    // ── public: session lifecycle ──────────────────────────────────────

    fun open() {
        val candidates = listOf(
            CipherSuiteSpec(id = 17, authAlgo = 0x03, integrityAlgo = 0x03, useSha256 = true),
            CipherSuiteSpec(id = 3, authAlgo = 0x01, integrityAlgo = 0x01, useSha256 = false),
        )
        var lastError: IpmiException? = null
        for (spec in candidates) {
            try {
                establish(spec)
                established = true
                outSeq = 1
                return
            } catch (e: IpmiException) {
                lastError = e
                established = false
            }
        }
        throw lastError ?: IpmiException("Unable to establish an IPMI session (no cipher suite negotiated)")
    }

    override fun close() {
        try {
            if (established) sendIpmiCloseSession()
        } catch (_: Exception) {
            // best-effort; BMC will time the session out anyway
        } finally {
            socket.close()
        }
    }

    /**
     * Sends an IPMI request (netFn/cmd/data) inside an authenticated+encrypted
     * RMCP+ payload and returns the raw response data (completion code
     * stripped, but throws IpmiException if it was non-zero).
     */
    fun sendIpmiRequest(netFn: Int, cmd: Int, data: ByteArray = ByteArray(0), rsAddr: Int = 0x20, rqAddr: Int = 0x81, rqLun: Int = 0): ByteArray {
        check(established) { "IPMI session not established" }
        val req = buildIpmiLanMessage(netFn, cmd, data, rsAddr, rqAddr, rqLun)
        val packet = wrapPayload(payloadType = 0x00, payload = req, authenticated = true, encrypted = true)
        sendRaw(packet)
        val respPayload = receivePayloadLoop(expectedPayloadType = 0x00)
        return parseIpmiLanResponse(respPayload)
    }

    /** Raw send for SOL (Serial-over-LAN) payload type 0x01 packets; used by IpmiSolChannel. */
    fun sendSolPayload(payload: ByteArray) {
        check(established) { "IPMI session not established" }
        val packet = wrapPayload(payloadType = 0x01, payload = payload, authenticated = true, encrypted = true)
        sendRaw(packet)
    }

    fun receiveSolPayload(): ByteArray? = receivePayloadLoopOrNull(expectedPayloadType = 0x01)

    // ── RMCP+ session establishment ────────────────────────────────────

    /** Resets per-attempt state and runs Open Session + RAKP1-4 for one candidate cipher suite. */
    private fun establish(spec: CipherSuiteSpec) {
        useSha256 = spec.useSha256
        sik = ByteArray(0); k1 = ByteArray(0); k2 = ByteArray(0)
        remoteConsoleSessionId = random.nextInt().let { if (it == 0) 1 else it }
        managedSystemSessionId = openSessionRequest(spec)
        val (rand2, guid) = rakp1And2()
        rakp3And4(rand2, guid)
    }

    private fun openSessionRequest(spec: CipherSuiteSpec): Int {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x00) // message tag
        buf.put(requestedPrivilege.toByte())
        buf.put(0x00); buf.put(0x00) // reserved
        buf.putInt(remoteConsoleSessionId)
        // Authentication payload
        buf.put(byteArrayOf(0x00, 0x00, 0x00, 0x08, spec.authAlgo.toByte(), 0x00, 0x00, 0x00))
        // Integrity payload
        buf.put(byteArrayOf(0x01, 0x00, 0x00, 0x08, spec.integrityAlgo.toByte(), 0x00, 0x00, 0x00))
        // Confidentiality payload: AES-CBC-128 (0x01) — same for both suites
        buf.put(byteArrayOf(0x02, 0x00, 0x00, 0x08, 0x01, 0x00, 0x00, 0x00))
        val body = buf.array().copyOf(buf.position())

        val packet = wrapPayload(payloadType = 0x10, payload = body, authenticated = false, encrypted = false)
        sendRaw(packet)
        val resp = receivePayloadLoop(expectedPayloadType = 0x11)

        val rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
        rb.get() // message tag
        val status = rb.get().toInt() and 0xFF
        if (status != 0) throw IpmiException("RMCP+ Open Session failed (cipher suite ${spec.id}), status=0x${status.toString(16)} (bad privilege level, or this cipher suite unsupported by this BMC)")
        rb.get(); rb.get() // max priv, reserved
        rb.int // remote console session id (echoed) - ignore, we trust our own value
        return rb.int // managed system session id
    }

    private fun rakp1And2(): Pair<ByteArray, ByteArray> {
        val rcRandom = IpmiCrypto.randomBytes(16)
        val unameBytes = username.toByteArray(Charsets.US_ASCII)

        val buf = ByteBuffer.allocate(32 + unameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x00) // message tag
        buf.put(byteArrayOf(0, 0, 0)) // reserved
        buf.putInt(managedSystemSessionId)
        buf.put(rcRandom)
        buf.put(requestedPrivilege.toByte())
        buf.put(0x00); buf.put(0x00) // reserved
        buf.put(unameBytes.size.toByte())
        buf.put(unameBytes)
        val body = buf.array().copyOf(buf.position())

        sendRaw(wrapPayload(payloadType = 0x12, payload = body, authenticated = false, encrypted = false))
        val resp = receivePayloadLoop(expectedPayloadType = 0x13)

        val rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
        rb.get() // tag
        val rakpCode = rb.get().toInt() and 0xFF
        if (rakpCode != 0) throw IpmiException("RAKP2 error, return code=0x${rakpCode.toString(16)} (wrong username, cipher suite mismatch, or BMC user disabled/locked)")
        rb.get(); rb.get() // reserved
        rb.int // remote console session id echoed
        val smRandom = ByteArray(16).also { rb.get(it) }
        val smGuid = ByteArray(16).also { rb.get(it) }
        // Key Exchange Auth Code is the *full* hash digest (20 bytes for
        // SHA-1, 32 bytes for SHA-256) — not truncated like the per-packet
        // integrity codes are.
        val authCodeLen = if (useSha256) 32 else 20
        val authCode = ByteArray(minOf(authCodeLen, resp.size - rb.position())).also { rb.get(it) }

        val pw = password.toByteArray(Charsets.UTF_8)
        val expected = if (useSha256) {
            IpmiCrypto.hmacSha256(
                pw,
                intToLe4(remoteConsoleSessionId), intToLe4(managedSystemSessionId),
                rcRandom, smRandom, smGuid,
                byteArrayOf(requestedPrivilege.toByte()), byteArrayOf(unameBytes.size.toByte()), unameBytes,
            )
        } else {
            IpmiCrypto.hmacSha1(
                pw,
                intToLe4(remoteConsoleSessionId), intToLe4(managedSystemSessionId),
                rcRandom, smRandom, smGuid,
                byteArrayOf(requestedPrivilege.toByte()), byteArrayOf(unameBytes.size.toByte()), unameBytes,
            )
        }
        if (authCode.isEmpty() || !authCode.contentEquals(expected.copyOf(authCode.size))) {
            throw IpmiException("RAKP2 auth code mismatch — wrong username or password")
        }

        sik = IpmiCrypto.deriveSik(pw, rcRandom, smRandom, requestedPrivilege, unameBytes, useSha256, bmcKeyBytes)
        k1 = IpmiCrypto.deriveK1(sik, useSha256)
        k2 = IpmiCrypto.deriveK2(sik, useSha256)

        // stash rcRandom for RAKP3
        this.rcRandomForRakp3 = rcRandom
        this.rqPrivByteForRakp3 = requestedPrivilege
        this.unameForRakp3 = unameBytes
        return smRandom to smGuid
    }

    private var rcRandomForRakp3: ByteArray = ByteArray(0)
    private var rqPrivByteForRakp3: Int = 0
    private var unameForRakp3: ByteArray = ByteArray(0)

    private fun rakp3And4(smRandom: ByteArray, @Suppress("UNUSED_PARAMETER") smGuid: ByteArray) {
        val pw = password.toByteArray(Charsets.UTF_8)
        val authCode = if (useSha256) {
            IpmiCrypto.hmacSha256(
                pw,
                smRandom, intToLe4(remoteConsoleSessionId),
                byteArrayOf(rqPrivByteForRakp3.toByte()), byteArrayOf(unameForRakp3.size.toByte()), unameForRakp3,
            )
        } else {
            IpmiCrypto.hmacSha1(
                pw,
                smRandom, intToLe4(remoteConsoleSessionId),
                byteArrayOf(rqPrivByteForRakp3.toByte()), byteArrayOf(unameForRakp3.size.toByte()), unameForRakp3,
            )
        }
        val buf = ByteBuffer.allocate(8 + authCode.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x00) // tag
        buf.put(0x00) // status = success
        buf.put(byteArrayOf(0, 0)) // reserved
        buf.putInt(managedSystemSessionId)
        buf.put(authCode)
        val body = buf.array().copyOf(buf.position())

        sendRaw(wrapPayload(payloadType = 0x14, payload = body, authenticated = false, encrypted = false))
        val resp = receivePayloadLoop(expectedPayloadType = 0x15)

        val rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
        rb.get() // tag
        val code = rb.get().toInt() and 0xFF
        if (code != 0) throw IpmiException("RAKP4 error, return code=0x${code.toString(16)}")
        // We don't strictly verify RAKP4's Integrity Check Value here (BMC
        // authenticity was already established via RAKP2's AuthCode); the
        // session is otherwise fully keyed and ready to use.
    }

    private fun sendIpmiCloseSession() {
        val data = intToLe4(managedSystemSessionId)
        val req = buildIpmiLanMessage(netFn = 0x06, cmd = 0x3C, data = data) // App / Close Session
        sendRaw(wrapPayload(payloadType = 0x00, payload = req, authenticated = true, encrypted = true))
    }

    // ── IPMI-over-LAN message framing (inside the RMCP+ payload) ───────

    private fun buildIpmiLanMessage(netFn: Int, cmd: Int, data: ByteArray, rsAddr: Int = 0x20, rqAddr: Int = 0x81, rqLun: Int = 0): ByteArray {
        // rsAddr/netFn/rqLun header + checksum1, then rqAddr/rqSeq/rqLun + cmd + data + checksum2
        val rqSeq = (outSeq and 0x3F)
        val head = byteArrayOf(rsAddr.toByte(), ((netFn shl 2) or 0).toByte())
        val csum1 = twosComplementChecksum(head)
        val tail = byteArrayOf(rqAddr.toByte(), ((rqSeq shl 2) or rqLun).toByte(), cmd.toByte()) + data
        val csum2 = twosComplementChecksum(tail)
        return head + csum1 + tail + csum2
    }

    private fun parseIpmiLanResponse(payload: ByteArray): ByteArray {
        if (payload.size < 7) throw IpmiException("IPMI response too short (${payload.size} bytes)")
        // payload[0]=rqAddr,[1]=netFn/rqLun,[2]=csum1,[3]=rsAddr,[4]=rqSeq/rsLun,[5]=cmd,[6]=completionCode,[7..]=data
        val completionCode = payload[6].toInt() and 0xFF
        if (completionCode != 0x00) {
            throw IpmiException("IPMI command failed, completion code=0x${completionCode.toString(16)} (${completionCodeMeaning(completionCode)})")
        }
        return payload.copyOfRange(7, payload.size - 1) // drop trailing checksum
    }

    private fun completionCodeMeaning(cc: Int): String = when (cc) {
        0xC0 -> "node busy"
        0xC1 -> "invalid/unsupported command"
        0xC5 -> "out of space / cannot execute"
        0xCC -> "invalid data field in request"
        0xD5 -> "cannot execute duplicated request"
        else -> "see IPMI spec table 5-2"
    }

    private fun twosComplementChecksum(bytes: ByteArray): ByteArray {
        var sum = 0
        for (b in bytes) sum += b.toInt()
        return byteArrayOf(((-sum) and 0xFF).toByte())
    }

    // ── RMCP+ session-header wrap/unwrap ────────────────────────────────

    /** Per-packet integrity AuthCode length: 12 bytes for HMAC-SHA1-96 (suite 3), 16 for HMAC-SHA256-128 (suite 17). */
    private val integrityAuthCodeLen: Int get() = if (useSha256) 16 else 12

    private fun wrapPayload(payloadType: Int, payload: ByteArray, authenticated: Boolean, encrypted: Boolean): ByteArray {
        val bodyPayload = if (encrypted && k2.isNotEmpty()) IpmiCrypto.aesCbc128Encrypt(k2, payload) else payload

        val sessId = if (payloadType in intArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15)) 0 else managedSystemSessionId
        val seq = if (authenticated) outSeq else 0

        val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        header.put(0x06) // AuthType = RMCP+ format
        var pt = payloadType and 0x3F
        if (authenticated) pt = pt or 0x40
        if (encrypted) pt = pt or 0x80
        header.put(pt.toByte())
        header.putInt(sessId)
        header.putInt(seq)

        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(bodyPayload.size.toShort())

        var packet = header.array() + lenBuf.array() + bodyPayload

        if (authenticated && k1.isNotEmpty()) {
            // Integrity pad: pad bytes (0xFF) + pad length + next header (0x07), then a
            // truncated-HMAC AuthCode over everything from AuthType through the
            // pad/len/nextheader, per spec 13.28.4 — HMAC-SHA1-96 (12 bytes) for
            // suite 3, HMAC-SHA256-128 (16 bytes) for suite 17.
            val padLen = (4 - ((packet.size + 2) % 4)) % 4
            val pad = ByteArray(padLen) { 0xFF.toByte() }
            val trailer = pad + byteArrayOf(padLen.toByte(), 0x07)
            val toMac = packet + trailer
            val authCode = if (useSha256) IpmiCrypto.hmacSha256_128(k1, toMac) else IpmiCrypto.hmacSha1_96(k1, toMac)
            packet = toMac + authCode
        }
        if (authenticated) outSeq++
        return packet
    }

    /** Parses one raw RMCP+ UDP datagram into (payloadType, decryptedPayload), or null if it's not a session packet we understand. */
    private fun unwrap(raw: ByteArray): Pair<Int, ByteArray>? {
        if (raw.size < 10 || raw[0].toInt() != 0x06) return null
        val ptByte = raw[1].toInt() and 0xFF
        val payloadType = ptByte and 0x3F
        val authenticated = (ptByte and 0x40) != 0
        val encrypted = (ptByte and 0x80) != 0
        val bb = ByteBuffer.wrap(raw, 2, 8).order(ByteOrder.LITTLE_ENDIAN)
        @Suppress("UNUSED_VARIABLE") val sessId = bb.int
        @Suppress("UNUSED_VARIABLE") val seq = bb.int
        val lenOffset = 10
        if (raw.size < lenOffset + 2) return null
        val len = ByteBuffer.wrap(raw, lenOffset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        var bodyStart = lenOffset + 2
        var bodyEnd = bodyStart + len
        if (bodyEnd > raw.size) return null

        // If authenticated, the trailer (pad + padLen + nextHeader + AuthCode)
        // sits after the declared payload length; we don't verify it here
        // (BMC authenticity already proven at RAKP2) but must not treat it
        // as payload data. AuthCode length depends on the negotiated suite.
        if (authenticated) {
            bodyEnd = minOf(bodyEnd, raw.size - integrityAuthCodeLen).coerceAtLeast(bodyStart)
        }
        var body = raw.copyOfRange(bodyStart, bodyStart + len.coerceAtMost(raw.size - bodyStart))
        if (encrypted && k2.isNotEmpty() && body.isNotEmpty()) {
            body = try { IpmiCrypto.aesCbc128Decrypt(k2, body) } catch (e: Exception) { return null }
        }
        return payloadType to body
    }

    // ── UDP transport ────────────────────────────────────────────────

    private fun sendRaw(sessionPayload: ByteArray) {
        // RMCP header: version 0x06, reserved 0x00, sequence 0xFF (no ACK), class 0x07 (IPMI)
        val rmcp = byteArrayOf(0x06, 0x00, 0xFF.toByte(), 0x07) + sessionPayload
        socket.send(DatagramPacket(rmcp, rmcp.size, address, port))
    }

    private fun receivePayloadLoop(expectedPayloadType: Int): ByteArray =
        receivePayloadLoopOrNull(expectedPayloadType)
            ?: throw IpmiException("Timed out waiting for IPMI response (payload type 0x${expectedPayloadType.toString(16)}) from $host:$port")

    private fun receivePayloadLoopOrNull(expectedPayloadType: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: java.net.SocketTimeoutException) {
                return null
            }
            if (packet.length < 4 || buf[0].toInt() != 0x06 || buf[3].toInt() != 0x07) continue // not RMCP/IPMI
            val sessionPayload = buf.copyOfRange(4, packet.length)
            val parsed = unwrap(sessionPayload) ?: continue
            if (parsed.first == expectedPayloadType) return parsed.second
            // else: stray/late packet (e.g. leftover SOL data while waiting
            // for a command reply) — ignore and keep waiting.
        }
        return null
    }

    private fun intToLe4(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
