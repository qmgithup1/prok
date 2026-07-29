package com.systemsgo.hex.remote.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream

/**
 * CLIPBOARD-SYNC FEATURE: single, protocol-agnostic engine that keeps the
 * Android system clipboard and a remote session's clipboard ([session])
 * mirrored in both directions.
 *
 * Replaces the bespoke, near-duplicate `OnPrimaryClipChangedListener` blocks
 * that used to live directly inside
 * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] and
 * [com.systemsgo.hex.vnc.protocol.VncClient] (plain-text only). Behavior for
 * plain text is unchanged — same echo-loop guard, same best-effort
 * semantics — this class just does it once, generalizes it to every
 * [ClipboardFormat], and adds duplicate-update detection.
 *
 * Responsibilities:
 *  - **Format detection**: turns whatever the user just copied (a
 *    [ClipData]) into a [ClipboardPayload], sniffing plain text / HTML /
 *    image / file-list.
 *  - **Loop prevention**: a payload this manager itself just wrote into the
 *    system clipboard (because the remote sent it) must never be sent right
 *    back to the remote as if the user had copied it — tracked via
 *    [selfInducedHash].
 *  - **Duplicate detection**: the same content copied twice in a row (either
 *    direction) is not re-sent — tracked via [lastSentHash] / [lastAppliedHash].
 *  - **Graceful degradation**: a format [session] can't transport is either
 *    downgraded (HTML -> its plain-text fallback) or simply skipped with a
 *    log line — never a crash, never a partial/corrupt send.
 *  - **Per-connection enable/disable**: [enabled] gates every direction at
 *    once; flipping it off immediately stops both listening for local
 *    changes and applying remote ones, without tearing down the underlying
 *    session.
 *
 * Android clipboard-privacy notes: this class never persists clipboard
 * content anywhere (no disk/db writes beyond the transient FileProvider
 * cache file used for images, cleaned up on [stop]), never logs clipboard
 * *content* (only sizes/formats), and relies on the platform's own
 * background-clipboard-access restrictions (Android 10+ already returns no
 * primary clip to apps that aren't the current input-focused/foreground
 * app) rather than trying to reimplement that policy here. [enabled] gives
 * the user an explicit opt-out on top of that platform behavior.
 */
class ClipboardSyncManager(
    private val appContext: Context,
    private val session: ClipboardCapableSession,
    private val scope: CoroutineScope,
    /** Per-connection toggle — mirrors RdpProfile.enableClipboard / VncCredentials.enableClipboard. */
    initiallyEnabled: Boolean = true,
) {
    companion object {
        private const val TAG = "ClipboardSyncManager"

        // Lightweight-implementation guardrails: refuse to even attempt
        // sync for absurdly large content instead of risking an OOM on a
        // phone-class device. A real "large file" transfer scenario is out
        // of scope for a clipboard channel on any protocol this app speaks.
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024 // 8 MB
        private const val MAX_FILE_COUNT = 50
    }

    @Volatile var enabled: Boolean = initiallyEnabled
        private set

    /** Runtime per-connection toggle (in addition to the profile-level default above). */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    private var clipboardManager: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var collectJob: Job? = null

    // Loop guard: hash of the payload we ourselves just wrote to the system
    // clipboard because the remote sent it. Cleared the moment the resulting
    // OnPrimaryClipChangedListener callback fires for it.
    @Volatile private var selfInducedHash: String? = null

    // Duplicate-update guards, one per direction.
    @Volatile private var lastSentHash: String? = null
    @Volatile private var lastAppliedHash: String? = null

    private val cacheDir: File by lazy {
        File(appContext.cacheDir, "clipboard").apply { mkdirs() }
    }

    /** Wires up both directions. Safe to call once per active connection. */
    fun start() {
        collectJob = session.remoteClipboardUpdates
            .onEach { payload -> applyRemotePayload(payload) }
            .launchIn(scope)

        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardManager = cm
        val l = ClipboardManager.OnPrimaryClipChangedListener { onLocalClipboardChanged() }
        listener = l
        cm.addPrimaryClipChangedListener(l)
    }

    /** Undoes [start]; always safe to call, including multiple times. */
    fun stop() {
        listener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        listener = null
        clipboardManager = null
        collectJob?.cancel()
        collectJob = null
        selfInducedHash = null
        lastSentHash = null
        lastAppliedHash = null
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ── Local (Android) clipboard -> remote session ─────────────────────────

    private fun onLocalClipboardChanged() {
        if (!enabled) return
        val cm = clipboardManager ?: return
        val clip = try {
            if (!cm.hasPrimaryClip()) return
            cm.primaryClip
        } catch (e: Exception) {
            // Platform clipboard-access restrictions (or a misbehaving
            // clipboard owner) can throw here; never let that take down the
            // session.
            Log.w(TAG, "Unable to read system clipboard: ${e.message}")
            return
        } ?: return
        if (clip.itemCount == 0) return

        val payload = detectPayload(clip) ?: return
        val hash = payload.contentHash()

        // Loop guard: this is the change we just caused by applying a
        // remote payload below.
        if (hash == selfInducedHash) {
            selfInducedHash = null
            return
        }
        // Duplicate guard: identical to the last thing we sent.
        if (hash == lastSentHash) return

        val toSend = downgradeIfUnsupported(payload) ?: run {
            Log.i(TAG, "Clipboard format ${payload.format} not supported by this session; skipping sync")
            return
        }
        lastSentHash = hash
        session.sendClipboardPayload(toSend)
    }

    /** Detects the richest [ClipboardPayload] a [ClipData] actually carries. */
    private fun detectPayload(clip: ClipData): ClipboardPayload? {
        val description = clip.description
        val item = clip.getItemAt(0)

        // Files: one or more items backed by a content:// Uri that isn't
        // itself plain text/HTML (e.g. copied from a file manager or the
        // Photos app "copy" action).
        val fileUris = (0 until clip.itemCount).mapNotNull { i -> clip.getItemAt(i).uri }
        if (fileUris.isNotEmpty()) {
            val mimeType = description.getMimeType(0) ?: ""
            if (mimeType.startsWith("image/") && fileUris.size == 1) {
                return readImagePayload(fileUris[0], mimeType)
            }
            val names = fileUris.take(MAX_FILE_COUNT).map { uri -> displayNameFor(uri) }
            return ClipboardPayload.Files(names, fileUris.take(MAX_FILE_COUNT))
        }

        // Rich text: ClipDescription advertises HTML alongside a coerced
        // plain-text fallback.
        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
            val html = item.htmlText
            if (html != null) {
                val plain = item.coerceToText(appContext)?.toString() ?: ""
                return ClipboardPayload.Html(html, plain)
            }
        }

        // Plain text — the common case, and the same fallback the previous
        // per-protocol implementations already used.
        val text = item.coerceToText(appContext)?.toString() ?: return null
        if (text.isEmpty()) return null
        return ClipboardPayload.Text(text)
    }

    private fun readImagePayload(uri: Uri, mimeType: String): ClipboardPayload? {
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_IMAGE_BYTES) {
                    Log.i(TAG, "Clipboard image (${bytes.size} bytes) exceeds $MAX_IMAGE_BYTES cap; skipping sync")
                    return null
                }
                ClipboardPayload.Image(mimeType, bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read clipboard image: ${e.message}")
            null
        }
    }

    private fun displayNameFor(uri: Uri): String {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return@use cursor.getString(idx)
                }
                null
            } ?: uri.lastPathSegment ?: uri.toString()
        } catch (e: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }
    }

    /**
     * Falls back to a plainer representation when [session] can't carry the
     * exact format detected, or returns null when nothing usable remains
     * (e.g. an image over a session that only supports text) — the
     * "gracefully handle unsupported clipboard formats" requirement.
     */
    private fun downgradeIfUnsupported(payload: ClipboardPayload): ClipboardPayload? {
        val supported = session.supportedClipboardFormats
        if (payload.format in supported) return payload
        return when (payload) {
            is ClipboardPayload.Html ->
                if (ClipboardFormat.PLAIN_TEXT in supported) ClipboardPayload.Text(payload.plainTextFallback) else null
            is ClipboardPayload.Text, is ClipboardPayload.Image, is ClipboardPayload.Files -> null
        }
    }

    // ── Remote session -> local (Android) clipboard ─────────────────────────

    private suspend fun applyRemotePayload(payload: ClipboardPayload) {
        if (!enabled) return
        val hash = payload.contentHash()
        if (hash == lastAppliedHash) return // duplicate-update guard

        val clip = withContext(Dispatchers.IO) { toClipData(payload) } ?: return
        lastAppliedHash = hash
        selfInducedHash = hash
        clipboardManager?.setPrimaryClip(clip)
    }

    private fun toClipData(payload: ClipboardPayload): ClipData? = when (payload) {
        is ClipboardPayload.Text ->
            ClipData.newPlainText("remote", payload.text)

        is ClipboardPayload.Html ->
            ClipData.newHtmlText("remote", payload.plainTextFallback, payload.html)

        is ClipboardPayload.Image -> writeImageToCache(payload)?.let { uri ->
            ClipData.newUri(appContext.contentResolver, "remote-image", uri)
        }

        // No file *bytes* arrive from the remote side yet (see the doc
        // comment on ClipboardCapableSession.supportedClipboardFormats) — the
        // best we can do gracefully is let the user see what was copied.
        is ClipboardPayload.Files ->
            ClipData.newPlainText("remote-files", payload.names.joinToString("\n"))
    }

    private fun writeImageToCache(payload: ClipboardPayload.Image): Uri? {
        return try {
            val ext = when (payload.mimeType) {
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "png"
            }
            val file = File(cacheDir, "clip_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { it.write(payload.bytes) }
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.clipboardprovider", file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stage clipboard image for paste: ${e.message}")
            null
        }
    }
}
