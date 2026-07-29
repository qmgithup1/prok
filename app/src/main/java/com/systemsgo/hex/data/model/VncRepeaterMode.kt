package com.systemsgo.hex.data.model

/**
 * ULTRAVNC-REPEATER FEATURE: which of UltraVNC repeater's two connection
 * modes a VNC profile with [RdpProfile.vncRepeaterEnabled] == true should
 * use. Same String-backed-enum pattern as [ProxyType]/[CodecPreference].
 *
 * - [MODE_I]: the repeater is configured (server-side, in its own .ini —
 *   `[MODE_I] IP1=... ; PORT1=...`) to map one fixed local port straight
 *   through to one specific target server. `host`/`port` on the profile
 *   are simply that mapped port on the repeater — from the wire protocol's
 *   perspective this is indistinguishable from a direct connection, so no
 *   ID frame is ever sent (see RfbConnectable's class doc). [vncRepeaterId]
 *   is not used in this mode.
 * - [MODE_II]: ID-based routing. `host`/`port` point at the repeater's
 *   shared listening port; immediately after TCP connect, the client sends
 *   a fixed 250-byte `"ID:<id>"` frame (RfbConnectable.sendRepeaterIdFrame)
 *   so the repeater can splice the connection to whichever target server
 *   registered that same ID. Requires [RdpProfile.vncRepeaterId] to be set.
 *
 * Defaults to MODE_II — the mode this app supported before Mode I existed,
 * so every profile persisted before this enum was introduced
 * (MIGRATION_31_32) keeps behaving exactly as it did.
 */
enum class VncRepeaterMode {
    MODE_I,
    MODE_II;

    companion object {
        fun fromName(value: String): VncRepeaterMode =
            entries.firstOrNull { it.name == value } ?: MODE_II
    }
}
