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
        
        fun write32(addr: Int, val1: Int): Boolean {
            if (c.bulkTransfer(oEp, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array(), 4, 2000) < 0) return false
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array(), 4, 2000) < 0) return false // 1 dword
            
            // קריאת סטטוס חובה
            val status = ByteArray(4)
            val statusRead = c.bulkTransfer(iEp, status, 4, 2000)
            if (statusRead != 4) {
                onProgress("  write32 status read failed (got $statusRead)")
                return false
            }
            val statusInt = ByteBuffer.wrap(status).order(ByteOrder.BIG_ENDIAN).int
            if (statusInt != 0) {
                onProgress("  write32 status is 0x${String.format(Locale.US, "%08X", statusInt)} (expected 0)")
                return false
            }
            
            if (c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(val1).array(), 4, 2000) < 0) return false
            return true
        }

        onProgress("1. השבתת Watchdog (0x10007000)...")
        if (!write32(0x10007000, 0x22000000)) { c.close(); return "השבתת Watchdog נכשלה." }

        onProgress("2. כתיבת כתובת Payload ל-Scratch (0x10007050)...")
        if (!write32(0x10007050, 0x100A00)) { c.close(); return "כתיבת Scratch נכשלה." }

        onProgress("3. שליחת 0xE0...")
        c.bulkTransfer(oEp, byteArrayOf(0xE0.toByte()), 1, 2000)
        val echoCmd = ByteArray(1)
        c.bulkTransfer(iEp, echoCmd, 1, 2000)

        onProgress("4. שליחת אורך (0xA00)...")
        c.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xA00).array(), 4, 2000)
        val echoSize = ByteArray(4)
        c.bulkTransfer(iEp, echoSize, 4, 2000)

        onProgress("5. שליחת Payload (${payloadBytes.size} בייט)...")
        if (c.bulkTransfer(oEp, payloadBytes, payloadBytes.size, 5000) < 0) {
            c.close(); return "שליחת Payload נכשלה."
        }

        onProgress("6. הפעלת ה-Trigger (controlTransfer)...")
        try {
            // ה-trigger של Kamakiri: wIndex=204 (0xCC)
            c.controlTransfer(0xA1, 0, 0, 204, null, 0, 5000)
        } catch (e: Exception) {
            onProgress("  Trigger exception: ${e.message}")
        }

        c.close()
        onProgress("Kamakiri הורץ! ממתין ל-reconnect...")
        return "KAMAKIRI_DONE"
    }

    fun runDa1Phase(usbManager: UsbManager, device: UsbDevice, daBytes: ByteArray, onProgress: (String) -> Unit): String {
        var c: UsbDeviceConnection? = null
        for (attempt in 1..30) {
            c = usbManager.openDevice(device)
            if (c != null) { onProgress("חיבור נפתח בניסיון $attempt"); break }
            Thread.sleep(50)
        }
        val connection = c ?: return "שגיאה: לא הצלחנו לפתוח חיבור."
        
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (connection.claimInterface(itf, true)) {
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                }
                if (inEp != null && outEp != null) break
            }
        }
        if (inEp == null || outEp == null) { connection.close(); return "שגיאה: endpoints." }
        val iEp = inEp; val oEp = outEp

        onProgress("=== שלב ג': Handshake חדש ===")
        if (!doHandshake(connection, iEp, oEp, onProgress)) { connection.close(); return "Handshake מחדש נכשל." }

        onProgress("=== שלב ד': שליחת DA1 ===")
        
        val da1Addr = 0x200000L
        val sigLen = 256
        onProgress("DA1: ${daBytes.size} בייט, addr=0x${java.lang.Long.toHexString(da1Addr)}, sigLen=$sigLen")
        
        val headerBuf = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        headerBuf.put(0xD7.toByte())
        headerBuf.putInt(da1Addr.toInt())
        headerBuf.putInt(daBytes.size)
        headerBuf.putInt(0x1000) // packetsize 4096
        
        if (connection.bulkTransfer(oEp, headerBuf.array(), 13, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת Header נכשלה."
        }
        
        val headerAck = ByteArray(1)
        if (connection.bulkTransfer(iEp, headerAck, 1, 2000) != 1) {
            connection.close(); return "שגיאה: לא התקבלה תגובה ל-Header."
        }
        
        val ackHex = String.format(Locale.US, "0x%02X", headerAck[0])
        if (headerAck[0] == 0xD8.toByte()) {
            connection.close()
            return "שגיאה: התקבל 0xD8. המכשיר דורש אימות SBC/SLA."
        } else if (headerAck[0] != 0x5A.toByte()) {
            connection.close(); return "שגיאה: Header לא אושר (קיבלנו $ackHex, צפינו ל-0x5A)."
        }
        
        onProgress("SEND_DA אושר! (0x5A) מתחיל להעביר נתונים...")

        var offset = 0
        val chunkSize = 0x1000
        var lastProgress = -10
        
        while (offset < daBytes.size) {
            val len = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + len)
            
            if (connection.bulkTransfer(oEp, chunk, len, 5000) < 0) {
                connection.close(); return "שגיאה: שליחת נתונים נכשלה ב-$offset."
            }
            
            val ackBuf = ByteArray(1)
            val ackRead = connection.bulkTransfer(iEp, ackBuf, 1, 5000)
            if (ackRead != 1 || ackBuf[0] != 0x5A.toByte()) {
                val actualAck = if (ackRead == 1) String.format(Locale.US, "0x%02X", ackBuf[0]) else "אין"
                connection.close()
                return "שגיאה: ACK לא תקין ב-$offset (קיבלנו $actualAck, צפינו ל-0x5A)."
            }
            
            offset += len
            val progress = (offset * 100) / daBytes.size
            if (progress >= lastProgress + 10) {
                onProgress("DA1: $progress% (offset=$offset, ack=0x5A)")
                lastProgress = progress
            }
        }
        onProgress("כל הנתונים נשלחו בהצלחה! ממתין ל-ACK סופי...")

        Thread.sleep(500)
        
        if (connection.bulkTransfer(oEp, byteArrayOf(0x5A.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת ACK סופי נכשלה."
        }
        
        val finalAckBuf = ByteArray(1)
        if (connection.bulkTransfer(iEp, finalAckBuf, 1, 5000) != 1) {
            connection.close(); return "שגיאה: לא התקבל ACK סופי."
        }
        onProgress("ACK סופי התקבל: 0x${String.format(Locale.US, "%02X", finalAckBuf[0])}")

        onProgress("=== שלב ה': קפיצה ל-DA1 ===")
        if (connection.bulkTransfer(oEp, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת JUMP_DA נכשלה."
        }
        connection.bulkTransfer(iEp, ByteArray(1), 1, 2000)
        connection.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(da1Addr.toInt()).array(), 4, 2000)
        connection.bulkTransfer(iEp, ByteArray(4), 4, 2000)
        
        val syncBuf = ByteArray(1)
        connection.bulkTransfer(iEp, syncBuf, 1, 5000)
        if (syncBuf[0] != 0xC0.toByte()) {
            connection.close(); return "sync=0x${String.format(Locale.US, "%02X", syncBuf[0])}"
        }
        onProgress("DA1 רץ! (0xC0 התקבל)")

        connection.close()
        return "הצלחה! DA1 נשלח ורץ."
    }
}
