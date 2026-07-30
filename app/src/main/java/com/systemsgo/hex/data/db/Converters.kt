package com.systemsgo.hex.data.db

import androidx.room.TypeConverter
import com.systemsgo.hex.data.model.CodecPreference
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.ProxyType
import com.systemsgo.hex.data.model.RemoteAppDisplayMode
import com.systemsgo.hex.data.model.SerialParity
import com.systemsgo.hex.data.model.SerialRedirectMode
import com.systemsgo.hex.data.model.SerialStopBits
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

    // ── Serial Console / Serial-over-Network (RdpProfile.serialRedirectMode /
    // serialConsoleTransport / serialConsoleParity / serialConsoleStopBits) ──
    // BUG FIX: these three enum-typed columns had no registered TypeConverter
    // at all — Room's schema validation fails for the whole RdpProfile entity
    // (and therefore the whole @Database) the moment any of its columns has an
    // unconvertible type, which is exactly what surfaced as KSP's cascading
    // "SystemsGoDatabase could not be resolved" errors. Same String-backed
    // enum pattern as SshAuthType above, including the same defensive
    // firstOrNull-with-fallback so a future renamed/removed enum constant in
    // an old on-disk value degrades to a safe default instead of crashing.
    @TypeConverter
    fun fromSerialRedirectMode(value: SerialRedirectMode): String = value.name

    @TypeConverter
    fun toSerialRedirectMode(value: String): SerialRedirectMode =
        SerialRedirectMode.entries.firstOrNull { it.name == value } ?: SerialRedirectMode.LOCAL_DEVICE

    @TypeConverter
    fun fromSerialParity(value: SerialParity): String = value.name

    @TypeConverter
    fun toSerialParity(value: String): SerialParity =
        SerialParity.entries.firstOrNull { it.name == value } ?: SerialParity.NONE

    @TypeConverter
    fun fromSerialStopBits(value: SerialStopBits): String = value.name

    @TypeConverter
    fun toSerialStopBits(value: String): SerialStopBits =
        SerialStopBits.entries.firstOrNull { it.name == value } ?: SerialStopBits.ONE

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
