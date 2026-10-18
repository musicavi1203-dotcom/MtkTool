package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {
    
    fun sendDaFile(
        usbManager: UsbManager, 
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור ל-USB נכשלה."
        
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        var claimedInterface = false

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
            return "שגיאה: לא נמצאו נקודות קצה."
        }

        val resp = ByteArray(1)
        
        fun readAck(stepName: String): String? {
            val received = connection.bulkTransfer(epIn, resp, 1, 2000)
            if (received <= 0) return "שגיאה: אין תגובה בשלב $stepName."
            val hex = String.format(Locale.US, "0x%02X", resp[0])
            
            // הוספנו את 0xD8 לתגובות החוקיות (המכשיר אומר שהוא מוכן)
            if (resp[0] == 0xD8.toByte() || resp[0] == 0x5A.toByte() || resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte()) {
                return null // Success
            }
            return "שגיאה: תגובה לא תקינה בשלב $stepName ($hex)."
        }

        // 1. לחיצת יד
        onProgress("מבצע לחיצת יד...")
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val handshakeCmd = byteArrayOf(if (isBrom) 0xA0.toByte() else 0xA1.toByte())

        if (connection.bulkTransfer(epOut, handshakeCmd, 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת לחיצת יד נכשלה."
        }
        readAck("לחיצת יד")?.let { connection.close(); return it }

        // 2. פקודת Send DA (0xD7)
        onProgress("מכין לשליחת DA...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת פקודת 0xD7 נכשלה."
        }
        readAck("פקודת 0xD7")?.let { connection.close(); return it }

        // 3. שליחת גודל הקובץ
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת גודל הקובץ נכשלה."
        }
        readAck("גודל הקובץ")?.let { connection.close(); return it }

        // 4. שליחת הנתונים
        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) {
                connection.close(); return "שגיאה: שליחת נתונים נכשלה במיקום $offset."
            }
            
            val ackError = readAck("נתונים ב-$offset")
            if (ackError != null) {
                connection.close(); return ackError
            }
            
            offset += length
            val progress = (offset * 100) / daBytes.size
            onProgress("שולח DA... $progress%")
            
            try { Thread.sleep(5) } catch (e: InterruptedException) {}
        }

        // 5. פקודת הרצה (Jump to DA - 0xD5)
        onProgress("מריץ את ה-DA...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת פקודת הרצה נכשלה."
        }
        
        val jumpResult = readAck("פקודת הרצה")
        connection.close()

        return if (jumpResult == null) {
            "הצלחה! ה-DA נשלח והורץ. המכשיר בשליטתנו!"
        } else {
            "ה-DA נשלח אבל ההרצה נכשלה: $jumpResult"
        }
    }
}
