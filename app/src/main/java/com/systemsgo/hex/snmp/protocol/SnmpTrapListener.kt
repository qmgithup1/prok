package com.systemsgo.hex.snmp.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.time.Instant

/** One received trap or inform, decoded into a display-ready shape. */
data class SnmpTrapEvent(
    val receivedAt: Instant,
    val sourceHost: String,
    val sourcePort: Int,
    val version: SnmpVersion,
    val isInform: Boolean,
    /** v1 only — the enterprise OID + generic/specific trap-type + sysUpTime that make up Trap-PDU's distinct header (RFC 1157 §4.1.6); null for v2c/v3 traps, which fold everything into varbinds instead (snmpTrapOID.0 is varbind #1 — see [trapOid]). */
    val v1Info: V1TrapInfo?,
    val varBinds: List<VarBind>,
) {
    data class V1TrapInfo(val enterprise: Oid, val agentAddress: String, val genericTrap: Int, val specificTrap: Int, val sysUpTime: Long)

    /** For v2c/v3: the snmpTrapOID.0 varbind's value — the notification's identity (e.g. `.1.3.6.1.6.3.1.1.5.3` == linkDown). Null for v1 (see [v1Info] instead). */
    val trapOid: Oid? get() = varBinds.firstOrNull { it.oid == Oid("1.3.6.1.6.3.1.1.4.1.0") }
        ?.let { (it.value as? SnmpValue.ObjectIdVal)?.oid }

    /** Best-effort human name for this event — v1's generic-trap enum, or the resolved snmpTrapOID for v2c/v3. */
    fun friendlyName(): String {
        v1Info?.let { info ->
            return when (info.genericTrap) {
                0 -> "coldStart"; 1 -> "warmStart"; 2 -> "linkDown"; 3 -> "linkUp"
                4 -> "authenticationFailure"; 5 -> "egpNeighborLoss"
                6 -> com.systemsgo.hex.snmp.mib.MibDictionary.describe(info.enterprise) + ".${info.specificTrap} (enterprise-specific)"
                else -> "trap type ${info.genericTrap}"
            }
        }
        return trapOid?.let { com.systemsgo.hex.snmp.mib.MibDictionary.describe(it) } ?: "notification"
    }
}

/**
 * Listens for SNMP traps (v1 Trap-PDU, RFC 1157 §4.1.6) and v2c/v3
 * notifications (SNMPv2-Trap-PDU / InformRequest-PDU, RFC 3416 §4.2.6-7)
 * on a UDP port, decoding each into an [SnmpTrapEvent].
 *
 * **Port 162 note:** the conventional SNMP-trap port (162) is below 1024,
 * and Android does not allow an unprivileged app to bind a port below
 * 1024 — this is an OS-level restriction, not something this class can
 * work around. Options in practice: (a) run this listener on a
 * non-privileged port (e.g. 1620) and have the network's router/firewall
 * port-forward UDP 162 → that port to this device; (b) on a rooted
 * device, bind 162 directly (this class will attempt whatever [port] it's
 * given — pass 162 and it'll simply fail with a bind exception on a
 * non-rooted device, which the caller should catch and explain). This
 * mirrors a well-known real-world limitation of every mobile SNMP trap
 * receiver, not something specific to this app.
 *
 * A trap receiver only has meaning while something is actively listening
 * — for continuous background reception rather than just "while this
 * screen is open", host this inside a foreground [android.app.Service]
 * (see the app's existing foreground-service patterns, e.g. any
 * long-lived session service) and call [start]/[stop] from its
 * lifecycle; this class itself has no Android framework dependency.
 */
class SnmpTrapListener(
    private val port: Int = 1620,
    private val communities: Set<String> = setOf("public"), // accepted v1/v2c communities; empty set == accept any
    private val v3Users: Map<String, SnmpCredentials.Usm> = emptyMap(), // keyed by username, for authenticating/decrypting v3 traps
    private val onEvent: (SnmpTrapEvent) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    val isListening: Boolean get() = job?.isActive == true

    /**
     * Set by [decodeV3] for the packet currently being processed, read
     * right afterwards by [sendInformAck] in the same sequential
     * receive-loop iteration (never touched concurrently — the loop
     * processes one packet fully before looping to `sock.receive()`
     * again). Cheaper than threading an extra return value through
     * [decode]'s otherwise-uniform `SnmpTrapEvent` return type.
     */
    private var pendingV3Ack: V3AckInfo? = null

    /** Everything needed to sign (and, if authPriv, encrypt) a Response-PDU acking a v3 Inform. See [sendInformAck]'s class-doc caveat about engine-authority correctness. */
    private data class V3AckInfo(
        val msgId: Int, val requestId: Int, val engineId: ByteArray, val engineBoots: Int, val engineTime: Int,
        val contextEngineId: ByteArray, val contextName: String, val creds: SnmpCredentials.Usm,
    )

    fun start(scope: CoroutineScope) {
        if (isListening) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(port)
                socket = sock
                val buf = ByteArray(65535)
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val bytes = packet.data.copyOfRange(0, packet.length)
                    val sourceHost = packet.address?.hostAddress ?: "?"
                    val sourcePort = packet.port
                    try {
                        val event = decode(bytes, sourceHost, sourcePort)
                        onEvent(event)
                        if (event.isInform) sendInformAck(bytes, sock, packet.address, sourcePort)
                    } catch (e: Exception) {
                        onError(SnmpException("Malformed trap/inform from $sourceHost:$sourcePort: ${e.message}", e))
                    }
                }
            } catch (e: SocketException) {
                // expected on stop() (socket.close() interrupts the blocking receive())
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
        job = null
    }

    private fun decode(bytes: ByteArray, sourceHost: String, sourcePort: Int): SnmpTrapEvent {
        pendingV3Ack = null
        val root = BerReader(bytes).readTlv()
        val r = root.reader()
        val versionNode = r.readTlv()
        val versionCode = berDecodeSignedInt(versionNode.content).toInt()

        return when (versionCode) {
            0 -> decodeV1(r, sourceHost, sourcePort) // SnmpVersion.V1.wireValue
            1 -> decodeV2cLike(r, sourceHost, sourcePort, SnmpVersion.V2C) // V2C
            3 -> decodeV3(bytes, sourceHost, sourcePort)
            else -> throw SnmpException("Unknown SNMP version code $versionCode in received packet")
        }
    }

    private fun decodeV1(r: BerReader, sourceHost: String, sourcePort: Int): SnmpTrapEvent {
        val communityNode = r.readTlv()
        val community = String(communityNode.content, Charsets.UTF_8)
        if (communities.isNotEmpty() && community !in communities) throw SnmpAuthenticationException("Unrecognized community \"$community\"")
        val pduNode = r.readTlv()
        if (pduNode.tag != Ber.TRAP_V1) throw SnmpException("Expected Trap-PDU, got tag 0x${pduNode.tag.toString(16)}")
        val pr = pduNode.reader()
        val enterprise = berDecodeOid(pr.readTlv().content)
        val agentAddrNode = pr.readTlv()
        val agentAddr = (SnmpValue.fromBer(agentAddrNode) as? SnmpValue.IpAddressVal)?.asText() ?: "?"
        val genericTrap = berDecodeSignedInt(pr.readTlv().content).toInt()
        val specificTrap = berDecodeSignedInt(pr.readTlv().content).toInt()
        val sysUpTime = berDecodeUnsigned(pr.readTlv().content)
        val varBinds = readVarBindList(pr.readTlv())
        return SnmpTrapEvent(
            Instant.now(), sourceHost, sourcePort, SnmpVersion.V1, isInform = false,
            v1Info = SnmpTrapEvent.V1TrapInfo(enterprise, agentAddr, genericTrap, specificTrap, sysUpTime),
            varBinds = varBinds,
        )
    }

    private fun decodeV2cLike(r: BerReader, sourceHost: String, sourcePort: Int, version: SnmpVersion): SnmpTrapEvent {
        val communityNode = r.readTlv()
        val community = String(communityNode.content, Charsets.UTF_8)
        if (communities.isNotEmpty() && community !in communities) throw SnmpAuthenticationException("Unrecognized community \"$community\"")
        val pduNode = r.readTlv()
        val isInform = pduNode.tag == Ber.INFORM_REQUEST
        if (pduNode.tag != Ber.SNMPV2_TRAP && !isInform) throw SnmpException("Expected SNMPv2-Trap-PDU or InformRequest-PDU, got tag 0x${pduNode.tag.toString(16)}")
        val response = parsePduBody(pduNode)
        return SnmpTrapEvent(Instant.now(), sourceHost, sourcePort, version, isInform, v1Info = null, varBinds = response)
    }

    private fun decodeV3(bytes: ByteArray, sourceHost: String, sourcePort: Int): SnmpTrapEvent {
        val root = BerReader(bytes).readTlv()
        val r = root.reader()
        r.readTlv() // version
        val headerReader = r.readTlv().reader()
        val msgId = berDecodeSignedInt(headerReader.readTlv().content).toInt()
        val usmNode = r.readTlv()
        val usmReader = BerReader(usmNode.content).readTlv().reader()
        val engineIdBytes = usmReader.readTlv().content
        val senderBoots = berDecodeSignedInt(usmReader.readTlv().content).toInt()
        val senderTime = berDecodeSignedInt(usmReader.readTlv().content).toInt()
        val userNode = usmReader.readTlv()
        val username = String(userNode.content, Charsets.UTF_8)
        val creds = v3Users[username]
            ?: throw SnmpAuthenticationException("No configured SNMPv3 user \"$username\" for this trap — add it under Trap Receiver settings to decode")
        // Note: authentication/decryption of the varbinds themselves reuses SnmpClient's private
        // v3 message-decoding logic conceptually, but a self-contained trap receiver can't share
        // that private method across classes — this is intentionally the same structure duplicated
        // at the level a trap actually needs (no request/response correlation, no engine
        // discovery — the trap already carries the sending engine's own boots/time above).
        val needsPriv = creds.securityLevel == SnmpSecurityLevel.AUTH_PRIV
        val scopedBytes: ByteArray = if (needsPriv) {
            usmReader.readTlv() // authParams (trap sender's own digest, not re-verified by this best-effort receiver)
            val privParamsNode = usmReader.readTlv()
            val encNode = r.readTlv()
            val ku = SnmpUsm.passwordToKey(creds.privPassphrase.ifEmpty { creds.authPassphrase }, creds.authProtocol)
            val kul = SnmpUsm.localizeKey(ku, engineIdBytes, creds.authProtocol)
            when (creds.privProtocol) {
                SnmpPrivProtocol.DES ->
                    SnmpUsm.desDecrypt(encNode.content, SnmpUsm.extendKey(ku, kul, creds.authProtocol, 16), privParamsNode.content)
                SnmpPrivProtocol.AES128, SnmpPrivProtocol.AES192, SnmpPrivProtocol.AES256 ->
                    SnmpUsm.aesDecrypt(
                        encNode.content,
                        SnmpUsm.extendKey(ku, kul, creds.authProtocol, creds.privProtocol.keyBytes),
                        senderBoots, senderTime, privParamsNode.content,
                    )
                SnmpPrivProtocol.NONE -> throw SnmpException("Trap claims authPriv but user \"$username\" has no privacy protocol configured")
            }
        } else {
            usmReader.readTlv(); usmReader.readTlv() // authParams, privParams (both empty/unused here)
            r.readTlv().content
        }
        val scopedReader = BerReader(scopedBytes).readTlv().reader()
        val contextEngineId = scopedReader.readTlv().content
        val contextName = String(scopedReader.readTlv().content, Charsets.UTF_8)
        val pduNode = scopedReader.readTlv()
        val isInform = pduNode.tag == Ber.INFORM_REQUEST
        val requestId = berDecodeSignedInt(pduNode.reader().readTlv().content).toInt()
        val varBinds = parsePduBody(pduNode)
        if (isInform) {
            pendingV3Ack = V3AckInfo(msgId, requestId, engineIdBytes, senderBoots, senderTime, contextEngineId, contextName, creds)
        }
        return SnmpTrapEvent(Instant.now(), sourceHost, sourcePort, SnmpVersion.V3, isInform, v1Info = null, varBinds = varBinds)
    }

    private fun parsePduBody(pduNode: BerNode): List<VarBind> {
        val pr = pduNode.reader()
        pr.readTlv() // request-id
        pr.readTlv() // error-status
        pr.readTlv() // error-index
        return readVarBindList(pr.readTlv())
    }

    private fun readVarBindList(listNode: BerNode): List<VarBind> {
        val listReader = listNode.reader()
        val result = ArrayList<VarBind>()
        while (listReader.hasRemaining) {
            val vbReader = listReader.readTlv().reader()
            val oid = berDecodeOid(vbReader.readTlv().content)
            result.add(VarBind(oid, SnmpValue.fromBer(vbReader.readTlv())))
        }
        return result
    }

    /**
     * RFC 3416 §4.2.7: an Inform must be acknowledged with a plain
     * Response-PDU echoing the same request-id (and, for v1/v2c, the same
     * community) — otherwise the sending manager keeps retransmitting it.
     *
     * **v3 caveat**: the ack below signs (and, for authPriv, encrypts)
     * the Response using the *same* engineID/boots/time the Inform itself
     * carried, which is the right behavior when the sending manager
     * already discovered this receiver's authoritative engine identity
     * before sending (the normal v3 Inform flow — RFC 3414 §4's discovery
     * exchange happens once, ahead of time, from the manager's side).
     * What this class does *not* implement is that discovery exchange
     * itself: an incoming v3 discovery probe (an empty-engineID
     * unauthenticated GetRequest soliciting a Report) isn't recognized or
     * answered, since this listener only parses Trap/Inform-shaped PDUs.
     * In practice this means the ack works correctly against any manager
     * that already knows (or is configured with) this device's engineID —
     * true of every mainstream NMS once a v3 Inform destination has been
     * added and successfully delivered at least once — but a manager
     * performing discovery fresh on every send won't get a valid ack.
     */
    private fun sendInformAck(originalBytes: ByteArray, sock: DatagramSocket, address: InetAddress?, sourcePort: Int) {
        if (address == null) return
        try {
            val ack = pendingV3Ack
            if (ack != null) {
                sendV3InformAck(ack, sock, address, sourcePort)
                return
            }
            val root = BerReader(originalBytes).readTlv()
            val r = root.reader()
            val versionNode = r.readTlv()
            val communityNode = r.readTlv()
            val pduNode = r.readTlv()
            val requestId = berDecodeSignedInt(pduNode.reader().readTlv().content).toInt()

            val pduWriter = BerWriter()
            pduWriter.writeConstructed(Ber.GET_RESPONSE) {
                writeInteger(requestId.toLong())
                writeInteger(0) // error-status
                writeInteger(0) // error-index
                writeConstructed(Ber.SEQUENCE) {} // empty varbind list is fine for an ack
            }
            val msgWriter = BerWriter()
            msgWriter.writeConstructed(Ber.SEQUENCE) {
                writeTlv(versionNode.tag, versionNode.content)
                writeTlv(communityNode.tag, communityNode.content)
            }
            val ackBytes = run {
                val stripped = BerReader(msgWriter.toByteArray()).readTlv().content
                val w = BerWriter(); w.writeTlv(Ber.SEQUENCE, stripped + pduWriter.toByteArray()); w.toByteArray()
            }
            sock.send(DatagramPacket(ackBytes, ackBytes.size, address, sourcePort))
        } catch (e: Exception) {
            onError(SnmpException("Failed to acknowledge Inform from ${address.hostAddress}: ${e.message}", e))
        } finally {
            pendingV3Ack = null
        }
    }

    /** Builds and sends the v3 Response-PDU for [ack] — see [sendInformAck]'s doc comment for what "correctly acked" means here. */
    private fun sendV3InformAck(ack: V3AckInfo, sock: DatagramSocket, address: InetAddress, sourcePort: Int) {
        val creds = ack.creds
        val needsAuth = creds.securityLevel != SnmpSecurityLevel.NO_AUTH_NO_PRIV
        val needsPriv = creds.securityLevel == SnmpSecurityLevel.AUTH_PRIV

        val pduWriter = BerWriter()
        pduWriter.writeConstructed(Ber.GET_RESPONSE) {
            writeInteger(ack.requestId.toLong())
            writeInteger(0) // error-status
            writeInteger(0) // error-index
            writeConstructed(Ber.SEQUENCE) {} // empty varbind list is fine for an ack
        }
        val scopedPduWriter = BerWriter()
        scopedPduWriter.writeConstructed(Ber.SEQUENCE) {
            writeOctetString(ack.contextEngineId)
            writeOctetString(ack.contextName)
        }
        val scopedPdu = run {
            val header = BerReader(scopedPduWriter.toByteArray()).readTlv().content
            val w = BerWriter(); w.writeTlv(Ber.SEQUENCE, header + pduWriter.toByteArray()); w.toByteArray()
        }

        var privParams = ByteArray(0)
        val msgDataBytes: ByteArray
        if (needsPriv) {
            val ku = SnmpUsm.passwordToKey(creds.privPassphrase.ifEmpty { creds.authPassphrase }, creds.authProtocol)
            val kul = SnmpUsm.localizeKey(ku, ack.engineId, creds.authProtocol)
            val encWriter = BerWriter()
            when (creds.privProtocol) {
                SnmpPrivProtocol.DES -> {
                    val key = SnmpUsm.extendKey(ku, kul, creds.authProtocol, 16)
                    privParams = SnmpUsm.desSalt(ack.engineBoots, (0..Int.MAX_VALUE).random())
                    encWriter.writeOctetString(SnmpUsm.desEncrypt(scopedPdu, key, privParams))
                }
                SnmpPrivProtocol.AES128, SnmpPrivProtocol.AES192, SnmpPrivProtocol.AES256 -> {
                    val key = SnmpUsm.extendKey(ku, kul, creds.authProtocol, creds.privProtocol.keyBytes)
                    privParams = SnmpUsm.generateSalt()
                    encWriter.writeOctetString(SnmpUsm.aesEncrypt(scopedPdu, key, ack.engineBoots, ack.engineTime, privParams))
                }
                SnmpPrivProtocol.NONE -> return // shouldn't happen — securityLevel already checked
            }
            msgDataBytes = encWriter.toByteArray()
        } else {
            msgDataBytes = scopedPdu
        }

        val authParamsLen = if (needsAuth) creds.authProtocol.digestBytes else 0
        val flags = (if (needsAuth) 0x01 else 0x00) or (if (needsPriv) 0x02 else 0x00) // reportable=0 on a response, RFC 3412 §7.1

        fun assemble(authParams: ByteArray): ByteArray {
            val usmWriter = BerWriter()
            usmWriter.writeConstructed(Ber.SEQUENCE) {
                writeOctetString(ack.engineId)
                writeInteger(ack.engineBoots.toLong())
                writeInteger(ack.engineTime.toLong())
                writeOctetString(creds.username)
                writeOctetString(authParams)
                writeOctetString(privParams)
            }
            val globalWriter = BerWriter()
            globalWriter.writeConstructed(Ber.SEQUENCE) {
                writeInteger(SnmpVersion.V3.wireValue.toLong())
                writeConstructed(Ber.SEQUENCE) {
                    writeInteger(ack.msgId.toLong())
                    writeInteger(65507)
                    writeOctetString(byteArrayOf(flags.toByte()))
                    writeInteger(3)
                }
                writeOctetString(usmWriter.toByteArray())
            }
            val header = BerReader(globalWriter.toByteArray()).readTlv().content
            val w = BerWriter(); w.writeTlv(Ber.SEQUENCE, header + msgDataBytes); return w.toByteArray()
        }

        val zeroed = assemble(ByteArray(authParamsLen))
        val finalMessage = if (!needsAuth) zeroed else {
            val ku = SnmpUsm.passwordToKey(creds.authPassphrase, creds.authProtocol)
            val kul = SnmpUsm.localizeKey(ku, ack.engineId, creds.authProtocol)
            assemble(SnmpUsm.computeAuthDigest(zeroed, kul, creds.authProtocol))
        }
        sock.send(DatagramPacket(finalMessage, finalMessage.size, address, sourcePort))
    }
}
