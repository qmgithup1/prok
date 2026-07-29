package com.systemsgo.hex.session

import com.systemsgo.hex.data.model.ConnectionState
import com.systemsgo.hex.data.model.RdpProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-05 · تعدد الجلسات
 *
 * Tracks every open RDP / VNC / SSH session so the UI can show them as tabs
 * and let the user switch between them without disconnecting.
 *
 * Each [SessionTab] is a lightweight descriptor; the actual heavy [RemoteSessionClient]
 * lives inside [RdpSessionViewModel] which is scoped to its Activity. The manager
 * only tracks metadata (profile, state, title) so the HomeScreen can render the
 * tab bar and deep-link into the right Activity instance.
 *
 * BUG FIX · حد أقصى للجلسات المتزامنة
 * كل جلسة تحجز bitmap مزدوج بحجم الشاشة كاملة في ذاكرة الـ native heap.
 * بدون حد أعلى، يمكن فتح عشرات الجلسات بدقة عالية مما يؤدي إلى OOM.
 * [MAX_TABS] يضع سقفاً صارماً متناسقاً مع ثابت MAX_TERMINAL_LINES.
 */
data class SessionTab(
    /** Stable identifier for this tab — also passed as Activity intent extra. */
    val tabId: String = UUID.randomUUID().toString(),
    val profile: RdpProfile,
    val state: ConnectionState = ConnectionState.CONNECTING,
    /** Optional short status text shown under the tab label. */
    val statusHint: String = "",
    /** Epoch-millis of when this tab was opened. */
    val openedAt: Long = System.currentTimeMillis(),
    /**
     * GUEST-MODE ISOLATION FIX: which profile context this tab was opened
     * under — stamped at creation time from [SessionTabManager]'s current
     * context (see [SessionTabManager.setGuestContext]) and never changed
     * afterwards. MainViewModel.sessionTabs filters on this so a tab opened
     * under the Primary profile never appears (or is connectable to) while
     * Guest Mode is active, and vice versa. Without this flag every open
     * RDP/VNC/SSH session — including host/username in its title — stayed
     * visible in the tab bar after switching to Guest, defeating the whole
     * point of Guest Mode being "a clean app".
     */
    val isGuest: Boolean = false,
    // CONNECTION-STATUS-INDICATOR FEATURE: epoch-millis of the most recent
    // transition into CONNECTED — null until that happens at least once.
    // The status badge's session-duration text (00:15:42) is computed as
    // now - connectedAtMillis, so it re-bases on every reconnect instead of
    // counting time spent RECONNECTING as part of the shown "session length".
    val connectedAtMillis: Long? = null,
    // CONNECTION-STATUS-INDICATOR FEATURE: how many times this tab has
    // entered RECONNECTING since it was opened — incremented once per
    // *transition into* RECONNECTING (not per retry tick within it) by
    // updateState() below. Surfaced in the status badge's long-press detail
    // popup.
    val reconnectCount: Int = 0
)

/**
 * Result of [SessionTabManager.openTab].
 *
 * BUGFIX · جلسات نشطة مكررة/متروكة (duplicate & stale active sessions):
 * `openTab()` used to unconditionally create a brand-new [SessionTab] every
 * time it was called, even if a tab with the exact same connection
 * parameters (protocol, host, port, username, domain) was already
 * connecting or connected. Repeated connection attempts to the same host
 * (double-tapping "Connect", retrying after navigating back, etc.) therefore
 * piled up duplicate entries in the Active Sessions list. This sealed result
 * lets the caller distinguish "a brand-new tab was created" from "an
 * identical, still-live tab already existed and was reused" so it can avoid
 * starting a second, redundant connection for the [Reused] case.
 */
sealed class OpenTabResult {
    /** A brand-new [SessionTab] was created for this connection attempt. */
    data class Created(val tabId: String) : OpenTabResult()
    /** An existing tab with identical connection parameters is still connecting/connected — reused instead of duplicated. */
    data class Reused(val tabId: String) : OpenTabResult()
    /** [SessionTabManager.MAX_TABS] concurrent sessions are already open. */
    object SessionLimitReached : OpenTabResult()
}

@Singleton
class SessionTabManager @Inject constructor() {

    companion object {
        /**
         * الحد الأقصى لعدد الجلسات المتزامنة (RDP / VNC / SSH).
         *
         * كل جلسة تحجز bitmap مزدوجاً (front + back buffer) بحجم الشاشة كاملة
         * في native heap. على دقة 1920×1080 بعمق 32-bit يعادل ذلك ~16 MB لكل
         * جلسة — أي 5 جلسات = ~80 MB لمجرد الـ bitmaps، وهو رقم آمن حتى على
         * أجهزة بذاكرة heap محدودة (256 MB).
         *
         * رفع هذا الحد يتطلب اختبار ذاكرة صريح على الأجهزة المستهدفة.
         */
        const val MAX_TABS = 5

        /**
         * States considered "still occupying a slot" for duplicate detection:
         * a tab in one of these states represents a live or in-progress
         * connection, so a new attempt with identical parameters should be
         * folded into it rather than spawning a duplicate entry. ERROR and
         * DISCONNECTED are deliberately excluded — those are terminal/failed
         * states and must never block (or be confused with) a fresh attempt.
         */
        private val ACTIVE_STATES = setOf(
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
            ConnectionState.CONNECTED
        )

        /**
         * Two profiles identify "the same session" when their protocol, host,
         * port, username, and domain all match — duplicate detection is based
         * on connection parameters, not profile identity (Quick Connect
         * sessions have no stable profile id, and two different saved
         * profiles can point at the same destination).
         */
        private fun isSameConnection(a: RdpProfile, b: RdpProfile): Boolean =
            a.protocolType == b.protocolType &&
                a.host == b.host &&
                a.port == b.port &&
                a.username == b.username &&
                a.domain == b.domain
    }

    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** true عندما تكون جميع فتحات الجلسات ممتلئة — تستخدمها الـ UI لتعطيل زر "اتصال جديد". */
    val isFull: Boolean get() = _tabs.value.size >= MAX_TABS

    /**
     * GUEST-MODE ISOLATION FIX: which profile context is currently active.
     * [SessionTabManager] is a process-wide [Singleton] — it has no notion of
     * "current user" on its own — so MainViewModel calls [setGuestContext]
     * every time it enters/exits Guest Mode (mirroring how it already resets
     * `guestProfiles`/`guestFolders`). Any tab opened via [openTab] is
     * stamped with whatever this flag was at that moment, so Guest sessions
     * and Primary sessions never appear in the same filtered list even
     * though they physically share this one manager.
     */
    @Volatile
    private var isGuestContext: Boolean = false

    /** Called by MainViewModel.enterGuestMode()/exitGuestMode() — see field doc above. */
    fun setGuestContext(isGuest: Boolean) {
        isGuestContext = isGuest
    }

    /**
     * GUEST-MODE ISOLATION FIX: removes every tab tagged [SessionTab.isGuest]
     * = true from the shared list — called by MainViewModel.exitGuestMode()
     * so leaving Guest Mode doesn't leave orphaned Guest session entries
     * that would otherwise still be visible (filtered out of the UI, but
     * needlessly retained in memory) once back on the Primary profile.
     *
     * Returns the full removed [SessionTab]s (not just ids) — the caller
     * needs each tab's `profile.id` to build the same "close_tab" Intent
     * RdpSessionActivity already understands (see SessionsScreen's × button /
     * RdpSessionActivity.handleCloseTabIntent), so it can actually tear down
     * the live connection instead of just discarding the list entry. This
     * manager only ever tracks lightweight metadata — see class doc — it has
     * no way to touch the connection itself.
     */
    fun closeAllGuestTabs(): List<SessionTab> {
        var removed: List<SessionTab> = emptyList()
        _tabs.update { list ->
            removed = list.filter { it.isGuest }
            if (removed.isEmpty()) return@update list
            list.filterNot { it.isGuest }
        }
        if (removed.isNotEmpty() && _activeTabId.value in removed.map { it.tabId }) {
            _activeTabId.value = _tabs.value.lastOrNull()?.tabId
        }
        return removed
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Open a tab for [profile], returning an [OpenTabResult] describing what
     * happened:
     *  - [OpenTabResult.Reused] — a tab with identical connection parameters
     *    (protocol, host, port, username, domain) is already CONNECTING,
     *    RECONNECTING, or CONNECTED. Its id is returned so the caller can
     *    switch to it; the caller MUST NOT start a second connection.
     *  - [OpenTabResult.Created] — no such tab existed, so a brand-new one
     *    was created.
     *  - [OpenTabResult.SessionLimitReached] — بدلاً من فتح جلسة جديدة، لأن
     *    عدد الجلسات المفتوحة بلغ [MAX_TABS]، مما يمنع استنزاف الذاكرة (OOM).
     *    على المُستدعي عرض رسالة خطأ مناسبة للمستخدم في هذه الحالة.
     *
     * BUG-N6 FIX (still applies): the size/duplicate check and the list
     * mutation happen inside a single _tabs.update{} lambda, so the
     * check-then-append is one atomic CAS operation — two concurrent callers
     * can never both pass the guard and both append.
     *
     * BUGFIX · duplicate active sessions: previously this always created a
     * new [SessionTab], so repeated connection attempts to the same host
     * (double-tapping "Connect", retrying a profile that's already
     * connecting elsewhere, etc.) produced multiple entries with identical
     * connection parameters. The dedup lookup below folds any such repeat
     * attempt into the existing tab instead.
     */
    fun openTab(profile: RdpProfile): OpenTabResult {
        var result: OpenTabResult = OpenTabResult.SessionLimitReached
        _tabs.update { current ->
            val existing = current.firstOrNull {
                it.state in ACTIVE_STATES && isSameConnection(it.profile, profile)
            }
            if (existing != null) {
                result = OpenTabResult.Reused(existing.tabId)
                return@update current
            }
            if (current.size >= MAX_TABS) {
                result = OpenTabResult.SessionLimitReached
                return@update current  // BUG-N6 FIX: atomic guard
            }
            val tab = SessionTab(profile = profile, isGuest = isGuestContext)
            result = OpenTabResult.Created(tab.tabId)
            current + tab
        }
        when (val r = result) {
            is OpenTabResult.Created -> {
                _activeTabId.value = r.tabId
                // CONNECTION-STATUS-INDICATOR FEATURE: a brand-new attempt is
                // starting for this profile — any stale "Connection Failed" /
                // "Authentication Required" badge left over from a previous
                // attempt no longer applies once the tab (now CONNECTING)
                // takes over as the live source of truth in the resolver.
                profile.id.takeIf { it.isNotBlank() }?.let { id ->
                    if (_lastFailures.value.containsKey(id)) {
                        _lastFailures.update { it - id }
                    }
                }
            }
            is OpenTabResult.Reused  -> _activeTabId.value = r.tabId
            is OpenTabResult.SessionLimitReached -> {}
        }
        return result
    }

    /** Make [tabId] the currently-visible tab. */
    fun switchTo(tabId: String) {
        if (_tabs.value.any { it.tabId == tabId }) {
            _activeTabId.value = tabId
        }
    }

    /**
     * CONNECTION-STATUS-INDICATOR FEATURE: [ERROR]/[AUTH_REQUIRED][ConnectionState]
     * transitions are usually followed almost immediately by [closeTab] in
     * RdpSessionActivity (see its AUTH_FAILED / final-ERROR branches) once a
     * connection attempt never reached CONNECTED — the tab, and with it
     * `tab.state`/`tab.statusHint`, disappears from [tabs] a moment later.
     * Without this cache the connection-status badge on the card would flash
     * "Connection Failed" for a single frame and then silently fall back to
     * "Offline", which reads as if nothing happened. This holds the most
     * recent failure per *profile id* (not per tab, which is about to be
     * gone) so the badge can keep showing the failure — with its reason —
     * until the user tries again. [openTab] below clears a profile's entry
     * the moment a fresh attempt starts for it.
     */
    data class SessionFailure(val reason: String, val atMillis: Long = System.currentTimeMillis())

    private val _lastFailures = MutableStateFlow<Map<String, SessionFailure>>(emptyMap())
    val lastFailures: StateFlow<Map<String, SessionFailure>> = _lastFailures.asStateFlow()

    /** Called by the session Activity when the connection state changes. */
    fun updateState(tabId: String, state: ConnectionState, hint: String = "") {
        var profileId: String? = null
        _tabs.update { list ->
            list.map { tab ->
                if (tab.tabId != tabId) return@map tab
                profileId = tab.profile.id
                tab.copy(
                    state             = state,
                    statusHint        = hint,
                    // Re-base the "connected since" timestamp on every fresh
                    // arrival into CONNECTED (including after a reconnect),
                    // so the duration shown reflects the current unbroken
                    // stretch of connectivity rather than counting time the
                    // link spent down while RECONNECTING.
                    connectedAtMillis = if (state == ConnectionState.CONNECTED)
                        System.currentTimeMillis() else tab.connectedAtMillis,
                    // Count *transitions into* RECONNECTING, not every retry
                    // tick — updateState() is called repeatedly with the same
                    // RECONNECTING state as the countdown/backoff progresses
                    // (see RdpSessionActivity), so only bump when the previous
                    // state wasn't already RECONNECTING.
                    reconnectCount    = if (state == ConnectionState.RECONNECTING &&
                        tab.state != ConnectionState.RECONNECTING) tab.reconnectCount + 1
                        else tab.reconnectCount
                )
            }
        }
        if (state == ConnectionState.ERROR || state == ConnectionState.AUTH_REQUIRED) {
            profileId?.takeIf { it.isNotBlank() }?.let { id ->
                _lastFailures.update { it + (id to SessionFailure(reason = hint)) }
            }
        }
    }

    /** Remove a tab (user closes it or session ends permanently). */
    fun closeTab(tabId: String) {
        // FIX-CLOSE-TAB-RACE: The previous implementation had two separate operations:
        //   1. _tabs.update { ... }          ← tabs updated
        //   2. _activeTabId.value = ...      ← activeTabId updated
        // Between these two steps any observer could see an inconsistent state:
        // tabs no longer contained the closed tab, but activeTabId still pointed to it.
        // Fix: compute the new active tab ID INSIDE _tabs.update so the decision is
        // based on the post-removal list, then apply both updates back-to-back before
        // any coroutine suspension point, minimising the observable window.
        var nextActiveId: String? = _activeTabId.value
        _tabs.update { list ->
            val newList = list.filterNot { it.tabId == tabId }
            // Determine successor only when closing the currently-active tab
            if (_activeTabId.value == tabId) {
                nextActiveId = newList.lastOrNull()?.tabId
            }
            newList
        }
        // Apply the new active ID immediately after the atomic tabs update.
        // This is still technically two operations, but nextActiveId is now computed
        // from the final tabs state, so there is never a logical inconsistency —
        // only a brief moment where activeTabId still holds the old closed-tab ID.
        _activeTabId.value = nextActiveId
    }

    /** Number of currently open sessions. */
    val count: Int get() = _tabs.value.size
}
