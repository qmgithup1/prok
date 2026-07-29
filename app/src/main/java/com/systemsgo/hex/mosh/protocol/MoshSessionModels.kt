package com.systemsgo.hex.mosh.protocol

/**
 * Everything the SSP (UDP) phase needs, parsed out of the one line
 * `mosh-server new` prints on success:
 *
 *   MOSH CONNECT 60001 5B9DizGE9WddZk60fZ+I2w
 *
 * = literal "MOSH CONNECT" | UDP port on the server | base64-ish session
 * key (mosh's own alphabet, NOT standard base64 — see
 * [MoshSessionManager.parseConnectLine] for why it isn't decoded here).
 *
 * [remoteHost] is carried alongside the parsed fields because the caller
 * (whoever opened the SSH connection) already knows it, and every future
 * consumer of this handle (eventually [com.systemsgo.hex.mosh.native.MoshBridge])
 * needs a host to open the UDP socket toward — mosh-server's stdout never
 * repeats the host itself.
 */
data class MoshConnectInfo(
    val remoteHost: String,
    val udpPort: Int,
    val sessionKey: String,
)

class MoshProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
