package com.systemsgo.hex.nfs

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: MOUNT protocol client (RFC 1813 Appendix I, program 100005,
// version 3 — the version that pairs with NFSv3, distinct from the MOUNT
// versions used by NFSv2/v4). Only MNT (procedure 1) and UMNT (procedure 3)
// are implemented — EXPORT (procedure 5, listing available exports) is not
// exposed in the UI, so it's left out; the user types the export path
// directly, same tradeoff SMB/WebDAV make by asking for share/URL up front
// rather than offering a server-side browse-the-shares step.
// ─────────────────────────────────────────────────────────────────────────────

private const val MOUNT_PROGRAM = 100005L
private const val MOUNT_VERSION = 3L
private const val PROC_NULL = 0L
private const val PROC_MNT = 1L
private const val PROC_UMNT = 3L

/** mountstat3 (RFC 1813 §I.2.1.1) values worth naming individually; rest fall through generically. */
object MountStatus {
    const val OK = 0L
    const val PERM = 1L
    const val NOENT = 2L
    const val ACCES = 13L
    const val NOTDIR = 20L

    fun message(status: Long): String = when (status) {
        OK -> "OK"
        PERM -> "Not permitted to mount this export"
        NOENT -> "Export path does not exist on server"
        ACCES -> "Access denied — check the server's export ACL for this client"
        NOTDIR -> "Export path is not a directory"
        else -> "Mount error $status"
    }
}

class MountException(val status: Long) :
    java.io.IOException("MNT failed: ${MountStatus.message(status)}")

class MountClient(private val rpc: OncRpcClient) {

    /** Issues MNT for [exportPath] (e.g. "/export/data") and returns the root NfsFileHandle. */
    fun mount(exportPath: String, auth: AuthSys): NfsFileHandle {
        val args = XdrEncoder(exportPath.length + 8)
        args.putString(exportPath)
        val reply = rpc.call(MOUNT_PROGRAM, MOUNT_VERSION, PROC_MNT, auth, args.toByteArray())

        val status = reply.getUInt()
        if (status != MountStatus.OK) throw MountException(status)

        // mountres3_ok: fhandle3 (variable opaque, <=64 bytes) + auth flavors list (ignored: AUTH_SYS is what we send).
        val fh = reply.getVarOpaque(64)
        return fh
    }

    /** Best-effort UMNT — servers that track mount state benefit from this, but nothing here depends on the reply. */
    fun unmount(exportPath: String, auth: AuthSys) {
        try {
            val args = XdrEncoder(exportPath.length + 8)
            args.putString(exportPath)
            rpc.call(MOUNT_PROGRAM, MOUNT_VERSION, PROC_UMNT, auth, args.toByteArray())
        } catch (_: Exception) {
            // Non-fatal: we're disconnecting anyway.
        }
    }
}
