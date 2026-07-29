package com.systemsgo.hex.nfs

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: shared MOUNT/NFSv3 data types (RFC 1813 struct definitions,
// trimmed to the fields this client actually uses).
// ─────────────────────────────────────────────────────────────────────────────

/** An NFSv3 file handle: opaque to the client, echoed back to the server on every op. */
typealias NfsFileHandle = ByteArray

/** ftype3 (RFC 1813 §2.5). Only the kinds this browser can meaningfully show/act on are named. */
enum class NfsFileType(val code: Long) {
    REG(1L), DIR(2L), BLK(3L), CHR(4L), LNK(5L), SOCK(6L), FIFO(7L);
    companion object {
        fun fromCode(code: Long): NfsFileType = values().firstOrNull { it.code == code } ?: REG
    }
}

/** fattr3 (RFC 1813 §2.5), trimmed to what the file browser and transfer engine need. */
data class NfsAttrs(
    val type: NfsFileType,
    val mode: Int,
    val size: Long,
    val mtimeSeconds: Long,
)

data class NfsDirEntry(
    val name: String,
    val fileHandle: NfsFileHandle?, // present when READDIRPLUS supplied it, null for plain READDIR
    val attrs: NfsAttrs?,
)

/** ACCESS3args/resok bitmask values (RFC 1813 §3.3.4). */
object NfsAccess {
    const val READ = 0x0001
    const val LOOKUP = 0x0002
    const val MODIFY = 0x0004
    const val EXTEND = 0x0008
    const val DELETE = 0x0010
    const val EXECUTE = 0x0020
}

/** FSINFO3resok (RFC 1813 §3.3.19), trimmed to the fields NfsFileBrowser's dynamic chunk sizing needs. */
data class NfsFsInfo(
    val rtmax: Long,
    val rtpref: Long,
    val wtmax: Long,
    val wtpref: Long,
    val maxFileSize: Long,
)

/** PATHCONF3resok (RFC 1813 §3.3.20), trimmed to the fields most likely to matter to a caller. */
data class NfsPathConf(
    val linkMax: Long,
    val nameMax: Long,
    val noTrunc: Boolean,
)

/**
 * nfsstat3 (RFC 1813 §2.6) — only the values this client distinguishes with a
 * dedicated message; everything else falls through to a generic "NFS error N".
 */
object NfsStatus {
    const val OK = 0L
    const val PERM = 1L
    const val NOENT = 2L
    const val IO = 5L
    const val NXIO = 6L
    const val ACCES = 13L
    const val EXIST = 17L
    const val NODEV = 19L
    const val NOTDIR = 20L
    const val ISDIR = 21L
    const val FBIG = 27L
    const val NOSPC = 28L
    const val ROFS = 30L
    const val NAMETOOLONG = 63L
    const val NOTEMPTY = 66L
    const val DQUOT = 69L
    const val STALE = 70L
    const val SERVERFAULT = 10006L

    fun message(status: Long): String = when (status) {
        OK -> "OK"
        PERM -> "Operation not permitted (check the AUTH_SYS uid/gid this connection presents)"
        NOENT -> "No such file or directory"
        IO -> "I/O error on server"
        ACCES -> "Permission denied"
        EXIST -> "File already exists"
        NOTDIR -> "Not a directory"
        ISDIR -> "Is a directory"
        FBIG -> "File too large"
        NOSPC -> "No space left on device"
        ROFS -> "Export is read-only"
        NAMETOOLONG -> "File name too long"
        NOTEMPTY -> "Directory not empty"
        DQUOT -> "Disk quota exceeded"
        STALE -> "Stale file handle"
        SERVERFAULT -> "Server fault"
        else -> "NFS error $status"
    }
}

class NfsException(val status: Long, procedure: String) :
    java.io.IOException("$procedure failed: ${NfsStatus.message(status)}")
