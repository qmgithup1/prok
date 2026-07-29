package com.systemsgo.hex.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.systemsgo.hex.data.repository.AppSettings

/**
 * TOOLBOX FEATURE (Stage 0): which screen edge the Quick Bar is currently
 * docked to. Kept RTL-aware (START/END instead of LEFT/RIGHT) — in an
 * Arabic/RTL layout, START is the *right* edge of the screen, so any code
 * that turns this into an actual `Alignment`/offset must go through
 * `LocalLayoutDirection`, never assume START == left.
 */
enum class DockEdge { TOP, BOTTOM, START, END;

    companion object {
        fun fromSetting(raw: String): DockEdge = when (raw) {
            "bottom" -> BOTTOM
            "start"  -> START
            "end"    -> END
            else     -> TOP
        }
    }

    fun toSetting(): String = when (this) {
        TOP -> "top"; BOTTOM -> "bottom"; START -> "start"; END -> "end"
    }
}

/**
 * TOOLBOX FEATURE (Stage 0): owns every piece of UI state for
 * [SessionToolbox] — which tools are pinned to the Quick Bar (and in what
 * order), where the floating container currently sits, which edge it's
 * docked to, and whether the Drawer ("كل الأدوات") is open.
 *
 * This class deliberately knows nothing about *what* the tools do — it only
 * stores ids and layout. [RdpSessionScreen] builds the actual `List<SessionTool>`
 * (icons/labels/onClick) each recomposition from live session state (pip
 * support, external displays, latency...) and hands both that list and this
 * state object to [SessionToolbox].
 *
 * Persistence mirrors every other session-UI setting in this codebase
 * (`sessionToolbarVisible`, `sessionExtraKeysVisible`...): reads seed the
 * initial value from [AppSettings], writes go back out through the three
 * callbacks supplied by the caller (backed by
 * `SessionToolboxViewModel.setToolboxQuickTools/-Position/-DockEdge`, which
 * persist via `AppSettingsRepository`).
 */
class SessionToolboxState internal constructor(
    initialQuickIds: List<String>,
    initialPosXFraction: Float,
    initialPosYFraction: Float,
    initialDockEdge: DockEdge,
    private val persistQuickIds: (List<String>) -> Unit,
    private val persistPosition: (Float, Float) -> Unit,
    private val persistDockEdge: (DockEdge) -> Unit,
) {
    /** Ordered list of tool ids pinned to the Quick Bar. */
    var quickToolIds: List<String> by mutableStateOf(initialQuickIds)
        private set

    /** Floating container position, as a 0f..1f fraction of the available area. */
    var posXFraction: Float by mutableStateOf(initialPosXFraction)
        private set
    var posYFraction: Float by mutableStateOf(initialPosYFraction)
        private set

    var dockEdge: DockEdge by mutableStateOf(initialDockEdge)
        private set

    /** Whether the "كل الأدوات" Drawer is currently open. */
    var isDrawerOpen: Boolean by mutableStateOf(false)

    /** Whether the Quick Bar itself is collapsed to a small handle. Session-only (not persisted) — every session starts expanded, matching the old SessionToolbar/ExtraKeysBar default-visible behaviour. */
    var isCollapsed: Boolean by mutableStateOf(false)

    fun isPinned(toolId: String): Boolean = quickToolIds.contains(toolId)

    /** Drawer → Quick Bar: pin a tool (drag or tap-to-add). No-op if already pinned. */
    fun addToQuickBar(toolId: String) {
        if (isPinned(toolId)) return
        quickToolIds = quickToolIds + toolId
        persistQuickIds(quickToolIds)
    }

    /** Quick Bar → Drawer: unpin a tool (drag or tap-to-remove). */
    fun removeFromQuickBar(toolId: String) {
        if (!isPinned(toolId)) return
        quickToolIds = quickToolIds - toolId
        persistQuickIds(quickToolIds)
    }

    fun toggleQuickBar(toolId: String) {
        if (isPinned(toolId)) removeFromQuickBar(toolId) else addToQuickBar(toolId)
    }

    /** Full reorder, e.g. after a drag-to-reorder gesture inside the Quick Bar. */
    fun reorderQuickBar(newOrder: List<String>) {
        quickToolIds = newOrder
        persistQuickIds(newOrder)
    }

    /** Called continuously while dragging the floating container, and once more on drop. */
    fun updatePosition(xFraction: Float, yFraction: Float) {
        posXFraction = xFraction.coerceIn(0f, 1f)
        posYFraction = yFraction.coerceIn(0f, 1f)
    }

    /** Commits the current position/edge to storage — call on drag end, not on every move. */
    fun commitPosition() = persistPosition(posXFraction, posYFraction)

    fun dockTo(edge: DockEdge) {
        dockEdge = edge
        persistDockEdge(edge)
    }

    companion object {
        /**
         * Quick Bar contents for a brand-new install / a user who never
         * customized it. Matches the buttons that were always visible in the
         * old fixed `SessionToolbar` (screenshot, Ctrl+Alt+Del, file
         * transfer, disconnect) so migration is invisible to existing users.
         * PiP/external-display/extra-keys toggle are available from the
         * Drawer but not pinned by default, since they were already
         * conditionally shown before (only when supported/connected).
         */
        val DEFAULT_QUICK_TOOL_IDS = listOf(
            "screenshot", "ctrl_alt_del", "file_transfer", "disconnect"
        )
    }
}

/**
 * Builds/remembers a [SessionToolboxState] seeded from persisted [settings],
 * wiring its mutations back to storage through the three setter lambdas.
 *
 * The Quick Bar order is only re-seeded from `settings.toolboxQuickToolIds`
 * once (on first composition) — after that, [SessionToolboxState] is the
 * single source of truth for the running session, same pattern already used
 * for `showToolbar`/`showExtraKeys` in [RdpSessionScreen] (BUGFIX-UI-7).
 */
@Composable
fun rememberSessionToolboxState(
    settings: AppSettings,
    onQuickToolsChanged: (List<String>) -> Unit,
    onPositionChanged: (Float, Float) -> Unit,
    onDockEdgeChanged: (String) -> Unit,
): SessionToolboxState {
    return remember {
        val savedIds = settings.toolboxQuickToolIds
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        SessionToolboxState(
            initialQuickIds     = savedIds.ifEmpty { SessionToolboxState.DEFAULT_QUICK_TOOL_IDS },
            initialPosXFraction = settings.toolboxPosXFraction,
            initialPosYFraction = settings.toolboxPosYFraction,
            initialDockEdge     = DockEdge.fromSetting(settings.toolboxDockEdge),
            persistQuickIds     = onQuickToolsChanged,
            persistPosition     = onPositionChanged,
            persistDockEdge     = { onDockEdgeChanged(it.toSetting()) },
        )
    }
}
