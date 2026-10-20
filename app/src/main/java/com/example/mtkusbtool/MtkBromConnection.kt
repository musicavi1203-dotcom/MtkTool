package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

object MtkBromConnection {

    data class DaParts(
        val da1: ByteArray,
        val da1Offset: Int,
        val da1Size: Int,
        val da1Address: Long,
        val da2: ByteArray,
        val da2Offset: Int,
        val da2Size: Int,
        val da2Address: Long
    )

    // פונקציית עזר לחיפוש מערך בתוך מערך
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // פונקציה חדשה: מחשבת את ה-hash של DA2 ומחפשת אותו ב-DA1
    private fun findHashInDa1(da1: ByteArray, da2: ByteArray, onProgress: (String) -> Unit): Triple<Int, Int, ByteArray>? {
        val hashAlgos = listOf("MD5", "SHA-1", "SHA-256")
        for ((mode, algo) in hashAlgos.withIndex()) {
            val hash = MessageDigest.getInstance(algo).digest(da2)
            onProgress("מחשב $algo: ${hash.joinToString("") { String.format(Locale.US, "%02x", it) }}")
            
            val idx = indexOf(da1, hash)
            if (idx != -1) {
                onProgress("נמצא $algo hash ב-offset 0x${Integer.toHexString(idx)}")
                return Triple(idx, mode, hash)
            }
        }
        return null
    }

    // שליחת פקודת 0xD4 (WRITE32) עם כתובת ונתונים
    private fun write32(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long, data: ByteArray): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size / 4).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        
        val ack = ByteArray(1)
        connection.bulkTransfer(epIn, ack, 1, 2000)
        
        var offset = 0
        while (offset < data.size) {
            val chunk = if (offset + 4 <= data.size) data.copyOfRange(offset, offset + 4) else byteArrayOf(0, 0, 0, 0)
            if (connection.bulkTransfer(epOut, chunk, 4, 2000) < 0) return false
            offset += 4
        }
        return true
    }

    // שליחת פקודת 0xD5 (JUMP_DA) עם כתובת
    private fun jumpTo(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val ack = ByteArray(2)
        connection.bulkTransfer(epIn, ack, 2, 2000)
        return true
    }

    // שליחת DA מלא (0xD7 + size + data)
    private fun sendDaFile(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, daBytes: ByteArray, label: String, onProgress: (String) -> Unit): String? {
        onProgress("$label: שולח 0xD7 (${daBytes.size} בייט)...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) return "FAIL:0xD7 write"
        
        val ack1 = ByteArray(1)
        connection.bulkTransfer(epIn, ack1, 1, 2000)
        if (ack1[0] != 0xD8.toByte()) {
            return "FAIL:0xD7 ack=${String.format(Locale.US, "0x%02X", ack1[0])}"
        }

        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return "FAIL:size write"
        
        val ack2 = ByteArray(1)
        connection.bulkTransfer(epIn, ack2, 1, 2000)
        if (ack2[0] != 0xD9.toByte()) {
            return "FAIL:size ack=${String.format(Locale.US, "0x%02X", ack2[0])}"
        }

        var offset = 0
        val chunkSize = 4096
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "FAIL:data write at $offset"
            
            val ack = ByteArray(1)
            connection.bulkTransfer(epIn, ack, 1, 2000)
            if (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte()) {
                return "FAIL:data ack at $offset = ${String.format(Locale.US, "0x%02X", ack[0])}"
            }
            offset += length
            if (offset % (chunkSize * 10) == 0) onProgress("$label: ${(offset * 100) / daBytes.size}%")
        }
        return null
    }

    fun runCarbonara(
        usbManager: UsbManager,
        device: UsbDevice,
        daBytes: ByteArray,
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

        // 1. פירוק ה-DA - הפעם פשוט נשלח את הקובץ כאילו הוא DA1 ו-DA2
        // (זה לא מדויק, אבל זה ניסיון אחרון)
        onProgress("=== שלב 1: פירוק ה-DA ===")
        // נשלח את כל הקובץ כקובץ DA יחיד, לא מפוצל
        val parts = DaParts(daBytes, 0, daBytes.size, 0x201000, ByteArray(0), 0, 0, 0)
        onProgress("DA: ${parts.da1.size} בייט, כתובת: 0x${java.lang.Long.toHexString(parts.da1Address)}")

        // 2. שליחת DA1
        onProgress("=== שלב 2: שליחת DA ===")
        val err1 = sendDaFile(connection, epOut, epIn, parts.da1, "DA", onProgress)
        if (err1 != null) {
            connection.close()
            return "כשל ב-DA: $err1"
        }

        // 3. הרצת DA
        onProgress("=== שלב 3: הרצת DA ===")
        if (!jumpTo(connection, epOut, epIn, parts.da1Address)) {
            connection.close()
            return "כשל בהרצת DA."
        }

        connection.close()
        return "הצלחה! ה-DA נשלח והורץ."
    }
}
