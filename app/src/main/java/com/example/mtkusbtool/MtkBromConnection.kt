package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

object MtkBromConnection {
    fun performHandshake(usbManager: UsbManager, device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device) ?: return false
        val usbInterface = device.getInterface(0)
        
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            return false
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
            }
        }

        if (epIn == null || epOut == null) {
            connection.close()
            return false
        }

        // 0xA0 ל-BROM, 0xA1 ל-Preloader
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val cmd = byteArrayOf(if (isBrom) 0xA0.toByte() else 0xA1.toByte())
        val resp = ByteArray(1)

        val sent = connection.bulkTransfer(epOut, cmd, 1, 2000)
        if (sent < 0) {
            connection.close()
            return false
        }

        val received = connection.bulkTransfer(epIn, resp, 1, 2000)
        connection.close()

        return received > 0 && (resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte())
    }
}
