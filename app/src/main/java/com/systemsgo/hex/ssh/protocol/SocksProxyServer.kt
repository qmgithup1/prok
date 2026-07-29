package com.systemsgo.hex.ssh.protocol

import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DYN-PROXY FIX: JSch — including the `com.github.mwiede` fork this project
 * depends on — never implemented OpenSSH's `-D` dynamic/SOCKS forwarding.
 * `Session.setPortForwardingL`/`setPortForwardingR` (local/remote, `-L`/`-R`)
 * exist; `setPortForwardingD`/`delPortForwardingD` (dynamic, `-D`) do not —
 * they were never part of JSch's API, in any released version. Code that
 * called them could never have compiled against a real JSch artifact.
 *
 * This class supplies the missing behaviour: a small local SOCKS4/4a/5
 * server (no authentication, CONNECT only — matching what OpenSSH's `-D`
 * itself offers) that hands every accepted connection to a fresh
 * "direct-tcpip" channel on the already-authenticated [session], exactly the
 * mechanism `ssh -D` uses under the hood. Any SOCKS-capable client pointed at
 * `127.0.0.1:<localPort>` gets its traffic relayed through the SSH session to
 * whatever destination it asks for.
 */
class SocksProxyServer(
    private val session: Session,
    private val bindAddress: String = "127.0.0.1",
) {
    companion object {
        private const val TAG = "SocksProxyServer"
        private const val SOCKS5 = 0x05
        private const val SOCKS4 = 0x04
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var executor: ExecutorService? = null
    private val activeSockets = CopyOnWriteArraySet<Socket>()
    private val activeChannels = CopyOnWriteArraySet<Channel>()

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Binds the local SOCKS listener and starts accepting connections in the
     * background. Returns the actual local port bound (== [requestedPort]
     * unless it was 0, in which case an ephemeral port is chosen).
     */
    @Throws(IOException::class)
    fun start(requestedPort: Int): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, requestedPort))
        serverSocket = ss
        running = true
        val pool = Executors.newCachedThreadPool()
        executor = pool
        pool.execute { acceptLoop(ss) }
        return ss.localPort
    }

    /** Stops accepting new connections and tears down everything already active. */
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        activeSockets.toList().forEach { s ->
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        activeSockets.clear()

        activeChannels.toList().forEach { ch ->
            try {
                ch.disconnect()
            } catch (_: Exception) {
            }
        }
        activeChannels.clear()

        executor?.shutdownNow()
        executor = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "SOCKS accept loop stopped: ${e.javaClass.simpleName}")
                return
            }
            activeSockets.add(client)
            executor?.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val target = when (val version = input.read()) {
                SOCKS5 -> negotiateSocks5(input, output)
                SOCKS4 -> negotiateSocks4(input, output)
                else -> {
                    Log.w(TAG, "Unsupported SOCKS version byte: $version")
                    null
                }
            } ?: return

            val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(target.first)
            ch.setPort(target.second)
            activeChannels.add(ch)
            ch.connect(15_000)

            relay(client, input, output, ch)
        } catch (e: Exception) {
            Log.w(TAG, "SOCKS client session error: ${e.javaClass.simpleName}")
        } finally {
            channel?.let { activeChannels.remove(it) }
            activeSockets.remove(client)
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Bidirectionally pipes bytes between the SOCKS client socket and the SSH channel. */
    private fun relay(client: Socket, clientIn: InputStream, clientOut: OutputStream, channel: ChannelDirectTCPIP) {
        val channelIn = channel.inputStream
        val channelOut = channel.outputStream

        val upstream = Thread({
            try {
                copyStream(clientIn, channelOut)
            } catch (_: Exception) {
            } finally {
                try {
                    channel.disconnect()
                } catch (_: Exception) {
                }
            }
        }, "socks-upstream")

        upstream.isDaemon = true
        upstream.start()

        try {
            copyStream(channelIn, clientOut)
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            try {
                channel.disconnect()
            } catch (_: Exception) {
            }
        }

        try {
            upstream.join(2_000)
        } catch (_: InterruptedException) {
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    /**
     * Minimal SOCKS5 handshake (RFC 1928): no-auth only, CONNECT command
     * only, IPv4/domain/IPv6 address types. Returns the requested
     * (host, port) destination, or null if the handshake failed/was
     * unsupported (in which case the caller should just close the socket).
     */
    private fun negotiateSocks5(input: InputStream, output: OutputStream): Pair<String, Int>? {
        val nMethods = input.read()
        if (nMethods < 0) return null
        val methods = ByteArray(nMethods)
        readFully(input, methods)
        // We only ever offer/accept "no authentication required" (0x00),
        // matching what ssh -D's built-in SOCKS server supports.
        output.write(byteArrayOf(SOCKS5.toByte(), 0x00))
        output.flush()

        val header = ByteArray(4)
        readFully(input, header)
        val cmd = header[1].toInt()
        val atyp = header[3].toInt()

        if (cmd != 0x01) { // only CONNECT is supported
            writeSocks5Reply(output, 0x07) // command not supported
            return null
        }

        val host: String
        when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                readFully(input, addr)
                host = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { // domain name
                val len = input.read()
                if (len < 0) return null
                val addr = ByteArray(len)
                readFully(input, addr)
                host = String(addr, Charsets.US_ASCII)
            }
            0x04 -> { // IPv6
                val addr = ByteArray(16)
                readFully(input, addr)
                host = java.net.InetAddress.getByAddress(addr).hostAddress ?: run {
                    writeSocks5Reply(output, 0x08)
                    return null
                }
            }
            else -> {
                writeSocks5Reply(output, 0x08) // address type not supported
                return null
            }
        }

        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        writeSocks5Reply(output, 0x00) // succeeded
        return host to port
    }

    private fun writeSocks5Reply(output: OutputStream, replyCode: Int) {
        // VER REP RSV ATYP BND.ADDR(0.0.0.0) BND.PORT(0) — bind address is not
        // meaningful here since we never actually bind a distinct relay socket.
        output.write(byteArrayOf(SOCKS5.toByte(), replyCode.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    /**
     * Minimal SOCKS4/4a handshake: CONNECT command only, no authentication
     * beyond the (ignored) USERID field. Supports SOCKS4a's "invalid IP,
     * followed by a domain name" convention for DNS resolution on the SSH
     * server side rather than locally.
     */
    private fun negotiateSocks4(input: InputStream, output: OutputStream): Pair<String, Int>? {
        val cmd = input.read()
        if (cmd != 0x01) { // only CONNECT is supported
            writeSocks4Reply(output, 0x5B)
            return null
        }

        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val ipBytes = ByteArray(4)
        readFully(input, ipBytes)

        // USERID, null-terminated — read and discard.
        while (input.read().let { it != 0 && it != -1 }) { /* discard */ }

        val isSocks4a = ipBytes[0] == 0.toByte() && ipBytes[1] == 0.toByte() &&
            ipBytes[2] == 0.toByte() && ipBytes[3] != 0.toByte()

        val host: String = if (isSocks4a) {
            val domain = StringBuilder()
            while (true) {
                val b = input.read()
                if (b <= 0) break
                domain.append(b.toChar())
            }
            domain.toString()
        } else {
            ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }

        writeSocks4Reply(output, 0x5A) // request granted
        return host to port
    }

    private fun writeSocks4Reply(output: OutputStream, status: Int) {
        // VN(0) CD BND.PORT(0) BND.ADDR(0.0.0.0)
        output.write(byteArrayOf(0x00, status.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Unexpected end of stream during SOCKS handshake")
            offset += read
        }
    }
}
