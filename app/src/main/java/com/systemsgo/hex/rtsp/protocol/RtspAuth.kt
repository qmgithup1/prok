package com.systemsgo.hex.rtsp.protocol

import android.util.Base64
import java.security.MessageDigest

/**
 * Builds the `Authorization` header for an RTSP request once a 401 response
 * has revealed the server's `WWW-Authenticate` challenge — Basic (rare, but
 * some cheap cameras still only support it) or Digest (RFC 2617, what most
 * IP cameras/NVRs actually require). RTSP reuses HTTP's auth scheme
 * verbatim (RFC 2326 §21) but with the request method/URI being e.g.
 * "DESCRIBE"/"SETUP"/"PLAY" instead of "GET".
 */
object RtspAuth {

    /** Parsed out of a `WWW-Authenticate: Digest realm="...", nonce="..."` header. */
    data class DigestChallenge(val realm: String, val nonce: String, val qop: String?, val opaque: String?)

    fun parseChallenge(wwwAuthenticate: String): DigestChallenge? {
        if (!wwwAuthenticate.trim().startsWith("Digest", ignoreCase = true)) return null
        fun extract(field: String): String? =
            Regex("$field=\"([^\"]*)\"").find(wwwAuthenticate)?.groupValues?.get(1)
        val realm = extract("realm") ?: return null
        val nonce = extract("nonce") ?: return null
        return DigestChallenge(realm = realm, nonce = nonce, qop = extract("qop"), opaque = extract("opaque"))
    }

    fun isBasicChallenge(wwwAuthenticate: String): Boolean =
        wwwAuthenticate.trim().startsWith("Basic", ignoreCase = true)

    fun basicAuthorizationHeader(username: String, password: String): String {
        val token = Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    /**
     * @param method the RTSP request method this header will be attached to
     *   (DESCRIBE/SETUP/PLAY/...) — digest auth is computed per-request
     *   since the method is part of HA2, unlike Basic which is static for
     *   the whole session.
     * @param uri the RTSP URL of that same request (the request-URI, not just the path).
     */
    fun digestAuthorizationHeader(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: DigestChallenge,
        nonceCount: Int = 1,
    ): String {
        fun md5(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")

        return if (challenge.qop != null) {
            val nc = "%08x".format(nonceCount)
            val cnonce = md5(System.nanoTime().toString()).take(8)
            val response = md5("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
            buildString {
                append("Digest username=\"$username\", realm=\"${challenge.realm}\", ")
                append("nonce=\"${challenge.nonce}\", uri=\"$uri\", response=\"$response\", ")
                append("qop=${challenge.qop}, nc=$nc, cnonce=\"$cnonce\"")
                if (challenge.opaque != null) append(", opaque=\"${challenge.opaque}\"")
            }
        } else {
            val response = md5("$ha1:${challenge.nonce}:$ha2")
            buildString {
                append("Digest username=\"$username\", realm=\"${challenge.realm}\", ")
                append("nonce=\"${challenge.nonce}\", uri=\"$uri\", response=\"$response\"")
                if (challenge.opaque != null) append(", opaque=\"${challenge.opaque}\"")
            }
        }
    }
}
