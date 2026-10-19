package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    // קריסה אגרסיבית: לא מחכים לתגובות, פשוט יורים את הפקודות
    fun aggressiveCrash(usbManager: UsbManager, device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device) ?: return false
        
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (connection.claimInterface(usbInterface, true)) {
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        epOut = ep
                    }
                }
                if (epOut != null) break
            }
        }

        if (epOut == null) { connection.close(); return false }

        try {
            // שליחת לחיצת יד (0xA1) בלי לחכות לתשובה
            connection.bulkTransfer(epOut, byteArrayOf(0xA1.toByte()), 1, 100)
            
            // שליחת פקודת Send DA (0xD7) בלי לחכות
            connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 100)
            
            // שליחת גודל הזוי (0xFFFFFFFF) בלי לחכות
            connection.bulkTransfer(epOut, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 4, 100)
            
            // המתנה קצרה מאוד וסגירה
            Thread.sleep(50)
        } catch (e: Exception) {
            // מתעלמים משגיאות, המכשיר אמור לקרוס
        } finally {
            connection.close()
        }
        return true
    }

    // פונקציית הפריצה
    fun executeBypassAndSendDa(
        usbManager: UsbManager, 
        device: UsbDevice,
        payloadBytes: ByteArray,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור ל-USB נכשלה."
        
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (connection.claimInterface(usbInterface, true)) {
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    }
                }
                if (epIn != null && epOut != null) break
            }
        }

        if (epIn == null || epOut == null) { connection.close(); return "שגיאה: לא נמצאו נקודות קצה." }

        val resp = ByteArray(1)
        fun sendCmd(cmd: Byte): Boolean = connection.bulkTransfer(epOut, byteArrayOf(cmd), 1, 2000) >= 0
        fun readAck(): Byte? = if (connection.bulkTransfer(epIn, resp, 1, 2000) > 0) resp[0] else null

        // פונקציה חכמה לשליחת קבצים
        fun sendBinaryFile(fileName: String, fileBytes: ByteArray): String? {
            onProgress("מכין לשלוח $fileName...")
            if (!sendCmd(0xD7.toByte())) return "שגיאה: פקודת 0xD7 נכשלה."
            if (readAck() != 0xD8.toByte()) return "שגיאה: תגובה לא תקינה ל-0xD7."

            val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileBytes.size).array()
            if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return "שגיאה: שליחת גודל נכשלה."
            if (readAck() != 0xD9.toByte()) return "שגיאה: תגובה לא תקינה לגודל."

            var offset = 0
            val chunkSize = 512
            while (offset < fileBytes.size) {
                val length = minOf(chunkSize, fileBytes.size - offset)
                val chunk = fileBytes.copyOfRange(offset, offset + length)
                if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "שגיאה: שליחת נתונים נכשלה ב-$offset."
                val ack = readAck()
                if (ack != 0x5A.toByte() && ack != 0x5F.toByte()) return "שגיאה: תגובה לא תקינה לנתונים."
                offset += length
                onProgress("שולח $fileName... ${(offset * 100) / fileBytes.size}%")
            }
            return null
        }

        // 1. לחיצת יד (מנסים גם BROM וגם Preloader)
        onProgress("מבצע לחיצת יד...")
        sendCmd(0xA0.toByte())
        var hs = readAck()
        if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
            sendCmd(0xA1.toByte())
            hs = readAck()
            if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
                connection.close(); return "שגיאה בלחיצת יד: $hs"
            }
        }

        // 2. שליחת ה-Payload
        sendBinaryFile("Payload", payloadBytes)?.let { connection.close(); return it }

        // 3. הרצת ה-Payload
        onProgress("מריץ Payload...")
        sendCmd(0xD5.toByte())
        readAck()
        
        // 4. שליחת ה-DA
        sendBinaryFile("DA", daBytes)?.let { connection.close(); return it }

        // 5. הרצת ה-DA
        onProgress("מריץ את ה-DA...")
        sendCmd(0xD5.toByte())
        readAck()
        
        connection.close()
        return "הצלחה מסחררת! המכשיר פרוץ!"
    }
}
