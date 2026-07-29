package com.systemsgo.hex.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * TOOLBOX FEATURE (Stage 0): a single unit inside [SessionToolbox].
 *
 * Every button that used to live directly inside `SessionToolbar` or
 * `ExtraKeysBar` (screenshot, Ctrl+Alt+Del, file transfer, PiP, external
 * display, disconnect...) is now expressed as one of these. Every tool added
 * by later stages (screen recording, virtual keyboard, blank-screen mode,
 * flip screen, cursor mode, FPS/latency toggles, live quality switch,
 * clipboard sync, chat...) plugs into the exact same list — [SessionToolbox]
 * itself never needs to change to gain a new tool.
 *
 * @param id            Stable, unique identifier (e.g. "screenshot",
 *                       "ctrl_alt_del"). Used as the persistence key for the
 *                       Quick Bar order (see [AppSettings.toolboxQuickToolIds])
 *                       and as the key in LazyRow/LazyColumn/drag lists, so it
 *                       must never change once shipped or a user's saved
 *                       Quick Bar layout would silently drop that tool.
 * @param icon           Icon shown in both the Quick Bar and the Drawer.
 * @param label          Human-readable label (already localized by the
 *                       caller via `stringResource`) shown under the icon and
 *                       used as the accessibility `contentDescription`.
 * @param tint           Optional icon tint. Defaults to null, meaning
 *                       "use the Toolbox's normal icon color" — callers only
 *                       pass a tint for tools that carry semantic color today
 *                       (screenshot = success green, disconnect = danger
 *                       pink...), always sourced from Theme.kt/Color.kt
 *                       aliases, never a hardcoded value.
 * @param badge          Optional small composable overlaid on the icon
 *                       (e.g. a red "recording" dot in Stage 1). Rare — most
 *                       tools leave this null.
 * @param enabled        Whether the tool is currently actionable. A disabled
 *                       tool still shows in the Drawer (so the user
 *                       understands it exists) but is dimmed and inert.
 * @param visibleInQuickBarByDefault  Whether a brand-new install should ship
 *                       this tool pinned to the Quick Bar out of the box
 *                       (see [SessionToolboxState.DEFAULT_QUICK_TOOL_IDS]).
 * @param forceVisible   When true, the tool shows in the Quick Bar
 *                       regardless of whether the user pinned it — used for
 *                       transient tools that must appear automatically for
 *                       as long as a condition holds, e.g. Stage 1's
 *                       "stop recording" button, which the plan requires to
 *                       "تظهر تلقائيًا بالشريط السريع طول مدة التسجيل فقط"
 *                       (appear automatically only for the recording's
 *                       duration). The caller achieves the "only" half by
 *                       simply omitting the tool from [tools] when the
 *                       condition (e.g. isRecording) is false.
 * @param onClick        Simple one-shot action (screenshot, disconnect...).
 *                       Mutually exclusive in practice with [popupContent] —
 *                       a tool normally provides one or the other.
 * @param popupContent   For tools that need more than a single tap (e.g.
 *                       Stage 1's photo/video picker with quality selector):
 *                       composable content shown in a small anchored popup
 *                       when the tool is tapped. Receives a `dismiss`
 *                       callback so the tool itself decides when to close.
 */
data class SessionTool(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
    val badge: (@Composable () -> Unit)? = null,
    val enabled: Boolean = true,
    val visibleInQuickBarByDefault: Boolean = false,
    val forceVisible: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val popupContent: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
)
