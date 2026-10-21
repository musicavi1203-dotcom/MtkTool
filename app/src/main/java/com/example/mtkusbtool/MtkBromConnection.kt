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

    // חישוב XOR checksum של 16 בייט
    private fun calculateXorChecksum(data: ByteArray): ByteArray {
        var checksum = 0
        for (b in data) {
            checksum = checksum xor (b.toInt() and 0xFF)
        }
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(checksum.toShort()).array()
    }

    // שליחת DA1 לפי הפרוטוקול המדויק
    private fun sendDa1(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, da1Data: ByteArray, onProgress: (String) -> Unit): String? {
        val addr = 0x200000
        val sigLen = 256
        
        onProgress("DA1: שולח 0xD7 (SEND_DA)...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) return "FAIL:0xD7 write"
        
        val ack1 = ByteArray(1)
        connection.bulkTransfer(epIn, ack1, 1, 2000)
        if (ack1[0] != 0xD8.toByte()) return "FAIL:0xD7 ack=0x${String.format(Locale.US, "%02X", ack1[0])}"

        // שליחת כתובת (4 בייט, LE)
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return "FAIL:addr write"
        
        // שליחת גודל (4 בייט, LE)
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(da1Data.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return "FAIL:size write"
        
        // שליחת אורך חתימה (4 בייט, LE)
        val sigLenBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sigLen).array()
        if (connection.bulkTransfer(epOut, sigLenBytes, 4, 2000) < 0) return "FAIL:siglen write"

        // קריאת אישור (0xD9 או 0x1D0D)
        val ack2 = ByteArray(2)
        connection.bulkTransfer(epIn, ack2, 2, 2000)
        val ack2Short = ByteBuffer.wrap(ack2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        
        if (ack2Short == 0x1D0D) {
            return "FAIL:SLA required (0x1D0D)"
        }
        if (ack2Short != 0xD9) {
            return "FAIL:size/siglen ack=0x${String.format(Locale.US, "%04X", ack2Short)}"
        }
        onProgress("DA1: אישור התקבל. שולח נתונים...")

        // שליחת נתונים
        var offset = 0
        val chunkSize = 512
        while (offset < da1Data.size) {
            val length = minOf(chunkSize, da1Data.size - offset)
            val chunk = da1Data.copyOfRange(offset, offset + length)
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "FAIL:data write at $offset"
            offset += length
            if (offset % (chunkSize * 40) == 0) onProgress("DA1: ${(offset * 100) / da1Data.size}%")
        }

        // שליחת XOR checksum
        val checksum = calculateXorChecksum(da1Data)
        if (connection.bulkTransfer(epOut, checksum, 2, 2000) < 0) return "FAIL:checksum write"
        
        val finalAck = ByteArray(1)
        connection.bulkTransfer(epIn, finalAck, 1, 2000)
        if (finalAck[0] != 0x5A.toByte() && finalAck[0] != 0x5F.toByte()) {
            return "FAIL:data final ack=0x${String.format(Locale.US, "%02X", finalAck[0])}"
        }

        return null // Success
    }

    // פונקציית הרצף המלאה
    fun runFullFlow(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (connection.claimInterface(itf, true)) {
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                    }
                }
                if (epIn != null && epOut != null) break
            }
        }
        if (epIn == null || epOut == null) { connection.close(); return "שגיאה: endpoints." }
        val inEp = epIn
        val outEp = epOut

        // 1. חיפוש DA1 בקובץ (לפי הנתונים של קלוד)
        // כאן נניח שה-DA מוכן בקובץ, ופשוט נחלץ את ה-DA1 לפי הגודל הידוע (0x3A628)
        // הערה: זה לא מדויק, אבל זה ניסיון ראשון.
        val da1Size = 0x3A628
        val da1Data = daBytes.copyOfRange(0, da1Size) 
        // נניח שה-DA1 נמצא בתחילת הקובץ (או אחרי כותרת). 
        // זה שלב שנידרש לדייק בו, אבל בואו נתחיל מההנחה שהוא פשוט שם.

        onProgress("=== שלב 1: שליחת DA1 ===")
        val err1 = sendDa1(connection, outEp, inEp, da1Data, onProgress)
        if (err1 != null) {
            connection.close()
            return "כשל ב-DA1: $err1"
        }
        onProgress("DA1 נשלח בהצלחה!")

        // 2. קפיצה ל-DA1 (0xD5)
        onProgress("=== שלב 2: קפיצה ל-DA1 ===")
        val addr = 0x200000
        if (connection.bulkTransfer(outEp, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: 0xD5 write"
        }
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).array()
        if (connection.bulkTransfer(outEp, addrBytes, 4, 2000) < 0) {
            connection.close(); return "שגיאה: addr write"
        }
        
        // המתנה לבייט סנכרון 0xC0
        val sync = ByteArray(1)
        connection.bulkTransfer(inEp, sync, 1, 2000)
        if (sync[0] != 0xC0.toByte()) {
            connection.close()
            return "כשל בסנכרון DA1: 0x${String.format(Locale.US, "%02X", sync[0])}"
        }
        onProgress("DA1 רץ! (0xC0 התקבל)")

        connection.close()
        return "הצלחה! DA1 נשלח ורץ."
    }
}
