package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.util.Locale

object MtkBromConnection {
    fun performHandshakeAndReadChipId(usbManager: UsbManager, device: UsbDevice): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור ל-USB נכשלה."
        
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        var claimedInterface = false

        // מציאת ה-Interfaces וה-Endpoints
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (connection.claimInterface(usbInterface, true)) {
                var foundIn: UsbEndpoint? = null
                var foundOut: UsbEndpoint? = null
                
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
                    }
                }
                
                if (foundIn != null && foundOut != null) {
                    epIn = foundIn
                    epOut = foundOut
                    claimedInterface = true
                    break 
                }
            }
        }

        if (!claimedInterface || epIn == null || epOut == null) {
            connection.close()
            return "שגיאה: לא נמצאו נקודות קצה (Endpoints)."
        }

        // 1. שלב הלחיצת יד
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val cmd = byteArrayOf(if (isBrom) 0xA0.toByte() else 0xA1.toByte())
        val resp = ByteArray(1)

        val sent = connection.bulkTransfer(epOut, cmd, 1, 2000)
        if (sent < 0) {
            connection.close()
            return "שגיאה: שליחת לחיצת יד נכשלה."
        }

        val received = connection.bulkTransfer(epIn, resp, 1, 2000)
        if (received <= 0 || (resp[0] != 0x5F.toByte() && resp[0] != 0xC0.toByte())) {
            val responseHex = if (received > 0) String.format(Locale.US, "0x%02X", resp[0]) else "אין תגובה"
            connection.close()
            return "כשל בלחיצת יד: תגובה $responseHex."
        }

        // 2. שלב קריאת Chip ID (פקודה 0xD8)
        val readIdCmd = byteArrayOf(0xD8.toByte())
        val chipIdBuffer = ByteArray(4)
        
        val idSent = connection.bulkTransfer(epOut, readIdCmd, 1, 2000)
        if (idSent < 0) {
            connection.close()
            return "לחיצת יד הצליחה, אבל שליחת פקודת Chip ID נכשלה."
        }

        val idReceived = connection.bulkTransfer(epIn, chipIdBuffer, 4, 2000)
        connection.close() // סוגרים רק אחרי הכל

        return if (idReceived > 0) {
            val chipIdHex = chipIdBuffer.joinToString("") { String.format(Locale.US, "%02X", it) }
            "הצלחה! Chip ID: $chipIdHex"
        } else {
            "לחיצת יד הצליחה, אבל לא התקבל Chip ID (המכשיר אולי מוגן או במצב לא נכון)."
        }
    }
}
