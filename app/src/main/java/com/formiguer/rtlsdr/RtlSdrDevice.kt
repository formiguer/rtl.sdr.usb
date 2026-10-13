package com.formiguer.rtlsdr

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Identifica dongles RTL2832U (Realtek) entre los dispositivos USB conectados. */
object RtlSdrDevice {
    private const val VENDOR_REALTEK = 0x0bda
    private val PRODUCT_IDS = setOf(0x2838, 0x2832)

    fun isRtlSdr(device: UsbDevice): Boolean =
        device.vendorId == VENDOR_REALTEK && device.productId in PRODUCT_IDS

    fun find(manager: UsbManager): UsbDevice? =
        manager.deviceList.values.firstOrNull(::isRtlSdr)

    fun describe(device: UsbDevice): String =
        "%04x:%04x".format(device.vendorId, device.productId)
}
