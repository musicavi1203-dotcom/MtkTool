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

    fun sendDaDirect(
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

        if (epIn == null || epOut == null) {
            connection.close()
            return "שגיאה: לא נמצאו endpoints."
        }

        fun usbwrite(data: ByteArray): Boolean {
            return connection.bulkTransfer(epOut, data, data.size, 2000) >= 0
        }

        fun usbread(len: Int, timeout: Int = 2000): ByteArray {
            val buf = ByteArray(len)
            connection.bulkTransfer(epIn, buf, len, timeout)
            return buf
        }

        // 1. לחיצת יד - חכמה לפי PID
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val hsCmd = if (isBrom) 0xA0.toByte() else 0xA1.toByte()
        onProgress("PID: 0x${String.format(Locale.US, "%04X", device.productId)} -> לחיצת יד 0x${String.format(Locale.US, "%02X", hsCmd)}")
        
        if (!usbwrite(byteArrayOf(hsCmd))) {
            connection.close()
            return "שגיאה: שליחת לחיצת יד נכשלה."
        }

        val hsAck = usbread(1)
        if (hsAck.isEmpty() || (hsAck[0] != 0x5F.toByte() && hsAck[0] != 0xC0.toByte())) {
            val hex = if (hsAck.isNotEmpty()) String.format(Locale.US, "0x%02X", hsAck[0]) else "אין תגובה"
            connection.close()
            return "שגיאה בלחיצת יד: $hex"
        }
        onProgress("לחיצת יד הצליחה!")

        // 2. שליחת פקודת 0xD7
        onProgress("שולח 0xD7...")
        if (!usbwrite(byteArrayOf(0xD7.toByte()))) {
            connection.close()
            return "שגיאה: 0xD7 נכשל."
        }

        val ack1 = usbread(1)
        if (ack1.isEmpty() || ack1[0] != 0xD8.toByte()) {
            val hex = if (ack1.isNotEmpty()) String.format(Locale.US, "0x%02X", ack1[0]) else "אין"
            connection.close()
            return "שגיאה: תגובה ל-0xD7: $hex"
        }
        onProgress("0xD7 אושר! (0xD8)")

        // 3. שליחת גודל (Little Endian)
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        onProgress("שולח גודל: ${daBytes.size} בייט...")
        if (!usbwrite(sizeBytes)) {
            connection.close()
            return "שגיאה: שליחת גודל נכשלה."
        }

        val ack2 = usbread(1)
        if (ack2.isEmpty() || ack2[0] != 0xD9.toByte()) {
            val hex = if (ack2.isNotEmpty()) String.format(Locale.US, "0x%02X", ack2[0]) else "אין"
            connection.close()
            return "שגיאה: תגובה לגודל: $hex"
        }
        onProgress("גודל אושר! (0xD9)")

        // 4. שליחת הנתונים
        onProgress("מעביר נתונים...")
        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)

            if (!usbwrite(chunk)) {
                connection.close()
                return "שגיאה: שליחת נתונים נכשלה ב-offset $offset."
            }

            val ack = usbread(1)
            if (ack.isEmpty() || (ack[0] != 0x5A.toByte() && ack[0] != 0x5F.toByte())) {
                val hex = if (ack.isNotEmpty()) String.format(Locale.US, "0x%02X", ack[0]) else "אין"
                connection.close()
                return "שגיאה: תגובה לא תקינה לנתונים ב-offset $offset ($hex)"
            }

            offset += length
            if (offset % (chunkSize * 100) == 0) {
                onProgress("התקדמות: ${(offset * 100) / daBytes.size}%")
            }
        }

        // 5. הרצת ה-DA
        onProgress("שולח 0xD5 (הרצה)...")
        if (!usbwrite(byteArrayOf(0xD5.toByte()))) {
            connection.close()
            return "שגיאה: 0xD5 נכשל."
        }
        usbread(2)

        connection.close()
        return "הצלחה!!! ה-DA נשלח והורץ. המכשיר פרוץ!"
    }
}
