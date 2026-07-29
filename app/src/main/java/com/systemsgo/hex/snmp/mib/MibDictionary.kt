package com.systemsgo.hex.snmp.mib

import com.systemsgo.hex.snmp.protocol.Oid

/**
 * Built-in OID ⇄ friendly-name table for the standard MIB-II tree (RFC
 * 1213 `system`/`interfaces`/`ip`/`icmp`/`tcp`/`udp`/`snmp` groups) plus a
 * handful of near-universal host/enterprise objects (Host Resources MIB
 * storage/processor, a few common vendor sysObjectID prefixes). This is
 * what powers name display without needing to load any MIB file — loading
 * a real `.mib`/`.txt` file via [MibParser] merges more names on top of
 * this base set for the session.
 *
 * Deliberately not a MIB compiler: no SYNTAX/textual-convention resolution,
 * no table INDEX-clause-driven row naming. It's a flat name↔OID map plus a
 * best-effort [DISPLAY_HINTS] table for how to render a scalar's raw value
 * (e.g. ifOperStatus's enum labels) — enough for a professional dashboard
 * without the scope of a full SMIv2 toolchain.
 */
object MibDictionary {
    private val builtin: MutableMap<String, String> = LinkedHashMap() // oid string -> name
    private val reverse: MutableMap<String, String> = LinkedHashMap() // name -> oid string

    /** Extra names merged in at runtime by [MibParser.loadInto] for user-supplied MIB files. */
    private val custom: MutableMap<String, String> = LinkedHashMap()
    private val customReverse: MutableMap<String, String> = LinkedHashMap()

    fun register(oid: String, name: String, isCustom: Boolean = false) {
        if (isCustom) { custom[oid] = name; customReverse[name] = oid }
        else { builtin[oid] = name; reverse[name] = oid }
    }

    fun nameFor(oid: Oid): String? = custom[oid.toString()] ?: builtin[oid.toString()]

    fun oidFor(name: String): Oid? = (customReverse[name] ?: reverse[name])?.let { Oid(it) }

    /** Longest registered-ancestor lookup — e.g. `1.3.6.1.2.1.2.2.1.8.3` (ifOperStatus.3) resolves to `"ifOperStatus.3"` via the ifOperStatus (…8) column plus the row's own index suffix. */
    fun describe(oid: Oid): String {
        nameFor(oid)?.let { return it }
        var best: Pair<String, String>? = null // name, oidString
        for ((oidStr, name) in custom) {
            val candidate = Oid(oidStr)
            if (oid.startsWith(candidate) && (best == null || candidate.arcs.size > Oid(best.second).arcs.size)) best = name to oidStr
        }
        for ((oidStr, name) in builtin) {
            val candidate = Oid(oidStr)
            if (oid.startsWith(candidate) && (best == null || candidate.arcs.size > Oid(best.second).arcs.size)) best = name to oidStr
        }
        return if (best != null) best.first + "." + oid.suffixAfter(Oid(best.second)).joinToString(".") else oid.toString()
    }

    fun allEntries(): List<Pair<String, String>> = (builtin + custom).map { it.key to it.value }.sortedBy { Oid(it.first) }

    /** Enum-label rendering for a handful of ubiquitous status columns, keyed by the *column* OID (RFC 1213 / RFC 2863). */
    val DISPLAY_HINTS: Map<String, Map<Int, String>> = mapOf(
        "1.3.6.1.2.1.2.2.1.7" to mapOf(1 to "up", 2 to "down", 3 to "testing"), // ifAdminStatus
        "1.3.6.1.2.1.2.2.1.8" to mapOf(1 to "up", 2 to "down", 3 to "testing", 4 to "unknown", 5 to "dormant", 6 to "notPresent", 7 to "lowerLayerDown"), // ifOperStatus
        "1.3.6.1.2.1.4.1" to mapOf(1 to "forwarding", 2 to "not-forwarding"), // ipForwarding
        "1.3.6.1.2.1.25.3.5.1.1" to mapOf(1 to "running", 2 to "warning", 3 to "testing", 4 to "down"), // hrPrinterStatus
    )

    init {
        val t = arrayOf(
            // ── system (1.3.6.1.2.1.1) — RFC 1213 §6.1 ──
            "1.3.6.1.2.1.1" to "system",
            "1.3.6.1.2.1.1.1.0" to "sysDescr.0",
            "1.3.6.1.2.1.1.2.0" to "sysObjectID.0",
            "1.3.6.1.2.1.1.3.0" to "sysUpTime.0",
            "1.3.6.1.2.1.1.4.0" to "sysContact.0",
            "1.3.6.1.2.1.1.5.0" to "sysName.0",
            "1.3.6.1.2.1.1.6.0" to "sysLocation.0",
            "1.3.6.1.2.1.1.7.0" to "sysServices.0",
            "1.3.6.1.2.1.1.9" to "sysORTable",
            // ── interfaces (1.3.6.1.2.1.2) — RFC 1213 §6.3 / RFC 2863 (ifTable/ifXTable) ──
            "1.3.6.1.2.1.2.1.0" to "ifNumber.0",
            "1.3.6.1.2.1.2.2" to "ifTable",
            "1.3.6.1.2.1.2.2.1.1" to "ifIndex",
            "1.3.6.1.2.1.2.2.1.2" to "ifDescr",
            "1.3.6.1.2.1.2.2.1.3" to "ifType",
            "1.3.6.1.2.1.2.2.1.4" to "ifMtu",
            "1.3.6.1.2.1.2.2.1.5" to "ifSpeed",
            "1.3.6.1.2.1.2.2.1.6" to "ifPhysAddress",
            "1.3.6.1.2.1.2.2.1.7" to "ifAdminStatus",
            "1.3.6.1.2.1.2.2.1.8" to "ifOperStatus",
            "1.3.6.1.2.1.2.2.1.9" to "ifLastChange",
            "1.3.6.1.2.1.2.2.1.10" to "ifInOctets",
            "1.3.6.1.2.1.2.2.1.11" to "ifInUcastPkts",
            "1.3.6.1.2.1.2.2.1.13" to "ifInDiscards",
            "1.3.6.1.2.1.2.2.1.14" to "ifInErrors",
            "1.3.6.1.2.1.2.2.1.16" to "ifOutOctets",
            "1.3.6.1.2.1.2.2.1.17" to "ifOutUcastPkts",
            "1.3.6.1.2.1.2.2.1.19" to "ifOutDiscards",
            "1.3.6.1.2.1.2.2.1.20" to "ifOutErrors",
            "1.3.6.1.2.1.31.1.1" to "ifXTable",
            "1.3.6.1.2.1.31.1.1.1.1" to "ifName",
            "1.3.6.1.2.1.31.1.1.1.6" to "ifHCInOctets",
            "1.3.6.1.2.1.31.1.1.1.10" to "ifHCOutOctets",
            "1.3.6.1.2.1.31.1.1.1.15" to "ifHighSpeed",
            "1.3.6.1.2.1.31.1.1.1.18" to "ifAlias",
            // ── ip (1.3.6.1.2.1.4) — RFC 1213 §6.4 ──
            "1.3.6.1.2.1.4.1.0" to "ipForwarding.0",
            "1.3.6.1.2.1.4.20" to "ipAddrTable",
            "1.3.6.1.2.1.4.20.1.1" to "ipAdEntAddr",
            "1.3.6.1.2.1.4.20.1.2" to "ipAdEntIfIndex",
            "1.3.6.1.2.1.4.20.1.3" to "ipAdEntNetMask",
            "1.3.6.1.2.1.4.21" to "ipRouteTable",
            "1.3.6.1.2.1.4.22" to "ipNetToMediaTable",
            // ── icmp (1.3.6.1.2.1.5) ──
            "1.3.6.1.2.1.5.1.0" to "icmpInMsgs.0",
            "1.3.6.1.2.1.5.3.0" to "icmpInDestUnreachs.0",
            // ── tcp (1.3.6.1.2.1.6) — RFC 1213 §6.6 ──
            "1.3.6.1.2.1.6.9.0" to "tcpCurrEstab.0",
            "1.3.6.1.2.1.6.13" to "tcpConnTable",
            "1.3.6.1.2.1.6.13.1.1" to "tcpConnState",
            // ── udp (1.3.6.1.2.1.7) ──
            "1.3.6.1.2.1.7.5" to "udpTable",
            // ── snmp (1.3.6.1.2.1.11) — agent's own stats ──
            "1.3.6.1.2.1.11.1.0" to "snmpInPkts.0",
            "1.3.6.1.2.1.11.2.0" to "snmpOutPkts.0",
            "1.3.6.1.2.1.11.30.0" to "snmpEnableAuthenTraps.0",
            // ── host resources MIB (1.3.6.1.2.1.25) — RFC 2790, the common cross-vendor "host stats" ──
            "1.3.6.1.2.1.25.1.1.0" to "hrSystemUptime.0",
            "1.3.6.1.2.1.25.1.5.0" to "hrSystemNumUsers.0",
            "1.3.6.1.2.1.25.1.6.0" to "hrSystemProcesses.0",
            "1.3.6.1.2.1.25.2.2.0" to "hrMemorySize.0",
            "1.3.6.1.2.1.25.2.3" to "hrStorageTable",
            "1.3.6.1.2.1.25.2.3.1.3" to "hrStorageDescr",
            "1.3.6.1.2.1.25.2.3.1.5" to "hrStorageSize",
            "1.3.6.1.2.1.25.2.3.1.6" to "hrStorageUsed",
            "1.3.6.1.2.1.25.3.3.1.2" to "hrProcessorLoad",
            "1.3.6.1.2.1.25.4.2.1.2" to "hrSWRunName",
            // ── SNMPv2-MIB traps / notifications (1.3.6.1.6.3.1.1.5) — RFC 3418 ──
            "1.3.6.1.6.3.1.1.4.1.0" to "snmpTrapOID.0",
            "1.3.6.1.6.3.1.1.5.1" to "coldStart",
            "1.3.6.1.6.3.1.1.5.2" to "warmStart",
            "1.3.6.1.6.3.1.1.5.3" to "linkDown",
            "1.3.6.1.6.3.1.1.5.4" to "linkUp",
            "1.3.6.1.6.3.1.1.5.5" to "authenticationFailure",
            // ── USM stats (1.3.6.1.6.3.15.1.1) — the OIDs a v3 Report-PDU names on auth failure, RFC 3414 §5 ──
            "1.3.6.1.6.3.15.1.1.1.0" to "usmStatsUnsupportedSecLevels.0",
            "1.3.6.1.6.3.15.1.1.2.0" to "usmStatsNotInTimeWindows.0",
            "1.3.6.1.6.3.15.1.1.3.0" to "usmStatsUnknownUserNames.0",
            "1.3.6.1.6.3.15.1.1.4.0" to "usmStatsUnknownEngineIDs.0",
            "1.3.6.1.6.3.15.1.1.5.0" to "usmStatsWrongDigests.0",
            "1.3.6.1.6.3.15.1.1.6.0" to "usmStatsDecryptionErrors.0",
        )
        t.forEach { (oid, name) -> register(oid, name) }
    }
}
