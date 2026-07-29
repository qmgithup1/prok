package com.systemsgo.hex.nfs

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: Portmapper / rpcbind client (RFC 1833, program 100000).
//
// Real-world NFS servers almost always run mountd and nfsd on the "well
// known" ports 2049 (nfsd) — often fixed by convention/config even though
// the protocol says it's dynamic — and some fixed or dynamic port for mountd.
// We still query the portmapper first because mountd's port is genuinely not
// standardized (varies by OS/distro, sometimes randomized per boot). If the
// portmapper itself is unreachable or the query fails (firewalled off,
// disabled, etc.), we fall back to the conventional ports:
//   - nfsd  -> 2049 (universal in practice for NFSv3-over-TCP)
//   - mountd -> 2049 as a *last-resort* guess only; many servers do NOT run
//     mountd on 2049, so this fallback is more likely to fail than the nfsd
//     one — it exists purely so we attempt *something* rather than give up
//     before even trying, consistent with the explicit-fallback style used
//     elsewhere in this project (e.g. TftpConfig's default port, or SMB's
//     port-445 default). Callers should treat a MOUNT failure after this
//     fallback as "this server needs its mountd port supplied manually" —
//     exposed as the optional mountdPort field on NfsConfig (see
//     FileTransferManager.kt) and the "Mountd port (optional)" field on the
//     NFS connect form (see NfsTransferScreen.kt); when set, it skips both
//     the portmapper query and the fallback guess above for MOUNT.
//
// We only implement PMAPPROC_GETPORT (procedure 3) using protocol=TCP (6),
// which is the only lookup this client needs. PMAPPROC_DUMP and the newer
// rpcbind v3/v4 GETADDR (with universal addresses) are out of scope.
// ─────────────────────────────────────────────────────────────────────────────

object Portmapper {
    const val PROGRAM = 100000L
    const val VERSION = 2L
    private const val PROC_GETPORT = 3L
    private const val IPPROTO_TCP = 6L

    const val DEFAULT_PORT = 111
    const val NFSD_FALLBACK_PORT = 2049
    /** Last-resort guess only — see class doc; frequently wrong for mountd. */
    const val MOUNTD_FALLBACK_PORT = 2049

    /**
     * Looks up the TCP port for (program, version) via the portmapper on
     * [host]:111. Returns null (not throws) on any failure — every caller
     * treats null as "use the documented fallback port" rather than a hard
     * error, since an unreachable/disabled portmapper is a normal, expected
     * condition on many locked-down NFS servers.
     */
    fun getPort(host: String, program: Long, version: Long, timeoutMs: Int = 5_000): Int? {
        val client = OncRpcClient(host, DEFAULT_PORT, connectTimeoutMs = timeoutMs, soTimeoutMs = timeoutMs)
        return try {
            client.connect()
            val args = XdrEncoder(16)
            args.putUInt(program)
            args.putUInt(version)
            args.putUInt(IPPROTO_TCP)
            args.putUInt(0L) // port field of the query mapping is ignored by the server
            val reply = client.call(PROGRAM, VERSION, PROC_GETPORT, auth = null, argsBody = args.toByteArray())
            val port = reply.getUInt().toInt()
            if (port <= 0) null else port
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }
}
