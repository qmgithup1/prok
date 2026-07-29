package com.systemsgo.hex.nfs

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: hand-rolled ONC RPC (RFC 5531) client, TCP transport only.
//
// Scope deliberately NOT implemented (documented gap, not an oversight):
//   - UDP transport. NFS/MOUNT/portmapper are all reachable over UDP too, but
//     TCP is universally supported by modern nfsd/mountd/rpcbind and avoids
//     re-implementing RPC-over-UDP retransmission/dedup semantics for a
//     mobile client that already deals with flaky Wi-Fi at the TCP layer.
//   - RPC message fragmentation for very large single fragments beyond a
//     32-bit length — not a realistic size for the arguments/results this
//     client sends (NFSv3 payloads are capped by rtmax/wtmax far below that).
//   - AUTH_GSS / RPCSEC_GSS (Kerberos) — only AUTH_SYS/AUTH_NONE are sent.
//   - "Authentication error" / prog mismatch retries: surfaced as exceptions
//     for the caller (NfsFileBrowser) to translate into a user-facing message.
//
// Record marking: every RPC message over TCP is prefixed by one or more
// 4-byte "fragment headers" — top bit = last-fragment flag, low 31 bits =
// fragment byte length. We only ever send a single fragment per call (simpler,
// and every server we need to interoperate with accepts that), but replies
// from some servers arrive in multiple fragments, so read side must
// reassemble until the last-fragment bit is set.
// ─────────────────────────────────────────────────────────────────────────────

private const val RPC_VERSION = 2L
private const val MSG_TYPE_CALL = 0L
private const val MSG_TYPE_REPLY = 1L
private const val REPLY_ACCEPTED = 0L
private const val REPLY_DENIED = 1L
private const val ACCEPT_SUCCESS = 0L
private const val AUTH_NONE = 0L
private const val AUTH_SYS = 1L

/** Thrown when the RPC layer itself reports a problem (as opposed to an NFS/MOUNT status code). */
class OncRpcException(message: String) : IOException(message)

/** AUTH_SYS (aka AUTH_UNIX, RFC 5531 §8.2) credentials sent with every call. */
data class AuthSys(
    val uid: Int,
    val gid: Int,
    val gids: IntArray = IntArray(0),
    val machineName: String = "systemsgo",
    val stamp: Long = System.currentTimeMillis() and 0xFFFFFFFFL,
)

/**
 * One TCP connection to a single ONC RPC service endpoint (portmapper on 111,
 * mountd or nfsd on whatever port they were assigned). Not thread-safe —
 * NfsFileBrowser serializes all calls through a single coroutine dispatcher,
 * matching how SmbFileBrowser/TftpFileBrowser are used elsewhere in this file.
 */
class OncRpcClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 8_000,
    private val soTimeoutMs: Int = 15_000,
) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val xidGen = AtomicInteger((System.nanoTime() and 0x7FFFFFFF).toInt())

    fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = soTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())
    }

    fun close() {
        try { socket?.close() } catch (e: Exception) { android.util.Log.d("OncRpc", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socket = null; input = null; output = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Issues one RPC call: program/version/procedure with AUTH_SYS (or
     * AUTH_NONE when [auth] is null, used only for the portmapper), writes
     * [argsBody] as the pre-encoded procedure-specific arguments, and returns
     * the decoder positioned at the start of the procedure-specific results
     * (i.e. past the RPC reply header, which this method fully validates).
     */
    fun call(program: Long, version: Long, procedure: Long, auth: AuthSys?, argsBody: ByteArray): XdrDecoder {
        val out = output ?: throw OncRpcException("Not connected")
        val xid = xidGen.incrementAndGet().toLong() and 0xFFFFFFFFL

        val enc = XdrEncoder(64 + argsBody.size)
        enc.putUInt(xid)
        enc.putUInt(MSG_TYPE_CALL)
        enc.putUInt(RPC_VERSION)
        enc.putUInt(program)
        enc.putUInt(version)
        enc.putUInt(procedure)
        writeAuth(enc, auth)
        // AUTH_NONE verifier (server ignores it for AUTH_SYS calls in practice for our purposes)
        enc.putUInt(AUTH_NONE); enc.putUInt(0L)
        val header = enc.toByteArray()

        val body = ByteArray(header.size + argsBody.size)
        System.arraycopy(header, 0, body, 0, header.size)
        System.arraycopy(argsBody, 0, body, header.size, argsBody.size)

        writeRecord(out, body)

        val replyBytes = try {
            readRecord(input ?: throw OncRpcException("Not connected"))
        } catch (e: SocketTimeoutException) {
            throw OncRpcException("RPC call timed out (program=$program proc=$procedure)")
        }
        val dec = XdrDecoder(replyBytes)

        val replyXid = dec.getUInt()
        if (replyXid != xid) throw OncRpcException("RPC XID mismatch (sent $xid, got $replyXid)")
        val msgType = dec.getUInt()
        if (msgType != MSG_TYPE_REPLY) throw OncRpcException("Expected RPC reply, got msg_type=$msgType")

        when (dec.getUInt()) {
            REPLY_ACCEPTED -> { /* fall through below */ }
            REPLY_DENIED -> {
                val rejectStat = dec.getUInt()
                throw OncRpcException(
                    if (rejectStat == 1L) "RPC call denied: auth error (uid/gid rejected by server)"
                    else "RPC call denied: RPC version mismatch"
                )
            }
            else -> throw OncRpcException("Malformed RPC reply header")
        }

        // Reply verifier (opaque_auth): flavor + variable-length body — skip it.
        dec.getUInt() // verifier flavor
        dec.getVarOpaque()

        return when (val acceptStat = dec.getUInt()) {
            ACCEPT_SUCCESS -> dec
            1L -> throw OncRpcException("RPC: program unavailable (service not registered on this port)")
            2L -> throw OncRpcException("RPC: program version mismatch")
            3L -> throw OncRpcException("RPC: procedure unavailable")
            4L -> throw OncRpcException("RPC: garbage arguments")
            5L -> throw OncRpcException("RPC: system error")
            else -> throw OncRpcException("RPC: unknown accept status $acceptStat")
        }
    }

    private fun writeAuth(enc: XdrEncoder, auth: AuthSys?) {
        if (auth == null) {
            enc.putUInt(AUTH_NONE)
            enc.putUInt(0L)
            return
        }
        val body = XdrEncoder(64)
        body.putUInt(auth.stamp)
        body.putString(auth.machineName.take(255))
        body.putUInt(auth.uid.toLong() and 0xFFFFFFFFL)
        body.putUInt(auth.gid.toLong() and 0xFFFFFFFFL)
        body.putUInt(auth.gids.size.toLong())
        for (g in auth.gids) body.putUInt(g.toLong() and 0xFFFFFFFFL)
        val bodyBytes = body.toByteArray()

        enc.putUInt(AUTH_SYS)
        enc.putUInt(bodyBytes.size.toLong())
        enc.putFixedOpaque(bodyBytes)
    }

    private fun writeRecord(out: BufferedOutputStream, body: ByteArray) {
        // Single fragment, last-fragment bit (0x80000000) set.
        val header = ByteArray(4)
        val lenWithFlag = (body.size.toLong() and 0x7FFFFFFFL) or 0x80000000L
        header[0] = ((lenWithFlag ushr 24) and 0xFF).toByte()
        header[1] = ((lenWithFlag ushr 16) and 0xFF).toByte()
        header[2] = ((lenWithFlag ushr 8) and 0xFF).toByte()
        header[3] = (lenWithFlag and 0xFF).toByte()
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun readRecord(input: BufferedInputStream): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        while (true) {
            val headerBytes = readFully(input, 4)
            val headerVal = ((headerBytes[0].toLong() and 0xFF) shl 24) or
                    ((headerBytes[1].toLong() and 0xFF) shl 16) or
                    ((headerBytes[2].toLong() and 0xFF) shl 8) or
                    (headerBytes[3].toLong() and 0xFF)
            val isLast = (headerVal and 0x80000000L) != 0L
            val fragLen = (headerVal and 0x7FFFFFFFL).toInt()
            if (fragLen > 32 * 1024 * 1024) throw OncRpcException("RPC fragment too large: $fragLen")
            val frag = readFully(input, fragLen)
            chunks.add(frag)
            total += fragLen
            if (isLast) break
        }
        if (chunks.size == 1) return chunks[0]
        val out = ByteArray(total)
        var off = 0
        for (c in chunks) { System.arraycopy(c, 0, out, off, c.size); off += c.size }
        return out
    }
}
