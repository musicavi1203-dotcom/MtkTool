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

    private const val TIMEOUT = 2000

    private fun readExact(c: UsbDeviceConnection, ep: UsbEndpoint, size: Int, timeout: Int): ByteArray? {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = c.bulkTransfer(ep, buf, offset, size - offset, timeout)
            if (read <= 0) return null
            offset += read
        }
        return buf
    }

    private fun echo(c: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint, data: ByteArray, timeout: Int, onProgress: (String) -> Unit): Boolean {
        if (c.bulkTransfer(outEp, data, data.size, timeout) < 0) {
            onProgress("  echo: send failed")
            return false
        }
        val echo = readExact(c, inEp, data.size, timeout)
        if (echo == null) {
            onProgress("  echo: read timeout")
            return false
        }
        if (!echo.contentEquals(data)) {
            val sent = data.joinToString(" ") { String.format(Locale.US, "%02X", it) }
            val got = echo.joinToString(" ") { String.format(Locale.US, "%02X", it) }
            onProgress("  echo mismatch! sent[$sent] got[$got]")
            return false
        }
        return true
    }

    private fun echoByte(c: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint, value: Byte, timeout: Int, onProgress: (String) -> Unit): Boolean {
        return echo(c, inEp, outEp, byteArrayOf(value), timeout, onProgress)
    }

    private fun checkStatus(c: UsbDeviceConnection, inEp: UsbEndpoint, timeout: Int, onProgress: (String) -> Unit): Boolean {
        val status = readExact(c, inEp, 2, timeout)
        if (status == null) {
            onProgress("  status read timeout")
            return false
        }
        val value = ByteBuffer.wrap(status).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        onProgress("  status = 0x${String.format(Locale.US, "%04X", value)}")
        if (value != 0 && value != 1) {
            onProgress("  status invalid")
            return false
        }
        return true
    }

    private fun write32(c: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint, addr: Int, value: Int, timeout: Int, onProgress: (String) -> Unit): Boolean {
        onProgress("  write32(0x${String.format(Locale.US, "%08X", addr)}, 0x${String.format(Locale.US, "%08X", value)})")
        if (!echoByte(c, inEp, outEp, 0xD4.toByte(), timeout, onProgress)) return false
        onProgress("  echo 0xD4 OK")
        
        if (!echo(c, inEp, outEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array(), timeout, onProgress)) return false
        onProgress("  echo addr OK")
        
        if (!echo(c, inEp, outEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array(), timeout, onProgress)) return false
        onProgress("  echo len OK")
        
        if (!checkStatus(c, inEp, timeout, onProgress)) return false
        
        if (!echo(c, inEp, outEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(), timeout, onProgress)) return false
        onProgress("  echo data OK")
        
        val finalStatus = readExact(c, inEp, 2, 500)
        if (finalStatus != null) {
            val valInt = ByteBuffer.wrap(finalStatus).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            onProgress("  final status = 0x${String.format(Locale.US, "%04X", valInt)}")
        }
        return true
    }

    private fun read32(c: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint, addr: Int, size: Int, timeout: Int, onProgress: (String) -> Unit): Boolean {
        onProgress("  read32(0x${String.format(Locale.US, "%08X", addr)}, size=$size)")
        if (!echoByte(c, inEp, outEp, 0xD1.toByte(), timeout, onProgress)) return false
        if (!echo(c, inEp, outEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array(), timeout, onProgress)) return false
        if (!echo(c, inEp, outEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array(), timeout, onProgress)) return false
        
        if (!checkStatus(c, inEp, timeout, onProgress)) return false
        
        val data = readExact(c, inEp, size * 4, timeout)
        if (data == null) {
            onProgress("  read32 data timeout")
            return false
        }
        
        val finalStatus = readExact(c, inEp, 2, 500)
        if (finalStatus != null) {
            val valInt = ByteBuffer.wrap(finalStatus).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            onProgress("  read32 final status = 0x${String.format(Locale.US, "%04X", valInt)}")
        }
        return true
    }

    private fun doHandshake(c: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint, onProgress: (String) -> Unit): Boolean {
        val sequence = byteArrayOf(0xA0.toByte(), 0x0A.toByte(), 0x50.toByte(), 0x05.toByte())
        for (i in sequence.indices) {
            if (c.bulkTransfer(outEp, byteArrayOf(sequence[i]), 1, TIMEOUT) < 0) return false
            val reply = readExact(c, inEp, 1, TIMEOUT) ?: return false
            val expected = (sequence[i].toInt().inv() and 0xFF).toByte()
            if (reply[0] != expected) {
                onProgress("Handshake failed at step $i (expected 0x${String.format(Locale.US, "%02X", expected)}, got 0x${String.format(Locale.US, "%02X", reply[0])})")
                return false
            }
        }
        onProgress("BROM handshake מלא OK!")
        return true
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

        val wdtAddr = 0x10007000
        val scratchAddr = wdtAddr + 0x50
        val payloadAddr = 0x100A00

        onProgress("1. השבתת Watchdog (0x10007000)...")
        if (!write32(c, iEp, oEp, wdtAddr, 0x22000064, TIMEOUT, onProgress)) {
            c.close(); return "השבתת Watchdog נכשלה."
        }

        onProgress("2. כתיבת כתובת Payload ל-Scratch (0x10007050)...")
        if (!write32(c, iEp, oEp, scratchAddr, payloadAddr, TIMEOUT, onProgress)) {
            c.close(); return "כתיבת Scratch נכשלה."
        }

        onProgress("3. לולאת read32 (15 פעמים)...")
        for (i in 0 until 15) {
            if (!read32(c, iEp, oEp, scratchAddr - (15 - i) * 4, 15 - i + 1, TIMEOUT, onProgress)) {
                onProgress("  read32 failed at iteration $i")
            }
        }

        onProgress("4. שליחת 0xE0...")
        if (!echoByte(c, iEp, oEp, 0xE0.toByte(), TIMEOUT, onProgress)) {
            c.close(); return "שליחת 0xE0 נכשלה."
        }

        onProgress("5. שליחת אורך (${payloadBytes.size})...")
        if (!echo(c, iEp, oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadBytes.size).array(), TIMEOUT, onProgress)) {
            c.close(); return "שליחת אורך נכשלה."
        }

        onProgress("6. קריאת status (2 בייטים)...")
        if (!checkStatus(c, iEp, TIMEOUT, onProgress)) {
            c.close(); return "status אחרי אורך לא תקין."
        }

        onProgress("7. שליחת Payload (${payloadBytes.size} בייט)...")
        if (c.bulkTransfer(oEp, payloadBytes, payloadBytes.size, TIMEOUT) < 0) {
            c.close(); return "שליחת Payload נכשלה."
        }

        onProgress("8. הפעלת ה-Trigger...")
        try {
            c.controlTransfer(0xA1, 0, 0, 0x7F, null, 0, TIMEOUT)
        } catch (e: Exception) {}

        c.close()
        onProgress("Kamakiri הורץ! ממתין ל-reconnect...")
        return "KAMAKIRI_DONE"
    }

    fun runDa1Phase(usbManager: UsbManager, device: UsbDevice, daBytes: ByteArray, authBytes: ByteArray?, onProgress: (String) -> Unit): String {
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

        // --- שלב ג.5: שליחת AUTH עם פקודה 0xE2 ---
        if (authBytes != null) {
            onProgress("=== שלב ג.5: שליחת AUTH (פקודה 0xE2) ===")
            if (!echoByte(connection, iEp, oEp, 0xE2.toByte(), TIMEOUT, onProgress)) {
                connection.close(); return "שליחת פקודת AUTH נכשלה."
            }
            if (!echo(connection, iEp, oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(authBytes.size).array(), TIMEOUT, onProgress)) {
                connection.close(); return "שליחת אורך AUTH נכשלה."
            }
            if (connection.bulkTransfer(oEp, authBytes, authBytes.size, TIMEOUT) < 0) {
                connection.close(); return "שליחת נתוני AUTH נכשלה."
            }
            val authStatus = readExact(connection, iEp, 2, TIMEOUT)
            if (authStatus != null) {
                val st = ByteBuffer.wrap(authStatus).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                onProgress("  AUTH status = 0x${String.format(Locale.US, "%04X", st)}")
            } else {
                onProgress("  AUTH status timeout (ממשיך בכל זאת)")
            }
        }

        onProgress("=== שלב ד': שליחת DA1 ===")
        
        val da1Addr = 0x200000L
        val sigLen = 256
        onProgress("DA1: ${daBytes.size} בייט, addr=0x${java.lang.Long.toHexString(da1Addr)}, sigLen=$sigLen")
        
        // --- שליחת ה-Header בשלבים נפרדים ---
        if (!echoByte(connection, iEp, oEp, 0xD7.toByte(), TIMEOUT, onProgress)) {
            connection.close(); return "שגיאה: שליחת פקודת SEND_DA נכשלה."
        }
        
        connection.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(da1Addr.toInt()).array(), 4, TIMEOUT)
        connection.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(daBytes.size).array(), 4, TIMEOUT)
        connection.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x1000).array(), 4, TIMEOUT)
        
        val headerAck = readExact(connection, iEp, 1, TIMEOUT)
        if (headerAck == null) {
            connection.close(); return "שגיאה: לא התקבלה תשובה ל-Header."
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
            
            if (connection.bulkTransfer(oEp, chunk, len, TIMEOUT) < 0) {
                connection.close(); return "שגיאה: שליחת נתונים נכשלה ב-$offset."
            }
            
            val ackBuf = readExact(connection, iEp, 1, TIMEOUT)
            if (ackBuf == null || ackBuf[0] != 0x5A.toByte()) {
                val actualAck = if (ackBuf != null) String.format(Locale.US, "0x%02X", ackBuf[0]) else "אין"
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
        
        if (connection.bulkTransfer(oEp, byteArrayOf(0x5A.toByte()), 1, TIMEOUT) < 0) {
            connection.close(); return "שגיאה: שליחת ACK סופי נכשלה."
        }
        
        val finalAck = readExact(connection, iEp, 1, TIMEOUT)
        if (finalAck == null) {
            connection.close(); return "שגיאה: לא התקבל ACK סופי."
        }
        onProgress("ACK סופי התקבל: 0x${String.format(Locale.US, "%02X", finalAck[0])}")

        onProgress("=== שלב ה': קפיצה ל-DA1 ===")
        if (connection.bulkTransfer(oEp, byteArrayOf(0xD5.toByte()), 1, TIMEOUT) < 0) {
            connection.close(); return "שגיאה: שליחת JUMP_DA נכשלה."
        }
        readExact(connection, iEp, 1, TIMEOUT)
        connection.bulkTransfer(oEp, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(da1Addr.toInt()).array(), 4, TIMEOUT)
        readExact(connection, iEp, 4, TIMEOUT)
        
        val sync = readExact(connection, iEp, 1, 5000)
        if (sync == null || sync[0] != 0xC0.toByte()) {
            val valStr = if (sync != null) String.format(Locale.US, "0x%02X", sync[0]) else "timeout"
            connection.close(); return "sync=$valStr"
        }
        onProgress("DA1 רץ! (0xC0 התקבל)")

        connection.close()
        return "הצלחה! DA1 נשלח ורץ."
    }
}
