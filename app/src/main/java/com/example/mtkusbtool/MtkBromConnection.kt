package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    fun aggressiveCrash(usbManager: UsbManager, device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device) ?: return false
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (connection.claimInterface(usbInterface, true)) {
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }
                if (epOut != null) break
            }
        }
        if (epOut == null) { connection.close(); return false }

        try {
            connection.bulkTransfer(epOut, byteArrayOf(0xA1.toByte()), 1, 100)
            connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 100)
            connection.bulkTransfer(epOut, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 4, 100)
            Thread.sleep(50)
        } catch (e: Exception) {} finally { connection.close() }
        return true
    }

    fun executeBypassAndSendDa(
        usbManager: UsbManager, 
        device: UsbDevice,
        payloadBytes: ByteArray,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור נכשלה."
        
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
        fun readAck(): Byte? = if (connection.bulkTransfer(epIn, resp, 1, 2000) > 0) resp[0] else null

        // 1. לחיצת יד
        onProgress("שולח לחיצת יד...")
        connection.bulkTransfer(epOut, byteArrayOf(0xA0.toByte()), 1, 1000)
        var hs = readAck()
        if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
            connection.bulkTransfer(epOut, byteArrayOf(0xA1.toByte()), 1, 1000)
            hs = readAck()
            if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
                connection.close(); return "שגיאה בלחיצת יד: ${hs?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין תגובה"}"
            }
        }

        // פונקציה פנימית
        fun sendBinaryFile(fileName: String, fileBytes: ByteArray): String? {
            onProgress("מכין לשלוח $fileName (${fileBytes.size} בייט)...")
            connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000)
            val ack1 = readAck()
            if (ack1 != 0xD8.toByte()) return "שגיאה ב-$fileName: תגובת 0xD7 לא תקינה (${ack1?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין"})"

            val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileBytes.size).array()
            connection.bulkTransfer(epOut, sizeBytes, 4, 2000)
            val ack2 = readAck()
            if (ack2 != 0xD9.toByte()) return "שגיאה ב-$fileName: תגובת גודל לא תקינה (${ack2?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין"})"

            var offset = 0
            val chunkSize = 512
            while (offset < fileBytes.size) {
                val length = minOf(chunkSize, fileBytes.size - offset)
                val chunk = fileBytes.copyOfRange(offset, offset + length)
                if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "שגיאה ב-$fileName: שליחת נתונים נכשלה ב-$offset."
                val ack = readAck()
                if (ack != 0x5A.toByte() && ack != 0x5F.toByte()) return "שגיאה ב-$fileName: תגובת נתונים לא תקינה (${ack?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין"})"
                offset += length
                if (offset % (chunkSize * 20) == 0) onProgress("$fileName: ${(offset * 100) / fileBytes.size}%")
            }
            return null // Success
        }

        // 2. שליחת ה-Payload
        sendBinaryFile("Payload", payloadBytes)?.let { connection.close(); return it }

        // 3. הרצת ה-Payload
        onProgress("מריץ Payload...")
        connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000)
        readAck()
        
        // 4. שליחת ה-DA
        sendBinaryFile("DA", daBytes)?.let { connection.close(); return it }

        // 5. הרצת ה-DA
        onProgress("מריץ DA...")
        connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000)
        readAck()
        
        connection.close()
        return "הצלחה מסחררת! המכשיר פרוץ!"
    }
}
