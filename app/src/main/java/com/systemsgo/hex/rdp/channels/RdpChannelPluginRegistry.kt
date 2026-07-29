package com.systemsgo.hex.rdp.channels

import com.systemsgo.hex.rdp.native.AFreeRdpBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * GENERIC-VCHANNEL FEATURE (Plugin System): dispatches
 * [AFreeRdpBridge.channelLifecycle] events to whichever registered
 * [RdpChannelPlugin]s care about that channel's name. This is the "plugin
 * system" half of the feature — [AFreeRdpBridge] itself only knows how to
 * emit one generic event stream (see `systemsgo_notify_channel_lifecycle()` in
 * `systemsgo_jni.c`); this class is what turns that into "call this specific
 * object's callback."
 *
 * One registry instance is meant to live for as long as the screen/session
 * that owns it (typically alongside the `RdpSessionActivity`/ViewModel that
 * already owns the [AFreeRdpBridge] instance) — create it, [register] every
 * plugin the screen cares about up front, then [wire] it to the bridge once
 * a session's connect() is imminent/underway. Plugins can be registered and
 * unregistered at any time, including mid-session; only the lifecycle
 * events for channels that are still connected *after* a late registration
 * won't be replayed (this registry does not remember past events — a
 * plugin registered after its channel already connected simply won't see
 * that particular `onChannelConnected()` call until the channel
 * reconnects, e.g. on the next session).
 *
 * Not thread-safety-hardened beyond what a `synchronized` block buys —
 * fine for this app's usage (register calls from the UI/main thread,
 * dispatch from one collector coroutine), not meant as a general-purpose
 * concurrent pub/sub utility.
 */
class RdpChannelPluginRegistry {

    private val lock = Any()
    private val pluginsByChannel = mutableMapOf<String, MutableList<RdpChannelPlugin>>()
    private var wireJob: Job? = null

    /** Registers [plugin] to receive [RdpChannelPlugin.channelName] lifecycle events. */
    fun register(plugin: RdpChannelPlugin) {
        synchronized(lock) {
            pluginsByChannel.getOrPut(plugin.channelName) { mutableListOf() }.add(plugin)
        }
    }

    /** Removes a previously [register]ed plugin; a no-op if it was never registered. */
    fun unregister(plugin: RdpChannelPlugin) {
        synchronized(lock) {
            pluginsByChannel[plugin.channelName]?.remove(plugin)
        }
    }

    /**
     * Convenience for the common case: [register] this plugin AND ask
     * [bridge] to load [RdpChannelPlugin.channelName] as a dynamic channel
     * via [AFreeRdpBridge.registerDynamicChannel] the next time it connects.
     *
     * CAUTION: only use this for a channel name this app does **not**
     * already request through one of [AFreeRdpBridge.connect]'s dedicated
     * enableXxx parameters (webcam/audio/clipboard/drive/printer/
     * smartcard/parallel/serial/display/touch/graphics all already register
     * their own channel when their flag is true) — requesting the same
     * dynamic channel name twice is untested against this build's FreeRDP
     * and best avoided. For those already-handled channels, just
     * [register] the plugin (no request call needed, the channel gets
     * requested elsewhere) and rely on this registry's dispatch alone.
     * Must be called before [AFreeRdpBridge.connect] to take effect this
     * session — see [AFreeRdpBridge.registerDynamicChannel]'s doc.
     */
    fun registerAndRequestChannel(bridge: AFreeRdpBridge, plugin: RdpChannelPlugin) {
        register(plugin)
        bridge.registerDynamicChannel(plugin.channelName)
    }

    /**
     * Starts dispatching [bridge]'s [AFreeRdpBridge.channelLifecycle]
     * events to registered plugins, collecting on [scope]. Safe to call
     * again (e.g. on reconnect with a fresh/reused bridge instance) — any
     * previous collection is cancelled first. Call [unwire] when the owning
     * screen is torn down if [scope] itself doesn't already get cancelled
     * at that point.
     */
    fun wire(bridge: AFreeRdpBridge, scope: CoroutineScope) {
        wireJob?.cancel()
        wireJob = scope.launch {
            bridge.channelLifecycle.collect { event ->
                val targets = synchronized(lock) { pluginsByChannel[event.name]?.toList() }
                if (targets.isNullOrEmpty()) return@collect
                for (plugin in targets) {
                    if (event.connected) plugin.onChannelConnected() else plugin.onChannelDisconnected()
                }
            }
        }
    }

    /** Stops dispatching events until [wire] is called again. */
    fun unwire() {
        wireJob?.cancel()
        wireJob = null
    }
}
