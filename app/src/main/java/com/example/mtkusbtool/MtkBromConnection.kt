package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    fun sendDaToPreloader(
        usbManager: UsbManager, 
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור נכשלה."
        
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

        if (epIn == null || epOut == null) { connection.close(); return "שגיאה: לא נמצאו נקודות קצה." }

        val resp = ByteArray(1)
        fun readAck(): Byte? = if (connection.bulkTransfer(epIn, resp, 1, 2000) > 0) resp[0] else null

        // 1. לחיצת יד חכמה (מזהה אם זה BROM או Preloader)
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val handshakeCmd = if (isBrom) 0xA0.toByte() else 0xA1.toByte()
        
        onProgress("שולח לחיצת יד (${if (isBrom) "BROM" else "Preloader"})...")
        connection.bulkTransfer(epOut, byteArrayOf(handshakeCmd), 1, 1000)
        
        val hs = readAck()
        if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
            connection.close()
            return "שגיאה בלחיצת יד: ${hs?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין תגובה"}"
        }

        // 2. שליחת ה-DA (הקטן)
        onProgress("מכין לשלוח DA (${daBytes.size} בייט)...")
        
        connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000)
        val ack1 = readAck()
        if (ack1 != 0xD8.toByte()) {
            connection.close()
            return "שגיאה: תגובת 0xD7 לא תקינה (${ack1?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין"})"
        }

        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        connection.bulkTransfer(epOut, sizeBytes, 4, 2000)
        val ack2 = readAck()
        if (ack2 != 0xD9.toByte()) {
            connection.close()
            return "שגיאה: תגובת גודל לא תקינה (${ack2?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין"})"
        }

        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) {
                connection.close()
                return "שגיאה: שליחת נתונים נכשלה ב-$offset."
            }
            val ack = readAck()
            if (ack != 0x5A.toByte() && ack != 0x5F.toByte()) {
                connection.close()
                return "שגיאה: תגובת נתונים לא תקינה (${ack?.let { String.format(Locale.US, "0x%02X", it) } ?: "אין"})"
            }
            offset += length
            if (offset % (chunkSize * 20) == 0) {
                onProgress("DA: ${(offset * 100) / daBytes.size}%")
            }
        }

        onProgress("מריץ DA...")
        connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000)
        readAck()
        
        connection.close()
        return "הצלחה מסחררת! ה-DA נשלח והורץ. המכשיר פרוץ!"
    }
}
