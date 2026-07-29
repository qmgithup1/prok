package com.systemsgo.hex.remote.clipboard

import kotlinx.coroutines.flow.SharedFlow

/**
 * CLIPBOARD-SYNC FEATURE: implemented by protocol adapters
 * ([com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter],
 * [com.systemsgo.hex.vnc.protocol.VncClient]) that can carry clipboard data
 * to/from the remote session. [com.systemsgo.hex.ssh.protocol.SshClient] does
 * not implement this — SSH is a raw terminal with no clipboard channel of
 * its own, and [com.systemsgo.hex.remote.clipboard.ClipboardSyncManager]
 * simply isn't started for it (see how
 * [com.systemsgo.hex.remote.RemoteSessionFactory] wires things up).
 *
 * This is a separate, optional interface rather than new members on
 * [com.systemsgo.hex.remote.RemoteSessionClient] on purpose: it keeps
 * clipboard concerns out of the core mouse/keyboard/framebuffer contract
 * every protocol has to implement, and lets each backend declare exactly
 * which [ClipboardFormat]s it can actually transport instead of forcing a
 * one-size-fits-all surface on protocols with very different underlying
 * capabilities (MS-RDPECLIP vs. the plain RFB clipboard messages).
 */
interface ClipboardCapableSession {

    /**
     * Formats this transport can actually put on the wire for the *current*
     * connection. [ClipboardSyncManager] consults this before sending
     * anything: a format not in this set is gracefully skipped (logged,
     * never thrown) instead of attempting a send the backend can't honor —
     * this is what "gracefully handle unsupported clipboard formats\" means
     * in practice. A payload richer than what's supported still falls back
     * to a plainer one where possible (e.g. HTML -> its plain-text
     * fallback) rather than being dropped outright.
     */
    val supportedClipboardFormats: Set<ClipboardFormat>

    /**
     * Clipboard content the remote session has just made available (e.g. a
     * user copied something on the Windows/X11 desktop). Emits at most one
     * item per remote clipboard change — never a duplicate of the payload
     * most recently emitted, since the underlying channel (cliprdr / RFB)
     * only tells us "the clipboard changed", not "changed to something
     * different from before".
     */
    val remoteClipboardUpdates: SharedFlow<ClipboardPayload>

    /**
     * Sends a locally-copied clipboard payload to the remote session.
     * Best-effort and non-throwing, matching every other outgoing call in
     * this codebase ([com.systemsgo.hex.remote.RemoteSessionClient.resize],
     * [com.systemsgo.hex.remote.RemoteSessionClient.refresh]): a disconnected
     * session, a channel that never negotiated, or a format the transport
     * doesn't support all simply no-op. Callers should check
     * [supportedClipboardFormats] first (as [ClipboardSyncManager] does) to
     * avoid silently discarding richer content that could have been sent in
     * a degraded form instead.
     */
    fun sendClipboardPayload(payload: ClipboardPayload)
}
