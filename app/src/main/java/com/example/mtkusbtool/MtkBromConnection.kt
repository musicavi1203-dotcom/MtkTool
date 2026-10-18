package com.example.mtkusbtool

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {
    
    fun sendDaFile(
        context: Context, 
        usbManager: UsbManager, 
        device: UsbDevice,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת החיבור ל-USB נכשלה."
        
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        var claimedInterface = false

        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (connection.claimInterface(usbInterface, true)) {
                var foundIn: UsbEndpoint? = null
                var foundOut: UsbEndpoint? = null
                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
                    }
                }
                if (foundIn != null && foundOut != null) {
                    epIn = foundIn
                    epOut = foundOut
                    claimedInterface = true
                    break 
                }
            }
        }

        if (!claimedInterface || epIn == null || epOut == null) {
            connection.close()
            return "שגיאה: לא נמצאו נקודות קצה."
        }

        // 1. קריאת קובץ ה-DA מה-Assets
        onProgress("קורא קובץ DA...")
        val daBytes: ByteArray
        try {
            val inputStream: InputStream = context.assets.open("MTK_AllInOne_DA.bin")
            daBytes = inputStream.readBytes()
            inputStream.close()
        } catch (e: Exception) {
            connection.close()
            return "שגיאה: לא ניתן לקרוא את הקובץ MTK_AllInOne_DA.bin"
        }

        // 2. לחיצת יד
        onProgress("מבצע לחיצת יד...")
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        val handshakeCmd = byteArrayOf(if (isBrom) 0xA0.toByte() else 0xA1.toByte())
        val resp = ByteArray(1)

        if (connection.bulkTransfer(epOut, handshakeCmd, 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת לחיצת יד נכשלה."
        }
        if (connection.bulkTransfer(epIn, resp, 1, 2000) <= 0 || (resp[0] != 0x5F.toByte() && resp[0] != 0xC0.toByte())) {
            connection.close(); return "שגיאה: לחיצת יד נכשלה."
        }

        // 3. שליחת פקודת Send DA (0xD7)
        onProgress("מכין לשליחת DA...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD7.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת פקודת 0xD7 נכשלה."
        }
        if (connection.bulkTransfer(epIn, resp, 1, 2000) <= 0) {
            connection.close(); return "שגיאה: אין תגובה לפקודת 0xD7."
        }

        // 4. שליחת גודל הקובץ
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(daBytes.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת גודל הקובץ נכשלה."
        }
        if (connection.bulkTransfer(epIn, resp, 1, 2000) <= 0) {
            connection.close(); return "שגיאה: אין תגובה לגודל הקובץ."
        }

        // 5. שליחת הנתונים בצ'אנקים
        var offset = 0
        val chunkSize = 4096
        while (offset < daBytes.size) {
            val length = minOf(chunkSize, daBytes.size - offset)
            val chunk = daBytes.copyOfRange(offset, offset + length)
            
            if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) {
                connection.close(); return "שגיאה: שליחת נתונים נכשלה במיקום $offset."
            }
            if (connection.bulkTransfer(epIn, resp, 1, 2000) <= 0) {
                connection.close(); return "שגיאה: אין ACK במיקום $offset."
            }
            
            offset += length
            val progress = (offset * 100) / daBytes.size
            onProgress("שולח DA... $progress%")
        }

        // 6. פקודת הרצה (Jump to DA - 0xD5)
        onProgress("מריץ את ה-DA...")
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) {
            connection.close(); return "שגיאה: שליחת פקודת הרצה נכשלה."
        }
        val jumpResp = connection.bulkTransfer(epIn, resp, 1, 2000)
        
        connection.close()

        return if (jumpResp > 0) {
            "הצלחה! ה-DA נשלח והורץ (תגובה: ${String.format(Locale.US, "0x%02X", resp[0])}). המכשיר אמור להישאר מחובר."
        } else {
            "ה-DA נשלח, אבל לא התקבלה תגובה לפקודת ההרצה."
        }
    }
}
