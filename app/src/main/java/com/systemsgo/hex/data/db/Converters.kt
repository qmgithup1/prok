package com.systemsgo.hex.data.db

import androidx.room.TypeConverter
import com.systemsgo.hex.data.model.CodecPreference
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.ProxyType
import com.systemsgo.hex.data.model.RemoteAppDisplayMode
import com.systemsgo.hex.data.model.SshAuthType
import com.systemsgo.hex.data.model.SshJumpHop
import com.systemsgo.hex.data.model.SshJumpHopCodec
import com.systemsgo.hex.data.model.SshPortForwardCodec
import com.systemsgo.hex.data.model.SshPortForwardRule
import com.systemsgo.hex.data.model.VncRepeaterMode

/**
 * Room type converters for the enum fields introduced when multi-protocol
 * support (RDP / VNC / SSH) was added to [com.systemsgo.hex.data.model.RdpProfile].
 */
class Converters {
    @TypeConverter
    fun fromProtocolType(value: ProtocolType): String = value.name

    @TypeConverter
    fun toProtocolType(value: String): ProtocolType = ProtocolType.fromName(value)

    @TypeConverter
    fun fromSshAuthType(value: SshAuthType): String = value.name

    @TypeConverter
    fun toSshAuthType(value: String): SshAuthType =
        SshAuthType.entries.firstOrNull { it.name == value } ?: SshAuthType.PASSWORD

    // REMOTEAPP-WINDOWS FEATURE: same String-backed enum pattern as
    // ProtocolType/SshAuthType above.
    @TypeConverter
    fun fromRemoteAppDisplayMode(value: RemoteAppDisplayMode): String = value.name

    @TypeConverter
    fun toRemoteAppDisplayMode(value: String): RemoteAppDisplayMode = RemoteAppDisplayMode.fromName(value)

    // CODEC-NEGOTIATION FEATURE: same String-backed enum pattern as
    // ProtocolType/SshAuthType/RemoteAppDisplayMode above.
    @TypeConverter
    fun fromCodecPreference(value: CodecPreference): String = value.name

    @TypeConverter
    fun toCodecPreference(value: String): CodecPreference = CodecPreference.fromName(value)

    // OUTBOUND-PROXY FEATURE: same String-backed enum pattern as
    // ProtocolType/SshAuthType/RemoteAppDisplayMode/CodecPreference above.
    @TypeConverter
    fun fromProxyType(value: ProxyType): String = value.name

    @TypeConverter
    fun toProxyType(value: String): ProxyType = ProxyType.fromName(value)

    // ULTRAVNC-REPEATER FEATURE (Mode I/II): same String-backed enum pattern
    // as ProxyType above.
    @TypeConverter
    fun fromVncRepeaterMode(value: VncRepeaterMode): String = value.name

    @TypeConverter
    fun toVncRepeaterMode(value: String): VncRepeaterMode = VncRepeaterMode.fromName(value)

    // ── Tags (RdpProfile.tags) ───────────────────────────────────────────────
    // Lightweight storage: tags have no identity of their own, so they're
    // persisted as a single delimited string column instead of a join table.
    // "\u001F" (ASCII Unit Separator) is used as the delimiter instead of a
    // visible character like ',' so a tag that itself contains a comma is
    // never accidentally split into two tags.
    private val TAG_DELIMITER = "\u001F"

    @TypeConverter
    fun fromTagList(value: List<String>): String = value.joinToString(TAG_DELIMITER)

    @TypeConverter
    fun toTagList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(TAG_DELIMITER)

    // ── SSH Local/Remote Port Forwarding (RdpProfile.sshPortForwards) ────────
    // Same delimited-string approach as tags above; see SshPortForwardCodec
    // for the actual encode/decode logic (kept in the model package since it
    // has nothing Room-specific about it).
    @TypeConverter
    fun fromSshPortForwardList(value: List<SshPortForwardRule>): String =
        SshPortForwardCodec.encode(value)

    @TypeConverter
    fun toSshPortForwardList(value: String): List<SshPortForwardRule> =
        SshPortForwardCodec.decode(value)

    // ── SSH ProxyJump chain (RdpProfile.sshTunnelHops) ────────────────────────
    // Same delimited-string approach as sshPortForwards above; see
    // SshJumpHopCodec for the encode/decode logic. Hop secrets
    // (password/privateKey/privateKeyPassphrase) are encrypted/decrypted at
    // the repository layer (RdpProfileRepository.withEncryptedSecrets/
    // withDecryptedSecrets) BEFORE this converter ever runs, exactly like
    // every other secret field on RdpProfile — this converter only ever sees
    // (and only ever produces) already-encrypted-or-already-decrypted
    // strings, same as fromSshTunnelPassword would if it existed.
    @TypeConverter
    fun fromSshJumpHopList(value: List<SshJumpHop>): String =
        SshJumpHopCodec.encode(value)

    @TypeConverter
    fun toSshJumpHopList(value: String): List<SshJumpHop> =
        SshJumpHopCodec.decode(value)
}
