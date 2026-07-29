package com.systemsgo.hex.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RailWindow
import com.systemsgo.hex.remote.RemoteMouseButton
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.HorizonGray
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.StarDust
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Local, on-screen floor a window can be resized down to — purely a UI
 *  guard so a tile never shrinks to something the user can no longer grab
 *  or read. Expressed in *remote* px (pre-scale), the same unit
 *  [RailWindow.rect] and [com.systemsgo.hex.session.RemoteAppWindowManager.moveWindow]
 *  both use. */
private const val MIN_WINDOW_REMOTE_PX = 120

/**
 * REMOTEAPP-WINDOWS FEATURE (part 3 — the piece
 * [com.systemsgo.hex.session.RemoteAppWindowManager.moveWindow] was built
 * for but never had a gesture wired to it): the actual freeform "desktop"
 * surface for MULTI_WINDOW mode — every open RAIL window drawn as its own
 * floating, draggable, resizable tile, positioned/sized to scale with
 * [RailWindow.rect] inside the shared RAIL desktop bounds
 * ([desktopWidth]×[desktopHeight] — the same composited-bitmap coordinate
 * space [RailWindow.rect] is already expressed in, see that property's doc
 * comment). [RemoteAppWindowSwitcherBar] (the tap-to-activate icon row)
 * stays on top of this as a quick-jump list — this is the surface it was
 * always missing underneath.
 *
 * INTERACTION DESIGN (why a title bar exists): every window tile is a crop
 * of one shared bitmap (`bitmapFlow` — see [RailWindow.rect]'s "NATIVE SCOPE
 * NOTE"), so a drag gesture can't be told apart from "the user is
 * interacting with the remote app's own content" just by *where* on the
 * tile it starts — both are touches on the same pixels. Each tile therefore
 * gets a thin, local-only title strip (never sent to the server, pure
 * client chrome) that is the *only* drag/resize surface; the content area
 * below forwards taps/long-presses to the remote app as left/right clicks
 * instead. This mirrors how every desktop windowing system solves the same
 * ambiguity (X11, Windows DWM, ...) and needs no protocol support.
 *
 * SCOPE NOTE: content-area forwarding here is deliberately tap/long-press
 * only (left-click / right-click), not full cursor-drag/hover parity — that
 * already exists, precisely, in RdpSessionActivity's SINGLE_WINDOW-mode
 * RdpCanvas. A user who needs to actually work inside one window (type,
 * drag-select, precise pointer movement) taps its tile to activate it, then
 * flips to SINGLE_WINDOW for that. This surface's job is arranging windows,
 * not being a second full input pipeline for all of them at once.
 */
@Composable
fun RemoteAppFreeformDesktop(
    bitmapFlow: StateFlow<Bitmap?>,
    windows: List<RailWindow>,
    desktopWidth: Int,
    desktopHeight: Int,
    activeWindowId: Int?,
    onActivate: (windowId: Int) -> Unit,
    onMove: (windowId: Int, rect: Rect) -> Unit,
    onMouseClick: (windowId: Int, localX: Int, localY: Int, button: RemoteMouseButton, down: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap by bitmapFlow.collectAsStateWithLifecycle()
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepSpace)
            .onSizeChanged { canvasSizePx = it }
    ) {
        if (desktopWidth > 0 && desktopHeight > 0 && canvasSizePx.width > 0 && canvasSizePx.height > 0) {
            // Fit-to-screen, letterboxed, no user zoom/pan — see the class doc
            // comment for why this surface intentionally stays simple
            // (arranging windows, not a second precision-input canvas). A
            // window dragged to the edge of desktopWidth/Height stays fully
            // on-screen since drag/resize clamp against those same bounds.
            val scale = minOf(
                canvasSizePx.width.toFloat() / desktopWidth,
                canvasSizePx.height.toFloat() / desktopHeight,
            )
            val offsetX = (canvasSizePx.width - desktopWidth * scale) / 2f
            val offsetY = (canvasSizePx.height - desktopHeight * scale) / 2f
            val titleBarDp = 26.dp

            // Highest z-order last, so the active/most-recently-touched
            // window paints on top of any it overlaps — same ordering
            // RailWindow.zOrder already documents ("most-recently-activated
            // first" when sorted descending; ascending here for draw order).
            val ordered = remember(windows) { windows.sortedBy { it.zOrder } }

            ordered.forEach { window ->
                key(window.windowId) {
                    RemoteAppWindowTile(
                        window        = window,
                        bitmap        = bitmap,
                        isActive      = window.windowId == activeWindowId,
                        scale         = scale,
                        offsetX       = offsetX,
                        offsetY       = offsetY,
                        titleBarDp    = titleBarDp,
                        desktopWidth  = desktopWidth,
                        desktopHeight = desktopHeight,
                        onActivate    = onActivate,
                        onMove        = onMove,
                        onMouseClick  = onMouseClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteAppWindowTile(
    window: RailWindow,
    bitmap: Bitmap?,
    isActive: Boolean,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    titleBarDp: androidx.compose.ui.unit.Dp,
    desktopWidth: Int,
    desktopHeight: Int,
    onActivate: (Int) -> Unit,
    onMove: (Int, Rect) -> Unit,
    onMouseClick: (Int, Int, Int, RemoteMouseButton, Boolean) -> Unit,
) {
    // Optimistic local preview while a drag/resize is in flight — mirrors
    // RemoteAppWindowManager.moveWindow's own "update immediately, let the
    // server's echo merge in normally" doc comment, just one layer higher
    // (here vs there) so the tile tracks the *finger*, not the next PDU.
    // Reset (back to null, i.e. "trust window.rect") once the server's own
    // update for this window catches up to what we predicted, so a late/
    // different server echo (e.g. the app itself refused the move) doesn't
    // get stuck showing our stale local guess forever.
    var previewRect by remember(window.windowId) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(window.rect) {
        if (previewRect == window.rect) previewRect = null
    }
    val rect = previewRect ?: window.rect

    val density = LocalDensity.current
    val leftPx   = offsetX + rect.left * scale
    val topPx    = offsetY + rect.top * scale
    val widthPx  = (rect.width() * scale).coerceAtLeast(1f)
    val heightPx = (rect.height() * scale).coerceAtLeast(1f)
    val tileAlpha = if (window.isVisible) 1f else 0.45f

    fun commit(newRect: Rect) {
        val clampedLeft = newRect.left.coerceIn(0, (desktopWidth - newRect.width()).coerceAtLeast(0))
        val clampedTop  = newRect.top.coerceIn(0, (desktopHeight - newRect.height()).coerceAtLeast(0))
        previewRect = Rect(clampedLeft, clampedTop, clampedLeft + newRect.width(), clampedTop + newRect.height())
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() } + titleBarDp)
            .zIndex(if (isActive) 10f else 0f)
            .alpha(tileAlpha)
            .clip(RoundedCornerShape(6.dp))
            .background(NebulaSurface)
            .border(1.dp, if (isActive) PulsarCyan else HorizonGray, RoundedCornerShape(6.dp))
    ) {
        // Remote content: an exact crop of the shared composited bitmap for
        // this window's rect — the same source data SINGLE_WINDOW's
        // cropRect draws, just placed at tile size/position instead of
        // full-screen. Anchored below the title strip.
        if (bitmap != null) {
            val srcW = rect.width().coerceIn(1, bitmap.width)
            val srcH = rect.height().coerceIn(1, bitmap.height)
            val srcX = rect.left.coerceIn(0, (bitmap.width - srcW).coerceAtLeast(0))
            val srcY = rect.top.coerceIn(0, (bitmap.height - srcH).coerceAtLeast(0))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { heightPx.toDp() })
                    .offset(y = titleBarDp)
                    .pointerInput(window.windowId, rect) {
                        // Content taps/long-presses only — see the class doc
                        // comment's SCOPE NOTE for why this isn't full drag
                        // parity. Local tile px -> window-local remote px is
                        // a plain /scale (this Canvas is drawn at exactly
                        // rect.width()*scale × rect.height()*scale).
                        detectTapGestures(
                            onTap = { local ->
                                onActivate(window.windowId)
                                val rx = (local.x / scale).roundToInt().coerceIn(0, rect.width())
                                val ry = (local.y / scale).roundToInt().coerceIn(0, rect.height())
                                onMouseClick(window.windowId, rx, ry, RemoteMouseButton.LEFT, true)
                                onMouseClick(window.windowId, rx, ry, RemoteMouseButton.LEFT, false)
                            },
                            onLongPress = { local ->
                                onActivate(window.windowId)
                                val rx = (local.x / scale).roundToInt().coerceIn(0, rect.width())
                                val ry = (local.y / scale).roundToInt().coerceIn(0, rect.height())
                                onMouseClick(window.windowId, rx, ry, RemoteMouseButton.RIGHT, true)
                                onMouseClick(window.windowId, rx, ry, RemoteMouseButton.RIGHT, false)
                            },
                        )
                    }
            ) {
                drawImage(
                    image     = bitmap.asImageBitmap(),
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize   = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize   = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }

        // Title bar — the only drag surface (move). Tapping/dragging it
        // also activates the window, same as tapping its content does.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleBarDp)
                .background(if (isActive) PulsarCyan.copy(alpha = 0.22f) else DeepSpace.copy(alpha = 0.85f))
                .pointerInput(window.windowId) {
                    detectDragGestures(
                        onDragStart  = { onActivate(window.windowId) },
                        onDragEnd    = { previewRect?.let { onMove(window.windowId, it) } },
                        onDragCancel = { previewRect = null },
                    ) { change, dragAmount ->
                        change.consume()
                        val base = previewRect ?: window.rect
                        val dx = (dragAmount.x / scale).roundToInt()
                        val dy = (dragAmount.y / scale).roundToInt()
                        commit(Rect(base.left + dx, base.top + dy, base.left + dx + base.width(), base.top + dy + base.height()))
                    }
                }
                .padding(horizontal = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = null,
                    tint = if (isActive) PulsarCyan else CometTail,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = window.title.ifBlank { stringResource(R.string.remote_app_window_untitled) },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) PulsarCyan else StarDust,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
        }

        // Resize handle — bottom-right corner, drags right/bottom edges only
        // (left/top stay pinned), floored at MIN_WINDOW_REMOTE_PX so a
        // window can't be dragged down to nothing.
        Icon(
            imageVector = Icons.Filled.OpenInFull,
            contentDescription = stringResource(R.string.remote_app_window_resize),
            tint = if (isActive) PulsarCyan else CometTail,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(16.dp)
                .padding(2.dp)
                .pointerInput(window.windowId) {
                    detectDragGestures(
                        onDragEnd    = { previewRect?.let { onMove(window.windowId, it) } },
                        onDragCancel = { previewRect = null },
                    ) { change, dragAmount ->
                        change.consume()
                        val base = previewRect ?: window.rect
                        val dw = (dragAmount.x / scale).roundToInt()
                        val dh = (dragAmount.y / scale).roundToInt()
                        val newWidth  = (base.width() + dw).coerceAtLeast(MIN_WINDOW_REMOTE_PX)
                        val newHeight = (base.height() + dh).coerceAtLeast(MIN_WINDOW_REMOTE_PX)
                        commit(Rect(base.left, base.top, base.left + newWidth, base.top + newHeight))
                    }
                },
        )
    }
}
