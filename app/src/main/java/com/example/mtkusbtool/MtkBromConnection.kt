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

    // הוספנו sigLen לנתונים
    data class DaParts(val da1: ByteArray, val da1Addr: Long, val da1SigLen: Int, val da2: ByteArray, val da2Addr: Long, val da2SigLen: Int)

    fun parseDaV5(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) return null
        val countDa = ByteBuffer.wrap(data, 0x68, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val oldLdr = data.size > 0x6C + 0xD8 + 2 &&
                data[0x6C + 0xD8] == 0xDA.toByte() &&
                data[0x6C + 0xD8 + 1] == 0xDA.toByte()
        val recordSize = if (oldLdr) 0xD8 else 0xDC
        var offset = 0x6C
        for (i in 0 until countDa) {
            if (offset + recordSize > data.size) break
            val buf = ByteBuffer.wrap(data, offset, recordSize).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.short.toInt() and 0xFFFF
            val hwCode = buf.short.toInt() and 0xFFFF
            val hwSub = buf.short.toInt() and 0xFFFF
            val hwVer = buf.short.toInt() and 0xFFFF
            buf.short; buf.short; buf.short; buf.short
            val regionIdx = buf.short.toInt() and 0xFFFF
            val regionCount = buf.short.toInt() and 0xFFFF
            if (hwCode == 0x6761) {
                val regions = mutableListOf<Triple<Int, Int, Long>>()
                val sigLens = mutableListOf<Int>()
                for (r in 0 until regionCount) {
                    if (buf.remaining() < 20) break
                    val mBuf = buf.int; val mLen = buf.int
                    val mAddr = buf.int.toLong() and 0xFFFFFFFFL
                    val mOffset = buf.int; val mSigLen = buf.int
                    regions.add(Triple(mOffset, mLen, mAddr))
                    sigLens.add(mSigLen)
                }
                if (regions.size >= 3) {
                    val da1 = regions[1]; val da2 = regions[2]
                    return DaParts(
                        data.copyOfRange(da1.first, da1.first + da1.second), da1.third, sigLens[1],
                        data.copyOfRange(da2.first, da2.first + da2.second), da2.third, sigLens[2]
                    )
                }
            }
            offset += recordSize
        }
        return null
    }

    private fun calcXor(data: ByteArray): ByteArray {
        var cs = 0
        for (b in data) cs = cs xor (b.toInt() and 0xFF)
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(cs.toShort()).array()
    }

    private fun doHandshake(c: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint, onProgress: (String) -> Unit): Boolean {
        val resp = ByteArray(1)
        onProgress("מנסה 0xA0 (BROM)...")
        c.bulkTransfer(outEp, byteArrayOf(0xA0.toByte()), 1, 1000)
        var r = c.bulkTransfer(inEp, resp, 1, 1000)
        if (r > 0 && (resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte())) {
            onProgress("BROM handshake OK!")
            return true
        }
        onProgress("מנסה 0xA1 (Preloader)...")
        c.bulkTransfer(outEp, byteArrayOf(0xA1.toByte()), 1, 1000)
        r = c.bulkTransfer(inEp, resp, 1, 1000)
        if (r > 0 && (resp[0] == 0x5F.toByte() || resp[0] == 0xC0.toByte())) {
            onProgress("Preloader handshake OK!")
            return true
        }
        val hex = if (r > 0) String.format(Locale.US, "0x%02X", resp[0]) else "אין"
        onProgress("שתי השיטות נכשלו ($hex)")
        return false
    }

    fun runKamakiriPhase(usbManager: UsbManager, device: UsbDevice, payloadBytes: ByteArray, onProgress: (String) -> Unit): String {
        val c = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (c.claimInterface(itf, true)) {
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                }
                if (inEp != null && outEp != null) break
            }
        }
        if (inEp == null || outEp == null) { c.close(); return "שגיאה: endpoints." }
        val iEp = inEp; val oEp = outEp

        onProgress("=== שלב א': Handshake ===")
        if (!doHandshake(c, iEp, oEp, onProgress)) { c.close(); return "Handshake נכשל." }

        onProgress("=== שלב ב': Kamakiri ===")
        fun write32(addr: Int, values: IntArray): Boolean {
            if (c.bulkTransfer(oEp, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array(), 4, 2000) < 0) return false
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.size).array(), 4, 2000) < 0) return false
            val ack = ByteArray(1); c.bulkTransfer(iEp, ack, 1, 2000)
            for (v in values) {
                if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array(), 4, 2000) < 0) return false
            }
            return true
        }
        fun read32(addr: Int, dwords: Int): Boolean {
            if (c.bulkTransfer(oEp, byteArrayOf(0xD1.toByte()), 1, 2000) < 0) return false
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array(), 4, 2000) < 0) return false
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(dwords).array(), 4, 2000) < 0) return false
            val ack = ByteArray(1); c.bulkTransfer(iEp, ack, 1, 2000)
            val data = ByteArray(dwords * 4); c.bulkTransfer(iEp, data, data.size, 2000)
            c.bulkTransfer(iEp, ByteArray(2), 2, 2000)
            return true
        }
        fun echo(data: ByteArray) { c.bulkTransfer(oEp, data, data.size, 2000); c.bulkTransfer(iEp, ByteArray(data.size), data.size, 2000) }

        onProgress("write32(0x10007050, revdword(0x100A00))...")
        if (!write32(0x10007050, intArrayOf(0x000A1000))) { c.close(); return "write32 נכשל." }
        
        for (i in 0 until 0xF) {
            read32(0x10007050 - (0xF - i) * 4, 0xF - i + 1)
        }
        
        onProgress("echo 0xE0...")
        echo(byteArrayOf(0xE0.toByte()))
        onProgress("send payload length...")
        echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadBytes.size).array())
        c.bulkTransfer(iEp, ByteArray(2), 2, 2000)
        onProgress("Sending payload (${payloadBytes.size} בייט)...")
        c.bulkTransfer(oEp, payloadBytes, payloadBytes.size, 2000)
        c.bulkTransfer(iEp, ByteArray(2), 2, 2000)
        c.bulkTransfer(iEp, ByteArray(2), 2, 2000)
        
        onProgress("Triggering ctrl_transfer(0xA1, 0, 0, 0x25, 0)...")
        try { c.controlTransfer(0xA1, 0, 0, 0x25, null, 0, 1000) } catch (e: Exception) {}
        
        c.close()
        onProgress("Kamakiri הורץ! ממתין ל-reconnect...")
        return "KAMAKIRI_DONE"
    }

    fun runDa1Phase(usbManager: UsbManager, device: UsbDevice, daBytes: ByteArray, onProgress: (String) -> Unit): String {
        val c = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (c.claimInterface(itf, true)) {
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                }
                if (inEp != null && outEp != null) break
            }
        }
        if (inEp == null || outEp == null) { c.close(); return "שגיאה: endpoints." }
        val iEp = inEp; val oEp = outEp

        onProgress("=== שלב ג': Handshake חדש ===")
        if (!doHandshake(c, iEp, oEp, onProgress)) { c.close(); return "Handshake מחדש נכשל." }

        onProgress("=== שלב ד': שליחת DA1 ===")
        val parts = parseDaV5(daBytes, onProgress) ?: run { c.close(); return "פירוס נכשל." }
        
        onProgress("DA1: ${parts.da1.size} בייט, addr=0x${java.lang.Long.toHexString(parts.da1Addr)}, sigLen=${parts.da1SigLen}")
        
        if (c.bulkTransfer(oEp, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) { c.close(); return "0xD7 נכשל." }
        val ack1 = ByteArray(1); c.bulkTransfer(iEp, ack1, 1, 2000)
        if (ack1[0] != 0xD8.toByte()) {
            c.close(); return "0xD7 ack=0x${String.format(Locale.US, "%02X", ack1[0])}"
        }
        onProgress("0xD7 אושר! (0xD8)")

        // שימוש ב-sigLen האמיתי
        c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(parts.da1Addr.toInt()).array(), 4, 2000)
        c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(parts.da1.size).array(), 4, 2000)
        c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(parts.da1SigLen).array(), 4, 2000)
        
        val ack2 = ByteArray(2); c.bulkTransfer(iEp, ack2, 2, 2000)
        val ack2Short = ByteBuffer.wrap(ack2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (ack2Short == 0x1D0D) { c.close(); return "SLA required (0x1D0D)" }
        if (ack2Short != 0xD9) { c.close(); return "ack=0x${String.format(Locale.US, "%04X", ack2Short)}" }
        onProgress("גודל אושר! שולח נתונים...")

        var offset = 0
        while (offset < parts.da1.size) {
            val len = minOf(512, parts.da1.size - offset)
            c.bulkTransfer(oEp, parts.da1.copyOfRange(offset, offset + len), len, 2000)
            offset += len
            if (offset % (512 * 40) == 0) onProgress("DA1: ${(offset * 100) / parts.da1.size}%")
        }

        c.bulkTransfer(oEp, calcXor(parts.da1), 2, 2000)
        val finalAck = ByteArray(1); c.bulkTransfer(iEp, finalAck, 1, 2000)
        if (finalAck[0] != 0x5A.toByte() && finalAck[0] != 0x5F.toByte()) {
            c.close(); return "final ack=0x${String.format(Locale.US, "%02X", finalAck[0])}"
        }
        onProgress("DA1 נשלח בהצלחה!")

        onProgress("=== שלב ה': קפיצה ל-DA1 ===")
        c.bulkTransfer(oEp, byteArrayOf(0xD5.toByte()), 1, 2000)
        c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(parts.da1Addr.toInt()).array(), 4, 2000)
        
        val sync = ByteArray(1); c.bulkTransfer(iEp, sync, 1, 2000)
        if (sync[0] != 0xC0.toByte()) {
            c.close(); return "sync=0x${String.format(Locale.US, "%02X", sync[0])}"
        }
        onProgress("DA1 רץ! (0xC0 התקבל)")

        c.close()
        return "הצלחה! DA1 נשלח ורץ."
    }
}
