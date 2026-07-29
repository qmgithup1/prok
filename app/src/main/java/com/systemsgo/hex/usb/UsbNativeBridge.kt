package com.systemsgo.hex.usb

import android.util.Log

/**
 * USB-REDIRECT FEATURE (Part 1/3 — Android/Kotlin layer): the JNI contract
 * between this Kotlin USB Host API layer and FreeRDP's URBDRC dynamic
 * virtual channel (MS-RDPEUSB), matching the shape already established by
 * [com.systemsgo.hex.smartcard.PcscUsbBridge]/`pcsc_shim.c` for the
 * smartcard feature: transfers are *not* proxied through a real Linux
 * usbfs/libusb backend (URBDRC's stock Linux backend, `urbdrc_channel`'s
 * `libusb_udevman`, needs libusb + a live usbfs mount — neither exists in an
 * unrooted Android app sandbox). Instead this bridge lets URBDRC's channel
 * core call into a custom `IUDEVICE`/`IUDEVMAN` backend
 * (`android_udevman`, implemented in `systemsgo_urbdrc_jni.c` — see
 * PART_2_PROMPT.md) whose every actual I/O call is forwarded here, onto a
 * real [android.hardware.usb.UsbDeviceConnection] already opened by
 * [UsbRedirectionManager].
 *
 * ## Why the bridge is directional the way it is
 * - Kotlin → native: [nativeDeviceAttached]/[nativeDeviceDetached] tell the
 *   URBDRC channel core a device just became available/unavailable — this
 *   is the Android-side hot-plug signal reaching MS-RDPEUSB's own
 *   Add/RemoveVirtualChannel-equivalent device-list notifications.
 * - native → Kotlin: [performControlTransfer]/[performBulkOrInterruptTransfer]/
 *   [performReset] are `@JvmStatic` targets the native `android_udevman`
 *   backend calls (via `CallStaticObjectMethod`/`CallStaticIntMethod`) every
 *   time URBDRC's channel core issues a transfer request (an incoming
 *   `URB_*` PDU per MS-RDPEUSB §2.2) that needs to actually reach hardware.
 *   This mirrors `pcsc_shim.c`'s `SCard*` forwarding shape exactly, just for
 *   USB transfers instead of PC/SC APDUs.
 *
 * All methods here are safe to call from any native thread: URBDRC's
 * channel core runs transfer requests on its own worker thread(s), not the
 * Java/Kotlin main thread — see [UsbRedirectionManager]'s doc comment for
 * how the Kotlin side keeps its own state thread-safe against that.
 *
 * This class intentionally has ZERO USB Host API calls of its own — it only
 * type-marshals between JNI primitives and [UsbRedirectionManager]'s public
 * API, so the only place that ever touches [android.hardware.usb] directly
 * is [UsbRedirectionManager] (single source of truth for open handles).
 */
object UsbNativeBridge {
    private const val TAG = "UsbNativeBridge"

    /**
     * Whether `libsystemsgo_urbdrc_jni.so` (built in Part 2) is present. Same
     * fail-soft shape as [com.systemsgo.hex.rdp.native.AFreeRdpBridge.isAvailable] —
     * until Part 2/3 land, USB redirection safely no-ops instead of crashing:
     * [UsbRedirectionManager] checks this before ever registering the
     * URBDRC channel with a session.
     */
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("systemsgo_urbdrc_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.i(TAG, "Native URBDRC bridge not present yet — USB redirection unavailable " +
                "until Part 2 (native JNI bridge) is built. See PART_2_PROMPT.md.")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Unexpected error probing native URBDRC bridge", e)
            false
        }
    }

    // ── Kotlin → native: lifecycle notifications ────────────────────────

    /**
     * Tells the native `android_udevman` backend a device is now open
     * ([android.hardware.usb.UsbDeviceConnection] already established by
     * [UsbRedirectionManager]) and should be announced to the remote
     * session's URBDRC channel as newly attached. `fd` is the raw file
     * descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor] —
     * the native side never opens `/dev/bus/usb/...` itself (it has no
     * permission to; only [UsbRedirectionManager], via [android.hardware.usb.UsbManager],
     * can obtain that fd), matching how every Android USB Host native
     * backend (e.g. libusb's Android backend) is required to receive an
     * already-open fd rather than opening the device node directly.
     *
     * Returns a native-assigned device handle (opaque to Kotlin) used as
     * the `deviceId` in every subsequent transfer/detach call for this
     * device, or -1 if the native side rejected the device (e.g. channel
     * not currently connected).
     */
    external fun nativeDeviceAttached(
        deviceKey: String,
        fd: Int,
        vendorId: Int,
        productId: Int,
        deviceClass: Int,
        deviceSubclass: Int,
        deviceProtocol: Int,
        speed: Int,
        rawDeviceDescriptor: ByteArray,
        rawConfigurationDescriptor: ByteArray,
    ): Int

    /** Tells the native backend a previously attached device is gone — see [UsbRedirectionManager.handleDeviceDetached]. */
    external fun nativeDeviceDetached(deviceId: Int)

    /** Registers/unregisters the URBDRC dynamic channel itself against the active FreeRDP session handle from [com.systemsgo.hex.rdp.native.AFreeRdpBridge]. */
    external fun nativeSetChannelActive(sessionHandle: Long, active: Boolean)

    // ── native → Kotlin: transfer callbacks (JvmStatic, called off any native thread) ──

    /**
     * Executes a USB control transfer for `deviceId`, mirroring
     * [android.hardware.usb.UsbDeviceConnection.controlTransfer]'s
     * parameters exactly (this method's whole job is to be a 1:1 JNI-safe
     * reflection of that call — see [UsbRedirectionManager.executeControlTransfer]
     * for where it's actually issued). `timeoutMs` of 0 means "no timeout",
     * matching Android's own convention for that API.
     *
     * Returns the number of bytes transferred (>=0), or a negative value on
     * failure — never throws across the JNI boundary (all exceptions are
     * caught and converted, see [UsbRedirectionManager.executeControlTransfer]).
     */
    @JvmStatic
    fun performControlTransfer(
        deviceId: Int,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): Int = UsbRedirectionManager.instanceOrNull
        ?.executeControlTransfer(deviceId, requestType, request, value, index, buffer, length, timeoutMs)
        ?: -1

    /**
     * Executes a bulk or interrupt transfer on one endpoint — both share a
     * single Android API ([android.hardware.usb.UsbDeviceConnection.bulkTransfer]/
     * [android.hardware.usb.UsbRequest] for interrupt), so `isInterrupt`
     * just picks which one [UsbRedirectionManager] issues, matching how
     * MS-RDPEUSB itself models both as generic "URB transfer" PDUs
     * differing only by endpoint type, not by wire format.
     */
    @JvmStatic
    fun performBulkOrInterruptTransfer(
        deviceId: Int,
        endpointAddress: Int,
        buffer: ByteArray?,
        length: Int,
        isInterrupt: Boolean,
        timeoutMs: Int,
    ): Int = UsbRedirectionManager.instanceOrNull
        ?.executeDataTransfer(deviceId, endpointAddress, buffer, length, isInterrupt, timeoutMs)
        ?: -1

    /** USB port/endpoint reset — see [UsbRedirectionManager.executeReset]. Returns true on success. */
    @JvmStatic
    fun performReset(deviceId: Int): Boolean =
        UsbRedirectionManager.instanceOrNull?.executeReset(deviceId) ?: false

    /** Claims/releases a USB interface so the native side can select alternate settings — see [UsbRedirectionManager.executeSetInterface]. */
    @JvmStatic
    fun performSetInterface(deviceId: Int, interfaceNumber: Int, alternateSetting: Int): Boolean =
        UsbRedirectionManager.instanceOrNull?.executeSetInterface(deviceId, interfaceNumber, alternateSetting) ?: false

    /** Structured log callback so native-side URBDRC errors surface in the same debug log the Kotlin side writes to — see [UsbRedirectionManager.onNativeLog]. */
    @JvmStatic
    fun nativeLog(level: Int, tag: String, message: String) {
        UsbRedirectionManager.instanceOrNull?.onNativeLog(level, tag, message)
    }
}
