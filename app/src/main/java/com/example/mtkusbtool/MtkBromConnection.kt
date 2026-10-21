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

    data class DaRegion(val buf: Int, val len: Int, val addr: Long, val offset: Int, val sigLen: Int)
    data class DaParts(val da1: ByteArray, val da1Addr: Long, val da2: ByteArray, val da2Addr: Long)

    // פירוס ה-DA לפי האלגוריתם האמיתי של mtkclient
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) { onProgress("!! קובץ קטן מדי"); return null }

        // 1. קריאת הכותרת (0x68 בייטים) ובדיקת v6
        val header = data.copyOfRange(0, 0x68)
        val v6 = String(header, Charsets.US_ASCII).contains("MTK_DA_v6")
        
        // 2. קריאת count_da (4 בייטים ב-offset 0x68)
        val countDa = ByteBuffer.wrap(data, 0x68, 4).order(ByteOrder.LITTLE_ENDIAN).int
        onProgress("count_da: $countDa, v6: $v6")
        
        // 3. בדיקת old_ldr (offset 0x6C + 0xD8)
        val oldLdrCheckOffset = 0x6C + 0xD8
        val oldLdr = oldLdrCheckOffset + 2 <= data.size &&
                data[oldLdrCheckOffset] == 0xDA.toByte() &&
                data[oldLdrCheckOffset + 1] == 0xDA.toByte()
        val recordOffset = if (oldLdr) 0xD8 else 0xDC
        onProgress("old_ldr: $oldLdr, recordOffset: 0x${Integer.toHexString(recordOffset)}")

        // 4. מעבר על כל רשומות ה-DA
        for (i in 0 until countDa) {
            val daStart = 0x6C + (i * recordOffset)
            if (daStart + recordOffset > data.size) break

            val daData = data.copyOfRange(daStart, daStart + recordOffset)
            val buf = ByteBuffer.wrap(daData).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buf.short.toInt() and 0xFFFF
            val hwCode = buf.short.toInt() and 0xFFFF
            val hwSubCode = buf.short.toInt() and 0xFFFF
            val hwVersion = buf.short.toInt() and 0xFFFF

            val entryRegionCount: Int
            if (oldLdr) {
                // בפורמט ישן: אחרי 4 השדות מגיעים ישר region_index ו-region_count
                val entryRegionIndex = buf.short.toInt() and 0xFFFF
                entryRegionCount = buf.short.toInt() and 0xFFFF
            } else {
                // בפורמט חדש: יש עוד 4 שדות לפני
                val swVersion = buf.short.toInt() and 0xFFFF
                val reserved1 = buf.short.toInt() and 0xFFFF
                val pagesize = buf.short.toInt() and 0xFFFF
                val reserved3 = buf.short.toInt() and 0xFFFF
                val entryRegionIndex = buf.short.toInt() and 0xFFFF
                entryRegionCount = buf.short.toInt() and 0xFFFF
            }

            onProgress("DA $i: hwCode=0x${Integer.toHexString(hwCode)}, regions=$entryRegionCount")

            if (hwCode == 0x717) {
                onProgress(">>> מצאנו את MT6761! <<<")
                val regions = mutableListOf<DaRegion>()
                for (r in 0 until entryRegionCount) {
                    if (buf.remaining() < 20) break
                    val mBuf = buf.int
                    val mLen = buf.int
                    val mStartAddr = buf.int.toLong() and 0xFFFFFFFFL
                    val mStartOffset = buf.int
                    val mSigLen = buf.int
                    regions.add(DaRegion(mBuf, mLen, mStartAddr, mStartOffset, mSigLen))
                    onProgress("  Region $r: len=${mLen}, addr=0x${java.lang.Long.toHexString(mStartAddr)}, offset=${mStartOffset}")
                }
                if (regions.size >= 3) {
                    val da1 = regions[1]
                    val da2 = regions[2]
                    onProgress("DA1: offset=0x${Integer.toHexString(da1.offset)}, len=${da1.len}, addr=0x${java.lang.Long.toHexString(da1.addr)}")
                    onProgress("DA2: offset=0x${Integer.toHexString(da2.offset)}, len=${da2.len}, addr=0x${java.lang.Long.toHexString(da2.addr)}")
                    
                    if (da1.offset + da1.len <= data.size && da2.offset + da2.len <= data.size) {
                        return DaParts(
                            data.copyOfRange(da1.offset, da1.offset + da1.len), da1.addr,
                            data.copyOfRange(da2.offset, da2.offset + da2.len), da2.addr
                        )
                    } else {
                        onProgress("!! offsets חורגים מגודל הקובץ")
                    }
                }
            }
        }
        onProgress("!! לא נמצאה רשומה ל-hwCode 0x717")
        return null
    }

    // שליחת DA שלם (0xD7 + size + data)
    private fun sendDaFile(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, daBytes: ByteArray, label: String, onProgress: (String) -> Unit): String? {
        onProgress("$label: שולח 0xD7 (${daBytes.size} בייט)...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) return "FAIL:0xD7 write"
        
        val ack1 = ByteArray(1)
        connection.bulkTransfer(epIn, ack1, 1, 2000)
        if (ack1[0] != 0xD8.toByte()) {
            return "FAIL:0xD7 ack=0x${String.format(Locale.US, "%02X", ack1[0])}"
        }

        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return "FAIL:size write"
        
        val ack2 = ByteArray(1)
        connection.bulkTransfer(epIn, ack2, 1, 2000)
        if (ack2[0] != 0xD9.toByte()) {
            return "FAIL:size ack=0x${String.format(Locale.US, "%02X", ack2[0])}"
        }

        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "FAIL:data write at $offset"
            
            val ack = ByteArray(1)
            connection.bulkTransfer(epIn, ack, 1, 2000)
            if (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte()) {
                return "FAIL:data ack at $offset = 0x${String.format(Locale.US, "%02X", ack[0])}"
            }
            offset += length
            if (offset % (chunkSize * 20) == 0) onProgress("$label: ${(offset * 100) / daBytes.size}%")
        }
        return null
    }

    // הרצת ה-DA (0xD5 + addr)
    private fun jumpToDa(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val ack = ByteArray(2)
        connection.bulkTransfer(epIn, ack, 2, 2000)
        return true
    }

    // הרצף המלא: פירוס -> שליחת DA1 -> שליחת DA2 -> הרצה
    fun runCarbonaraFlow(
        usbManager: UsbManager,
        device: UsbDevice,
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

        onProgress("=== שלב 1: פירוס ה-DA ===")
        val parts = parseDa(daBytes, onProgress) ?: run { connection.close(); return "פירוס נכשל." }

        // לחיצת יד
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val hsCmd = if (isBrom) 0xA0.toByte() else 0xA1.toByte()
        onProgress("PID: 0x${String.format(Locale.US, "%04X", device.productId)} -> לחיצת יד 0x${String.format(Locale.US, "%02X", hsCmd)}")
        if (connection.bulkTransfer(outEp, byteArrayOf(hsCmd), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת לחיצת יד נכשלה."
        }
        val hsAck = ByteArray(1)
        connection.bulkTransfer(inEp, hsAck, 1, 2000)
        if (hsAck[0] != 0x5F.toByte() && hsAck[0] != 0xC0.toByte()) {
            connection.close()
            return "שגיאה בלחיצת יד: 0x${String.format(Locale.US, "%02X", hsAck[0])}"
        }
        onProgress("לחיצת יד הצליחה!")

        // שלב 2: שליחת DA1
        onProgress("=== שלב 2: שליחת DA1 ===")
        val err1 = sendDaFile(connection, outEp, inEp, parts.da1, "DA1", onProgress)
        if (err1 != null) { connection.close(); return "כשל ב-DA1: $err1" }
        onProgress("DA1 נשלח בהצלחה!")

        // שלב 3: שליחת DA2
        onProgress("=== שלב 3: שליחת DA2 ===")
        val err2 = sendDaFile(connection, outEp, inEp, parts.da2, "DA2", onProgress)
        if (err2 != null) { connection.close(); return "כשל ב-DA2: $err2" }
        onProgress("DA2 נשלח בהצלחה!")

        // שלב 4: הרצת DA2
        onProgress("=== שלב 4: הרצת DA2 ===")
        if (!jumpToDa(connection, outEp, inEp, parts.da2Addr)) {
            connection.close(); return "כשל בהרצת DA2."
        }

        connection.close()
        return "הצלחה!!! DA1 ו-DA2 נשלחו. ה-DA2 רץ."
    }
}
