package com.systemsgo.hex.nfs

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: NFSv3 client (RFC 1813, program 100003, version 3).
//
// Implemented procedures — the minimum needed for a browse/upload/download
// file manager, matching what NfsFileBrowser (FileTransferManager.kt) calls:
//   GETATTR(1), LOOKUP(3), READ(6), WRITE(7), CREATE(8), COMMIT(21),
//   READDIRPLUS(17), FSSTAT(18).
//
// NFS-FEATURE (file-manager write ops): SETATTR(2), REMOVE(12), RENAME(14),
// LINK(15), MKDIR(9), RMDIR(13) added below alongside the browse/transfer
// set above, so the file manager can offer delete/rename/mkdir/chmod the
// same way SmbFileBrowser/FtpFileBrowser already do. All six follow RFC 1813
// verbatim (§3.3.2, .12, .14, .15, .9, .13) using the same wcc_data/
// diropargs3 helpers the browse/transfer procedures above already rely on.
//
// NFS-FEATURE (symlinks + server intel): READLINK(5)/SYMLINK(10) resolve and
// create symlinks — NfsFileBrowser now follows a symlink to its real target
// (see resolveSymlink() in NfsSymlinkResolver.kt) instead of surfacing "not a
// regular file". ACCESS(4), FSINFO(19), PATHCONF(20) added alongside; FSINFO
// is a real integration point — NfsFileBrowser.connect() calls it and uses
// the server's own rtpref/wtpref instead of a single hand-measured
// CHUNK_SIZE for every server. MKNOD(11) and plain READDIR(16) are
// implemented per RFC 1813 for completeness but have no UI caller: the file
// manager doesn't offer "create special file", and READDIRPLUS already
// covers every browse need (see below).
//
// Explicitly NOT implemented (documented gap, not silent omission):
//   - Device/socket/FIFO special files: READDIRPLUS/READDIR list these
//     (ftype3 BLK/CHR/SOCK/FIFO) so the user can see they exist, and MKNOD
//     can create them, but LOOKUP/READ/download treats them as unsupported —
//     only REG and (as of this change) LNK-resolving-to-REG are opened.
//   - NFSv2, NFSv4/4.1, pNFS: only NFSv3 (program version 3) is spoken.
//   - AUTH_SYS is the only credential flavor sent (see OncRpc.kt / AuthSys).
//     The server is trusted to have already mapped this client's uid/gid
//     to something sane via its export config (no_root_squash, mapping to
//     "nobody", etc.) — NFSv3/AUTH_SYS has no cryptographic identity proof
//     at all, so *any* uid/gid this client claims is taken on faith by the
//     server. That's a protocol-level limitation, not something this client
//     can improve on; the UI must make clear that NFS access here is only as
//     safe as the network path and the server's export ACLs, not a
//     substitute for real authentication like SFTP/SMB(signed)/WebDAV(TLS+
//     basic auth) provide.
// ─────────────────────────────────────────────────────────────────────────────

const val NFS_PROGRAM = 100003L
const val NFS_VERSION = 3L

private const val PROC_GETATTR = 1L
private const val PROC_SETATTR = 2L
private const val PROC_LOOKUP = 3L
private const val PROC_ACCESS = 4L
private const val PROC_READLINK = 5L
private const val PROC_READ = 6L
private const val PROC_WRITE = 7L
private const val PROC_CREATE = 8L
private const val PROC_MKDIR = 9L
private const val PROC_SYMLINK = 10L
private const val PROC_MKNOD = 11L
private const val PROC_REMOVE = 12L
private const val PROC_RMDIR = 13L
private const val PROC_RENAME = 14L
private const val PROC_LINK = 15L
private const val PROC_READDIR = 16L
private const val PROC_READDIRPLUS = 17L
private const val PROC_FSSTAT = 18L
private const val PROC_FSINFO = 19L
private const val PROC_PATHCONF = 20L
private const val PROC_COMMIT = 21L

/** stable_how (RFC 1813 §2.6). Every WRITE below uses UNSTABLE + a trailing COMMIT
 *  (see [write]/[commit]) rather than FILE_SYNC per chunk — one round trip's worth
 *  of durability-wait at the end of a transfer instead of on every chunk. */
private const val UNSTABLE = 0L

class Nfsv3Client(private val rpc: OncRpcClient, private val auth: AuthSys) {

    // ── attribute parsing ──────────────────────────────────────────────────

    /** Parses one fixed-size fattr3 (84 bytes, RFC 1813 §2.5) starting at the decoder's current position. */
    private fun parseFattr3(dec: XdrDecoder): NfsAttrs {
        val type = NfsFileType.fromCode(dec.getUInt())
        val mode = dec.getUInt().toInt()
        dec.getUInt() // nlink
        dec.getUInt() // uid
        dec.getUInt() // gid
        val size = dec.getUHyper()
        dec.getUHyper() // used
        dec.getUInt(); dec.getUInt() // rdev (specdata1, specdata2)
        dec.getUHyper() // fsid
        dec.getUHyper() // fileid
        dec.getUInt(); dec.getUInt() // atime (seconds, nseconds)
        val mtimeSec = dec.getUInt()
        dec.getUInt() // mtime nseconds
        dec.getUInt(); dec.getUInt() // ctime (seconds, nseconds)
        return NfsAttrs(type = type, mode = mode, size = size, mtimeSeconds = mtimeSec)
    }

    private fun parsePostOpAttr(dec: XdrDecoder): NfsAttrs? =
        if (dec.getBool()) parseFattr3(dec) else null

    /** wcc_data (RFC 1813 §2.6): pre_op_attr + post_op_attr — we only need to consume it correctly, values unused. */
    private fun skipWccData(dec: XdrDecoder) {
        if (dec.getBool()) { dec.getUHyper(); dec.getUInt(); dec.getUInt(); dec.getUInt(); dec.getUInt() } // wcc_attr: size + mtime(8) + ctime(8)
        parsePostOpAttr(dec)
    }

    private fun parsePostOpFh3(dec: XdrDecoder): NfsFileHandle? =
        if (dec.getBool()) dec.getVarOpaque(64) else null

    // ── GETATTR ─────────────────────────────────────────────────────────────

    fun getAttr(fh: NfsFileHandle): NfsAttrs {
        val args = XdrEncoder(fh.size + 8)
        args.putVarOpaque(fh)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_GETATTR, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) throw NfsException(status, "GETATTR")
        return parseFattr3(reply)
    }

    // ── SETATTR ─────────────────────────────────────────────────────────────

    /**
     * Changes a file's mode and/or size (sattr3, RFC 1813 §3.3.2). Both
     * parameters are optional (`null` = "don't set"), matching the union
     * discriminants sattr3 defines per-field — uid/gid/atime/mtime are
     * always left at DONT_CHANGE since neither the file browser nor the
     * transfer engine expose UI for them, and no ctime guard (sattrguard3)
     * is sent since this client has no cached ctime to compare against.
     */
    fun setAttr(fh: NfsFileHandle, mode: Int? = null, size: Long? = null) {
        val args = XdrEncoder(fh_size(fh) + 40)
        args.putVarOpaque(fh)
        // sattr3.mode (set_mode3)
        if (mode != null) { args.putBool(true); args.putUInt(mode.toLong()) } else args.putBool(false)
        args.putBool(false) // set_uid3 — DONT_CHANGE
        args.putBool(false) // set_gid3 — DONT_CHANGE
        // sattr3.size (set_size3)
        if (size != null) { args.putBool(true); args.putUHyper(size) } else args.putBool(false)
        args.putUInt(0L) // set_atime — DONT_CHANGE
        args.putUInt(0L) // set_mtime — DONT_CHANGE
        args.putBool(false) // sattrguard3.check — no ctime guard
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_SETATTR, auth, args.toByteArray())
        val status = reply.getUInt()
        skipWccData(reply) // obj_wcc — present on both success and failure
        if (status != NfsStatus.OK) throw NfsException(status, "SETATTR")
    }

    // ── LOOKUP ──────────────────────────────────────────────────────────────

    /** Returns (childHandle, childAttrs-if-server-sent-them) for `name` inside `dirFh`. */
    fun lookup(dirFh: NfsFileHandle, name: String): Pair<NfsFileHandle, NfsAttrs?> {
        val args = XdrEncoder(fh_size(dirFh) + name.length + 16)
        args.putVarOpaque(dirFh)
        args.putString(name)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_LOOKUP, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            // dir_attributes (post_op_attr) follows even on failure — consume then throw.
            parsePostOpAttr(reply)
            throw NfsException(status, "LOOKUP($name)")
        }
        val childFh = reply.getVarOpaque(64)
        val childAttrs = parsePostOpAttr(reply)
        parsePostOpAttr(reply) // dir_attributes — not needed by callers
        return childFh to childAttrs
    }

    /**
     * Resolves a slash-separated path (relative to the mount's root handle)
     * one LOOKUP per segment. No handle caching between calls — every
     * listDir()/downloadFile()/uploadFile() re-walks from root. Simpler and
     * safer against stale cached handles (files moved/deleted server-side)
     * than a cache would be, at the cost of one extra round trip per path
     * segment; acceptable for a phone-to-LAN-server file manager where
     * directory depth is small and each RTT is sub-millisecond on Wi-Fi.
     */
    fun resolvePath(rootFh: NfsFileHandle, path: String): NfsFileHandle {
        var fh = rootFh
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            fh = lookup(fh, segment).first
        }
        return fh
    }

    /** Same as [resolvePath] but also returns the parent handle and final segment name — used by CREATE/upload. */
    fun resolveParentAndName(rootFh: NfsFileHandle, path: String): Triple<NfsFileHandle, String, NfsFileHandle?> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "Empty path" }
        var parent = rootFh
        for (i in 0 until segments.size - 1) parent = lookup(parent, segments[i]).first
        val name = segments.last()
        val existing = try { lookup(parent, name).first } catch (_: NfsException) { null }
        return Triple(parent, name, existing)
    }

    // ── READLINK ────────────────────────────────────────────────────────────

    /** Returns the raw target path recorded in a symlink (RFC 1813 §3.3.5). Caller decides absolute-vs-relative and does the actual re-resolution — see resolveSymlink() in NfsSymlinkResolver.kt. */
    fun readLink(fh: NfsFileHandle): String {
        val args = XdrEncoder(fh_size(fh) + 8)
        args.putVarOpaque(fh)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_READLINK, auth, args.toByteArray())
        val status = reply.getUInt()
        parsePostOpAttr(reply) // symlink_attributes — present on both outcomes
        if (status != NfsStatus.OK) throw NfsException(status, "READLINK")
        return reply.getString(4_096) // nfspath3 — symlink targets are always short; generous cap, not a real limit
    }

    // ── SYMLINK ─────────────────────────────────────────────────────────────

    /** Creates a symlink named `name` (in `dirFh`) pointing at `target` (symlinkdata3 = sattr3 + nfspath3, RFC 1813 §3.3.10). Mirrors [create]/[mkdir]'s all-defaults sattr3. */
    fun symlink(dirFh: NfsFileHandle, name: String, target: String): NfsFileHandle {
        val args = XdrEncoder(fh_size(dirFh) + name.length + target.length + 48)
        args.putVarOpaque(dirFh)
        args.putString(name)
        // sattr3, all six optional fields left "not set" — same as CREATE/MKDIR's all-defaults path.
        repeat(6) { args.putUInt(0L) }
        args.putString(target)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_SYMLINK, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            skipWccData(reply) // dir_wcc
            throw NfsException(status, "SYMLINK($name -> $target)")
        }
        val fh = parsePostOpFh3(reply)
            ?: throw NfsException(NfsStatus.SERVERFAULT, "SYMLINK($name) — server did not return a file handle")
        parsePostOpAttr(reply) // obj_attributes
        skipWccData(reply)     // dir_wcc
        return fh
    }

    // ── ACCESS ──────────────────────────────────────────────────────────────

    /** Sends a bitmask of requested [NfsAccess] permissions, returns the bitmask the server actually grants (RFC 1813 §3.3.4). */
    fun access(fh: NfsFileHandle, requested: Int): Int {
        val args = XdrEncoder(fh_size(fh) + 8)
        args.putVarOpaque(fh)
        args.putUInt(requested.toLong() and 0xFFFFFFFFL)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_ACCESS, auth, args.toByteArray())
        val status = reply.getUInt()
        parsePostOpAttr(reply) // obj_attributes — present on both outcomes
        if (status != NfsStatus.OK) throw NfsException(status, "ACCESS")
        return reply.getUInt().toInt()
    }

    // ── FSINFO ──────────────────────────────────────────────────────────────

    /** Server-reported transfer-size preferences/limits (RFC 1813 §3.3.19). Real integration point: NfsFileBrowser.connect() calls this once and sizes its READ/WRITE chunks from rtpref/wtpref instead of a single hand-picked constant for every server. */
    fun fsInfo(fh: NfsFileHandle): NfsFsInfo {
        val args = XdrEncoder(fh_size(fh) + 8)
        args.putVarOpaque(fh)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_FSINFO, auth, args.toByteArray())
        val status = reply.getUInt()
        parsePostOpAttr(reply) // obj_attributes — present on both outcomes
        if (status != NfsStatus.OK) throw NfsException(status, "FSINFO")
        val rtmax = reply.getUInt()
        val rtpref = reply.getUInt()
        reply.getUInt() // rtmult — not used by this client (chunk sizes aren't required to be a multiple of it)
        val wtmax = reply.getUInt()
        val wtpref = reply.getUInt()
        reply.getUInt() // wtmult
        reply.getUInt() // dtpref — READDIR(PLUS) hint; readDirPlus() already uses its own fixed hints
        val maxFileSize = reply.getUHyper()
        reply.getUInt(); reply.getUInt() // time_delta (seconds, nseconds)
        reply.getUInt() // properties (FSF_LINK/SYMLINK/HOMOGENEOUS/CANSETTIME bits) — not consulted
        return NfsFsInfo(rtmax = rtmax, rtpref = rtpref, wtmax = wtmax, wtpref = wtpref, maxFileSize = maxFileSize)
    }

    // ── PATHCONF ────────────────────────────────────────────────────────────

    /** Per-file-system name/path limits (RFC 1813 §3.3.20). Lower priority than FSINFO; implemented for completeness, no UI caller yet. */
    fun pathConf(fh: NfsFileHandle): NfsPathConf {
        val args = XdrEncoder(fh_size(fh) + 8)
        args.putVarOpaque(fh)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_PATHCONF, auth, args.toByteArray())
        val status = reply.getUInt()
        parsePostOpAttr(reply) // obj_attributes — present on both outcomes
        if (status != NfsStatus.OK) throw NfsException(status, "PATHCONF")
        val linkMax = reply.getUInt()
        val nameMax = reply.getUInt()
        val noTrunc = reply.getBool()
        reply.getBool() // chown_restricted
        reply.getBool() // case_insensitive
        reply.getBool() // case_preserving
        return NfsPathConf(linkMax = linkMax, nameMax = nameMax, noTrunc = noTrunc)
    }

    // ── MKNOD ───────────────────────────────────────────────────────────────

    /**
     * Creates a special file (block/char device, socket, or FIFO — RFC 1813
     * §3.3.11). [major]/[minor] are only sent for CHR/BLK (mknoddata3's
     * devicedata3 arm); SOCK/FIFO send just an sattr3, like [create]/[mkdir].
     * No UI caller: the file manager doesn't offer "create special file".
     */
    fun mknod(dirFh: NfsFileHandle, name: String, type: NfsFileType, major: Int = 0, minor: Int = 0): NfsFileHandle {
        require(type == NfsFileType.CHR || type == NfsFileType.BLK || type == NfsFileType.SOCK || type == NfsFileType.FIFO) {
            "MKNOD only supports CHR/BLK/SOCK/FIFO special files, got $type"
        }
        val args = XdrEncoder(fh_size(dirFh) + name.length + 56)
        args.putVarOpaque(dirFh)
        args.putString(name)
        args.putUInt(type.code)
        // sattr3, all six optional fields left "not set" — same as CREATE/MKDIR/SYMLINK's all-defaults path.
        repeat(6) { args.putUInt(0L) }
        if (type == NfsFileType.CHR || type == NfsFileType.BLK) {
            args.putUInt(major.toLong() and 0xFFFFFFFFL) // specdata3.specdata1
            args.putUInt(minor.toLong() and 0xFFFFFFFFL) // specdata3.specdata2
        }
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_MKNOD, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            skipWccData(reply) // dir_wcc
            throw NfsException(status, "MKNOD($name)")
        }
        val fh = parsePostOpFh3(reply)
            ?: throw NfsException(NfsStatus.SERVERFAULT, "MKNOD($name) — server did not return a file handle")
        parsePostOpAttr(reply) // obj_attributes
        skipWccData(reply)     // dir_wcc
        return fh
    }

    // ── READDIR (plain) ─────────────────────────────────────────────────────

    /** Plain READDIR (RFC 1813 §3.3.16) — names + cookies only, no attrs/handles. Lower priority: [readDirPlus] above already covers every browse need this client has; implemented for completeness. */
    fun readDir(dirFh: NfsFileHandle, maxEntries: Int = 5_000): List<String> {
        val out = ArrayList<String>()
        var cookie = 0L
        var cookieVerf = ByteArray(8)
        var eof = false

        while (!eof && out.size < maxEntries) {
            val args = XdrEncoder(fh_size(dirFh) + 24)
            args.putVarOpaque(dirFh)
            args.putUHyper(cookie)
            args.putFixedOpaque(cookieVerf)
            args.putUInt(8_192L) // count: hint for total reply bytes
            val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_READDIR, auth, args.toByteArray())

            val status = reply.getUInt()
            if (status != NfsStatus.OK) {
                parsePostOpAttr(reply)
                throw NfsException(status, "READDIR")
            }
            parsePostOpAttr(reply) // dir_attributes
            cookieVerf = reply.getFixedOpaque(8)

            while (reply.getBool()) {
                reply.getUHyper() // fileid
                val name = reply.getString()
                cookie = reply.getUHyper()
                if (name != "." && name != "..") {
                    out.add(name)
                    if (out.size >= maxEntries) break
                }
            }
            eof = reply.getBool()
        }
        return out
    }

    // ── READDIRPLUS ─────────────────────────────────────────────────────────

    /**
     * Lists a directory's entries. Pages through READDIRPLUS calls using the
     * cookie/cookieverf the server returns until eof=true or [maxEntries] is
     * reached (mirrors SmbFileBrowser's MAX_DIR_ENTRIES cap so one huge
     * directory can't hang the UI).
     */
    fun readDirPlus(dirFh: NfsFileHandle, maxEntries: Int = 5_000): List<NfsDirEntry> {
        val out = ArrayList<NfsDirEntry>()
        var cookie = 0L
        var cookieVerf = ByteArray(8)
        var eof = false

        while (!eof && out.size < maxEntries) {
            val args = XdrEncoder(fh_size(dirFh) + 32)
            args.putVarOpaque(dirFh)
            args.putUHyper(cookie)
            args.putFixedOpaque(cookieVerf)
            args.putUInt(8_192L)   // dircount: hint for name+attrs bytes
            args.putUInt(32_768L)  // maxcount: hint for total reply bytes
            val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_READDIRPLUS, auth, args.toByteArray())

            val status = reply.getUInt()
            if (status != NfsStatus.OK) {
                parsePostOpAttr(reply)
                throw NfsException(status, "READDIRPLUS")
            }
            parsePostOpAttr(reply) // dir_attributes
            cookieVerf = reply.getFixedOpaque(8)

            while (reply.getBool()) {
                reply.getUHyper() // fileid
                val name = reply.getString()
                cookie = reply.getUHyper()
                val attrs = parsePostOpAttr(reply)
                val fh = parsePostOpFh3(reply)
                if (name != "." && name != "..") {
                    out.add(NfsDirEntry(name = name, fileHandle = fh, attrs = attrs))
                    if (out.size >= maxEntries) break
                }
            }
            eof = reply.getBool()
        }
        return out
    }

    // ── READ ────────────────────────────────────────────────────────────────

    /** One READ call. Caller loops, advancing offset by the bytes returned, until eof. */
    fun read(fh: NfsFileHandle, offset: Long, count: Int): ReadResult {
        val args = XdrEncoder(fh_size(fh) + 20)
        args.putVarOpaque(fh)
        args.putUHyper(offset)
        args.putUInt(count.toLong())
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_READ, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            parsePostOpAttr(reply)
            throw NfsException(status, "READ")
        }
        parsePostOpAttr(reply) // file_attributes
        val actualCount = reply.getUInt().toInt()
        val eof = reply.getBool()
        val data = reply.getVarOpaque(count.coerceAtLeast(1) + 1024)
        return ReadResult(data = data, eof = eof || actualCount == 0)
    }

    data class ReadResult(val data: ByteArray, val eof: Boolean)

    // ── CREATE ──────────────────────────────────────────────────────────────

    /** Creates (or truncates, via UNCHECKED create mode) a regular file and returns its handle. */
    fun create(dirFh: NfsFileHandle, name: String): NfsFileHandle {
        val args = XdrEncoder(fh_size(dirFh) + name.length + 40)
        args.putVarOpaque(dirFh)
        args.putString(name)
        args.putUInt(0L) // createmode3 = UNCHECKED — create, or truncate if it already exists
        // sattr3, all six optional fields left "not set" (all-zero) so the server applies its own defaults/umask.
        repeat(6) { args.putUInt(0L) }
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_CREATE, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            skipWccData(reply)
            throw NfsException(status, "CREATE($name)")
        }
        val fh = parsePostOpFh3(reply)
            ?: throw NfsException(NfsStatus.SERVERFAULT, "CREATE($name) — server did not return a file handle")
        parsePostOpAttr(reply) // obj_attributes
        skipWccData(reply)     // dir_wcc
        return fh
    }

    // ── REMOVE ──────────────────────────────────────────────────────────────

    /** Deletes a (non-directory) file. Same wcc_data-on-both-outcomes shape as CREATE. */
    fun remove(dirFh: NfsFileHandle, name: String) {
        val args = XdrEncoder(fh_size(dirFh) + name.length + 8)
        args.putVarOpaque(dirFh)
        args.putString(name)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_REMOVE, auth, args.toByteArray())
        val status = reply.getUInt()
        skipWccData(reply) // dir_wcc
        if (status != NfsStatus.OK) throw NfsException(status, "REMOVE($name)")
    }

    // ── RMDIR ───────────────────────────────────────────────────────────────

    /** Deletes an empty directory. Server returns NfsStatus.NOTEMPTY if it isn't. */
    fun rmdir(dirFh: NfsFileHandle, name: String) {
        val args = XdrEncoder(fh_size(dirFh) + name.length + 8)
        args.putVarOpaque(dirFh)
        args.putString(name)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_RMDIR, auth, args.toByteArray())
        val status = reply.getUInt()
        skipWccData(reply) // dir_wcc
        if (status != NfsStatus.OK) throw NfsException(status, "RMDIR($name)")
    }

    // ── RENAME ──────────────────────────────────────────────────────────────

    /** Renames/moves `fromName` (in `fromDirFh`) to `toName` (in `toDirFh`) — same handle for both when it's a plain in-place rename. */
    fun rename(fromDirFh: NfsFileHandle, fromName: String, toDirFh: NfsFileHandle, toName: String) {
        val args = XdrEncoder(fh_size(fromDirFh) + fromName.length + fh_size(toDirFh) + toName.length + 16)
        args.putVarOpaque(fromDirFh)
        args.putString(fromName)
        args.putVarOpaque(toDirFh)
        args.putString(toName)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_RENAME, auth, args.toByteArray())
        val status = reply.getUInt()
        // Both wcc_data blocks are present regardless of outcome (RFC 1813 §3.3.14) — consume in order.
        skipWccData(reply) // fromdir_wcc
        skipWccData(reply) // todir_wcc
        if (status != NfsStatus.OK) throw NfsException(status, "RENAME($fromName -> $toName)")
    }

    // ── MKDIR ───────────────────────────────────────────────────────────────

    /** Creates a directory and returns its handle. Mirrors [create]'s all-defaults sattr3 (server applies its own mode/umask). */
    fun mkdir(dirFh: NfsFileHandle, name: String): NfsFileHandle {
        val args = XdrEncoder(fh_size(dirFh) + name.length + 40)
        args.putVarOpaque(dirFh)
        args.putString(name)
        // sattr3, all six optional fields left "not set" — same as CREATE's UNCHECKED path.
        repeat(6) { args.putUInt(0L) }
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_MKDIR, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            skipWccData(reply) // dir_wcc
            throw NfsException(status, "MKDIR($name)")
        }
        val fh = parsePostOpFh3(reply)
            ?: throw NfsException(NfsStatus.SERVERFAULT, "MKDIR($name) — server did not return a file handle")
        parsePostOpAttr(reply) // obj_attributes
        skipWccData(reply)     // dir_wcc
        return fh
    }

    // ── LINK ────────────────────────────────────────────────────────────────

    /** Creates a hard link named `linkName` (in `linkDirFh`) pointing at the existing file `fh`. */
    fun link(fh: NfsFileHandle, linkDirFh: NfsFileHandle, linkName: String) {
        val args = XdrEncoder(fh_size(fh) + fh_size(linkDirFh) + linkName.length + 16)
        args.putVarOpaque(fh)
        args.putVarOpaque(linkDirFh)
        args.putString(linkName)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_LINK, auth, args.toByteArray())
        val status = reply.getUInt()
        parsePostOpAttr(reply) // file_attributes
        skipWccData(reply)     // linkdir_wcc
        if (status != NfsStatus.OK) throw NfsException(status, "LINK($linkName)")
    }

    // ── WRITE / COMMIT ─────────────────────────────────────────────────────

    /**
     * Writes one chunk at [offset] using UNSTABLE storage (server may buffer
     * in memory) for throughput, tracking the server's writeverf3 so a
     * mismatch (server rebooted mid-transfer) is detected instead of
     * silently producing a corrupt remote file. Call [commit] after the
     * final chunk to force the data to stable storage, matching the
     * WRITE(UNSTABLE)*...COMMIT pattern RFC 1813 §3.3.7/§3.3.21 describe.
     */
    fun write(fh: NfsFileHandle, offset: Long, data: ByteArray, expectedVerf: ByteArray?): ByteArray {
        val args = XdrEncoder(fh_size(fh) + data.size + 32)
        args.putVarOpaque(fh)
        args.putUHyper(offset)
        args.putUInt(data.size.toLong())
        args.putUInt(UNSTABLE)
        args.putVarOpaque(data)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_WRITE, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            skipWccData(reply)
            throw NfsException(status, "WRITE")
        }
        skipWccData(reply)
        val written = reply.getUInt()
        if (written.toInt() != data.size) {
            throw NfsException(NfsStatus.IO, "WRITE (server only accepted ${written}/${data.size} bytes)")
        }
        reply.getUInt() // committed (stable_how the server actually used)
        val verf = reply.getFixedOpaque(8)
        if (expectedVerf != null && !verf.contentEquals(expectedVerf)) {
            throw NfsException(NfsStatus.IO, "WRITE (server write-verifier changed mid-transfer — server likely restarted; restart the upload)")
        }
        return verf
    }

    /** Forces previously UNSTABLE-written data to stable storage. */
    fun commit(fh: NfsFileHandle, offset: Long, count: Long) {
        val args = XdrEncoder(fh_size(fh) + 20)
        args.putVarOpaque(fh)
        args.putUHyper(offset)
        args.putUInt(count)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_COMMIT, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            skipWccData(reply)
            throw NfsException(status, "COMMIT")
        }
        skipWccData(reply)
        reply.getFixedOpaque(8) // final writeverf3 — nothing left to compare it against at this point
    }

    // ── FSSTAT ──────────────────────────────────────────────────────────────

    fun fsStat(fh: NfsFileHandle): StoragePair {
        val args = XdrEncoder(fh.size + 8)
        args.putVarOpaque(fh)
        val reply = rpc.call(NFS_PROGRAM, NFS_VERSION, PROC_FSSTAT, auth, args.toByteArray())
        val status = reply.getUInt()
        if (status != NfsStatus.OK) {
            parsePostOpAttr(reply)
            throw NfsException(status, "FSSTAT")
        }
        parsePostOpAttr(reply)
        val totalBytes = reply.getUHyper()
        reply.getUHyper() // fbytes (free, including root-reserved space)
        val availBytes = reply.getUHyper() // abytes: free space available to this (unprivileged) client — matches statvfs "available"
        return StoragePair(freeBytes = availBytes, totalBytes = totalBytes)
    }

    data class StoragePair(val freeBytes: Long, val totalBytes: Long)

    private fun fh_size(fh: NfsFileHandle) = fh.size
}
