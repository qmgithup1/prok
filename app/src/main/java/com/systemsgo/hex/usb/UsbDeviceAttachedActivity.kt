package com.systemsgo.hex.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * USB-REDIRECT FEATURE (Part 1/3): invisible trampoline Android launches
 * directly (per `android.hardware.usb.action.USB_DEVICE_ATTACHED` +
 * `res/xml/usb_device_filter.xml`, declared in AndroidManifest.xml) when a
 * USB device is plugged in while this app isn't already running with its
 * hot-plug [BroadcastReceiver][UsbRedirectionManager] registered.
 *
 * Does no UI of its own (`noHistory`/`excludeFromRecents` in the manifest) —
 * it only starts [UsbRedirectionService] (which brings up
 * [UsbRedirectionManager] and its receivers) and forwards this exact
 * attach event into it, then finishes immediately. If USB redirection is
 * disabled in settings, [UsbRedirectionManager.handleDeviceAttachedFromSystem]
 * itself is a no-op — this activity never forces the feature on.
 *
 * Extends [ComponentActivity] rather than the plain platform [android.app.Activity]
 * because Hilt requires every @AndroidEntryPoint Activity to be a (direct or
 * indirect) subclass of androidx.activity.ComponentActivity — this activity
 * never draws any UI, so ComponentActivity (no AppCompat theme dependency
 * needed) is the minimal correct base class.
 */
@AndroidEntryPoint
class UsbDeviceAttachedActivity : ComponentActivity() {

    @Inject lateinit var usbRedirectionManager: UsbRedirectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

        if (device != null) {
            UsbRedirectionService.start(this)
            usbRedirectionManager.handleDeviceAttachedFromSystem(device)
        }
        finish()
    }
}
