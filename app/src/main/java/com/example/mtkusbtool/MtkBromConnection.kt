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
        
        // פונקציית עזר לבדיקת תגובות
        fun readExpectedAck(stepName: String, expected: List<Byte>): String? {
            val received = connection.bulkTransfer(epIn, resp, 1, 2000)
            if (received <= 0) return "שגיאה: אין תגובה בשלב $stepName."
            val hex = String.format(Locale.US, "0x%02X", resp[0])
            
            if (expected.contains(resp[0])) {
                return null // Success
            }
            return "שגיאה: תגובה לא תקינה בשלב $stepName ($hex)."
        }

        // 1. לחיצת יד (מצפה ל-0x5F או 0xC0)
        onProgress("מבצע לחיצת יד...")
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val handshakeCmd = byteArrayOf(if (isBrom) 0xA0.toByte() else 0xA1.toByte())

        if (connection.bulkTransfer(epOut, handshakeCmd, 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת לחיצת יד נכשלה."
        }
        readExpectedAck("לחיצת יד", listOf(0x5F.toByte(), 0xC0.toByte()))?.let { connection.close(); return it }

        // 2. פקודת Send DA (0xD7) - מצפה ל-0xD8
        onProgress("מכין לשליחת DA...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת פקודת 0xD7 נכשלה."
        }
        readExpectedAck("פקודת 0xD7", listOf(0xD8.toByte()))?.let { connection.close(); return it }

        // 3. שליחת גודל הקובץ - מצפה ל-0xD9
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת גודל הקובץ נכשלה."
        }
        readExpectedAck("גודל הקובץ", listOf(0xD9.toByte()))?.let { connection.close(); return it }

        // 4. שליחת הנתונים - מצפה ל-0x5A (או 0x5F)
        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) {
                connection.close(); return "שגיאה: שליחת נתונים נכשלה במיקום $offset."
            }
            
            val ackError = readExpectedAck("נתונים ב-$offset", listOf(0x5A.toByte(), 0x5F.toByte()))
            if (ackError != null) {
                connection.close(); return ackError
            }
            
            offset += length
            val progress = (offset * 100) / daBytes.size
            onProgress("שולח DA... $progress%")
            
            try { Thread.sleep(5) } catch (e: InterruptedException) {}
        }

        // 5. פקודת הרצה (0xD5) - מצפה ל-0x5A או 0x5F
        onProgress("מריץ את ה-DA...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת פקודת הרצה נכשלה."
        }
        
        val jumpResult = readExpectedAck("פקודת הרצה", listOf(0x5A.toByte(), 0x5F.toByte()))
        connection.close()

        return if (jumpResult == null) {
            "הצלחה! ה-DA נשלח והורץ. המכשיר בשליטתנו!"
        } else {
            "ה-DA נשלח אבל ההרצה נכשלה: $jumpResult"
        }
    }
}
