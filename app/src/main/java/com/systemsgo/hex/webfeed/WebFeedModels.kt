package com.systemsgo.hex.webfeed

/**
 * RD-WEB-FEED FEATURE: whether a published [WebFeedResource] launches a
 * single program (MS-RDPERP RemoteApp/RAIL) or a full remote desktop.
 * Mirrors the feed XML's Resource@Type attribute ("RemoteApp" / "Desktop").
 */
enum class WebFeedResourceType { REMOTE_APP, DESKTOP }

/**
 * One published item from an RD Web Access feed (MS-TSWP ResourceCollection
 * XML) — e.g. one RemoteApp ("Paint") or one full desktop collection. Holds
 * only what's needed to show it in a list and fetch its actual connection
 * settings on demand; the full settings (server, gateway, program, ...) only
 * get resolved when the user taps to add it, by downloading [resourceFileUrl]
 * and running it through the existing [com.systemsgo.hex.util.RdpFileParser]
 * — the same parser already used for plain .rdp file import, since a feed's
 * per-resource file is a normal .rdp file.
 */
data class WebFeedResource(
    /** The feed's stable identifier for this resource (Resource@Alias). Used to
     *  detect "already imported" / "still published" across refreshes — see
     *  [com.systemsgo.hex.data.model.RdpProfile.webFeedAlias]. */
    val alias: String,
    val title: String,
    val type: WebFeedResourceType,
    /** Absolute URL to this resource's icon (PNG), or null if the feed omitted one. */
    val iconUrl: String? = null,
    /** Absolute URL to fetch this resource's server-side .rdp file from. */
    val resourceFileUrl: String,
    /** Name of the RDS server/farm hosting this resource (TerminalServerRef), display-only. */
    val terminalServerName: String = "",
)

/** Outcome of fetching and parsing a feed's ResourceCollection XML. */
sealed class WebFeedFetchResult {
    data class Success(val resources: List<WebFeedResource>, val publisherName: String) : WebFeedFetchResult()
    /** Server responded 401, or redirected to an HTML login page instead of XML —
     *  the supplied credentials were rejected (or Basic auth isn't enabled on the
     *  server's Feed endpoint at all — see RdWebFeedClient's doc comment). */
    object AuthRequired : WebFeedFetchResult()
    data class Error(val message: String) : WebFeedFetchResult()
}
