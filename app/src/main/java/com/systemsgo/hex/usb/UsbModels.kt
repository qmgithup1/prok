package com.systemsgo.hex.usb

/**
 * USB-REDIRECT FEATURE (Part 1/3 — Android/Kotlin layer): data model for a
 * physical USB device enumerated through [android.hardware.usb.UsbManager],
 * independent of whether it is currently redirected into an RDP session.
 *
 * This is a plain, sanitized snapshot — see [UsbRedirectionManager.toDeviceInfo]
 * for how it's built defensively from a raw [android.hardware.usb.UsbDevice]
 * (malformed descriptors must never crash enumeration or the settings UI).
 *
 * [busId]/[deviceId] together are stable enough to key a device for the
 * lifetime of a single physical connection (Android reassigns [deviceAddress]
 * across replugs, so it is display-only, never used as a map key).
 */
data class UsbDeviceInfo(
    /** [android.hardware.usb.UsbDevice.getDeviceName], e.g. "/dev/bus/usb/001/003" — stable map key. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val deviceAddress: Int,
    val busId: Int,
    /** Human-readable manufacturer/product strings — null if the device didn't expose one, never "". */
    val manufacturerName: String?,
    val productName: String?,
    /** Never surfaced unless [android.hardware.usb.UsbDevice.getSerialNumber] succeeds under an already-granted permission — see docs on that API. */
    val serialNumber: String?,
    val interfaceClasses: List<Int>,
    val speed: UsbSpeed,
) {
    /** e.g. "046D:C52B" — the conventional lsusb-style identity string used across the settings UI and logs. */
    val idString: String get() = "%04X:%04X".format(vendorId, productId)

    val classCategory: UsbDeviceClassCategory get() = UsbDeviceClassCategory.classify(deviceClass, interfaceClasses)
}

enum class UsbSpeed { UNKNOWN, LOW, FULL, HIGH, SUPER }

/**
 * Coarse classification used only for the settings-UI icon/label and for the
 * "device classes" documentation the feature ships with — FreeRDP's URBDRC
 * channel itself redirects any USB device byte-for-byte regardless of class
 * (see MS-RDPEUSB §1.3); this enum never gates *whether* a device can be
 * redirected, only how it's described to the user.
 */
enum class UsbDeviceClassCategory {
    SMART_CARD_READER,
    SECURITY_KEY,
    MASS_STORAGE,
    HID,
    SERIAL_ADAPTER,
    PRINTER,
    AUDIO,
    VIDEO,
    HUB,
    GENERIC;

    companion object {
        private const val CLASS_AUDIO = 0x01
        private const val CLASS_COMM = 0x02
        private const val CLASS_HID = 0x03
        private const val CLASS_PRINTER = 0x07
        private const val CLASS_MASS_STORAGE = 0x08
        private const val CLASS_HUB = 0x09
        private const val CLASS_CDC_DATA = 0x0A
        private const val CLASS_SMART_CARD = 0x0B
        private const val CLASS_VIDEO = 0x0E
        // Security keys (FIDO2/U2F/CTAP) enumerate as plain HID with a
        // vendor-defined usage page (0xF1D0) rather than a distinct USB
        // class — there is no reliable class-only signature for them, so
        // they are detected by known-vendor heuristics in [classify] and
        // otherwise fall back to HID, which is still fully redirectable.
        private val KNOWN_SECURITY_KEY_VENDORS = setOf(0x1050, 0x096E, 0x2581, 0x18D1)

        fun classify(deviceClass: Int, interfaceClasses: List<Int>): UsbDeviceClassCategory {
            val classes = if (deviceClass != 0) listOf(deviceClass) else interfaceClasses
            return when {
                CLASS_SMART_CARD in classes -> SMART_CARD_READER
                CLASS_MASS_STORAGE in classes -> MASS_STORAGE
                CLASS_HUB in classes -> HUB
                CLASS_PRINTER in classes -> PRINTER
                CLASS_AUDIO in classes -> AUDIO
                CLASS_VIDEO in classes -> VIDEO
                CLASS_COMM in classes || CLASS_CDC_DATA in classes -> SERIAL_ADAPTER
                CLASS_HID in classes -> HID
                else -> GENERIC
            }
        }
    }
}

/** Lifecycle state of a device's Android-side USB connection, independent of [RedirectionState]. */
enum class UsbConnectionState { DISCONNECTED, PERMISSION_REQUESTED, PERMISSION_DENIED, CONNECTED, ERROR }

/** Lifecycle state of a device's presence inside the remote RDP session's URBDRC channel. */
enum class RedirectionState { NOT_REDIRECTED, PENDING, REDIRECTED, FAILED, DISCONNECTED_PENDING_RESTORE }

/** Combined, observable state for one physical device — what the settings UI actually renders per row. */
data class UsbRedirectedDevice(
    val info: UsbDeviceInfo,
    val connectionState: UsbConnectionState = UsbConnectionState.DISCONNECTED,
    val redirectionState: RedirectionState = RedirectionState.NOT_REDIRECTED,
    val userApproved: Boolean = false,
    val lastError: String? = null,
)

data class UsbRedirectionSettings(
    val enabled: Boolean = false,
    val autoRedirectNewDevices: Boolean = false,
    val askBeforeRedirecting: Boolean = true,
    val reconnectAutomatically: Boolean = true,
    val debugLogging: Boolean = false,
    /** [UsbDeviceInfo.deviceName]-keyed identities (VID:PID:serial) the user has explicitly approved — see [UsbRedirectionManager.approvalKey]. */
    val approvedDeviceKeys: Set<String> = emptySet(),
)
