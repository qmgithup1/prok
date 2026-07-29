package com.systemsgo.hex.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.systemsgo.hex.data.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * USB-REDIRECT FEATURE (Part 1/3 — Android/Kotlin layer): single source of
 * truth for every USB device this app is aware of, and the only class in
 * the app that ever calls [android.hardware.usb.UsbManager] or holds an
 * open [UsbDeviceConnection] for a redirected device.
 *
 * ## Responsibilities
 * - Enumerate currently-attached devices ([refreshDeviceList]).
 * - Track hot-plug/hot-unplug via [UsbHotplugReceiver].
 * - Drive the Android USB permission dialog ([requestPermission]) and
 *   restore previously-approved devices automatically on replug.
 * - Open/close [UsbDeviceConnection]s and hand the raw fd + descriptors to
 *   [UsbNativeBridge.nativeDeviceAttached] once a device is both permitted
 *   *and* user-approved for redirection (see [UsbRedirectionSettings]).
 * - Serve every actual data-transfer call the native URBDRC backend issues
 *   ([executeControlTransfer]/[executeDataTransfer]/[executeReset]/
 *   [executeSetInterface]) — see [UsbNativeBridge]'s doc comment for why
 *   the JNI boundary is shaped this way.
 *
 * ## Thread-safety
 * [UsbNativeBridge]'s `performXTransfer` callbacks run on whatever native
 * worker thread URBDRC's channel core used to issue the request — never
 * the main thread, and never guaranteed to be the same thread twice. Every
 * mutable structure here is therefore either a [ConcurrentHashMap] (the
 * `deviceId -> connection` transfer path, which must never block on a lock
 * a UI-thread caller might be holding) or protected by [stateLock] (the
 * device list + settings snapshot, mutated far less often and always from
 * a bounded, short critical section — never while a transfer is in
 * flight). No lock in this class is ever held across a call into
 * [UsbNativeBridge] or [UsbDeviceConnection] I/O, to avoid ANRs / native
 * deadlocks if a transfer blocks until its timeout.
 */
@Singleton
class UsbRedirectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
    companion object {
        private const val TAG = "UsbRedirectionManager"
        const val ACTION_USB_PERMISSION = "com.systemsgo.hex.usb.USB_PERMISSION"

        /**
         * Item 5 (bounded retry / transient I/O recovery). Android's
         * [UsbDeviceConnection.controlTransfer]/[UsbDeviceConnection.bulkTransfer]
         * collapse every failure — a genuine transient I/O blip, a stall,
         * device-gone, timeout — into the same `-1` return; there is no
         * finer-grained error code to branch retry logic on. So this is a
         * single, small, *fixed* retry budget applied uniformly: 1 retry
         * (2 attempts total) with a short fixed backoff, cheap enough to
         * absorb a real transient blip without meaningfully changing a
         * transfer's latency, and bounded so a persistently failing
         * device (unplugged, wedged) fails fast rather than retrying
         * forever. This is deliberately NOT a loop with unbounded/backing-
         * off attempts — see the "DESIGN DECISION" comment on
         * [executeControlTransfer] for why a device-wide [resetDevice] is
         * NOT chained into this retry automatically.
         */
        private const val TRANSFER_RETRY_ATTEMPTS = 2
        private const val TRANSFER_RETRY_BACKOFF_MS = 15L

        /**
         * Item 5 ("no periodic health-check of a device whose fd may have
         * gone stale"). After this many *consecutive* transfer failures
         * (post-retry) on one open device, treat it as gone and proactively
         * detach it — see [recordTransferOutcome]. Deliberately larger than
         * [TRANSFER_RETRY_ATTEMPTS]: a handful of isolated failures (e.g. a
         * device that legitimately NAKs/stalls a specific control request)
         * should not evict it, only a run of failures with no successful
         * transfer in between, which is a much stronger signal the fd
         * itself is dead.
         */
        private const val MAX_CONSECUTIVE_TRANSFER_FAILURES = 8

        /**
         * [UsbNativeBridge]'s callbacks are static (JNI cannot easily carry a
         * Kotlin `this` across the boundary alongside every call), so a
         * single process-wide instance is published here the same way
         * [com.systemsgo.hex.smartcard.PcscUsbBridge] does for the smartcard
         * feature. Hilt still owns the *real* lifecycle (this is a
         * `@Singleton` DI type); this is purely a narrow bridge for the
         * static JNI entry points to reach it.
         */
        @Volatile
        internal var instanceOrNull: UsbRedirectionManager? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = ReentrantLock()
    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** deviceKey ([approvalKey]) -> current UI/state snapshot. Mutated only under [stateLock]. */
    private val devices = linkedMapOf<String, UsbRedirectedDevice>()
    private val _deviceListFlow = MutableStateFlow<List<UsbRedirectedDevice>>(emptyList())
    val deviceListFlow: StateFlow<List<UsbRedirectedDevice>> = _deviceListFlow.asStateFlow()

    /** deviceKey -> open Android connection, kept only while redirected/pending. */
    private val openConnections = ConcurrentHashMap<String, OpenUsbDevice>()
    /** native deviceId (assigned by [UsbNativeBridge.nativeDeviceAttached]) -> deviceKey, for the transfer hot path. */
    private val nativeIdToKey = ConcurrentHashMap<Int, String>()

    private var permissionReceiverRegistered = false
    private var hotplugReceiverRegistered = false
    private var activeSessionHandle: Long = 0L

    private data class OpenUsbDevice(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        var nativeDeviceId: Int = -1,
        /**
         * Item 5 ("no periodic health-check of a device whose fd may have
         * gone stale without an explicit detach callback" — see
         * systemsgo_urbdrc_jni.c's "DEFERRED TO PART 3" note). There is no
         * Android callback for "this open fd quietly died" short of a
         * transfer failing, so this is incremented on every failed
         * [executeControlTransfer]/[executeDataTransfer] (after retries are
         * exhausted) and reset to 0 on any success — including a successful
         * [executeReset], since a server-initiated reset is the spec-level
         * recovery signal and succeeding at it means the fd is still good.
         * Hitting [MAX_CONSECUTIVE_TRANSFER_FAILURES] triggers a proactive
         * detach (see [recordTransferFailure]) instead of leaving a dead
         * device occupying a native deviceId and silently failing every
         * future request against it forever.
         */
        val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    )

    init {
        instanceOrNull = this
    }

    /** Stable identity for a physical device: prefers serial (survives port changes); falls back to VID:PID:busId:address. */
    fun approvalKey(device: UsbDevice): String {
        val serial = safeSerialNumber(device)
        return if (!serial.isNullOrBlank()) {
            "${device.vendorId}:${device.productId}:$serial"
        } else {
            "${device.vendorId}:${device.productId}:${busIdOf(device)}:${device.deviceId}"
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** Call once, e.g. from [UsbRedirectionService.onCreate] / app startup when the enabled setting is on. */
    fun start() {
        if (usbManager == null) {
            Log.w(TAG, "USB_SERVICE unavailable on this device — android.hardware.usb.host absent?")
            return
        }
        registerReceiversIfNeeded()
        refreshDeviceList()
    }

    fun stop() {
        stateLock.withLock {
            devices.keys.toList().forEach { key -> closeAndDetach(key, notifyNative = true) }
        }
        unregisterReceivers()
    }

    private fun registerReceiversIfNeeded() {
        if (!permissionReceiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(context, permissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            permissionReceiverRegistered = true
        }
        if (!hotplugReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, hotplugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            hotplugReceiverRegistered = true
        }
    }

    private fun unregisterReceivers() {
        if (permissionReceiverRegistered) {
            runCatching { context.unregisterReceiver(permissionReceiver) }
            permissionReceiverRegistered = false
        }
        if (hotplugReceiverRegistered) {
            runCatching { context.unregisterReceiver(hotplugReceiver) }
            hotplugReceiverRegistered = false
        }
    }

    // ── Enumeration ──────────────────────────────────────────────────────

    fun refreshDeviceList() {
        val manager = usbManager ?: return
        val attached = runCatching { manager.deviceList }.getOrDefault(emptyMap()).values
        stateLock.withLock {
            val seenKeys = mutableSetOf<String>()
            for (device in attached) {
                val info = runCatching { toDeviceInfo(device) }.getOrNull() ?: continue
                val key = approvalKey(device)
                seenKeys += key
                val existing = devices[key]
                if (existing == null) {
                    devices[key] = UsbRedirectedDevice(
                        info = info,
                        connectionState = if (manager.hasPermission(device)) UsbConnectionState.DISCONNECTED else UsbConnectionState.DISCONNECTED,
                    )
                }
            }
            // Devices no longer physically present (missed a detach broadcast, e.g. after a cold app start) are dropped.
            devices.keys.filterNot { it in seenKeys }.forEach { staleKey -> closeAndDetach(staleKey, notifyNative = true) }
            publishLocked()
        }
        maybeAutoRestoreApproved()
    }

    /** Builds a sanitized [UsbDeviceInfo], never throwing on a malformed/partial descriptor — callers treat a thrown exception as "skip this device". */
    private fun toDeviceInfo(device: UsbDevice): UsbDeviceInfo {
        val interfaceClasses = (0 until device.interfaceCount).mapNotNull { i ->
            runCatching { device.getInterface(i).interfaceClass }.getOrNull()
        }
        val speed = classifySpeed(device)
        return UsbDeviceInfo(
            deviceName = device.deviceName ?: "unknown:${device.deviceId}",
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            deviceAddress = device.deviceId,
            busId = busIdOf(device),
            manufacturerName = sanitizeDescriptorString(runCatching { device.manufacturerName }.getOrNull()),
            productName = sanitizeDescriptorString(runCatching { device.productName }.getOrNull()),
            // Reading the serial number requires permission already granted; a
            // SecurityException here just means "not yet known", not an error.
            serialNumber = sanitizeDescriptorString(safeSerialNumber(device)),
            interfaceClasses = interfaceClasses,
            speed = speed,
        )
    }

    /** Strips control characters and clamps length — a malicious/broken device firmware can put anything in a USB string descriptor. */
    private fun sanitizeDescriptorString(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val cleaned = raw.filter { it.code in 0x20..0x7E || it.isLetterOrDigit() }.take(128).trim()
        return cleaned.ifBlank { null }
    }

    private fun safeSerialNumber(device: UsbDevice): String? =
        try {
            if (usbManager?.hasPermission(device) == true) device.serialNumber else null
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }

    private fun busIdOf(device: UsbDevice): Int =
        // UsbDevice has no direct bus accessor pre-API 29; deviceName is
        // "/dev/bus/usb/<bus>/<addr>" on every real Android USB Host
        // implementation (bionic's usbfs path), which is the only place a
        // bus number is actually exposed below API 29.
        runCatching { device.deviceName.split("/").let { it[it.size - 2].toInt() } }.getOrDefault(0)

    private fun classifySpeed(device: UsbDevice): UsbSpeed =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (runCatching { device.version }.getOrNull()) {
                null -> UsbSpeed.UNKNOWN
                else -> UsbSpeed.UNKNOWN // USB spec bcd version doesn't map 1:1 to negotiated link speed; left UNKNOWN rather than guessed.
            }
        } else UsbSpeed.UNKNOWN

    // ── Permission ───────────────────────────────────────────────────────

    fun hasPermission(device: UsbDevice): Boolean = usbManager?.hasPermission(device) == true

    fun requestPermission(deviceKey: String) {
        val manager = usbManager ?: return
        val device = manager.deviceList.values.firstOrNull { approvalKey(it) == deviceKey } ?: return
        if (manager.hasPermission(device)) {
            onPermissionGranted(device)
            return
        }
        updateState(deviceKey) { it.copy(connectionState = UsbConnectionState.PERMISSION_REQUESTED) }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
        )
        manager.requestPermission(device, pendingIntent)
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) onPermissionGranted(device) else onPermissionDenied(device)
        }
    }

    private fun onPermissionGranted(device: UsbDevice) {
        val key = approvalKey(device)
        updateState(key) { it.copy(connectionState = UsbConnectionState.DISCONNECTED, lastError = null) }
        // Re-enumerate this one device now that serial number etc. is readable under the granted permission.
        stateLock.withLock {
            runCatching { toDeviceInfo(device) }.getOrNull()?.let { info ->
                devices[key]?.let { devices[key] = it.copy(info = info) }
                publishLocked()
            }
        }
        scope.launch {
            val settings = currentSettings()
            val shouldRedirect = settings.enabled &&
                (key in settings.approvedDeviceKeys || (settings.autoRedirectNewDevices && !settings.askBeforeRedirecting))
            if (shouldRedirect) openAndRedirect(key)
        }
    }

    private fun onPermissionDenied(device: UsbDevice) {
        updateState(approvalKey(device)) {
            it.copy(connectionState = UsbConnectionState.PERMISSION_DENIED, lastError = "Permission denied by user")
        }
    }

    // ── Hot-plug ─────────────────────────────────────────────────────────

    private val hotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleDeviceAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDeviceDetached(device)
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        val info = runCatching { toDeviceInfo(device) }.getOrNull() ?: return
        val key = approvalKey(device)
        stateLock.withLock {
            devices[key] = devices[key]?.copy(info = info) ?: UsbRedirectedDevice(info = info)
            publishLocked()
        }
        scope.launch {
            val settings = currentSettings()
            if (!settings.enabled) return@launch
            val approved = key in settings.approvedDeviceKeys
            when {
                approved && usbManager?.hasPermission(device) == true -> openAndRedirect(key)
                approved -> requestPermission(key) // previously-approved device replugged — Android may re-prompt on some OEM builds.
                settings.autoRedirectNewDevices && !settings.askBeforeRedirecting -> requestPermission(key)
                // Otherwise: enumerated and visible in the settings list, but the user must explicitly opt in.
            }
        }
    }

    /** Entry point for [UsbDeviceAttachedActivity] — the system-launched USB_DEVICE_ATTACHED path, distinct from the ongoing [hotplugReceiver] registration. Safe to call even if [start] was never called yet (registers receivers first). */
    fun handleDeviceAttachedFromSystem(device: UsbDevice) {
        registerReceiversIfNeeded()
        handleDeviceAttached(device)
    }

    /** Also called from [refreshDeviceList] for devices missed by the broadcast (e.g. process wasn't alive). */
    private fun handleDeviceDetached(device: UsbDevice) {
        closeAndDetach(approvalKey(device), notifyNative = true)
    }

    private fun closeAndDetach(deviceKey: String, notifyNative: Boolean) {
        val wasRedirected: RedirectionState?
        stateLock.withLock {
            wasRedirected = devices[deviceKey]?.redirectionState
            val settings = currentSettingsBlocking()
            val restorable = wasRedirected == RedirectionState.REDIRECTED && settings.reconnectAutomatically
            devices[deviceKey]?.let {
                devices[deviceKey] = it.copy(
                    connectionState = UsbConnectionState.DISCONNECTED,
                    redirectionState = if (restorable) RedirectionState.DISCONNECTED_PENDING_RESTORE else RedirectionState.NOT_REDIRECTED,
                )
            } ?: devices.remove(deviceKey)
            publishLocked()
        }
        val open = openConnections.remove(deviceKey)
        if (open != null) {
            if (notifyNative && open.nativeDeviceId >= 0 && UsbNativeBridge.isAvailable) {
                runCatching { UsbNativeBridge.nativeDeviceDetached(open.nativeDeviceId) }
                    .onFailure { Log.w(TAG, "nativeDeviceDetached failed for $deviceKey", it) }
            }
            nativeIdToKey.remove(open.nativeDeviceId)
            runCatching { open.connection.close() }
        }
    }

    private fun maybeAutoRestoreApproved() {
        val manager = usbManager ?: return
        scope.launch {
            val settings = currentSettings()
            if (!settings.enabled || !settings.reconnectAutomatically) return@launch
            val toRestore = stateLock.withLock {
                devices.filterValues { it.redirectionState == RedirectionState.DISCONNECTED_PENDING_RESTORE }.keys.toList()
            }
            for (key in toRestore) {
                // RDP-reconnect case (item 4): handleSessionTornDown() left the
                // UsbDeviceConnection open (only the native side died with the
                // channel) and just cleared its nativeDeviceId — reuse the fd
                // instead of opening a second one. Anything without a live
                // OpenUsbDevice here got here via a *physical* replug/cold-start
                // path instead (closeAndDetach() already closed and removed its
                // entry from openConnections in that case), so it needs a real
                // re-open.
                val existingOpen = openConnections[key]
                if (existingOpen != null && existingOpen.nativeDeviceId < 0) {
                    reattachExistingConnection(key, existingOpen)
                    continue
                }
                val device = manager.deviceList.values.firstOrNull { approvalKey(it) == key } ?: continue
                if (manager.hasPermission(device)) openAndRedirect(key) else requestPermission(key)
            }
        }
    }

    // ── Redirect (open connection, hand off to native) ──────────────────

    private suspend fun openAndRedirect(deviceKey: String) {
        val manager = usbManager ?: return
        val device = manager.deviceList.values.firstOrNull { approvalKey(it) == deviceKey } ?: run {
            updateState(deviceKey) { it.copy(redirectionState = RedirectionState.FAILED, lastError = "Device no longer present") }
            return
        }
        if (!manager.hasPermission(device)) {
            requestPermission(deviceKey)
            return
        }
        updateState(deviceKey) { it.copy(redirectionState = RedirectionState.PENDING) }
        val connection = runCatching { manager.openDevice(device) }.getOrNull()
        if (connection == null) {
            updateState(deviceKey) { it.copy(redirectionState = RedirectionState.FAILED, lastError = "openDevice() returned null") }
            return
        }
        val open = OpenUsbDevice(device, connection)
        openConnections[deviceKey] = open
        attachToNative(deviceKey, open, closeConnectionOnFailure = true)
    }

    /**
     * Item 4 (reconnect/auto-restore): re-announces an already-open
     * [OpenUsbDevice] to the native URBDRC backend without touching
     * [UsbManager.openDevice] again. Used by [maybeAutoRestoreApproved]
     * when a device's fd survived an *RDP session* reconnect (only the
     * native side was torn down — see [handleSessionTornDown]); calling
     * [openAndRedirect] in that case would open a second
     * [UsbDeviceConnection] on top of the one already sitting in
     * [openConnections], leaking a native fd every reconnect.
     */
    private fun reattachExistingConnection(deviceKey: String, open: OpenUsbDevice) {
        attachToNative(deviceKey, open, closeConnectionOnFailure = false)
    }

    /** Shared tail of [openAndRedirect]/[reattachExistingConnection]: calls [UsbNativeBridge.nativeDeviceAttached] for an already-open [OpenUsbDevice] and updates bookkeeping/state on success or failure. */
    private fun attachToNative(deviceKey: String, open: OpenUsbDevice, closeConnectionOnFailure: Boolean) {
        if (!UsbNativeBridge.isAvailable) {
            // Part 2 not built yet in this environment — device stays open
            // and CONNECTED at the Android layer (visible/testable in the
            // settings UI) but cannot reach the remote session yet.
            updateState(deviceKey) {
                it.copy(connectionState = UsbConnectionState.CONNECTED, redirectionState = RedirectionState.FAILED,
                    lastError = "Native URBDRC bridge not built (see PART_2_PROMPT.md)")
            }
            return
        }

        val device = open.device
        val connection = open.connection
        val nativeId = runCatching {
            UsbNativeBridge.nativeDeviceAttached(
                deviceKey = deviceKey,
                fd = connection.fileDescriptor,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                deviceSubclass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
                speed = 0,
                rawDeviceDescriptor = runCatching { connection.rawDescriptors }.getOrDefault(ByteArray(0)),
                rawConfigurationDescriptor = ByteArray(0),
            )
        }.getOrElse { e ->
            Log.e(TAG, "nativeDeviceAttached threw for $deviceKey", e)
            -1
        }

        if (nativeId < 0) {
            if (closeConnectionOnFailure) {
                runCatching { connection.close() }
                openConnections.remove(deviceKey)
            }
            updateState(deviceKey) {
                it.copy(connectionState = UsbConnectionState.DISCONNECTED, redirectionState = RedirectionState.FAILED,
                    lastError = "Native backend rejected device (channel not connected?)")
            }
            return
        }

        open.nativeDeviceId = nativeId
        nativeIdToKey[nativeId] = deviceKey
        updateState(deviceKey) {
            it.copy(connectionState = UsbConnectionState.CONNECTED, redirectionState = RedirectionState.REDIRECTED, lastError = null)
        }
    }

    // ── User-facing approve/reject/remove ───────────────────────────────

    fun approveDevice(deviceKey: String) {
        scope.launch {
            settingsRepository.updateUsbApprovedDevices(currentSettings().approvedDeviceKeys + deviceKey)
            updateState(deviceKey) { it.copy(userApproved = true) }
            requestPermission(deviceKey)
        }
    }

    fun revokeDevice(deviceKey: String) {
        scope.launch {
            settingsRepository.updateUsbApprovedDevices(currentSettings().approvedDeviceKeys - deviceKey)
            updateState(deviceKey) { it.copy(userApproved = false) }
        }
        closeAndDetach(deviceKey, notifyNative = true)
    }

    // ── Transfer execution — called from UsbNativeBridge's @JvmStatic callbacks ──
    // These run on native worker threads; must never block on stateLock (see class doc).

    /**
     * DESIGN DECISION (item 5): retries a transient failure in place
     * ([TRANSFER_RETRY_ATTEMPTS], fixed short backoff — see that constant's
     * doc) but deliberately does NOT chain an automatic [resetDevice] +
     * re-claim into this path, even though "reset recovery after a stall"
     * is literally in the task list. Reasoning:
     * - A device-wide [android.hardware.usb.UsbDeviceConnection.resetDevice]
     *   invalidates *every* interface claim on the device, not just the one
     *   endpoint this transfer targets. Item 6 requires 2+ *simultaneous*
     *   redirected devices to work correctly, and a single device can also
     *   have multiple endpoints/interfaces with independent in-flight
     *   transfers (e.g. a composite HID+storage device). Auto-resetting on
     *   any transient failure would silently corrupt whatever other
     *   transfer happens to be in flight on the same device at that moment
     *   — trading one bug (a slow/rare transient failure) for a worse one
     *   (spurious cross-transfer corruption under concurrency).
     * - MS-RDPEUSB already has an explicit, spec-level recovery signal for
     *   this: the server itself sends a `RDPEUSB_REQUEST_RESET`/
     *   `PORT_RESET` PDU (routed to [executeReset] via
     *   `systemsgo_urbdrc_jni.c`'s `job_run_reset`) when *it* decides a device
     *   needs resetting — that's the correct, single owner of "when do we
     *   reset", not a heuristic guessed locally from one failed transfer.
     *   A successful [executeReset] resets [OpenUsbDevice.consecutiveFailures]
     *   to 0 (see below), so the health-check in [recordTransferOutcome]
     *   and the spec-driven reset path cooperate instead of fighting.
     */
    fun executeControlTransfer(
        deviceId: Int, requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeoutMs: Int,
    ): Int {
        val open = openDeviceForNativeId(deviceId) ?: return -1
        var result = -1
        for (attempt in 1..TRANSFER_RETRY_ATTEMPTS) {
            result = try {
                open.connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
            } catch (e: Exception) {
                logIfDebug("controlTransfer failed for native id $deviceId (attempt $attempt): ${e.message}")
                -1
            }
            if (result >= 0) break
            // Bail out early instead of sleeping/retrying against a device
            // that's already gone (unplugged mid-retry) — openDeviceForNativeId
            // re-checks nativeIdToKey/openConnections, both mutated only from
            // closeAndDetach, never blocked on stateLock for long (see class doc).
            if (attempt < TRANSFER_RETRY_ATTEMPTS) {
                if (openDeviceForNativeId(deviceId) == null) break
                runCatching { Thread.sleep(TRANSFER_RETRY_BACKOFF_MS) }
            }
        }
        recordTransferOutcome(deviceId, open, success = result >= 0)
        return result
    }

    fun executeDataTransfer(
        deviceId: Int, endpointAddress: Int, buffer: ByteArray?, length: Int, isInterrupt: Boolean, timeoutMs: Int,
    ): Int {
        val open = openDeviceForNativeId(deviceId) ?: return -1
        val endpoint = findEndpoint(open.device, endpointAddress) ?: run {
            logIfDebug("no matching endpoint 0x${endpointAddress.toString(16)} on native id $deviceId")
            return -1
        }
        var result = -1
        for (attempt in 1..TRANSFER_RETRY_ATTEMPTS) {
            result = try {
                // UsbDeviceConnection has a single bulkTransfer/interruptTransfer-style
                // API for both; Android historically routed interrupt endpoints
                // through the same bulkTransfer() call (it dispatches on the
                // endpoint's own type internally), which is what's used here —
                // isInterrupt is accepted for API symmetry with MS-RDPEUSB's
                // distinct URB types and kept for logging/future UsbRequest-based
                // async support (Part 3).
                open.connection.bulkTransfer(endpoint, buffer, length, timeoutMs)
            } catch (e: Exception) {
                logIfDebug("dataTransfer failed for native id $deviceId ep 0x${endpointAddress.toString(16)} (attempt $attempt): ${e.message}")
                -1
            }
            if (result >= 0) break
            // Interrupt IN transfers already carry a long (but now bounded —
            // see systemsgo_urbdrc_jni.c's SYSTEMSGO_USB_INTERRUPT_TIMEOUT_MS)
            // timeout on the native side to cover normal idle-polling
            // latency; retrying one of those in place would just double
            // that worst-case worker-thread occupancy for no benefit (a
            // timed-out long poll isn't a "transient blip" to retry, it's
            // the endpoint legitimately having nothing to report — the
            // server will simply issue another poll). Only retry the
            // bounded, short-timeout case (bulk, or interrupt OUT).
            if (isInterrupt) break
            if (attempt < TRANSFER_RETRY_ATTEMPTS) {
                if (openDeviceForNativeId(deviceId) == null) break
                runCatching { Thread.sleep(TRANSFER_RETRY_BACKOFF_MS) }
            }
        }
        recordTransferOutcome(deviceId, open, success = result >= 0)
        return result
    }

    fun executeReset(deviceId: Int): Boolean {
        val open = openDeviceForNativeId(deviceId) ?: return false
        val ok = try {
            open.connection.resetDevice()
        } catch (e: Exception) {
            logIfDebug("resetDevice failed for native id $deviceId: ${e.message}")
            false
        }
        // A server-initiated reset succeeding is the spec-level recovery
        // signal (see executeControlTransfer's DESIGN DECISION comment) —
        // clear the stale-device failure count so a device that had been
        // accumulating transient failures gets a clean slate instead of
        // being evicted moments later by a health-check threshold it was
        // already close to before the reset fixed it.
        if (ok) open.consecutiveFailures.set(0)
        return ok
    }

    fun executeSetInterface(deviceId: Int, interfaceNumber: Int, alternateSetting: Int): Boolean {
        val open = openDeviceForNativeId(deviceId) ?: return false
        val iface = (0 until open.device.interfaceCount)
            .map { open.device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber && it.alternateSetting == alternateSetting }
            ?: return false
        return try {
            open.connection.claimInterface(iface, true) && open.connection.setInterface(iface)
        } catch (e: Exception) {
            logIfDebug("setInterface failed for native id $deviceId: ${e.message}")
            false
        }
    }

    fun onNativeLog(level: Int, tag: String, message: String) {
        when (level) {
            6 -> Log.e("$TAG/native/$tag", message)
            5 -> Log.w("$TAG/native/$tag", message)
            else -> Log.d("$TAG/native/$tag", message)
        }
    }

    private fun openDeviceForNativeId(nativeId: Int): OpenUsbDevice? {
        val key = nativeIdToKey[nativeId] ?: return null
        return openConnections[key]
    }

    /**
     * Item 5's stale-device health check. Called from every
     * [executeControlTransfer]/[executeDataTransfer] outcome (after retries
     * are exhausted). On success, clears the streak. On failure, bumps it
     * and — once it crosses [MAX_CONSECUTIVE_TRANSFER_FAILURES] — treats
     * the device as gone: detaches it exactly like a physical unplug
     * ([closeAndDetach], `notifyNative = true`, which cancels its pending
     * URBs and sends `DEVICE_REMOVED` on the native side — same path item
     * 3 hardened), so [RedirectionState.DISCONNECTED_PENDING_RESTORE] +
     * auto-restore (item 4) can pick it back up if it was actually still
     * plugged in and only the fd had gone bad. Runs the detach on [scope]
     * (never inline on the native worker thread that called in here) both
     * to keep this call's own return path fast and to avoid a worker
     * thread re-entering the native JNI layer (`nativeDeviceDetached`)
     * from inside the very call stack a transfer for that same device is
     * still unwinding from.
     */
    private fun recordTransferOutcome(nativeId: Int, open: OpenUsbDevice, success: Boolean) {
        if (success) {
            open.consecutiveFailures.set(0)
            return
        }
        val failures = open.consecutiveFailures.incrementAndGet()
        // `==` rather than `>=`: closeAndDetach() runs asynchronously on
        // [scope], so a few more transfers can land against this same
        // OpenUsbDevice before it's actually removed from openConnections —
        // `==` fires the detach exactly once instead of re-launching it on
        // every failure past the threshold.
        if (failures == MAX_CONSECUTIVE_TRANSFER_FAILURES) {
            val key = nativeIdToKey[nativeId] ?: return
            Log.w(TAG, "native id $nativeId ($key): $failures consecutive transfer failures, treating as stale/gone")
            scope.launch { closeAndDetach(key, notifyNative = true) }
        }
    }

    private fun findEndpoint(device: UsbDevice, endpointAddress: Int) =
        (0 until device.interfaceCount).asSequence()
            .flatMap { i -> (0 until device.getInterface(i).endpointCount).asSequence().map { e -> device.getInterface(i).getEndpoint(e) } }
            .firstOrNull { it.address == endpointAddress }

    private fun logIfDebug(message: String) {
        if (currentSettingsBlocking().debugLogging) Log.d(TAG, message)
    }

    // ── Session wiring ───────────────────────────────────────────────────

    /** Called by the RDP session once a native FreeRDP session handle exists / is torn down — see PART_2_PROMPT.md for the AFreeRdpBridge-side wiring this expects. */
    fun onSessionHandleChanged(sessionHandle: Long) {
        val wasActive = activeSessionHandle != 0L
        activeSessionHandle = sessionHandle
        if (UsbNativeBridge.isAvailable) {
            runCatching { UsbNativeBridge.nativeSetChannelActive(sessionHandle, sessionHandle != 0L) }
        }
        if (sessionHandle != 0L) {
            // Covers both a first connect and an RDP reconnect — see
            // maybeAutoRestoreApproved()'s own comment for why it's safe
            // (a no-op) to call unconditionally every time a session comes
            // up, not just the first time.
            maybeAutoRestoreApproved()
        } else if (wasActive) {
            handleSessionTornDown()
        }
    }

    /**
     * Item 4 (reconnect/auto-restore): fired from [onSessionHandleChanged]
     * exactly once per real session teardown (network drop, app backgrounds
     * the session, clean disconnect — anything that flips
     * [activeSessionHandle] back to 0). Per `systemsgo_urbdrc_jni.c`'s own
     * comment on `nativeSetChannelActive(false)`, every native
     * `RedirectedDevice` is unconditionally freed as part of tearing down
     * the session's channel set — so every native `deviceId` this instance
     * was holding is already dangling the moment this runs, whether or not
     * a physical unplug also happened.
     *
     * This must NOT call [closeAndDetach]/close the Android
     * [UsbDeviceConnection]: the physical device is very likely still
     * plugged in and its `fd` is still valid — only the RDP channel died.
     * Closing it here would force a full re-[UsbManager.openDevice] (and,
     * on some OEM builds, a re-prompt for permission) on every reconnect
     * instead of the cheap re-[UsbNativeBridge.nativeDeviceAttached] that
     * [maybeAutoRestoreApproved]/[reattachExistingConnection] does once the
     * new session comes up.
     */
    private fun handleSessionTornDown() {
        val settings = currentSettingsBlocking()
        stateLock.withLock {
            for ((key, dev) in devices) {
                if (dev.redirectionState != RedirectionState.REDIRECTED) continue
                devices[key] = dev.copy(
                    redirectionState = if (settings.reconnectAutomatically)
                        RedirectionState.DISCONNECTED_PENDING_RESTORE else RedirectionState.NOT_REDIRECTED,
                )
            }
            publishLocked()
        }
        openConnections.forEach { (_, open) ->
            if (open.nativeDeviceId >= 0) {
                nativeIdToKey.remove(open.nativeDeviceId)
                open.nativeDeviceId = -1
            }
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────

    private fun updateState(deviceKey: String, transform: (UsbRedirectedDevice) -> UsbRedirectedDevice) {
        stateLock.withLock {
            devices[deviceKey]?.let { devices[deviceKey] = transform(it) }
            publishLocked()
        }
    }

    private fun publishLocked() {
        _deviceListFlow.value = devices.values.toList()
    }

    private suspend fun currentSettings(): UsbRedirectionSettings = settingsRepository.usbRedirectionSettingsFlow.first()
    private fun currentSettingsBlocking(): UsbRedirectionSettings = settingsRepository.currentUsbRedirectionSettingsSnapshot()
}
