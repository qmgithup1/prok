package com.systemsgo.hex.redfish.protocol

/** Redfish ResetType values (DSP0268) — subset every implementation supports. */
enum class RedfishResetType(val wireValue: String, val label: String) {
    ON("On", "Power On"),
    FORCE_OFF("ForceOff", "Force Power Off"),
    GRACEFUL_SHUTDOWN("GracefulShutdown", "Graceful Shutdown"),
    GRACEFUL_RESTART("GracefulRestart", "Graceful Restart"),
    FORCE_RESTART("ForceRestart", "Force Restart"),
    NMI("Nmi", "Diagnostic Interrupt (NMI)"),
    FORCE_ON("ForceOn", "Force Power On"),
    PUSH_POWER_BUTTON("PushPowerButton", "Push Power Button"),
}

data class RedfishSystemSummary(
    val id: String,
    val name: String,
    val powerState: String,
    val health: String?,
    val model: String?,
    val manufacturer: String?,
    val serialNumber: String?,
    val biosVersion: String?,
    val processorSummary: String?,
    val memorySummaryGiB: Double?,
    val odataId: String,
)

data class RedfishChassisSummary(
    val id: String,
    val name: String,
    val health: String?,
    val odataId: String,
)

data class RedfishManagerSummary(
    val id: String,
    val name: String,
    val firmwareVersion: String?,
    val odataId: String,
)

data class RedfishSensorReading(
    val name: String,
    val reading: Double?,
    val units: String?,
    val health: String?,
    val upperCritical: Double? = null,
    val lowerCritical: Double? = null,
)

data class RedfishLogEntry(
    val id: String,
    val created: String?,
    val severity: String?,
    val message: String?,
    val entryType: String?,
)

data class RedfishVirtualMedia(
    val id: String,
    val name: String,
    val mediaTypes: List<String>,
    val inserted: Boolean,
    val image: String?,
    val odataId: String,
)

data class RedfishTaskStatus(
    val id: String,
    val state: String,
    val percentComplete: Int?,
    val messages: List<String>,
)

class RedfishException(message: String, val httpStatus: Int? = null, cause: Throwable? = null) : Exception(message, cause)
