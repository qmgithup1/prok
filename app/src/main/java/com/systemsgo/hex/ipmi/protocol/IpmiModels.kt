package com.systemsgo.hex.ipmi.protocol

/** IPMI Chassis Control command (0x00/0x02) actions — IPMI spec table 28-8. */
enum class IpmiPowerAction(val code: Int, val label: String) {
    POWER_DOWN(0x00, "Power Off"),
    POWER_UP(0x01, "Power On"),
    POWER_CYCLE(0x02, "Power Cycle"),
    HARD_RESET(0x03, "Hard Reset"),
    PULSE_DIAGNOSTIC_INTERRUPT(0x04, "Diagnostic Interrupt (NMI)"),
    SOFT_SHUTDOWN(0x05, "Soft Shutdown (ACPI)"),
}

data class IpmiChassisStatus(
    val powerIsOn: Boolean,
    val overload: Boolean,
    val interlock: Boolean,
    val fault: Boolean,
    val controlFault: Boolean,
    val lastPowerOnCause: String,
    val identifySupported: Boolean,
)

data class IpmiDeviceId(
    val deviceId: Int,
    val firmwareVersion: String,
    val manufacturerId: Int,
    val productId: Int,
    val ipmiVersion: String,
)

data class IpmiSelInfo(
    val version: String,
    val entryCount: Int,
    val freeSpaceBytes: Int,
    val supportsOverflow: Boolean,
)

data class IpmiSelEntry(
    val recordId: Int,
    val timestamp: Long, // seconds since epoch, 0 if pre-init/unspecified
    val sensorType: Int,
    val sensorNumber: Int,
    val eventDescription: String,
    val raw: ByteArray,
)

data class IpmiSensorReading(
    val sensorNumber: Int,
    val name: String,
    val rawReading: Int,
    val convertedValue: Double?,
    val unit: String,
    val eventStatusRaw: Int,
    val readingUnavailable: Boolean,
)

/**
 * A sensor discovered by walking the SDR (Sensor Data Record) Repository —
 * unlike [IpmiSensorReading] (which needs the caller to already know a
 * sensor number), this is how sensor names/units are actually resolved: the
 * SDR gives the human-readable ID string plus, for Full records, the
 * linear-conversion factors needed to turn a raw byte into a real-world
 * value. Compact records (no analog conversion data) still surface their
 * ID/type, just with [value] left null.
 */
data class IpmiSensor(
    val sensorNumber: Int,
    val name: String,
    val sensorType: Int,
    val sensorTypeLabel: String,
    val entityId: Int,
    val isFullRecord: Boolean,
    val value: Double?,
    val unit: String,
    val rawReading: Int,
    val readingUnavailable: Boolean,
    val stateAsserted: Boolean,
)

/** Parsed FRU (Field Replaceable Unit) inventory — the BMC/board's own
 *  "asset tag" data, read from FRU Device ID 0 (the BMC's own controller,
 *  which on essentially every server board *is* the baseboard FRU). Fields
 *  are null when that Info Area is absent or empty, which is common/valid —
 *  not every vendor populates every area. */
data class IpmiFruInfo(
    val chassisType: String?,
    val chassisPartNumber: String?,
    val chassisSerial: String?,
    val boardManufacturer: String?,
    val boardProduct: String?,
    val boardSerial: String?,
    val boardPartNumber: String?,
    val productManufacturer: String?,
    val productName: String?,
    val productPartNumber: String?,
    val productSerial: String?,
    val productAssetTag: String?,
)

/** BMC LAN Configuration Parameters (NetFn 0x0C) for one channel — the
 *  network identity the BMC itself uses, independent of the host OS's NIC. */
data class IpmiLanConfig(
    val channel: Int,
    val ipAddress: String,
    val ipSource: String, // "Static", "DHCP", "BIOS/other", "unspecified"
    val subnetMask: String,
    val macAddress: String,
    val defaultGateway: String,
    val vlanEnabled: Boolean,
    val vlanId: Int?,
)

/** One BMC-local IPMI user account (Get User Access + Get User Name, NetFn 0x06). */
data class IpmiUserAccount(
    val userId: Int,
    val name: String,
    val enabled: Boolean,
    val privilege: String, // "Callback"/"User"/"Operator"/"Administrator"/"No Access"/"Unknown"
    val callInEnabled: Boolean,
    val linkAuthEnabled: Boolean,
    val ipmiMessagingEnabled: Boolean,
)

/** PEF (Platform Event Filtering) global status — Get PEF Capabilities +
 *  Get PEF Configuration Parameters, param 1 (NetFn 0x04). Full alert-policy
 *  table configuration is out of scope; this covers "is PEF on at all". */
data class IpmiPefStatus(
    val supported: Boolean,
    val pefEnabled: Boolean,
    val pefEventMessagesEnabled: Boolean,
    val version: String,
    val supportedActionsRaw: Int,
)

enum class IpmiWatchdogAction(val code: Int, val label: String) {
    NO_ACTION(0x00, "No action"),
    HARD_RESET(0x01, "Hard reset"),
    POWER_DOWN(0x02, "Power down"),
    POWER_CYCLE(0x03, "Power cycle"),
}

enum class IpmiWatchdogUse(val code: Int, val label: String) {
    BIOS_FRB2(0x01, "BIOS/FRB2"),
    BIOS_POST(0x02, "BIOS POST"),
    OS_LOAD(0x03, "OS Load"),
    SMS_OS(0x04, "SMS/OS Watchdog"),
    OEM(0x05, "OEM"),
}

/** Get Watchdog Timer response (NetFn 0x06 App, cmd 0x25). */
data class IpmiWatchdogConfig(
    val running: Boolean,
    val use: IpmiWatchdogUse,
    val action: IpmiWatchdogAction,
    val preTimeoutIntervalSeconds: Int,
    val initialCountdownSeconds: Double,
    val presentCountdownSeconds: Double,
)

/** Thrown for any IPMI-LAN-level failure: RMCP+ handshake, completion codes, timeouts. */
class IpmiException(message: String, cause: Throwable? = null) : Exception(message, cause)
