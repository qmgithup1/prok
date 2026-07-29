package com.systemsgo.hex.guacamole.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.systemsgo.hex.remote.RemoteFrameUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 2/N).
 *
 * Interprets the server→client instruction stream
 * ([com.systemsgo.hex.guacamole.protocol.GuacamoleTunnelClient.instructions])
 * into actual pixels, playing the same role
 * [com.undatech.opaque.RfbConnectable]'s framebuffer decoder plays for VNC
 * or FreeRDP's surface callbacks play for RDP — except Guacamole's protocol
 * is a small 2D drawing-instruction language (per
 * https://guacamole.apache.org/doc/gug/guacamole-protocol.html) rather than
 * a raw pixel/tile stream, so this class is closer to a minimal software
 * rasterizer than a codec.
 *
 * SCOPE:
 *  - Layer z-order/opacity (`move`/`shade`) IS implemented — see
 *    [hasStackedLayers]/[recompositeFullScreen]. A layer becomes part of
 *    the visible tree only once explicitly `move`d under a parent (root is
 *    layer 0); layers used purely as off-screen buffers (referenced only
 *    via `copy`, never `move`d) correctly stay invisible. Nesting depth is
 *    unlimited (real parent/child recursion, not a hardcoded two levels).
 *  - The `transfer` instruction (arbitrary per-pixel binary ops between
 *    layers) — treated as a no-op; `copy` (the common case) is implemented.
 *  - Audio playback (the `audio` stream) IS implemented — see
 *    [audioFrames]/[audioChannelConnected] — but only for raw PCM
 *    (`audio/L16`), which is the only format
 *    [com.systemsgo.hex.guacamole.protocol.GuacamoleTunnelConfig] ever
 *    negotiates; `video` streams are still opened-and-discarded.
 *  - File transfer (the `file` stream, reg.txt's FILE TRANSFER section) IS
 *    implemented for both directions — see [filesReceived] (download) and
 *    [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient.sendFile]
 *    (upload) — but ONLY the simple bounded "one file, one stream"
 *    exchange most backends use for ad-hoc transfers, buffered entirely
 *    in memory until `end` (fine for typical file sizes; a very large
 *    transfer would be better streamed to disk incrementally instead —
 *    not done here).
 *  - Guacamole's separate "filesystem" object protocol
 *    (`filesystem`/`body`/`get`/`put`/`undefine`, plus the pre-existing
 *    `ack`) IS implemented — see [filesystems]/[registerPendingGet]/
 *    [registerPendingAck] and
 *    [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient.listDirectory]/
 *    [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient.downloadObjectFile]/
 *    [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient.uploadObjectFile].
 *    This is the protocol full remote-drive browsing uses (e.g. RDP drive
 *    redirection's folder UI in the official web client) — a `filesystem`
 *    instruction offers an object (a "drive"); its root ("/") and any
 *    subdirectory `get` resolves to a `body` stream whose payload is JSON
 *    (mimetype `application/vnd.glyptodon.guacamole.stream-index+json`,
 *    per https://guacamole.apache.org/doc/gug/protocol-reference.html#get-instruction):
 *    a flat `{name: mimetype}` map of that directory's immediate entries.
 *    An entry is itself a subdirectory if its mimetype is that same
 *    stream-index mimetype; otherwise it's an ordinary file, downloadable
 *    with a further `get` for that entry's name. Entry names are opaque
 *    per the spec ("dictated by the object"), but every backend that
 *    exposes filesystem objects in practice (guacd's SFTP and RDP
 *    drive-redirection filesystems) uses the full path from the object's
 *    root as the name — e.g. browsing into "docs" and requesting its
 *    listing means requesting name "/docs", not "docs" — so that's what
 *    [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient]'s
 *    directory-browsing API assumes too. Uploads (`put`) and downloads
 *    (`get`) both buffer the whole stream in memory until `end`, same
 *    simplification as the `file` stream above. No compiler/live server
 *    available to verify this against — see [opBody]'s doc for the one
 *    place a wrong assumption here would actually bite (auto-ack timing).
 *  - Virtual printing (reg.txt's PRINTING section) needs NO separate code:
 *    guacd's printer redirection is, at the wire level, just a `file`
 *    stream with an `application/pdf` mimetype — indistinguishable from a
 *    regular file download from this class's point of view, so
 *    [filesReceived] already covers it.
 *  - `pipe` streams are still opened-and-discarded — unlike `file`, there's
 *    no single well-defined client action for an arbitrary named pipe
 *    (they're backend/extension-specific side channels); consuming
 *    `blob`/`end` so the stream-index table doesn't desync is the correct,
 *    complete behavior for a pipe whose specific purpose isn't otherwise
 *    handled.
 */
class GuacamoleDisplayRenderer(
    initialWidth: Int,
    initialHeight: Int,
    // FILESYSTEM-BROWSING FEATURE: lets this class send the one instruction
    // it needs to originate itself — the `ack` that must follow a `body`
    // instruction (see [opBody]'s doc) — without taking a dependency on
    // [GuacamoleTunnelClient] the way [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient]
    // does. Every other outgoing instruction in this app still goes through
    // that class, keeping "renderer interprets, session client transports"
    // intact everywhere except this one unavoidable exception.
    private val outgoing: (GuacamoleInstruction) -> Unit = {},
) {

    private val TAG = "GuacamoleRenderer"

    /** Guacamole layer/buffer bitmaps, keyed by the protocol's layer index (0 = default/visible; negative = off-screen buffer). */
    private val layers = HashMap<Int, Bitmap>()

    // LAYER-COMPOSITING FEATURE: a layer only becomes part of the visible
    // tree once guacd explicitly `move`s it under a parent (root is layer 0,
    // always implicitly present) — layers used purely as off-screen buffers
    // (referenced only via `copy`, never `move`d) are correctly never
    // rendered. See [recompositeFullScreen] for how the tree is flattened.
    private data class LayerNode(var parent: Int = 0, var x: Int = 0, var y: Int = 0, var z: Int = 0, var opacity: Int = 255)
    private val layerNodes = HashMap<Int, LayerNode>()
    /** True once any `move`/`shade` has ever been seen this session — switches [drainDirtyFrame] from the cheap layer-0-only dirty-rect path to a full-tree recomposite (see that function's doc). */
    private var hasStackedLayers = false
    /** Set by any drawing op on ANY layer (not just 0) once [hasStackedLayers] — the plain layer-0 [dirty] rect isn't enough once other layers can contribute to the final image. */
    private var contentChanged = false
    private var compositeBitmap: Bitmap? = null

    /** Accumulated dirty region of layer 0 since the last [drainDirtyFrame] — what actually needs re-sending to the UI. */
    private var dirty: Rect? = null

    /** In-flight `img`/`audio`/`video`/`file`/`pipe`/`clipboard` streams, keyed by stream index — see [handleStreamOpen]/[handleBlob]/[handleEnd]. */
    private data class Stream(
        val kind: String, // "img", "audio", "video", "file", "pipe", "clipboard"
        val mimetype: String,
        val layer: Int = 0,
        val x: Int = 0,
        val y: Int = 0,
        // AUDIO-PLAYBACK FEATURE (Part 5/N): parsed once at stream-open time from `mimetype`
        // (e.g. "audio/L16;rate=44100,channels=2") — see opStreamOpen's audio branch.
        val sampleRate: Int = 44100,
        val channels: Int = 2,
        // FILE-TRANSFER FEATURE (Part 6/N): only meaningful for kind == "file" — see opFileOpen.
        // FILESYSTEM-BROWSING FEATURE: for kind == "body", filename doubles as the object stream's `name`
        // (the value opBody's args[3] carries) rather than a real filesystem filename.
        val filename: String = "",
        // FILESYSTEM-BROWSING FEATURE: only meaningful for kind == "body" — the object this stream belongs to, needed to resolve pendingGets[objectIndex to filename] once `end` arrives.
        val objectIndex: Int = -1,
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(),
    )
    private val streams = HashMap<Int, Stream>()

    /** FILE-TRANSFER FEATURE (Part 6/N): a completed server→client `file` stream, ready to be saved. */
    data class GuacamoleReceivedFile(val filename: String, val mimetype: String, val bytes: ByteArray)
    private val _filesReceived = MutableSharedFlow<GuacamoleReceivedFile>(extraBufferCapacity = 8)
    /** GUACAMOLE-PROTOCOL FEATURE (Part 6/N): emits every completed server-initiated file download — [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient] bridges this into save-to-device handling. Covers reg.txt's "Download" case; "Upload" is client-initiated — see [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient.sendFile]. */
    val filesReceived: SharedFlow<GuacamoleReceivedFile> = _filesReceived

    // ── Filesystem object protocol ──────────────────────────────────────────
    // See class doc's SCOPE note for the full picture. `filesystem` objects
    // are offered by the server at any point in the session (not just at
    // connect) and stay valid until an `undefine` for that object index.

    /** One filesystem ("drive") guacd has offered via a `filesystem` instruction. */
    data class GuacamoleFilesystem(val objectIndex: Int, val name: String)
    private val _filesystems = MutableStateFlow<List<GuacamoleFilesystem>>(emptyList())
    /** Every filesystem object currently offered this session — [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient] exposes this directly for the hosting UI to build a drive picker. */
    val filesystems: StateFlow<List<GuacamoleFilesystem>> = _filesystems.asStateFlow()

    /** Resolved contents of a completed object `get` request — see [opEnd]'s "body" branch for which of the two this becomes. */
    sealed class GuacamoleObjectStreamResult {
        /** The root ("/") or any subdirectory entry whose mimetype was the reserved stream-index mimetype — a flat map of immediate child name → mimetype. */
        data class Directory(val entries: Map<String, String>) : GuacamoleObjectStreamResult()
        /** An ordinary file entry's full content. */
        data class FileContent(val mimetype: String, val bytes: ByteArray) : GuacamoleObjectStreamResult()
    }

    /** Result of an `ack` instruction — completes the [CompletableDeferred] a client-initiated `put` upload is awaiting (see [registerPendingAck]). `status == 0` is success; anything else is a Guacamole status code (see the protocol reference's Status codes table) and implicitly ends the stream. */
    data class GuacamoleAck(val streamIndex: Int, val message: String, val status: Int)

    // Keyed by (objectIndex, name) rather than stream index because the
    // client doesn't learn which stream index the server chose for the
    // response until the `body` instruction itself arrives — see opBody.
    private val pendingGets = HashMap<Pair<Int, String>, CompletableDeferred<GuacamoleObjectStreamResult>>()
    private val pendingAcks = HashMap<Int, CompletableDeferred<GuacamoleAck>>()

    /** Called by [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient] immediately before sending a `get` instruction for ([objectIndex], [name]) — registers where the eventual `body`/blob(s)/`end` for that request should be delivered. Must be called before the `get` is sent (no race in practice: this is synchronous and the reply can only arrive after the send that follows it). */
    fun registerPendingGet(objectIndex: Int, name: String): CompletableDeferred<GuacamoleObjectStreamResult> {
        val deferred = CompletableDeferred<GuacamoleObjectStreamResult>()
        pendingGets[objectIndex to name] = deferred
        return deferred
    }

    /** Same registration pattern as [registerPendingGet], for the `ack` a client-initiated `put` upload receives once the server has processed its blobs. */
    fun registerPendingAck(streamIndex: Int): CompletableDeferred<GuacamoleAck> {
        val deferred = CompletableDeferred<GuacamoleAck>()
        pendingAcks[streamIndex] = deferred
        return deferred
    }

    /** AUDIO-PLAYBACK FEATURE (Part 5/N): one decoded PCM chunk from an `audio` stream's `blob` — pushed immediately, not buffered until `end` (an audio channel typically stays open, streaming continuously, for the whole session). */
    data class GuacAudioFrame(val pcm: ByteArray, val sampleRate: Int, val channels: Int)
    private val _audioFrames = MutableSharedFlow<GuacAudioFrame>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<GuacAudioFrame> = _audioFrames

    private val _audioChannelConnected = MutableStateFlow(false)
    /** True once the server has opened an `audio` stream (i.e. actually offered audio for this session), false once it closes — [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient] bridges this into [com.systemsgo.hex.audio.RemoteAudioManager.AudioChannelState]. */
    val audioChannelConnected: StateFlow<Boolean> = _audioChannelConnected.asStateFlow()

    /** Per-layer pending rect from the last `rect` instruction — consumed by the next `cfill`/`lfill`. See class doc's SCOPE note on the simplified path model. */
    private val pendingRect = HashMap<Int, Rect>()

    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val blitPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }

    /** Last clipboard text received from the server via a `clipboard` stream (reg.txt's two-way CLIPBOARD section — server→client half only; see class doc). */
    var lastClipboardText: String? = null
        private set

    private val _clipboardReceived = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** GUACAMOLE-PROTOCOL FEATURE (Part 3/N): emits every time the server pushes new clipboard text — [com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient] bridges this into [com.systemsgo.hex.remote.clipboard.ClipboardCapableSession.remoteClipboardUpdates]. */
    val clipboardReceived: SharedFlow<String> = _clipboardReceived

    init {
        getOrCreateLayer(0, initialWidth, initialHeight)
    }

    /** Applies one decoded instruction. Returns a human-readable warning for unrecognized/malformed instructions (never throws) so the caller can surface it in [com.systemsgo.hex.remote.RemoteSessionClient.error] without tearing down the session over one bad drawing op. */
    fun apply(instruction: GuacamoleInstruction): String? {
        return try {
            when (instruction.opcode) {
                "size" -> opSize(instruction.args)
                "img" -> opImgOpen(instruction.args)
                "audio" -> opStreamOpen(instruction.args, kind = "audio", mimetypeIndex = 1)
                "video" -> opStreamOpen(instruction.args, kind = "video", mimetypeIndex = 2)
                "file" -> opFileOpen(instruction.args)
                "pipe" -> opStreamOpen(instruction.args, kind = "pipe", mimetypeIndex = 1)
                "clipboard" -> opStreamOpen(instruction.args, kind = "clipboard", mimetypeIndex = 1)
                "blob" -> opBlob(instruction.args)
                "end" -> opEnd(instruction.args)
                "rect" -> opRect(instruction.args)
                "cfill" -> opCfill(instruction.args)
                "copy" -> opCopy(instruction.args)
                "dispose" -> opDispose(instruction.args)
                "move" -> opMove(instruction.args)
                "shade" -> opShade(instruction.args)
                "filesystem" -> opFilesystem(instruction.args)
                "body" -> opBody(instruction.args)
                "ack" -> opAck(instruction.args)
                "undefine" -> opUndefine(instruction.args)
                "sync", "nop", "name", "cursor", "select", "disconnect", "error" -> null
                else -> null // Unknown opcode: forward-compatible with future protocol additions (reg.txt: "Any future protocol supported by the Guacamole server") — ignored, not fatal.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply instruction ${instruction.opcode}", e)
            "Guacamole: malformed ${instruction.opcode} instruction (${e.message})"
        }
    }

    /** Call once per `sync` from the server — returns the next frame as a [RemoteFrameUpdate], or null if nothing changed since the last call. Once [hasStackedLayers] (any `move`/`shade` ever seen), this recomposites the whole visible layer tree and returns a full-screen update; until then, it's the cheap layer-0-only dirty-rect path every session starts with. */
    fun drainDirtyFrame(): RemoteFrameUpdate? {
        if (hasStackedLayers) {
            if (!contentChanged) return null
            contentChanged = false
            val composite = recompositeFullScreen() ?: return null
            val pixels = IntArray(composite.width * composite.height)
            composite.getPixels(pixels, 0, composite.width, 0, 0, composite.width, composite.height)
            return RemoteFrameUpdate(0, 0, composite.width, composite.height, pixels, fullScreen = true)
        }
        val rect = dirty ?: return null
        dirty = null
        val bitmap = layers[0] ?: return null
        val clamped = Rect(
            rect.left.coerceIn(0, bitmap.width),
            rect.top.coerceIn(0, bitmap.height),
            rect.right.coerceIn(0, bitmap.width),
            rect.bottom.coerceIn(0, bitmap.height),
        )
        val w = clamped.width()
        val h = clamped.height()
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, clamped.left, clamped.top, w, h)
        return RemoteFrameUpdate(clamped.left, clamped.top, w, h, pixels, fullScreen = false)
    }

    /** LAYER-COMPOSITING FEATURE: flattens layer 0 plus every layer `move`d under it (directly or via a chain of parents), each drawn at its cumulative offset with its own opacity, in ascending z-order among siblings — the actual on-screen picture once more than the default layer is in play. */
    private fun recompositeFullScreen(): Bitmap? {
        val root = layers[0] ?: return null
        val existing = compositeBitmap
        val composite = if (existing != null && existing.width == root.width && existing.height == root.height) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(composite)
        canvas.drawColor(Color.BLACK) // Clear — matches guacd's own default background for the root layer.
        canvas.drawBitmap(root, 0f, 0f, blitPaint)
        for (childId in childrenOf(0)) drawLayerSubtree(canvas, childId, 0, 0)
        compositeBitmap = composite
        return composite
    }

    private fun childrenOf(parent: Int): List<Int> =
        layerNodes.entries.filter { it.value.parent == parent }.sortedBy { it.value.z }.map { it.key }

    private fun drawLayerSubtree(canvas: Canvas, layerId: Int, parentOffsetX: Int, parentOffsetY: Int) {
        val bitmap = layers[layerId] ?: return
        val node = layerNodes[layerId] ?: return
        val x = parentOffsetX + node.x
        val y = parentOffsetY + node.y
        if (node.opacity > 0) {
            val paint = if (node.opacity >= 255) blitPaint else Paint(blitPaint).apply { alpha = node.opacity }
            canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), paint)
        }
        for (childId in childrenOf(layerId)) drawLayerSubtree(canvas, childId, x, y)
    }

    /** `move`: layer, parent, x, y, z — attaches [layer] under [parent] at the given offset/z-order, making it part of the visible tree (see [hasStackedLayers]'s doc). */
    private fun opMove(args: List<String>): String? {
        val layer = args.getOrNull(0)?.toIntOrNull() ?: return "move: missing layer"
        val parent = args.getOrNull(1)?.toIntOrNull() ?: return "move: missing parent"
        val x = args.getOrNull(2)?.toIntOrNull() ?: return "move: missing x"
        val y = args.getOrNull(3)?.toIntOrNull() ?: return "move: missing y"
        val z = args.getOrNull(4)?.toIntOrNull() ?: 0
        val node = layerNodes.getOrPut(layer) { LayerNode() }
        node.parent = parent; node.x = x; node.y = y; node.z = z
        hasStackedLayers = true
        contentChanged = true
        return null
    }

    /** `shade`: layer, alpha (0-255) — sets a layer's opacity within the visible tree. */
    private fun opShade(args: List<String>): String? {
        val layer = args.getOrNull(0)?.toIntOrNull() ?: return "shade: missing layer"
        val alpha = args.getOrNull(1)?.toIntOrNull() ?: return "shade: missing alpha"
        layerNodes.getOrPut(layer) { LayerNode() }.opacity = alpha.coerceIn(0, 255)
        hasStackedLayers = true
        contentChanged = true
        return null
    }

    fun defaultLayerSize(): Pair<Int, Int> = layers[0]?.let { it.width to it.height } ?: (0 to 0)

    fun dispose() {
        layers.values.forEach { it.recycle() }
        layers.clear()
        compositeBitmap?.recycle()
        compositeBitmap = null
    }

    // ── Instruction handlers ────────────────────────────────────────────────

    private fun opSize(args: List<String>): String? {
        val layer = args.getOrNull(0)?.toIntOrNull() ?: return "size: missing layer"
        val w = args.getOrNull(1)?.toIntOrNull() ?: return "size: missing width"
        val h = args.getOrNull(2)?.toIntOrNull() ?: return "size: missing height"
        if (w <= 0 || h <= 0) return null
        val old = layers[layer]
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (old != null) {
            Canvas(fresh).drawBitmap(old, 0f, 0f, blitPaint)
            old.recycle()
        }
        layers[layer] = fresh
        contentChanged = true
        if (layer == 0) markDirty(Rect(0, 0, w, h))
        return null
    }

    private fun getOrCreateLayer(layer: Int, w: Int, h: Int): Bitmap =
        layers.getOrPut(layer) { Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888) }

    /** `img`: stream_index, channel_mask(ignored — see class doc), layer, mimetype, x, y */
    private fun opImgOpen(args: List<String>): String? {
        val streamIndex = args.getOrNull(0)?.toIntOrNull() ?: return "img: missing stream index"
        val layer = args.getOrNull(2)?.toIntOrNull() ?: return "img: missing layer"
        val mimetype = args.getOrNull(3) ?: "image/png"
        val x = args.getOrNull(4)?.toIntOrNull() ?: 0
        val y = args.getOrNull(5)?.toIntOrNull() ?: 0
        streams[streamIndex] = Stream(kind = "img", mimetype = mimetype, layer = layer, x = x, y = y)
        return null
    }

    /** `file`: stream_index, mimetype, filename — a server-initiated file *download* (reg.txt's FILE TRANSFER → "Download"). Uploads are client-initiated — see GuacamoleSessionClient.sendFile, which sends this same opcode the other direction. */
    private fun opFileOpen(args: List<String>): String? {
        val streamIndex = args.getOrNull(0)?.toIntOrNull() ?: return "file: missing stream index"
        val mimetype = args.getOrNull(1) ?: "application/octet-stream"
        val filename = args.getOrNull(2) ?: "download"
        streams[streamIndex] = Stream(kind = "file", mimetype = mimetype, filename = filename)
        return null
    }

    private fun opStreamOpen(args: List<String>, kind: String, mimetypeIndex: Int): String? {
        val streamIndex = args.getOrNull(0)?.toIntOrNull() ?: return "$kind: missing stream index"
        val mimetype = args.getOrNull(mimetypeIndex) ?: ""
        if (kind == "audio") {
            // AUDIO-PLAYBACK FEATURE (Part 5/N): only raw PCM (audio/L16) is decoded —
            // see class doc's SCOPE note. Any other negotiated audio mimetype (unlikely,
            // since GuacamoleTunnelConfig only ever advertises L16 support — see
            // RemoteSessionFactory's GUACAMOLE branch) would need a real decoder here.
            val rateMatch = Regex("rate=(\\d+)").find(mimetype)
            val channelsMatch = Regex("channels=(\\d+)").find(mimetype)
            streams[streamIndex] = Stream(
                kind = kind, mimetype = mimetype,
                sampleRate = rateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 44100,
                channels = channelsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 2,
            )
            _audioChannelConnected.value = true
        } else {
            streams[streamIndex] = Stream(kind = kind, mimetype = mimetype)
        }
        return null
    }

    private fun opBlob(args: List<String>): String? {
        val streamIndex = args.getOrNull(0)?.toIntOrNull() ?: return "blob: missing stream index"
        val data = args.getOrNull(1) ?: return "blob: missing data"
        val stream = streams[streamIndex] ?: return null // Unknown/already-closed stream — silently ignore rather than fail the session.
        val bytes = Base64.decode(data, Base64.DEFAULT)
        if (stream.kind == "audio") {
            // AUDIO-PLAYBACK FEATURE (Part 5/N): pushed straight through, not buffered —
            // see the Stream/audioFrames doc comments above for why.
            _audioFrames.tryEmit(GuacAudioFrame(bytes, stream.sampleRate, stream.channels))
        } else {
            stream.buffer.write(bytes)
        }
        return null
    }

    private fun opEnd(args: List<String>): String? {
        val streamIndex = args.getOrNull(0)?.toIntOrNull() ?: return "end: missing stream index"
        val stream = streams.remove(streamIndex) ?: return null
        when (stream.kind) {
            "img" -> {
                val bytes = stream.buffer.toByteArray()
                if (bytes.isEmpty()) return null // A zero-length "img" stream is guacd's way of *clearing* the target region in some backends — nothing to draw either way.
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return "img: could not decode $bytes.size bytes as ${stream.mimetype}"
                val target = getOrCreateLayer(stream.layer, stream.x + bmp.width, stream.y + bmp.height)
                Canvas(target).drawBitmap(bmp, stream.x.toFloat(), stream.y.toFloat(), blitPaint)
                bmp.recycle()
                contentChanged = true
                if (stream.layer == 0) markDirty(Rect(stream.x, stream.y, stream.x + bmp.width, stream.y + bmp.height))
            }
            "clipboard" -> {
                if (stream.mimetype.startsWith("text/")) {
                    val text = stream.buffer.toString(Charsets.UTF_8)
                    lastClipboardText = text
                    _clipboardReceived.tryEmit(text)
                }
            }
            "audio" -> _audioChannelConnected.value = false
            "file" -> {
                val bytes = stream.buffer.toByteArray()
                if (bytes.isNotEmpty()) {
                    _filesReceived.tryEmit(GuacamoleReceivedFile(stream.filename, stream.mimetype, bytes))
                }
            }
            "body" -> {
                val bytes = stream.buffer.toByteArray()
                val result = if (stream.mimetype == STREAM_INDEX_MIMETYPE) {
                    GuacamoleObjectStreamResult.Directory(parseStreamIndexJson(bytes))
                } else {
                    GuacamoleObjectStreamResult.FileContent(stream.mimetype, bytes)
                }
                // No pending deferred is a legitimate case, not a bug: guacd may
                // push a `body` unprompted (e.g. re-sending the root listing after
                // the underlying drive changes) with nothing currently awaiting it —
                // silently dropping it here matches how filesReceived-less `img`
                // updates on a disposed layer are handled elsewhere in this class.
                pendingGets.remove(stream.objectIndex to stream.filename)?.complete(result)
            }
            // "video"/"pipe": payload discarded — see class doc SCOPE note.
        }
        return null
    }

    /** `rect`: layer, x, y, w, h — defines the pending rectangle [cfill] fills next. */
    private fun opRect(args: List<String>): String? {
        val layer = args.getOrNull(0)?.toIntOrNull() ?: return "rect: missing layer"
        val x = args.getOrNull(1)?.toIntOrNull() ?: return "rect: missing x"
        val y = args.getOrNull(2)?.toIntOrNull() ?: return "rect: missing y"
        val w = args.getOrNull(3)?.toIntOrNull() ?: return "rect: missing w"
        val h = args.getOrNull(4)?.toIntOrNull() ?: return "rect: missing h"
        pendingRect[layer] = Rect(x, y, x + w, y + h)
        return null
    }

    /** `cfill`: channel_mask, layer, r, g, b, a — fills the layer's [pendingRect] (or the whole layer if none was set) with a solid color. */
    private fun opCfill(args: List<String>): String? {
        val layer = args.getOrNull(1)?.toIntOrNull() ?: return "cfill: missing layer"
        val r = args.getOrNull(2)?.toIntOrNull() ?: return "cfill: missing r"
        val g = args.getOrNull(3)?.toIntOrNull() ?: return "cfill: missing g"
        val b = args.getOrNull(4)?.toIntOrNull() ?: return "cfill: missing b"
        val a = args.getOrNull(5)?.toIntOrNull() ?: 255
        val bitmap = layers[layer] ?: return null
        val rect = pendingRect.remove(layer) ?: Rect(0, 0, bitmap.width, bitmap.height)
        fillPaint.color = Color.argb(a, r, g, b)
        fillPaint.xfermode = if (a < 255) PorterDuffXfermode(PorterDuff.Mode.SRC_OVER) else null
        Canvas(bitmap).drawRect(rect, fillPaint)
        contentChanged = true
        if (layer == 0) markDirty(rect)
        return null
    }

    /** `copy`: src_layer, src_x, src_y, w, h, channel_mask(ignored), dst_layer, dst_x, dst_y */
    private fun opCopy(args: List<String>): String? {
        val srcLayer = args.getOrNull(0)?.toIntOrNull() ?: return "copy: missing src layer"
        val srcX = args.getOrNull(1)?.toIntOrNull() ?: return "copy: missing src x"
        val srcY = args.getOrNull(2)?.toIntOrNull() ?: return "copy: missing src y"
        val w = args.getOrNull(3)?.toIntOrNull() ?: return "copy: missing w"
        val h = args.getOrNull(4)?.toIntOrNull() ?: return "copy: missing h"
        val dstLayer = args.getOrNull(6)?.toIntOrNull() ?: return "copy: missing dst layer"
        val dstX = args.getOrNull(7)?.toIntOrNull() ?: return "copy: missing dst x"
        val dstY = args.getOrNull(8)?.toIntOrNull() ?: return "copy: missing dst y"
        val src = layers[srcLayer] ?: return null
        if (w <= 0 || h <= 0) return null
        val srcRect = Rect(srcX, srcY, (srcX + w).coerceAtMost(src.width), (srcY + h).coerceAtMost(src.height))
        if (srcRect.width() <= 0 || srcRect.height() <= 0) return null
        val dst = getOrCreateLayer(dstLayer, dstX + srcRect.width(), dstY + srcRect.height())
        val dstRect = Rect(dstX, dstY, dstX + srcRect.width(), dstY + srcRect.height())
        Canvas(dst).drawBitmap(src, srcRect, dstRect, blitPaint)
        contentChanged = true
        if (dstLayer == 0) markDirty(dstRect)
        return null
    }

    private fun opDispose(args: List<String>): String? {
        val layer = args.getOrNull(0)?.toIntOrNull() ?: return "dispose: missing layer"
        if (layer == 0) return null // Never dispose the default/visible layer.
        layers.remove(layer)?.recycle()
        pendingRect.remove(layer)
        if (layerNodes.remove(layer) != null) { hasStackedLayers = true; contentChanged = true }
        return null
    }

    private fun markDirty(rect: Rect) {
        val current = dirty
        dirty = if (current == null) Rect(rect) else Rect(current).apply { union(rect) }
    }

    // ── Filesystem object protocol handlers ─────────────────────────────────

    /** `filesystem`: object, name — guacd offering a new browsable filesystem ("drive"). */
    private fun opFilesystem(args: List<String>): String? {
        val objectIndex = args.getOrNull(0)?.toIntOrNull() ?: return "filesystem: missing object index"
        val name = args.getOrNull(1) ?: ""
        _filesystems.value = _filesystems.value.filterNot { it.objectIndex == objectIndex } + GuacamoleFilesystem(objectIndex, name)
        return null
    }

    /**
     * `body`: object, stream, mimetype, name — guacd's response to a client
     * `get`, opening the actual data stream (blob/end follow, same as any
     * other stream). Per the protocol reference
     * (https://guacamole.apache.org/doc/gug/protocol-reference.html#streaming-instructions),
     * `ack` "acknowledges a received data blob" and real guacamole-common-js
     * clients send it immediately on receiving the stream-opening
     * instruction (`stream.sendAck('OK', SUCCESS)` right in the `onfile`/
     * `onbody` handler, before any blobs arrive) rather than per-blob —
     * mirrored here via [outgoing] so a strict backend that gates further
     * blobs on this ack (unlike this app's other stream kinds, which never
     * ack and haven't needed to) doesn't stall. If that assumption is wrong
     * for some backend, the visible symptom would be a directory listing or
     * file download that never completes — not a corrupted one.
     */
    private fun opBody(args: List<String>): String? {
        val objectIndex = args.getOrNull(0)?.toIntOrNull() ?: return "body: missing object index"
        val streamIndex = args.getOrNull(1)?.toIntOrNull() ?: return "body: missing stream index"
        val mimetype = args.getOrNull(2) ?: ""
        val name = args.getOrNull(3) ?: ""
        streams[streamIndex] = Stream(kind = "body", mimetype = mimetype, filename = name, objectIndex = objectIndex)
        outgoing(GuacamoleInstruction.of("ack", streamIndex.toString(), "OK", "0"))
        return null
    }

    /** `ack`: stream, message, status — for this class's purposes, only ever the server acknowledging a client-initiated `put` upload (see [registerPendingAck]); acks for other stream kinds this app sends (`file`, `clipboard`) are intentionally not awaited anywhere, so they resolve nothing and are silently ignored here, matching that existing fire-and-forget behavior. */
    private fun opAck(args: List<String>): String? {
        val streamIndex = args.getOrNull(0)?.toIntOrNull() ?: return "ack: missing stream index"
        val message = args.getOrNull(1) ?: ""
        val status = args.getOrNull(2)?.toIntOrNull() ?: 0
        pendingAcks.remove(streamIndex)?.complete(GuacamoleAck(streamIndex, message, status))
        return null
    }

    /** `undefine`: object — the filesystem is gone; drop it from [filesystems] and fail any of its requests still in flight rather than leaving them to time out. */
    private fun opUndefine(args: List<String>): String? {
        val objectIndex = args.getOrNull(0)?.toIntOrNull() ?: return "undefine: missing object index"
        _filesystems.value = _filesystems.value.filterNot { it.objectIndex == objectIndex }
        val stale = pendingGets.keys.filter { it.first == objectIndex }
        stale.forEach { key -> pendingGets.remove(key)?.complete(GuacamoleObjectStreamResult.Directory(emptyMap())) }
        return null
    }

    /** Parses a `body` stream's JSON payload for the reserved [STREAM_INDEX_MIMETYPE] into its `{name: mimetype}` map — see class doc's SCOPE note for the shape. Malformed JSON (a backend deviating from the documented format) yields an empty map rather than throwing, since [apply] callers treat this class's stream handlers as never-fatal. */
    private fun parseStreamIndexJson(bytes: ByteArray): Map<String, String> {
        if (bytes.isEmpty()) return emptyMap()
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val result = LinkedHashMap<String, String>(json.length())
            json.keys().forEach { key -> result[key] = json.optString(key, "") }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Malformed stream-index JSON in body response", e)
            emptyMap()
        }
    }

    private companion object {
        /** Reserved mimetype (https://guacamole.apache.org/doc/gug/protocol-reference.html#get-instruction) marking a `body` stream's payload as a directory listing rather than file content. */
        const val STREAM_INDEX_MIMETYPE = "application/vnd.glyptodon.guacamole.stream-index+json"
    }
}
