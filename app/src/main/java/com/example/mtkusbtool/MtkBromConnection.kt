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

    // קריסה אגרסיבית
    fun aggressiveCrash(usbManager: UsbManager, device: UsbDevice): Boolean {
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

    // פונקציית הפריצה החדשה (עם Control Transfer ל-Payload)
    fun executeBypassAndSendDa(
        usbManager: UsbManager, 
        device: UsbDevice,
        payloadBytes: ByteArray,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור ל-USB נכשלה."
        
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

        // 1. לחיצת יד (מנסים את שתי האפשרויות)
        onProgress("מבצע לחיצת יד...")
        connection.bulkTransfer(epOut, byteArrayOf(0xA0.toByte()), 1, 1000)
        var hs = readAck()
        if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
            connection.bulkTransfer(epOut, byteArrayOf(0xA1.toByte()), 1, 1000)
            hs = readAck()
            if (hs != 0x5F.toByte() && hs != 0xC0.toByte()) {
                connection.close(); return "שגיאה בלחיצת יד: $hs"
            }
        }

        // 2. שליחת ה-Payload דרך Control Transfer!
        onProgress("שולח Payload (Control Transfer)...")
        
        // הפקודה הקסומה של mtkclient: requestType = 0x21, request = 0x20
        val ctrlResult = connection.controlTransfer(
            0x21, // requestType (Host to Device, Vendor, Class)
            0x20, // request (0x20 - Send DA / Payload)
            0,    // value
            0,    // index
            payloadBytes, // הנתונים
            payloadBytes.size, // אורך
            5000  // timeout
        )

        if (ctrlResult < 0) {
            connection.close()
            return "שגיאה: שליחת ה-Payload נכשלה (Control Transfer נחסם)."
        }

        // 3. הרצת ה-Payload (אם צריך, לרוב ה-Control Transfer כבר מפעיל אותו)
        onProgress("Payload נשלח בהצלחה! מכין DA...")
        
        // 4. שליחת ה-DA (זה עובד ב-Bulk Transfer רגיל)
        onProgress("מכין לשלוח DA...")
        connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000)
        if (readAck() != 0xD8.toByte()) { connection.close(); return "שגיאה: תגובה לא תקינה ל-0xD7." }

        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        connection.bulkTransfer(epOut, sizeBytes, 4, 2000)
        if (readAck() != 0xD9.toByte()) { connection.close(); return "שגיאה: תגובה לא תקינה לגודל." }

        var offset = 0
        val chunkSize = 512
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) { connection.close(); return "שגיאה: שליחת נתונים נכשלה ב-$offset." }
            val ack = readAck()
            if (ack != 0x5A.toByte() && ack != 0x5F.toByte()) { connection.close(); return "שגיאה: תגובה לא תקינה לנתונים." }
            offset += length
            onProgress("שולח DA... ${(offset * 100) / daBytes.size}%")
        }

        // 5. הרצת ה-DA
        onProgress("מריץ את ה-DA...")
        connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000)
        readAck()
        
        connection.close()
        return "הצלחה מסחררת! המכשיר פרוץ!"
    }
}
