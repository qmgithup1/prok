package com.systemsgo.hex.remote.clipboard

import android.net.Uri
import java.security.MessageDigest

/**
 * CLIPBOARD-SYNC FEATURE: the set of clipboard content categories this app
 * knows how to represent locally. Not every remote protocol/backend can
 * carry every format on the wire — see [ClipboardCapableSession.supportedFormats]
 * for how a given transport advertises which of these it can actually send,
 * so the rest of the pipeline can degrade gracefully (e.g. an image copied
 * locally is simply not forwarded to a VNC session, rather than crashing or
 * silently corrupting the stream).
 */
enum class ClipboardFormat { PLAIN_TEXT, HTML, IMAGE, FILE_LIST }

/**
 * A single clipboard "snapshot" flowing in either direction between the
 * Android system clipboard and a remote session. Immutable and cheap to
 * hash/compare, which is what powers both duplicate-update detection and
 * echo-loop prevention in [ClipboardSyncManager].
 */
sealed class ClipboardPayload {
    abstract val format: ClipboardFormat

    /** Plain UTF-16 text — CF_UNICODETEXT (RDP) / ClientCutText-ServerCutText (VNC). */
    data class Text(val text: String) : ClipboardPayload() {
        override val format get() = ClipboardFormat.PLAIN_TEXT
    }

    /**
     * Rich (HTML) text. [plainTextFallback] is always populated (Android's
     * own [android.content.ClipData.Item.coerceToText] already does this for
     * us) so a transport that only understands [ClipboardFormat.PLAIN_TEXT]
     * can still carry *something* useful instead of dropping the copy
     * entirely.
     */
    data class Html(val html: String, val plainTextFallback: String) : ClipboardPayload() {
        override val format get() = ClipboardFormat.HTML
    }

    /** A bitmap image, already decoded to a compressed byte buffer (e.g. PNG). */
    data class Image(val mimeType: String, val bytes: ByteArray) : ClipboardPayload() {
        override val format get() = ClipboardFormat.IMAGE

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
    }

    /**
     * One or more files referenced from the clipboard (e.g. copied from a
     * file manager). [uris] are the local content:// / file:// locations
     * when this payload originated on this device; a payload received from
     * a remote session only carries [names] — see the doc comment on
     * [ClipboardCapableSession.supportedFormats] for why file *contents*
     * are not transferred yet (name-only sync, not full byte transfer).
     */
    data class Files(val names: List<String>, val uris: List<Uri> = emptyList()) : ClipboardPayload() {
        override val format get() = ClipboardFormat.FILE_LIST
    }
}

/**
 * Stable content fingerprint used for both duplicate-update detection and
 * echo-loop prevention. Two payloads that would look identical to the user
 * (same text, same image bytes, same file list) hash identically regardless
 * of which direction they travelled, so [ClipboardSyncManager] can tell
 * "the remote just echoed back what we sent it" apart from "the user
 * genuinely copied something new" with one cheap comparison instead of
 * bespoke per-type equality checks scattered through the sync logic.
 */
fun ClipboardPayload.contentHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    when (this) {
        is ClipboardPayload.Text -> {
            digest.update("T:".toByteArray())
            digest.update(text.toByteArray(Charsets.UTF_8))
        }
        is ClipboardPayload.Html -> {
            digest.update("H:".toByteArray())
            digest.update(html.toByteArray(Charsets.UTF_8))
        }
        is ClipboardPayload.Image -> {
            digest.update("I:$mimeType:".toByteArray())
            digest.update(bytes)
        }
        is ClipboardPayload.Files -> {
            digest.update("F:${names.joinToString("|")}".toByteArray())
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
