package com.systemsgo.hex.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.data.repository.AppSettingsRepository
import com.systemsgo.hex.usb.UsbRedirectedDevice
import com.systemsgo.hex.usb.UsbRedirectionManager
import com.systemsgo.hex.usb.UsbRedirectionSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * USB-REDIRECT FEATURE (Part 1/3): thin ViewModel — all state lives in
 * [AppSettingsRepository] (persisted toggles) and [UsbRedirectionManager]
 * (live device list); this class only adapts them to `collectAsState()`-
 * friendly [StateFlow]s and forwards user actions.
 */
@HiltViewModel
class UsbRedirectionSettingsViewModel @Inject constructor(
    private val settingsRepository: AppSettingsRepository,
    private val usbRedirectionManager: UsbRedirectionManager,
) : ViewModel() {

    val settings: StateFlow<UsbRedirectionSettings> = settingsRepository.usbRedirectionSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsbRedirectionSettings())

    val devices: StateFlow<List<UsbRedirectedDevice>> = usbRedirectionManager.deviceListFlow

    init {
        usbRedirectionManager.refreshDeviceList()
    }

    fun setEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.updateUsbRedirectionEnabled(value)
        if (value) usbRedirectionManager.start() else usbRedirectionManager.stop()
    }

    fun setAutoRedirectNewDevices(value: Boolean) = viewModelScope.launch {
        settingsRepository.updateUsbAutoRedirectNewDevices(value)
    }

    fun setAskBeforeRedirecting(value: Boolean) = viewModelScope.launch {
        settingsRepository.updateUsbAskBeforeRedirecting(value)
    }

    fun setReconnectAutomatically(value: Boolean) = viewModelScope.launch {
        settingsRepository.updateUsbReconnectAutomatically(value)
    }

    fun setDebugLogging(value: Boolean) = viewModelScope.launch {
        settingsRepository.updateUsbDebugLogging(value)
    }

    fun onRedirectClicked(device: UsbRedirectedDevice) {
        val key = usbRedirectionKeyOf(device)
        usbRedirectionManager.approveDevice(key)
    }

    fun onStopClicked(device: UsbRedirectedDevice) {
        val key = usbRedirectionKeyOf(device)
        usbRedirectionManager.revokeDevice(key)
    }

    // [UsbRedirectedDevice] doesn't carry its own key (it's a pure Android-side
    // snapshot) — reconstruct the same identity UsbRedirectionManager.approvalKey
    // would compute so approve/revoke target the right entry.
    private fun usbRedirectionKeyOf(device: UsbRedirectedDevice): String {
        val info = device.info
        return if (!info.serialNumber.isNullOrBlank()) {
            "${info.vendorId}:${info.productId}:${info.serialNumber}"
        } else {
            "${info.vendorId}:${info.productId}:${info.busId}:${info.deviceAddress}"
        }
    }
}
