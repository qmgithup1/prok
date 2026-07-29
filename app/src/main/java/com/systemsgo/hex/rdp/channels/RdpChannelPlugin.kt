package com.systemsgo.hex.rdp.channels

/**
 * GENERIC-VCHANNEL FEATURE (Plugin System): implement this to react to one
 * named RDP virtual channel's lifecycle without touching
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge] or `systemsgo_jni.c` directly.
 *
 * A plugin is notified whenever its [channelName] connects or disconnects
 * for the current session — see [RdpChannelPluginRegistry] for how plugins
 * get wired to a live [com.systemsgo.hex.rdp.native.AFreeRdpBridge] and how
 * "connects" is defined (mirrors FreeRDP's own ChannelConnected/
 * ChannelDisconnected PubSub events, relayed generically for every channel
 * by `systemsgo_notify_channel_lifecycle()` in `systemsgo_jni.c`).
 *
 * SCOPE: this only gives *lifecycle* visibility (connected/disconnected),
 * not raw channel data. Reading/writing the actual channel payload for the
 * channels this app already implements (clipboard, RAIL windows, audio,
 * graphics, touch, printer/smartcard/drive redirection, display resize)
 * still goes through their existing dedicated, typed APIs on
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge] (e.g. [sendClipboardText],
 * [sendTouchFrame]) — a plugin is the right tool for "do something when
 * channel X becomes available/unavailable" (update a UI badge, log/audit,
 * gate a feature toggle, request a channel be attempted at all via
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.registerDynamicChannel]),
 * not for defining a brand-new raw byte-stream protocol — FreeRDP's dynamic
 * channel loader on this project's static (no-dlopen) Android build can
 * only ever open channels FreeRDP itself ships an addin for and this
 * build's FreeRDP prebuilt was actually compiled with; see SETUP.md's
 * "GENERIC-VCHANNEL FEATURE" section for the exact limits and how to check
 * what a given build supports.
 */
interface RdpChannelPlugin {
    /**
     * The RDP virtual channel name this plugin cares about, exactly as
     * FreeRDP reports it (case-sensitive) — e.g. "rdpecam", "rdpgfx",
     * "cliprdr", "rail", "disp", "rdpei", "rdpsnd", "audin", "rdpdr", or any
     * name previously passed to
     * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.registerDynamicChannel].
     */
    val channelName: String

    /** Called once this session's [channelName] channel has connected. */
    fun onChannelConnected() {}

    /**
     * Called once this session's [channelName] channel has disconnected
     * (including at the end of the session, if it was ever connected).
     */
    fun onChannelDisconnected() {}
}
