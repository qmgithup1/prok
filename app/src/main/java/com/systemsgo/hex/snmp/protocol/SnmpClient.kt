package com.systemsgo.hex.snmp.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * SNMP manager-side client (v1/v2c/v3) — the SNMP counterpart to
 * [com.systemsgo.hex.ipmi.protocol.IpmiClient]. A native UDP + BER
 * implementation ([Asn1], [SnmpUsm]); no SNMP library is used.
 *
 * Usage:
 * ```
 * val client = SnmpClient("192.168.1.1", credentials = SnmpCredentials.Community(SnmpVersion.V2C, "public"))
 * val sysDescr = client.get(Oid("1.3.6.1.2.1.1.1.0"))
 * val ifTable = client.walk(Oid("1.3.6.1.2.1.2.2"))
 * client.close()
 * ```
 *
 * All I/O methods are suspend functions dispatched on Dispatchers.IO — call
 * from a ViewModel/coroutine scope, never directly from the UI thread.
 *
 * v3 engine discovery (RFC 3414 §4: an authoritative engine's
 * snmpEngineID/Boots/Time, needed before the first authenticated request)
 * happens automatically and lazily on first use if
 * [SnmpCredentials.Usm.contextEngineId] wasn't supplied — it costs one
 * extra round trip (an unauthenticated Report-soliciting GET) the first
 * time only; the discovered engine state is cached on the client instance
 * for its lifetime (boots/time are re-synced from every subsequent
 * response's header, per §3.2 step 7, so a long-lived client stays valid
 * across the agent's own reboots too).
 */
class SnmpClient(
    private val host: String,
    private val port: Int = 161,
    private val credentials: SnmpCredentials,
    private val timeoutMillis: Int = 3000,
    private val retries: Int = 1,
) : AutoCloseable {

    private var socket: DatagramSocket? = null
    private val requestIdSeq = AtomicInteger((0..Int.MAX_VALUE / 2).random())
    private val msgIdSeq = AtomicInteger((0..Int.MAX_VALUE / 2).random())
    private var localCounter = AtomicInteger(0)

    // Discovered/tracked v3 engine state (RFC 3414 §4/§3.2).
    private var engineId: ByteArray? = null
    private var engineBoots: Int = 0
    private var engineTime: Int = 0

    private fun ensureSocket(): DatagramSocket {
        var s = socket
        if (s == null || s.isClosed) {
            s = DatagramSocket()
            s.soTimeout = timeoutMillis
            socket = s
        }
        return s
    }

    override fun close() {
        socket?.close()
        socket = null
    }

    // ── Public operations ────────────────────────────────────────────

    suspend fun get(vararg oids: Oid): SnmpResponse = request(Ber.GET_REQUEST, oids.map { VarBind.request(it) })

    suspend fun getNext(vararg oids: Oid): SnmpResponse = request(Ber.GET_NEXT_REQUEST, oids.map { VarBind.request(it) })

    /**
     * GetBulk (RFC 3416 §4.2.3, SNMPv2c/v3 only — v1 has no bulk request;
     * calling this with v1 credentials throws). [nonRepeaters] OIDs are
     * fetched once each (like a plain GetNext); the remaining OIDs each
     * get up to [maxRepetitions] successive GetNext-style rows — the
     * standard way to page through a table in one round trip.
     */
    suspend fun getBulk(oids: List<Oid>, nonRepeaters: Int = 0, maxRepetitions: Int = 10): SnmpResponse {
        if (credentials.version == SnmpVersion.V1) throw SnmpException("GetBulk requires SNMPv2c or SNMPv3 (agent/version is SNMPv1)")
        return requestBulk(oids.map { VarBind.request(it) }, nonRepeaters, maxRepetitions)
    }

    suspend fun set(vararg varBinds: VarBind): SnmpResponse = request(Ber.SET_REQUEST, varBinds.toList())

    /**
     * Walks the subtree under [rootOid], returning every varbind strictly
     * within it in lexicographic order (the classic `snmpwalk`). Uses
     * GetBulk automatically for v2c/v3 (far fewer round trips than plain
     * GetNext); falls back to GetNext-by-GetNext for v1.
     */
    suspend fun walk(rootOid: Oid, maxRows: Int = 10_000): List<VarBind> = withContext(Dispatchers.IO) {
        val results = ArrayList<VarBind>()
        var current = rootOid
        val useBulk = credentials.version != SnmpVersion.V1
        while (results.size < maxRows) {
            if (useBulk) {
                val resp = requestBulk(listOf(VarBind.request(current)), nonRepeaters = 0, maxRepetitions = 25)
                if (resp.varBinds.isEmpty()) break
                var stopped = false
                for (vb in resp.varBinds) {
                    if (vb.value.isException || !vb.oid.startsWith(rootOid)) { stopped = true; break } // EndOfMibView, or past the requested subtree
                    if (vb.oid <= current) { stopped = true; break } // agent misbehaving / non-monotonic tree — avoid an infinite loop
                    results.add(vb)
                    current = vb.oid
                    if (results.size >= maxRows) break
                }
                if (stopped) break
            } else {
                val resp = request(Ber.GET_NEXT_REQUEST, listOf(VarBind.request(current)))
                val vb = resp.varBinds.firstOrNull() ?: break
                if (vb.value.isException || !vb.oid.startsWith(rootOid)) break
                if (vb.oid <= current) break // agent misbehaving / non-monotonic tree — avoid an infinite loop
                results.add(vb)
                current = vb.oid
            }
        }
        results
    }

    // ── Message assembly / dispatch ──────────────────────────────────

    private suspend fun request(pduType: Int, varBinds: List<VarBind>): SnmpResponse = withContext(Dispatchers.IO) {
        if (credentials.version == SnmpVersion.V3) requestV3(pduType, varBinds, nonRepeaters = 0, maxRepetitions = 0)
        else requestV1V2c(pduType, varBinds, nonRepeaters = 0, maxRepetitions = 0)
    }

    private suspend fun requestBulk(varBinds: List<VarBind>, nonRepeaters: Int, maxRepetitions: Int): SnmpResponse =
        withContext(Dispatchers.IO) {
            if (credentials.version == SnmpVersion.V3) requestV3(Ber.GET_BULK_REQUEST, varBinds, nonRepeaters, maxRepetitions)
            else requestV1V2c(Ber.GET_BULK_REQUEST, varBinds, nonRepeaters, maxRepetitions)
        }

    private fun buildPdu(writer: BerWriter, pduType: Int, requestId: Int, varBinds: List<VarBind>, nonRepeaters: Int, maxRepetitions: Int) {
        writer.writeConstructed(pduType) {
            writeInteger(requestId.toLong())
            if (pduType == Ber.GET_BULK_REQUEST) {
                writeInteger(nonRepeaters.toLong())
                writeInteger(maxRepetitions.toLong())
            } else {
                writeInteger(0) // error-status
                writeInteger(0) // error-index
            }
            writeConstructed(Ber.SEQUENCE) {
                for (vb in varBinds) {
                    writeConstructed(Ber.SEQUENCE) {
                        writeOid(vb.oid)
                        vb.value.toBer(this)
                    }
                }
            }
        }
    }

    private fun parsePdu(node: BerNode): SnmpResponse {
        val r = node.reader()
        val requestId = berDecodeSignedInt(r.readTlv().content).toInt()
        val errorStatus = SnmpErrorStatus.fromCode(berDecodeSignedInt(r.readTlv().content).toInt())
        val errorIndex = berDecodeSignedInt(r.readTlv().content).toInt()
        val listNode = r.readTlv()
        val listReader = listNode.reader()
        val varBinds = ArrayList<VarBind>()
        while (listReader.hasRemaining) {
            val vbNode = listReader.readTlv()
            val vbReader = vbNode.reader()
            val oid = berDecodeOid(vbReader.readTlv().content)
            val valueNode = vbReader.readTlv()
            varBinds.add(VarBind(oid, SnmpValue.fromBer(valueNode)))
        }
        return SnmpResponse(requestId, errorStatus, errorIndex, varBinds)
    }

    // ── SNMPv1 / SNMPv2c: community-based (RFC 1157 / RFC 3416) ──────

    private fun requestV1V2c(pduType: Int, varBinds: List<VarBind>, nonRepeaters: Int, maxRepetitions: Int): SnmpResponse {
        val creds = credentials as? SnmpCredentials.Community
            ?: throw SnmpException("SNMPv1/v2c requires community credentials")
        val requestId = requestIdSeq.incrementAndGet()

        val writer = BerWriter()
        writer.writeConstructed(Ber.SEQUENCE) {
            writeInteger(creds.version.wireValue.toLong())
            writeOctetString(creds.community)
        }
        val header = writer.toByteArray()
        val pduWriter = BerWriter()
        buildPdu(pduWriter, pduType, requestId, varBinds, nonRepeaters, maxRepetitions)
        val pduBytes = pduWriter.toByteArray()

        // header/pduBytes were each written as complete standalone TLVs (a SEQUENCE and a PDU
        // respectively); splice their raw bytes together inside one outer SEQUENCE rather than
        // re-walking them, since BerWriter has no "append raw sibling TLVs" primitive of its own.
        val combined = stripOuterSequence(header) + pduBytes
        val message = wrapSequence(combined)

        val respBytes = sendAndReceive(message)
        val respRoot = BerReader(respBytes).readTlv()
        val respReader = respRoot.reader()
        respReader.readTlv() // version
        respReader.readTlv() // community
        val pduNode = respReader.readTlv()
        if (pduNode.tag != Ber.GET_RESPONSE) throw SnmpException("Expected Response-PDU, got tag 0x${pduNode.tag.toString(16)}")
        val response = parsePdu(pduNode)
        if (response.requestId != requestId) throw SnmpException("Response request-id mismatch (sent $requestId, got ${response.requestId})")
        checkError(response)
        return response
    }

    // ── SNMPv3: USM (RFC 3412 message wrapper, RFC 3414 security) ────

    private fun requestV3(pduType: Int, varBinds: List<VarBind>, nonRepeaters: Int, maxRepetitions: Int): SnmpResponse {
        val creds = credentials as? SnmpCredentials.Usm ?: throw SnmpException("SNMPv3 requires USM credentials")
        if (engineId == null) discoverEngine(creds)
        val eid = engineId ?: throw SnmpException("SNMPv3 engine discovery failed")

        val requestId = requestIdSeq.incrementAndGet()
        val msgId = msgIdSeq.incrementAndGet()
        val message = buildV3Message(creds, msgId, requestId, eid, pduType, varBinds, nonRepeaters, maxRepetitions, reportable = true)

        val respBytes = sendAndReceive(message)
        val decoded = decodeV3Message(respBytes, creds)
        if (decoded.pduNode.tag == Ber.REPORT) {
            // usmStats* report — most commonly a boots/time resync after the agent restarted; the
            // client already captured the fresh engineBoots/Time in decodeV3Message, so retry once.
            val retryMsgId = msgIdSeq.incrementAndGet()
            val retryRequestId = requestIdSeq.incrementAndGet()
            val retryMessage = buildV3Message(creds, retryMsgId, retryRequestId, eid, pduType, varBinds, nonRepeaters, maxRepetitions, reportable = true)
            val retryRespBytes = sendAndReceive(retryMessage)
            val retryDecoded = decodeV3Message(retryRespBytes, creds)
            if (retryDecoded.pduNode.tag == Ber.REPORT) {
                throw SnmpAuthenticationException(describeReport(retryDecoded.pduNode))
            }
            val response = parsePdu(retryDecoded.pduNode)
            if (response.requestId != retryRequestId) throw SnmpException("Response request-id mismatch")
            checkError(response)
            return response
        }
        val response = parsePdu(decoded.pduNode)
        if (response.requestId != requestId) throw SnmpException("Response request-id mismatch (sent $requestId, got ${response.requestId})")
        checkError(response)
        return response
    }

    /** RFC 3414 §4: an unauthenticated GetRequest that solicits a Report carrying the agent's engineID/boots/time. */
    private fun discoverEngine(creds: SnmpCredentials.Usm) {
        val probeMsgId = msgIdSeq.incrementAndGet()
        val probeRequestId = requestIdSeq.incrementAndGet()
        val probe = buildV3Message(
            creds.copy(securityLevel = SnmpSecurityLevel.NO_AUTH_NO_PRIV, username = ""),
            probeMsgId, probeRequestId, ByteArray(0), Ber.GET_REQUEST, emptyList(), 0, 0, reportable = true,
        )
        val respBytes = sendAndReceive(probe)
        val decoded = decodeV3Message(respBytes, creds.copy(securityLevel = SnmpSecurityLevel.NO_AUTH_NO_PRIV))
        if (decoded.engineId.isEmpty()) throw SnmpException("Agent did not return an engineID during v3 discovery")
        engineId = decoded.engineId
        engineBoots = decoded.engineBoots
        engineTime = decoded.engineTime
    }

    private data class DecodedV3(val pduNode: BerNode, val engineId: ByteArray, val engineBoots: Int, val engineTime: Int)

    private fun buildV3Message(
        creds: SnmpCredentials.Usm, msgId: Int, requestId: Int, eid: ByteArray,
        pduType: Int, varBinds: List<VarBind>, nonRepeaters: Int, maxRepetitions: Int, reportable: Boolean,
    ): ByteArray {
        val needsAuth = creds.securityLevel != SnmpSecurityLevel.NO_AUTH_NO_PRIV
        val needsPriv = creds.securityLevel == SnmpSecurityLevel.AUTH_PRIV

        // ── scoped PDU (plaintext payload: contextEngineID, contextName, the PDU itself) ──
        val pduWriter = BerWriter()
        buildPdu(pduWriter, pduType, requestId, varBinds, nonRepeaters, maxRepetitions)
        val scopedPduWriter = BerWriter()
        scopedPduWriter.writeConstructed(Ber.SEQUENCE) {
            writeOctetString(eid)
            writeOctetString(creds.contextName)
        }
        val scopedPduBytes = stripOuterSequence(scopedPduWriter.toByteArray()) + pduWriter.toByteArray()
        val scopedPdu = wrapSequence(scopedPduBytes)

        // ── msgData: either plaintext scopedPDU, or an OCTET STRING of its encrypted bytes ──
        var privParams = ByteArray(0)
        val msgDataBytes: ByteArray
        if (needsPriv) {
            val ku = SnmpUsm.passwordToKey(creds.privPassphrase.ifEmpty { creds.authPassphrase }, creds.authProtocol)
            val kul = SnmpUsm.localizeKey(ku, eid, creds.authProtocol)
            val privWriter = BerWriter()
            when (creds.privProtocol) {
                SnmpPrivProtocol.DES -> {
                    val key = SnmpUsm.extendKey(ku, kul, creds.authProtocol, 16)
                    privParams = SnmpUsm.desSalt(engineBoots, localCounter.incrementAndGet())
                    val enc = SnmpUsm.desEncrypt(scopedPdu, key, privParams)
                    privWriter.writeOctetString(enc)
                }
                SnmpPrivProtocol.AES128, SnmpPrivProtocol.AES192, SnmpPrivProtocol.AES256 -> {
                    val key = SnmpUsm.extendKey(ku, kul, creds.authProtocol, creds.privProtocol.keyBytes)
                    privParams = SnmpUsm.generateSalt()
                    val enc = SnmpUsm.aesEncrypt(scopedPdu, key, engineBoots, engineTime, privParams)
                    privWriter.writeOctetString(enc)
                }
                SnmpPrivProtocol.NONE -> throw SnmpException("authPriv requires a privacy protocol")
            }
            msgDataBytes = privWriter.toByteArray()
        } else {
            msgDataBytes = scopedPdu
        }

        // ── USM security parameters (RFC 3414 §2.4) ──
        val authParamsLen = if (needsAuth) creds.authProtocol.digestBytes else 0
        val flags = (if (needsAuth) 0x01 else 0x00) or (if (needsPriv) 0x02 else 0x00) or (if (reportable) 0x04 else 0x00)

        fun assembleMessage(authParams: ByteArray): ByteArray {
            val usmWriter = BerWriter()
            usmWriter.writeConstructed(Ber.SEQUENCE) {
                writeOctetString(eid)
                writeInteger(engineBoots.toLong())
                writeInteger(engineTime.toLong())
                writeOctetString(creds.username)
                writeOctetString(authParams)
                writeOctetString(privParams)
            }
            val globalWriter = BerWriter()
            globalWriter.writeConstructed(Ber.SEQUENCE) {
                writeInteger(SnmpVersion.V3.wireValue.toLong())
                writeConstructed(Ber.SEQUENCE) { // HeaderData
                    writeInteger(msgId.toLong())
                    writeInteger(65507) // msgMaxSize — max SNMP message size we'll accept back, RFC 3412 §6.4
                    writeOctetString(byteArrayOf(flags.toByte()))
                    writeInteger(3) // msgSecurityModel — 3 == USM (RFC 3414 §1)
                }
                writeOctetString(usmWriter.toByteArray()) // msgSecurityParameters wraps the USM SEQUENCE as an OCTET STRING (RFC 3412 §6.3 step 7)
            }
            // msgData: [msgDataBytes] is already a complete TLV either way — an OCTET STRING of
            // the ciphertext when encrypted (RFC 3412 §6.3 step 7), or the scopedPDU's own
            // SEQUENCE directly when plaintext (§7.1) — so splice it on as-is as the last sibling
            // inside the outer SEQUENCE.
            return wrapSequence(stripOuterSequence(globalWriter.toByteArray()) + msgDataBytes)
        }

        // Pass 1: zeroed auth-params placeholder, so its length is correct for the HMAC to cover
        // the field position but not its (not-yet-known) content — RFC 3414 §6.3.1 step 4.
        val zeroedMessage = assembleMessage(ByteArray(authParamsLen))
        if (!needsAuth) return zeroedMessage

        // Pass 2: real digest, computed over pass 1's bytes, spliced into the same structure.
        val ku = SnmpUsm.passwordToKey(creds.authPassphrase, creds.authProtocol)
        val kul = SnmpUsm.localizeKey(ku, eid, creds.authProtocol)
        val digest = SnmpUsm.computeAuthDigest(zeroedMessage, kul, creds.authProtocol)
        return assembleMessage(digest)
    }

    private fun decodeV3Message(bytes: ByteArray, creds: SnmpCredentials.Usm): DecodedV3 {
        val root = BerReader(bytes).readTlv()
        val r = root.reader()
        r.readTlv() // msgVersion
        val headerReader = r.readTlv().reader()
        headerReader.readTlv() // msgID
        headerReader.readTlv() // msgMaxSize
        headerReader.readTlv() // msgFlags
        headerReader.readTlv() // msgSecurityModel
        val usmNode = r.readTlv()
        val usmReader = BerReader(usmNode.content).readTlv().reader()
        val respEngineId = usmReader.readTlv().content
        val respBoots = berDecodeSignedInt(usmReader.readTlv().content).toInt()
        val respTime = berDecodeSignedInt(usmReader.readTlv().content).toInt()
        usmReader.readTlv() // msgUserName (unused here — caller already knows who it asked as)
        usmReader.readTlv() // msgAuthenticationParameters
        val respPrivParams = usmReader.readTlv().content

        engineBoots = respBoots
        engineTime = respTime

        val needsPriv = creds.securityLevel == SnmpSecurityLevel.AUTH_PRIV
        val scopedPduBytes: ByteArray = if (needsPriv) {
            val encNode = r.readTlv()
            val ku = SnmpUsm.passwordToKey(creds.privPassphrase.ifEmpty { creds.authPassphrase }, creds.authProtocol)
            val kul = SnmpUsm.localizeKey(ku, respEngineId, creds.authProtocol)
            when (creds.privProtocol) {
                SnmpPrivProtocol.DES -> SnmpUsm.desDecrypt(encNode.content, SnmpUsm.extendKey(ku, kul, creds.authProtocol, 16), respPrivParams)
                SnmpPrivProtocol.AES128, SnmpPrivProtocol.AES192, SnmpPrivProtocol.AES256 ->
                    SnmpUsm.aesDecrypt(encNode.content, SnmpUsm.extendKey(ku, kul, creds.authProtocol, creds.privProtocol.keyBytes), respBoots, respTime, respPrivParams)
                SnmpPrivProtocol.NONE -> throw SnmpException("Response is encrypted but no privacy protocol configured")
            }
        } else {
            r.readTlv().let { it.content } // plaintext scopedPDU, already just past the security-params OCTET STRING
        }

        val scopedReader = BerReader(scopedPduBytes).readTlv().reader()
        scopedReader.readTlv() // contextEngineID
        scopedReader.readTlv() // contextName
        val pduNode = scopedReader.readTlv()
        return DecodedV3(pduNode, respEngineId, respBoots, respTime)
    }

    private fun describeReport(reportNode: BerNode): String {
        // Best-effort: surface the usmStats* OID from the report's one varbind, which names the
        // specific failure (wrong digest, unknown user, unsynchronized time window, etc — RFC 3414 §5).
        return try {
            val r = reportNode.reader()
            r.readTlv(); r.readTlv(); r.readTlv() // requestId, errorStatus, errorIndex
            val vbList = r.readTlv().reader()
            val vb = vbList.readTlv().reader()
            val oid = berDecodeOid(vb.readTlv().content)
            "Agent rejected the request: ${MibDictionary.nameFor(oid) ?: oid}"
        } catch (e: Exception) {
            "Agent rejected the request (usmStats report, reason undecodable)"
        }
    }

    // ── shared wire helpers ────────────────────────────────────────

    private fun checkError(response: SnmpResponse) {
        if (response.errorStatus != SnmpErrorStatus.NO_ERROR) {
            val failing = response.varBinds.getOrNull(response.errorIndex - 1)?.oid
            throw SnmpErrorStatusException(response.errorStatus, failing)
        }
    }

    private fun sendAndReceive(message: ByteArray): ByteArray {
        val sock = ensureSocket()
        val address = InetAddress.getByName(host)
        repeat(retries + 1) {
            try {
                sock.send(DatagramPacket(message, message.size, address, port))
                val buf = ByteArray(65535)
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                return packet.data.copyOfRange(0, packet.length)
            } catch (e: SocketTimeoutException) {
                // retry, up to `retries` more times — SnmpTimeoutException below if they all time out
            }
        }
        throw SnmpTimeoutException(host, port)
    }

    /** Strips the outer SEQUENCE tag+length off an already-encoded TLV, leaving just its content bytes, so they can be spliced as siblings into a different outer SEQUENCE. */
    private fun stripOuterSequence(tlv: ByteArray): ByteArray = BerReader(tlv).readTlv().content

    private fun wrapSequence(content: ByteArray): ByteArray {
        val w = BerWriter()
        w.writeTlv(Ber.SEQUENCE, content)
        return w.toByteArray()
    }

}
