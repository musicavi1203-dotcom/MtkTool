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

    // קריסת Preloader
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

    // echo
    private fun echo(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, data: ByteArray): Boolean {
        if (connection.bulkTransfer(epOut, data, data.size, 2000) < 0) return false
        val response = ByteArray(data.size)
        if (connection.bulkTransfer(epIn, response, data.size, 2000) <= 0) return false
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

    // הרצף: שני חיבורים במקביל
    fun runKamakiriWithTwoConnections(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        // 1. קריסה
        onProgress("קורס Preloader...")
        crashPreloader(usbManager, device)

        onProgress("ממתין ל-BROM...")
        var foundBrom: UsbDevice? = null
        for (i in 0 until 100) {
            foundBrom = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D && it.productId == 0x0003 }
            if (foundBrom != null) break
            Thread.sleep(100)
        }
        if (foundBrom == null) return "שגיאה: לא הצלחנו להגיע ל-BROM."
        val bd: UsbDevice = foundBrom

        onProgress("מצאנו BROM! ממתין 1.5 שניות...")
        Thread.sleep(1500)

        // 2. פתיחת חיבור A
        val connectionA = usbManager.openDevice(bd) ?: return "שגיאה: פתיחת חיבור A נכשלה."
        var epInA: UsbEndpoint? = null
        var epOutA: UsbEndpoint? = null
        for (i in 0 until bd.interfaceCount) {
            val usbInterface = bd.getInterface(i)
            if (connectionA.claimInterface(usbInterface, true)) {
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epInA = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) epOutA = ep
                    }
                }
                if (epInA != null && epOutA != null) break
            }
        }
        if (epInA == null || epOutA == null) { connectionA.close(); return "שגיאה: לא נמצאו endpoints בחיבור A." }
        val inA = epInA
        val outA = epOutA

        // 3. לחיצת יד על חיבור A
        onProgress("לחיצת יד על חיבור A...")
        connectionA.bulkTransfer(outA, byteArrayOf(0xA0.toByte()), 1, 1000)
        val hsAck = ByteArray(1)
        val hsResult = connectionA.bulkTransfer(inA, hsAck, 1, 1000)
        if (hsResult <= 0 || (hsAck[0] != 0x5F.toByte() && hsAck[0] != 0xC0.toByte())) {
            val hex = if (hsResult > 0) String.format(Locale.US, "0x%02X", hsAck[0]) else "אין"
            connectionA.close()
            return "שגיאה בלחיצת יד A: $hex"
        }

        // 4. כתיבת ה-payload על חיבור A
        onProgress("כותב Payload לחיבור A (${payloadBytes.size} בייט)...")
        val payloadAddr = 0x100A00
        var offset = 0
        while (offset < payloadBytes.size) {
            val chunk = payloadBytes.copyOfRange(offset, minOf(offset + 4, payloadBytes.size))
            val value = ByteBuffer.wrap(chunk + ByteArray(4 - chunk.size)).order(ByteOrder.BIG_ENDIAN).int
            if (!write32(connectionA, outA, inA, payloadAddr + offset, intArrayOf(value))) {
                connectionA.close()
                return "שגיאה בכתיבת Payload ב-offset $offset"
            }
            offset += 4
            if (offset % 100 == 0) onProgress("Payload: ${(offset * 100) / payloadBytes.size}%")
        }

        // 5. פתיחת חיבור B (מקביל לחיבור A, לא סוגרים את A!)
        onProgress("\nפותח חיבור B (מקביל)...")
        val connectionB = usbManager.openDevice(bd)
        if (connectionB == null) {
            onProgress("!! פתיחת חיבור B נכשלה. ממשיך עם חיבור A בלבד...")
        } else {
            onProgress("חיבור B נפתח בהצלחה!")
            
            // 6. שליחת ctrl_transfer על חיבור B
            onProgress("שולח ctrl_transfer על חיבור B...")
            try {
                connectionB.controlTransfer(0xA1, 0, 0, 0x25, null, 0, 1000)
                onProgress("ctrl_transfer בוצע על B!")
            } catch (e: Exception) {
                onProgress("ctrl_transfer על B נכשל: ${e.message}")
            }
            
            // לא סוגרים את connectionB! אולי הוא נחוץ להמשך
        }

        // 7. ניסיון לשלוח את ה-DA על חיבור A (שעדיין פתוח!)
        onProgress("\nמנסה לשלוח DA על חיבור A המקורי...")
        
        // פקודת 0xD7
        if (connectionA.bulkTransfer(outA, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) {
            connectionA.close()
            return "שגיאה: שליחת 0xD7 על A נכשלה."
        }
        
        val ack1 = ByteArray(1)
        val ack1Result = connectionA.bulkTransfer(inA, ack1, 1, 2000)
        if (ack1Result <= 0 || ack1[0] != 0xD8.toByte()) {
            val hex = if (ack1Result > 0) String.format(Locale.US, "0x%02X", ack1[0]) else "אין"
            connectionA.close()
            return "שגיאה: תגובה ל-0xD7: $hex"
        }
        onProgress("0xD7 אושר! (0xD8)")

        // שליחת גודל
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connectionA.bulkTransfer(outA, sizeBytes, 4, 2000) < 0) {
            connectionA.close()
            return "שגיאה: שליחת גודל DA נכשלה."
        }
        
        val ack2 = ByteArray(1)
        val ack2Result = connectionA.bulkTransfer(inA, ack2, 1, 2000)
        if (ack2Result <= 0 || ack2[0] != 0xD9.toByte()) {
            val hex = if (ack2Result > 0) String.format(Locale.US, "0x%02X", ack2[0]) else "אין"
            connectionA.close()
            return "שגיאה: תגובה לגודל: $hex"
        }
        onProgress("גודל אושר! (0xD9) - מתחיל להעביר נתונים...")

        // שליחת נתוני ה-DA
        var daOffset = 0
        val chunkSize = 512
        while (daOffset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - daOffset)
            val chunk = daBytes.copyOfRange(daOffset, daOffset + length)
            if (connectionA.bulkTransfer(outA, chunk, length, 2000) < 0) {
                connectionA.close()
                return "שגיאה: שליחת נתונים ב-$daOffset."
            }
            val ack = ByteArray(1)
            val ackResult = connectionA.bulkTransfer(inA, ack, 1, 2000)
            if (ackResult <= 0 || (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte())) {
                val hex = if (ackResult > 0) String.format(Locale.US, "0x%02X", ack[0]) else "אין"
                connectionA.close()
                return "שגיאה: תגובה לנתונים ב-$daOffset: $hex"
            }
            daOffset += length
            if (daOffset % (chunkSize * 40) == 0) onProgress("DA: ${(daOffset * 100) / daBytes.size}%")
        }

        // הרצת ה-DA
        onProgress("מריץ DA...")
        if (connectionA.bulkTransfer(outA, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connectionA.close()
            return "שגיאה: שליחת 0xD5 נכשלה."
        }
        val finalAck = ByteArray(2)
        connectionA.bulkTransfer(inA, finalAck, 2, 2000)

        connectionA.close()
        return "הצלחה מסחררת! ה-DA נשלח והורץ על חיבור A המקורי!"
    }
}
