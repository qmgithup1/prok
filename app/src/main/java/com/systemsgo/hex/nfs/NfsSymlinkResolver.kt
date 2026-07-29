package com.systemsgo.hex.nfs

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: symlink chain resolution for NfsFileBrowser (FileTransferManager.kt).
//
// READLINK only gives you the raw target path recorded *in* one symlink —
// resolving it to something the browser can actually open (a REG file or a
// DIR) is client-side work: pick root-vs-current-directory based on whether
// the target is absolute, LOOKUP each path segment, and if the final entry
// is *itself* a symlink, repeat. Kept here as a pure function over injected
// [readLink]/[lookup] callbacks (no Nfsv3Client/OncRpcClient reference) so
// the loop-detection and absolute-vs-relative branching can be unit tested
// against a fake in-memory filesystem instead of a live NFS server — see
// NfsSymlinkResolverTest.kt.
// ─────────────────────────────────────────────────────────────────────────────

/** Max symlink hops (not path segments) before giving up — matches typical OS resolvers (Linux's own limit is 40; 8 is plenty for a phone-to-LAN file manager and fails fast on a real circular-loop misconfiguration instead of hanging). */
const val MAX_SYMLINK_DEPTH = 8

/** Thrown when a symlink chain exceeds [MAX_SYMLINK_DEPTH] hops without landing on a non-symlink — almost always a circular symlink loop (a -> b -> a), a real and fairly common misconfiguration, not just a theoretical edge case. */
class NfsSymlinkLoopException(message: String) : java.io.IOException(message)

/**
 * Resolves [fh] (a symlink, at [parentFh] in the directory tree) to its
 * final non-symlink target, following further hops if that target is also
 * a symlink, up to [MAX_SYMLINK_DEPTH].
 *
 * - Absolute targets (leading `/`) resolve from [rootFh].
 * - Relative targets resolve from the symlink's own parent directory.
 * - [readLink] returns the raw target path recorded in one symlink.
 * - [lookup] resolves a single path segment inside a directory handle,
 *   mirroring [Nfsv3Client.lookup]'s `(childHandle, childAttrs?)` shape.
 *
 * Returns the resolved (handle, attrs) pair — attrs may be null if the
 * server didn't return them, same as every other lookup-based call in this
 * client; callers should GETATTR explicitly if they need attrs guaranteed.
 */
fun resolveSymlink(
    fh: NfsFileHandle,
    parentFh: NfsFileHandle,
    rootFh: NfsFileHandle,
    readLink: (NfsFileHandle) -> String,
    lookup: (NfsFileHandle, String) -> Pair<NfsFileHandle, NfsAttrs?>,
): Pair<NfsFileHandle, NfsAttrs?> {
    var curFh = fh
    var curParent = parentFh

    for (hop in 1..MAX_SYMLINK_DEPTH) {
        val target = readLink(curFh)
        val segments = target.split('/').filter { it.isNotEmpty() }

        var walkFh = if (target.startsWith("/")) rootFh else curParent
        var walkParent = walkFh
        var walkAttrs: NfsAttrs? = null
        for (segment in segments) {
            walkParent = walkFh
            val (childFh, childAttrs) = lookup(walkFh, segment)
            walkFh = childFh
            walkAttrs = childAttrs
        }

        if (walkAttrs?.type != NfsFileType.LNK) {
            return walkFh to walkAttrs
        }
        // Still a symlink — next hop resolves relative targets against *this*
        // link's own parent directory, not the one we started at.
        curFh = walkFh
        curParent = walkParent
    }
    throw NfsSymlinkLoopException(
        "Symlink chain exceeded $MAX_SYMLINK_DEPTH hops (possible circular symlink loop)"
    )
}
