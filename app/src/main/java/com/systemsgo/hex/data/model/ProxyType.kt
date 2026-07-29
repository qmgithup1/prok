package com.systemsgo.hex.data.model

/**
 * OUTBOUND-PROXY FEATURE: pure-Kotlin, Room-persisted enum for [RdpProfile]/
 * [RdpCredentials].proxyType — same shape as [CodecPreference]: this app's
 * data/UI layer never references `AFreeRdpBridge.ProxyType` (the native-JNI
 * layer's own enum) directly, so the model layer has no compile-time
 * dependency on `com.systemsgo.hex.rdp.native`. RdpRemoteAdapter's
 * `toBridgeProxyType()` maps this onto `AFreeRdpBridge.ProxyType` right
 * before the native `connect()` call, the same way `toBridgeCodecPreference()`
 * already does for [CodecPreference].
 *
 * Ordinal order intentionally mirrors `AFreeRdpBridge.ProxyType` /
 * FreeRDP's own PROXY_TYPE (NONE=0, HTTP=1, SOCKS=2, HTTPS=3 — see below),
 * but `toBridgeProxyType()` maps *by name*, not by ordinal, so this is a
 * readability convention, not a correctness requirement — unlike
 * `AFreeRdpBridge.ProxyType`'s own doc comment, which warns its ordinal IS
 * read directly by systemsgo_jni.c.
 *
 * HTTPS-PROXY FEATURE: HTTPS is a genuinely different wire behaviour from
 * HTTP, not just a UI label — see `AFreeRdpBridge.ProxyType`'s doc comment
 * for why stock FreeRDP has no such value and what the `0002-proxy-https`
 * patch under `freerdp-patches/` adds to support it (TLS handshake to the
 * proxy itself before the plaintext HTTP CONNECT tunnel request).
 */
enum class ProxyType {
    NONE,
    HTTP,
    SOCKS,
    HTTPS;

    companion object {
        fun fromName(value: String): ProxyType =
            entries.firstOrNull { it.name == value } ?: SOCKS
    }
}
