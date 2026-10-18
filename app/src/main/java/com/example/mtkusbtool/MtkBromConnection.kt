package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {
    
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
        var claimedInterface = false

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
                if (epIn != null && epOut != null) {
                    claimedInterface = true
                    break 
                }
            }
        }

        if (!claimedInterface || epIn == null || epOut == null) {
            connection.close()
            return "שגיאה: לא נמצאו נקודות קצה."
        }

        val resp = ByteArray(1)
        
        fun sendCmd(cmd: Byte): Boolean = connection.bulkTransfer(epOut, byteArrayOf(cmd), 1, 2000) >= 0
        fun readAck(): Byte? {
            return if (connection.bulkTransfer(epIn, resp, 1, 2000) > 0) resp[0] else null
        }

        // פונקציה פנימית לשליחת קובץ בינארי (משמשת גם ל-Payload וגם ל-DA)
        fun sendBinaryFile(fileName: String, fileBytes: ByteArray): String? {
            onProgress("מכין לשלוח $fileName...")
            if (!sendCmd(0xD7.toByte())) return "שגיאה: פקודת 0xD7 נכשלה."
            
            val ack1 = readAck()
            if (ack1 != 0xD8.toByte()) return "שגיאה: תגובה לא תקינה ל-0xD7 ($ack1)."

            val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileBytes.size).array()
            if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return "שגיאה: שליחת גודל הקובץ נכשלה."
            
            val ack2 = readAck()
            if (ack2 != 0xD9.toByte()) return "שגיאה: תגובה לא תקינה לגודל הקובץ ($ack2)."

            var offset = 0
            val chunkSize = 512
            while (offset < fileBytes.size) {
                val length = minOf(chunkSize, fileBytes.size - offset)
                val chunk = fileBytes.copyOfRange(offset, offset + length)
                
                if (connection.bulkTransfer(epOut, chunk, length, 2000) < 0) return "שגיאה: שליחת נתונים נכשלה ב-$offset."
                
                val ack3 = readAck()
                if (ack3 != 0x5A.toByte() && ack3 != 0x5F.toByte()) return "שגיאה: תגובה לא תקינה לנתונים ($ack3)."
                
                offset += length
                onProgress("שולח $fileName... ${(offset * 100) / fileBytes.size}%")
            }
            return null // Success
        }

        // 1. לחיצת יד
        onProgress("מבצע לחיצת יד...")
        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
        if (!sendCmd(if (isBrom) 0xA0.toByte() else 0xA1.toByte())) {
            connection.close(); return "שגיאה: שליחת לחיצת יד נכשלה."
        }
        val hsAck = readAck()
        if (hsAck != 0x5F.toByte() && hsAck != 0xC0.toByte()) {
            connection.close(); return "שגיאה בלחיצת יד: $hsAck"
        }

        // 2. שליחת ה-Payload (הפריצה)
        val payloadError = sendBinaryFile("Payload", payloadBytes)
        if (payloadError != null) {
            connection.close(); return payloadError
        }

        // 3. הרצת ה-Payload
        onProgress("מריץ Payload (עוקף אבטחה)...")
        if (!sendCmd(0xD5.toByte())) {
            connection.close(); return "שגיאה: שליחת פקודת הרצה ל-Payload נכשלה."
        }
        readAck() // לא חייבים לבדוק, לרוב מתנתק פה

        // --- אם הגענו לפה, האבטחה אמורה להיות עקופה ---
        
        // 4. שליחת ה-DA האמיתי
        val daError = sendBinaryFile("DA", daBytes)
        if (daError != null) {
            connection.close(); return daError
        }

        // 5. הרצת ה-DA
        onProgress("מריץ את ה-DA...")
        if (!sendCmd(0xD5.toByte())) {
            connection.close(); return "שגיאה: שליחת פקודת הרצה ל-DA נכשלה."
        }
        readAck()
        
        connection.close()
        return "הצלחה מסחררת! ה-Payload עבד, ה-DA נשלח והורץ. המכשיר פרוץ!"
    }
}
