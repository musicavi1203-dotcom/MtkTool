package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.util.Locale

object MtkBromConnection {
    fun performHandshake(usbManager: UsbManager, device: UsbDevice): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור ל-USB נכשלה."
        
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        var claimedInterface = false

        // עוברים על כל ה-Interfaces של המכשיר כדי למצוא את ה-Endpoints
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
                
                // אם מצאנו את שניהם ב-Interface הזה, נשמור אותם ונצא מהלולאה
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
            return "שגיאה: לא נמצאו נקודות קצה (Endpoints) מתאימות באף Interface."
        }

        // 0xA0 ל-BROM, 0xA1 ל-Preloader
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val cmd = byteArrayOf(if (isBrom) 0xA0.toByte() else 0xA1.toByte())
        val resp = ByteArray(1)

        val sent = connection.bulkTransfer(epOut, cmd, 1, 2000)
        if (sent < 0) {
            connection.close()
            return "שגיאה: שליחת פקודת לחיצת היד נכשלה (Timeout בשליחה)."
        }

        val received = connection.bulkTransfer(epIn, resp, 1, 2000)
        connection.close()

        if (received <= 0) {
            return "שגיאה: לא התקבלה תגובה מהמכשיר (Timeout בקריאה). המכשיר אולי ניתק."
        }

        val responseHex = String.format(Locale.US, "0x%02X", resp[0])
        
        return if (resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte()) {
            "לחיצת יד הצליחה! ✓ (תגובה: $responseHex)"
        } else {
            "כשל: התקבלה תגובה לא תקינה ($responseHex). המכשיר כנראה במצב טעינה."
        }
    }
}
