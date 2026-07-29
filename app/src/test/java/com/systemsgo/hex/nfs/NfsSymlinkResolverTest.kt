package com.systemsgo.hex.nfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plain JVM tests for [resolveSymlink] — no network, no Android framework
 * dependency, matching Asn1Test's style. resolveSymlink() only depends on
 * injected [readLink]/[lookup] callbacks, so we exercise it against a tiny
 * fake in-memory filesystem instead of a live NFS server/OncRpcClient.
 */
class NfsSymlinkResolverTest {

    // ── fake filesystem ─────────────────────────────────────────────────────

    private sealed class Node {
        data class Dir(val children: MutableMap<String, String> = linkedMapOf()) : Node() // name -> child id
        data class Reg(val size: Long = 0L) : Node()
        data class Link(val target: String) : Node()
    }

    private class FakeFs {
        private val nodes = linkedMapOf<String, Node>("root" to Node.Dir())
        private val parentOf = linkedMapOf<String, String>("root" to "root")

        fun handle(id: String): NfsFileHandle = id.toByteArray(Charsets.UTF_8)
        private fun idOf(fh: NfsFileHandle): String = String(fh, Charsets.UTF_8)

        val rootFh: NfsFileHandle get() = handle("root")

        fun mkdir(parentId: String, name: String): String {
            val id = "$parentId/$name"
            (nodes[parentId] as Node.Dir).children[name] = id
            nodes[id] = Node.Dir()
            parentOf[id] = parentId
            return id
        }

        fun mkfile(parentId: String, name: String, size: Long = 0L): String {
            val id = "$parentId/$name"
            (nodes[parentId] as Node.Dir).children[name] = id
            nodes[id] = Node.Reg(size)
            return id
        }

        fun mklink(parentId: String, name: String, target: String): String {
            val id = "$parentId/$name"
            (nodes[parentId] as Node.Dir).children[name] = id
            nodes[id] = Node.Link(target)
            return id
        }

        /** Same (childHandle, attrs?) shape as Nfsv3Client.lookup(). Handles "." and ".." like a real NFS server (they're real directory entries, per RFC 1813). */
        fun lookup(dirFh: NfsFileHandle, name: String): Pair<NfsFileHandle, NfsAttrs?> {
            val dirId = idOf(dirFh)
            nodes[dirId] as? Node.Dir ?: throw NfsException(NfsStatus.NOTDIR, "LOOKUP($name)")
            val childId = when (name) {
                "." -> dirId
                ".." -> parentOf[dirId] ?: dirId
                else -> (nodes[dirId] as Node.Dir).children[name] ?: throw NfsException(NfsStatus.NOENT, "LOOKUP($name)")
            }
            return handle(childId) to attrsOf(childId)
        }

        fun readLink(fh: NfsFileHandle): String {
            val id = idOf(fh)
            val node = nodes[id] as? Node.Link ?: throw NfsException(NfsStatus.IO, "READLINK")
            return node.target
        }

        private fun attrsOf(id: String): NfsAttrs = when (val n = nodes[id]) {
            is Node.Dir -> NfsAttrs(NfsFileType.DIR, mode = 0, size = 0L, mtimeSeconds = 0L)
            is Node.Reg -> NfsAttrs(NfsFileType.REG, mode = 0, size = n.size, mtimeSeconds = 0L)
            is Node.Link -> NfsAttrs(NfsFileType.LNK, mode = 0, size = 0L, mtimeSeconds = 0L)
            null -> throw NfsException(NfsStatus.NOENT, "GETATTR")
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `relative symlink resolves within its own directory`() {
        val fs = FakeFs()
        val dirId = fs.mkdir("root", "docs")
        fs.mkfile(dirId, "report.txt", size = 42L)
        val linkId = fs.mklink(dirId, "latest", "report.txt")

        val (resolvedFh, attrs) = resolveSymlink(
            fh = fs.handle(linkId),
            parentFh = fs.handle(dirId),
            rootFh = fs.rootFh,
            readLink = fs::readLink,
            lookup = fs::lookup,
        )

        assertEquals(NfsFileType.REG, attrs?.type)
        assertEquals(42L, attrs?.size)
        assertEquals(fs.handle("$dirId/report.txt"), resolvedFh)
    }

    @Test
    fun `absolute symlink resolves from root regardless of parent directory`() {
        val fs = FakeFs()
        val sharedDirId = fs.mkdir("root", "shared")
        fs.mkfile(sharedDirId, "logo.png", size = 1024L)
        val deepDirId = fs.mkdir(fs.mkdir("root", "a"), "b")
        val linkId = fs.mklink(deepDirId, "logo-link", "/shared/logo.png")

        val (_, attrs) = resolveSymlink(
            fh = fs.handle(linkId),
            parentFh = fs.handle(deepDirId),
            rootFh = fs.rootFh,
            readLink = fs::readLink,
            lookup = fs::lookup,
        )

        assertEquals(NfsFileType.REG, attrs?.type)
        assertEquals(1024L, attrs?.size)
    }

    @Test
    fun `chained symlinks follow through to the final regular file`() {
        val fs = FakeFs()
        val dirId = fs.mkdir("root", "chain")
        fs.mkfile(dirId, "real.txt", size = 7L)
        fs.mklink(dirId, "link2", "real.txt")
        val link1Id = fs.mklink(dirId, "link1", "link2")

        val (_, attrs) = resolveSymlink(
            fh = fs.handle(link1Id),
            parentFh = fs.handle(dirId),
            rootFh = fs.rootFh,
            readLink = fs::readLink,
            lookup = fs::lookup,
        )

        assertEquals(NfsFileType.REG, attrs?.type)
        assertEquals(7L, attrs?.size)
    }

    @Test
    fun `relative symlink chain across subdirectories resolves each hop from its own parent`() {
        // root/a/link_to_b_target -> "../b/target"   (relative, resolves from root/a)
        // root/b/target -> "../c/final"               (relative, resolves from root/b — NOT root/a)
        // root/c/final = regular file
        val fs = FakeFs()
        val aId = fs.mkdir("root", "a")
        val bId = fs.mkdir("root", "b")
        val cId = fs.mkdir("root", "c")
        fs.mkfile(cId, "final", size = 99L)
        fs.mklink(bId, "target", "../c/final")
        val entryLinkId = fs.mklink(aId, "link_to_b_target", "../b/target")

        val (_, attrs) = resolveSymlink(
            fh = fs.handle(entryLinkId),
            parentFh = fs.handle(aId),
            rootFh = fs.rootFh,
            readLink = fs::readLink,
            lookup = fs::lookup,
        )

        assertEquals(NfsFileType.REG, attrs?.type)
        assertEquals(99L, attrs?.size)
    }

    @Test
    fun `circular symlink loop throws instead of hanging`() {
        val fs = FakeFs()
        val dirId = fs.mkdir("root", "loop")
        fs.mklink(dirId, "a", "b")
        fs.mklink(dirId, "b", "a")

        try {
            resolveSymlink(
                fh = fs.handle("$dirId/a"),
                parentFh = fs.handle(dirId),
                rootFh = fs.rootFh,
                readLink = fs::readLink,
                lookup = fs::lookup,
            )
            fail("Expected NfsSymlinkLoopException for a circular a -> b -> a loop")
        } catch (e: NfsSymlinkLoopException) {
            assertTrue(e.message?.contains("$MAX_SYMLINK_DEPTH") == true)
        }
    }

    @Test
    fun `self-referential symlink is a one-node loop and still throws`() {
        val fs = FakeFs()
        val dirId = fs.mkdir("root", "selfloop")
        fs.mklink(dirId, "self", "self")

        try {
            resolveSymlink(
                fh = fs.handle("$dirId/self"),
                parentFh = fs.handle(dirId),
                rootFh = fs.rootFh,
                readLink = fs::readLink,
                lookup = fs::lookup,
            )
            fail("Expected NfsSymlinkLoopException for a -> a self-reference")
        } catch (e: NfsSymlinkLoopException) {
            // expected
        }
    }

    @Test
    fun `symlink resolving to a directory is returned, not an error`() {
        val fs = FakeFs()
        fs.mkdir("root", "real_dir")
        val linkId = fs.mklink("root", "dir_link", "real_dir")

        val (_, attrs) = resolveSymlink(
            fh = fs.handle(linkId),
            parentFh = fs.rootFh,
            rootFh = fs.rootFh,
            readLink = fs::readLink,
            lookup = fs::lookup,
        )

        assertEquals(NfsFileType.DIR, attrs?.type)
    }

    @Test
    fun `broken symlink propagates NOENT rather than being silently swallowed`() {
        val fs = FakeFs()
        val linkId = fs.mklink("root", "dangling", "does_not_exist.txt")

        try {
            resolveSymlink(
                fh = fs.handle(linkId),
                parentFh = fs.rootFh,
                rootFh = fs.rootFh,
                readLink = fs::readLink,
                lookup = fs::lookup,
            )
            fail("Expected NfsException(NOENT) for a dangling symlink target")
        } catch (e: NfsException) {
            assertEquals(NfsStatus.NOENT, e.status)
        }
    }
}
