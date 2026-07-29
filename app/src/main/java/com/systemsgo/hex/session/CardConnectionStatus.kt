package com.systemsgo.hex.session

import com.systemsgo.hex.data.model.ConnectionState

/**
 * CONNECTION-STATUS-INDICATOR FEATURE
 *
 * The seven states shown by the small live status badge on every connection
 * card (see [com.systemsgo.hex.ui.components.SessionStatusIndicator]).
 *
 * Deliberately a distinct type from [ConnectionState] rather than reusing it
 * directly: [ConnectionState] is the *session-tab* state machine (only
 * meaningful while a tab exists), while a card also needs to represent
 * "never connected" / "tab closed after a failure, but here's why" — which
 * live outside any single tab's lifetime. [resolveCardStatus] is the one
 * place that reconciles the two.
 */
enum class CardConnectionStatus {
    OFFLINE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_REQUIRED,
    FAILED,
    SUSPENDED
}

/**
 * Everything a card needs to render its status badge for one profile.
 *
 * @param status the badge state to show.
 * @param connectedSinceMillis set only when [status] is [CardConnectionStatus.CONNECTED] —
 *   epoch-millis the current unbroken connected stretch began, used to
 *   render the "00:15:42" duration text.
 * @param reasonText human-readable detail — the failure reason for [CardConnectionStatus.FAILED]/
 *   [CardConnectionStatus.AUTH_REQUIRED], or blank otherwise. Shown in the tooltip and the
 *   long-press detail popup.
 * @param reconnectCount how many times the current/most-recent tab has
 *   entered RECONNECTING — shown only in the long-press detail popup.
 * @param tab the live [SessionTab] backing this status, if any (null once a
 *   failed attempt's tab has been closed and only the failure cache remains).
 */
data class CardStatusInfo(
    val status: CardConnectionStatus,
    val connectedSinceMillis: Long? = null,
    val reasonText: String = "",
    val reconnectCount: Int = 0,
    val tab: SessionTab? = null
) {
    companion object {
        val Offline = CardStatusInfo(CardConnectionStatus.OFFLINE)
    }
}

/**
 * Pure mapping function — no Context/Compose dependency, easy to unit test.
 *
 * @param tab the live tab for this profile from [SessionTabManager.tabs], if any.
 * @param failure the cached last-failure entry for this profile from
 *   [SessionTabManager.lastFailures], if any (only consulted when there's no
 *   live tab, or the live tab itself just errored — see below).
 * @param authFailureHint the resolved string for `R.string.session_tab_auth_failed`
 *   — the one existing hint RdpSessionActivity already tags specifically as
 *   an authentication failure (as opposed to any other error). Comparing
 *   against it is how a generic [ConnectionState.ERROR] gets classified as
 *   [CardConnectionStatus.AUTH_REQUIRED] vs [CardConnectionStatus.FAILED] today, ahead of any
 *   protocol client emitting [ConnectionState.AUTH_REQUIRED] directly.
 */
fun resolveCardStatus(
    tab: SessionTab?,
    failure: SessionTabManager.SessionFailure?,
    authFailureHint: String
): CardStatusInfo {
    if (tab != null) {
        return when (tab.state) {
            ConnectionState.CONNECTING    -> CardStatusInfo(CardConnectionStatus.CONNECTING, tab = tab)
            ConnectionState.RECONNECTING  -> CardStatusInfo(
                CardConnectionStatus.RECONNECTING,
                reasonText     = tab.statusHint,
                reconnectCount = tab.reconnectCount,
                tab            = tab
            )
            ConnectionState.CONNECTED     -> CardStatusInfo(
                CardConnectionStatus.CONNECTED,
                connectedSinceMillis = tab.connectedAtMillis,
                reconnectCount       = tab.reconnectCount,
                tab                  = tab
            )
            ConnectionState.AUTH_REQUIRED -> CardStatusInfo(
                CardConnectionStatus.AUTH_REQUIRED,
                reasonText = tab.statusHint,
                tab        = tab
            )
            ConnectionState.SUSPENDED     -> CardStatusInfo(CardConnectionStatus.SUSPENDED, tab = tab)
            ConnectionState.ERROR         -> if (tab.statusHint == authFailureHint) {
                CardStatusInfo(CardConnectionStatus.AUTH_REQUIRED, reasonText = tab.statusHint, tab = tab)
            } else {
                CardStatusInfo(
                    CardConnectionStatus.FAILED,
                    reasonText     = tab.statusHint,
                    reconnectCount = tab.reconnectCount,
                    tab            = tab
                )
            }
            ConnectionState.DISCONNECTED  -> CardStatusInfo.Offline
        }
    }
    // No live tab (either never connected, or the tab was closed right after
    // a failed attempt — see SessionTabManager.lastFailures' doc comment).
    if (failure != null) {
        val isAuth = failure.reason == authFailureHint
        return CardStatusInfo(
            status     = if (isAuth) CardConnectionStatus.AUTH_REQUIRED else CardConnectionStatus.FAILED,
            reasonText = failure.reason
        )
    }
    return CardStatusInfo.Offline
}
