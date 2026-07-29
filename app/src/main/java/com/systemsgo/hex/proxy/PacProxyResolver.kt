package com.systemsgo.hex.proxy

import android.util.Log
import com.systemsgo.hex.data.model.ProxyType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.util.PacEvaluationResult
import com.systemsgo.hex.util.PacFetchResult
import com.systemsgo.hex.util.PacFileParser
import com.systemsgo.hex.util.PacProxyDirective
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PAC-SUPPORT FEATURE (Part 2/n): the glue between [PacFileParser] (Part 1 —
 * pure fetch + execute a .pac script, no network-connection logic at all)
 * and an actual RDP/VNC/SSH connect attempt.
 *
 * Mirrors the exact shape [com.systemsgo.hex.auth.GatewayTokenProvider]
 * already uses for the Gateway bearer token: `RemoteSessionFactory.create()`
 * is a plain synchronous function, so it cannot itself run the suspend
 * fetch/eval this class needs. Callers resolve a profile's *effective*
 * outbound proxy — a [Resolved] — once, right before calling `create()`,
 * exactly the same place gatewayBearerToken/effectivePerformanceLevel are
 * already resolved (see RdpSessionActivity.resolveGatewayBearerTokenOrAbort
 * for the established pattern this follows).
 *
 * ── Priority: pacUrl vs. the static proxyEnabled/proxyType/proxyHost/... fields ──
 * A profile can have BOTH [RdpProfile.pacUrl] and the static proxy* fields
 * filled in at once — the profile form doesn't force them to be mutually
 * exclusive (e.g. a user who typed a manual proxy first, then later added a
 * PAC URL without clearing the old fields). The rule, applied in [resolve]:
 *
 *  1. [RdpProfile.pacUrl] is blank              → the static fields win,
 *     completely unchanged. This is every profile that predates this
 *     feature, and every profile where the user simply never used PAC —
 *     zero behavior change.
 *  2. pacUrl is set AND resolves successfully   → the PAC result wins
 *     OUTRIGHT, even when proxyEnabled/proxyHost/... are also set. PAC is
 *     the more specific, more current source of truth (evaluated fresh,
 *     per-destination, on every single connect attempt) — a stale manual
 *     proxy field left over in the same profile must never silently
 *     override it.
 *  3. pacUrl is set BUT fetch/eval fails        → falls back to the static
 *     fields IF [RdpProfile.proxyEnabled] is true (the user's manual config
 *     becomes a safety net for a temporarily-unreachable/broken PAC
 *     server), otherwise falls back to [Resolved.Direct] (connect straight
 *     through, no proxy at all).
 *
 * IMPORTANT: in NEITHER failure sub-case of (3) does a broken PAC file fail
 * the whole connection attempt — this deliberately matches how every
 * mainstream browser already handles a PAC file that can't be fetched or
 * throws: it degrades to DIRECT (or a configured fallback) rather than
 * blocking all network access. Every failure/fallback path below is logged
 * via [Log.w] under [TAG] so this is *visible* in logcat instead of a silent
 * behavior change — a caller that instead needs "fail closed" (e.g. a
 * compliance requirement that traffic must never leave via a fallback path
 * when the intended egress proxy is unreachable) should watch for that log
 * line / add its own explicit check rather than relying on [Resolved] alone,
 * since by design [Resolved] never distinguishes "PAC wasn't configured"
 * from "PAC failed and fell back" — both collapse to the same outcome.
 */
@Singleton
class PacProxyResolver @Inject constructor(
    private val pacFileParser: PacFileParser,
) {

    /**
     * The effective, already-decided outbound proxy for one connect
     * attempt. [ProxyType.HTTPS] can only ever appear here via the static
     * fallback path (case 3 above) or case 1 — a PAC script's own
     * `FindProxyForURL` return grammar has no HTTPS keyword, only
     * `PROXY`/`SOCKS`/`DIRECT` (see [PacFileParser.parseSingleDirective]),
     * so a *successful* PAC resolution (case 2) only ever produces
     * [ProxyType.HTTP], [ProxyType.SOCKS], or [Direct].
     */
    sealed interface Resolved {
        /** No proxy — connect straight to the destination. */
        data object Direct : Resolved

        data class UseProxy(
            val type: ProxyType,
            val host: String,
            val port: Int,
            // PAC's directive grammar carries no credentials at all — these
            // are only ever populated when [Resolved] came from the static
            // proxyUsername/proxyPassword fallback (case 1 or 3 above), not
            // from a genuinely-resolved PAC directive (case 2).
            val username: String = "",
            val password: String = "",
        ) : Resolved
    }

    /**
     * Resolves [profile]'s effective outbound proxy for connecting to
     * [targetHost]:[targetPort] — the actual next-hop TCP destination this
     * device dials out to. For a direct (non-tunneled) connection that's
     * simply [RdpProfile.host]/[RdpProfile.port]; for an SSH-tunneled
     * RDP/VNC/Telnet profile it's the FIRST tunnel hop's host/port instead
     * (see [outboundDialTarget] below) — every later hop in the chain is
     * reached through the previous hop's own forwarded connection, never a
     * fresh dial from this device, so it is never itself subject to an
     * outbound-proxy decision.
     */
    suspend fun resolve(profile: RdpProfile, targetHost: String, targetPort: Int): Resolved {
        if (profile.pacUrl.isBlank()) {
            return profile.toStaticResolved()
        }

        // FindProxyForURL's `url` argument is conventionally a full
        // scheme://host:port/ URL — real-world PAC scripts overwhelmingly
        // only inspect the separate `host` argument (see MDN's PAC
        // reference), so the scheme/path here are placeholders, not a claim
        // that this is actually an HTTPS request.
        val targetUrl = "https://$targetHost:$targetPort/"

        val fetch = pacFileParser.fetchPacScript(profile.pacUrl)
        val script = when (fetch) {
            is PacFetchResult.Success -> fetch.script
            is PacFetchResult.HttpError ->
                return failOrFallback(profile, "PAC fetch returned HTTP ${fetch.code}")
            is PacFetchResult.NetworkError ->
                return failOrFallback(profile, "PAC fetch failed: ${fetch.message}")
        }

        val evaluation = pacFileParser.findProxyForUrl(script, targetUrl, targetHost)
        val directives = when (evaluation) {
            is PacEvaluationResult.Success -> evaluation.directives
            is PacEvaluationResult.Error ->
                return failOrFallback(profile, "PAC evaluation failed: ${evaluation.message}")
        }

        // The PAC standard says: try each returned directive in order until
        // one connects. This resolver makes a single up-front choice rather
        // than a retry chain (SshClient/AFreeRdpBridge's connect() calls
        // aren't wired to retry through a directive list today), so it picks
        // the first *usable* entry — DIRECT if that's first (the script
        // explicitly wants no proxy for this host), else the first PROXY/
        // SOCKS entry, skipping any Unrecognized ones.
        for (directive in directives) {
            when (directive) {
                is PacProxyDirective.Direct -> return Resolved.Direct
                is PacProxyDirective.Proxy -> return Resolved.UseProxy(ProxyType.HTTP, directive.host, directive.port)
                is PacProxyDirective.Socks -> return Resolved.UseProxy(ProxyType.SOCKS, directive.host, directive.port)
                is PacProxyDirective.Unrecognized -> continue
            }
        }
        // Every entry was Unrecognized — script returned something malformed
        // for every clause. Same failure handling as a fetch/eval error.
        return failOrFallback(profile, "PAC script returned no usable PROXY/SOCKS/DIRECT directive")
    }

    /**
     * Which host/port this device actually dials out to for [profile] — the
     * right target to resolve a proxy *for*. Mirrors
     * [RdpProfile.effectiveSshTunnelHops]' own "first hop is the only real
     * outbound dial" reasoning: everything after hop 0 is reached through
     * hop 0's own forwarded port, not a fresh socket from this device.
     */
    fun outboundDialTarget(profile: RdpProfile): Pair<String, Int> {
        val hops = profile.effectiveSshTunnelHops
        return if (profile.sshTunnelEnabled && hops.isNotEmpty()) {
            hops.first().host to hops.first().port
        } else {
            profile.host to profile.port
        }
    }

    private fun failOrFallback(profile: RdpProfile, reason: String): Resolved {
        val fallbackDescription = if (profile.proxyEnabled) "static proxy fields" else "DIRECT (no proxy)"
        Log.w(TAG, "$reason — profile '${profile.name}' (pacUrl=${profile.pacUrl}); falling back to $fallbackDescription")
        return profile.toStaticResolved()
    }

    private fun RdpProfile.toStaticResolved(): Resolved =
        if (proxyEnabled) {
            Resolved.UseProxy(proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword)
        } else {
            Resolved.Direct
        }

    private companion object {
        const val TAG = "PacProxyResolver"
    }
}
