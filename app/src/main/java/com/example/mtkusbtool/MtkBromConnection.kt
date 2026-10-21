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

    data class DaParts(val da1: ByteArray, val da1Addr: Long, val da2: ByteArray, val da2Addr: Long)

    // פירוס MTK_DA_V5.bin לפי המבנה של mtkclient
    fun parseDaV5(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) { onProgress("!! קובץ קטן מדי"); return null }

        val countDa = ByteBuffer.wrap(data, 0x68, 4).order(ByteOrder.LITTLE_ENDIAN).int
        onProgress("count_da: $countDa")

        // בדיקת old_ldr (offset 0x6C + 0xD8)
        val oldLdr = data.size > 0x6C + 0xD8 + 2 &&
                data[0x6C + 0xD8] == 0xDA.toByte() &&
                data[0x6C + 0xD8 + 1] == 0xDA.toByte()
        val recordSize = if (oldLdr) 0xD8 else 0xDC
        onProgress("old_ldr: $oldLdr, recordSize: 0x${Integer.toHexString(recordSize)}")

        var offset = 0x6C
        for (i in 0 until countDa) {
            if (offset + recordSize > data.size) break
            val buf = ByteBuffer.wrap(data, offset, recordSize).order(ByteOrder.LITTLE_ENDIAN)
            
            val magic = buf.short.toInt() and 0xFFFF
            val hwCode = buf.short.toInt() and 0xFFFF
            val hwSubCode = buf.short.toInt() and 0xFFFF
            val hwVersion = buf.short.toInt() and 0xFFFF
            val swVersion = buf.short.toInt() and 0xFFFF
            val reserved1 = buf.short.toInt() and 0xFFFF
            val pagesize = buf.short.toInt() and 0xFFFF
            val reserved3 = buf.short.toInt() and 0xFFFF
            val entryRegionIndex = buf.short.toInt() and 0xFFFF
            val entryRegionCount = buf.short.toInt() and 0xFFFF

            onProgress("DA $i: magic=0x${Integer.toHexString(magic)}, hwCode=0x${Integer.toHexString(hwCode)}, hwSub=0x${Integer.toHexString(hwSubCode)}, hwVer=0x${Integer.toHexString(hwVersion)}, regions=$entryRegionCount")

            // חיפוש ה-MT6761: hwCode=0x6761, hwSub=0x8A00, hwVer=0xCA00
            if (hwCode == 0x6761 && hwSubCode == 0x8A00 && hwVersion == 0xCA00) {
                onProgress(">>> מצאנו MT6761! <<<")
                val regions = mutableListOf<Pair<Int, Int>>()
                for (r in 0 until entryRegionCount) {
                    if (buf.remaining() < 20) break
                    val mBuf = buf.int
                    val mLen = buf.int
                    val mAddr = buf.int.toLong() and 0xFFFFFFFFL
                    val mOffset = buf.int
                    val mSigLen = buf.int
                    regions.add(mOffset to mLen)
                    onProgress("  Region $r: len=${mLen}, addr=0x${java.lang.Long.toHexString(mAddr)}, offset=${mOffset}")
                }
                if (regions.size >= 3) {
                    val da1 = regions[1]
                    val da2 = regions[2]
                    if (da1.first + da1.second <= data.size && da2.first + da2.second <= data.size) {
                        return DaParts(
                            data.copyOfRange(da1.first, da1.first + da1.second),
                            0x200000,
                            data.copyOfRange(da2.first, da2.first + da2.second),
                            0x40000000
                        )
                    }
                }
            }
            offset += recordSize
        }
        onProgress("!! לא נמצאה רשומה ל-MT6761")
        return null
    }

    // חישוב XOR checksum
    private fun calculateXorChecksum(data: ByteArray): ByteArray {
        var checksum = 0
        for (b in data) {
            checksum = checksum xor (b.toInt() and 0xFF)
        }
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(checksum.toShort()).array()
    }

    // שליחת DA1
    private fun sendDa1(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, da1Data: ByteArray, onProgress: (String) -> Unit): String? {
        val addr = 0x200000
        val sigLen = 256
        
        onProgress("DA1: שולח 0xD7 (SEND_DA)...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) return "FAIL:0xD7 write"
        
        val ack1 = ByteArray(1)
        connection.bulkTransfer(epIn, ack1, 1, 2000)
        if (ack1[0] != 0xD8.toByte()) return "FAIL:0xD7 ack=0x${String.format(Locale.US, "%02X", ack1[0])}"

        // כתובת
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return "FAIL:addr write"
        
        // גודל
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(da1Data.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return "FAIL:size write"
        
        // אורך חתימה
        val sigLenBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sigLen).array()
        if (connection.bulkTransfer(epOut, sigLenBytes, 4, 2000) < 0) return "FAIL:siglen write"

        // קריאת אישור
        val ack2 = ByteArray(2)
        connection.bulkTransfer(epIn, ack2, 2, 2000)
        val ack2Short = ByteBuffer.wrap(ack2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        
        if (ack2Short == 0x1D0D) return "FAIL:SLA required (0x1D0D)"
        if (ack2Short != 0xD9) return "FAIL:ack=0x${String.format(Locale.US, "%04X", ack2Short)}"
        onProgress("DA1: אישור התקבל. שולח נתונים...")

        // נתונים
        var offset = 0
        val chunkSize = 512
        while (offset < da1Data.size) {
            val length = minOf(chunkSize, da1Data.size - offset)
            val chunk = da1Data.copyOfRange(offset, offset + length)
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "FAIL:data write at $offset"
            offset += length
            if (offset % (chunkSize * 40) == 0) onProgress("DA1: ${(offset * 100) / da1Data.size}%")
        }

        // checksum
        val checksum = calculateXorChecksum(da1Data)
        if (connection.bulkTransfer(epOut, checksum, 2, 2000) < 0) return "FAIL:checksum write"
        
        val finalAck = ByteArray(1)
        connection.bulkTransfer(epIn, finalAck, 1, 2000)
        if (finalAck[0] != 0x5A.toByte() && finalAck[0] != 0x5F.toByte()) {
            return "FAIL:final ack=0x${String.format(Locale.US, "%02X", finalAck[0])}"
        }

        return null
    }

    fun runFullFlow(usbManager: UsbManager, device: UsbDevice, daBytes: ByteArray, onProgress: (String) -> Unit): String {
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

        onProgress("=== שלב 1: פירוס MTK_DA_V5.bin ===")
        val parts = parseDaV5(daBytes, onProgress) ?: run { connection.close(); return "פירוס נכשל." }

        onProgress("=== שלב 2: שליחת DA1 ===")
        val err1 = sendDa1(connection, outEp, inEp, parts.da1, onProgress)
        if (err1 != null) { connection.close(); return "כשל ב-DA1: $err1" }
        onProgress("DA1 נשלח בהצלחה!")

        onProgress("=== שלב 3: קפיצה ל-DA1 ===")
        if (connection.bulkTransfer(outEp, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: 0xD5 write"
        }
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200000).array()
        if (connection.bulkTransfer(outEp, addrBytes, 4, 2000) < 0) {
            connection.close(); return "שגיאה: addr write"
        }
        
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
