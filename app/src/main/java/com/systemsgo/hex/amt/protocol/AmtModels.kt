package com.systemsgo.hex.amt.protocol

/**
 * Intel AMT power actions via CIM_PowerManagementService.RequestPowerStateChange.
 * Restricted to the four values the Intel AMT SDK documents as "always
 * supported" across firmware releases 3.0-11.0+ regardless of whether the
 * OS-side LMS driver is installed — the common case for the bare-metal /
 * headless-box scenario this feature is mainly for (a machine with no OS
 * booted yet, or a crashed OS, is exactly when out-of-band power control is
 * needed). Mirrors [com.systemsgo.hex.ipmi.protocol.IpmiPowerAction]'s shape
 * (code + label) so [BmcManagementScreen]'s `FlowRowButtons` helper works
 * unchanged for AMT.
 */
enum class AmtPowerAction(val wsmanValue: Int, val label: String) {
    POWER_ON(2, "Power On"),
    POWER_CYCLE(5, "Power Cycle"),
    POWER_OFF(8, "Power Off"),
    MASTER_BUS_RESET(10, "Hard Reset"),
}

/**
 * Read-only snapshot of AMT_GeneralSettings — used as both the
 * connectivity/identity check right after [AmtClient.connect] and the
 * "what am I even connected to" header on the Power tab, the AMT
 * counterpart to [com.systemsgo.hex.redfish.protocol.RedfishSystemSummary] /
 * [com.systemsgo.hex.ipmi.protocol.IpmiDeviceId].
 */
data class AmtGeneralInfo(
    val amtVersion: String?,
    val hostName: String?,
    val networkInterfaceEnabled: Boolean,
    val digestRealm: String?,
)

/** Current reported power state (CIM_AssociatedPowerManagementService.PowerState). */
data class AmtPowerStatus(
    val stateValue: Int,
    val label: String,
)

/**
 * A one-shot boot target settable via [AmtClient.setOneShotBoot] — phase 2's
 * "boot to PXE/BIOS/CD" feature. [PXE]/[CD_DVD]/[HARD_DRIVE] are the fixed
 * `CIM_BootSourceSetting` instances Intel AMT always exposes (see
 * `CIM_BootConfigSetting.ChangeBootOrder` in the AMT SDK class reference);
 * [BIOS_SETUP] isn't a boot *source* at all — it's the
 * `AMT_BootSettingData.BIOSSetup` flag, mutually exclusive with the other
 * three, which is why [AmtClient.setOneShotBoot] routes it down a different
 * WS-Man path (`AMT_BootSettingData.Put` instead of `ChangeBootOrder`).
 *
 * [IDER_FLOPPY]/[IDER_CD_DVD] close the AMT_VPRO_ROADMAP.md phase 5 "open
 * follow-up": arming the next boot to IDE-R virtual media, the same
 * `AMT_BootSettingData.Put` path as [BIOS_SETUP] but setting `UseIDER=true`
 * + `IDERBootDevice` (see [AmtIderMediaType]'s `iderBootDeviceValue`)
 * instead of `BIOSSetup=true` — also mutually exclusive with a
 * `CIM_BootSourceSetting` boot source, so [AmtClient.setOneShotBoot] clears
 * any existing selection first, same as [BIOS_SETUP]. Only takes effect on
 * AMT 3.0–10.x firmware — see the phase 5 deprecation note in
 * AMT_VPRO_ROADMAP.md; arming it on newer (USB-R-only) firmware is harmless,
 * it just won't do anything on reset.
 */
enum class AmtBootDevice(val label: String, val bootSourceInstanceId: String?) {
    PXE("PXE Network Boot", "Intel(r) AMT: Force PXE Boot"),
    CD_DVD("CD/DVD", "Intel(r) AMT: Force CD/DVD Boot"),
    HARD_DRIVE("Hard Drive", "Intel(r) AMT: Force Hard-drive Boot"),
    BIOS_SETUP("BIOS Setup", null),
    IDER_FLOPPY("IDE-R Floppy", null),
    IDER_CD_DVD("IDE-R CD/DVD", null),
}

/**
 * One decoded `AMT_AuditLog.ReadRecords` entry — the AMT counterpart to
 * [com.systemsgo.hex.ipmi.protocol.IpmiSelEntry] /
 * [com.systemsgo.hex.redfish.protocol.RedfishLogEntry]. Unlike those two,
 * AMT's audit log isn't XML — `ReadRecords` returns each record as a
 * base64-encoded fixed-layout binary blob (see
 * [AmtClient.decodeAuditRecord]), so this is a best-effort decode: fields
 * this app couldn't confidently parse (Kerberos-initiated records, whose
 * SID encoding isn't in Intel's public WS-Man class reference) are left
 * null rather than guessed at.
 */
data class AmtAuditLogEntry(
    val auditAppId: Int,
    val auditAppName: String,
    val eventId: Int,
    val initiatorType: Int,
    val initiator: String?,
    val timestampEpochSeconds: Long?,
    val netAddress: String?,
)

/**
 * One entry of `AMT_RedirectionService.AccessLog` — a real-time record of
 * Storage Redirection (IDE-R) and SOL sessions, distinct from
 * [AmtAuditLogEntry]'s general security audit log. Unlike the audit log,
 * Intel documents this property as already being an array of plain
 * strings (no binary decoding needed), each formatted
 * `"Date (MM/DD/YYYY), Time (hh:mm:ss), IP:Port"` — see
 * [AmtClient.getRedirectionAccessLog]. [date]/[time]/[ipPort] are a
 * best-effort split of that format; [raw] is always kept as a fallback
 * in case a firmware release formats it slightly differently.
 */
data class AmtRedirectionAccessLogEntry(
    val raw: String,
    val date: String?,
    val time: String?,
    val ipPort: String?,
)

/** Thrown for any AMT WS-Management failure: transport, Digest auth, SOAP
 *  Fault, or a non-zero CIM ReturnValue. */
class AmtException(message: String, val returnValueCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * IDE-R redirects one of two virtual device slots — a "floppy" (`0`) and a
 * "CD/DVD" (`1`) — matching `AMT_BootSettingData.IDERBootDevice`'s
 * ValueMap and the two PnP devices Intel's docs say Intel AMT exposes to
 * the managed OS once IDE-R is enabled. AMT-VPRO FEATURE phase 5 (see
 * [AmtIderSession] and AMT_VPRO_ROADMAP.md phase 5).
 */
enum class AmtIderMediaType(val iderBootDeviceValue: Int, val label: String) {
    FLOPPY(0, "Floppy (.img)"),
    CD_ROM(1, "CD/DVD (.iso)"),
}

/**
 * Lifecycle state of an [AmtIderSession], surfaced to the UI so it can show
 * *why* a session isn't streaming media rather than just "not connected".
 * [CHANNEL_OPEN] is as far as [AmtIderSession] currently goes — see that
 * class's doc comment for what's still missing before [MEDIA_ACTIVE] is
 * reachable.
 */
enum class AmtIderSessionState {
    CONNECTING,
    CHANNEL_OPEN,
    MEDIA_ACTIVE,
    CLOSED,
}
