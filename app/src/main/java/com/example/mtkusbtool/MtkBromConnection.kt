package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

object MtkBromConnection {

    // מבנה לפרטי ה-DA
    data class DaParts(
        val da1: ByteArray,
        val da1Address: Long,
        val da2: ByteArray,
        val da2Address: Long
    )

    // פירוק קובץ ה-DA ל-DA1 ו-DA2 (מבוסס על mtk_daloader)
    private fun parseDa(daBytes: ByteArray): DaParts? {
        // הערה: זהו פישוט. הקוד האמיתי של mtkclient מפרסר כותרות מורכבות יותר.
        // כאן אנו מניחים מבנה סטנדרטי של DA1 (0x6C בייטים) ואחריו DA2.
        // בקבצי DA אמיתיים, הגדלים והכתובות נמצאים בכותרות.
        // לצורך הדגמה, נשתמש בהנחות סבירות.
        
        // בקבצי DA של MediaTek, לרוב ה-DA1 הוא באורך של כ-0x6C בייטים,
        // ואחריו מגיע ה-DA2. הכתובות נמצאות בתוך הכותרת.
        // כאן, לשם פשטות, נניח שה-DA1 הוא 0x6C בייטים.
        // זה כנראה לא נכון לקובץ הספציפי שלנו, אבל זה שלד.
        
        // הערה: המימוש האמיתי דורש קריאת הכותרת המדויקת.
        // בוא נחזיר null כרגע, כי בלי כותרות מדויקות זה לא יעבוד.
        return null 
    }

    // חישוב hash של DA2 ומציאת מיקומו ב-DA1
    private fun computeHashPos(da1: ByteArray, da2: ByteArray): Triple<Int, Int, Int>? {
        val hashlen = da2.size
        val md5 = MessageDigest.getInstance("MD5").digest(da2)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(da2)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(da2)

        // חיפוש ה-hash בתוך DA1
        var idx = indexOf(da1, md5)
        var hashmode = 0
        if (idx == -1) {
            idx = indexOf(da1, sha1)
            hashmode = 1
        }
        if (idx == -1) {
            idx = indexOf(da1, sha256)
            hashmode = 2
        }
        if (idx == -1) return null

        return Triple(idx, hashmode, hashlen)
    }

    // פונקציית עזר לחיפוש מערך בתוך מערך
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // שליחת פקודת boot_to (0xD5) עם כתובת ונתונים
    private fun bootTo(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long, data: ByteArray): Boolean {
        // 0xD5 = JUMP_DA / BOOT_TO
        // שלח את הפקודה
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) return false
        // שלח את הכתובת (Little Endian, 4 בייטים)
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        // שלח את הגודל
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        // שלח את הנתונים
        if (connection.bulkTransfer(epOut, data, data.size, 2000) < 0) return false
        // קריאת ACK
        val resp = ByteArray(1)
        connection.bulkTransfer(epIn, resp, 1, 2000)
        return true
    }

    // הרצף המלא של Carbonara
    fun runCarbonara(
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

        if (epIn == null || epOut == null) { connection.close(); return "שגיאה: לא נמצאו נקודות קצה." }

        // 1. פירוק ה-DA (כרגע מחזיר null - צריך לממש כותרות)
        onProgress("מפרסר את קובץ ה-DA...")
        val parts = parseDa(daBytes)
        if (parts == null) {
            connection.close()
            return "שגיאה: פירוק ה-DA נכשל (כותרות לא ממומשות)."
        }

        // 2. חישוב hash
        onProgress("מחשב hash...")
        val hashInfo = computeHashPos(parts.da1, parts.da2)
        if (hashInfo == null) {
            connection.close()
            return "שגיאה: לא נמצא hash מתאים ב-DA1."
        }
        val (hashAddr, hashMode, hashLen) = hashInfo
        onProgress("נמצא hash ב-offset 0x${Integer.toHexString(hashAddr)} (mode=$hashMode)")

        // 3. הכנת ה-hash החדש (ללא patch, סתם ה-hash של ה-DA2 המקורי)
        val newHash = when (hashMode) {
            0 -> MessageDigest.getInstance("MD5").digest(parts.da2.copyOfRange(0, hashLen))
            1 -> MessageDigest.getInstance("SHA-1").digest(parts.da2.copyOfRange(0, hashLen))
            2 -> MessageDigest.getInstance("SHA-256").digest(parts.da2.copyOfRange(0, hashLen))
            else -> null
        }
        if (newHash == null) { connection.close(); return "שגיאה: חישוב hash חדש נכשל." }

        // 4. שליחת הרצף
        onProgress("שולח DA1...")
        // כאן צריך לשלוח את DA1 (פקודת 0xD7 עם DA1)
        // ...
        
        onProgress("שולח DA2...")
        // כאן צריך לשלוח את DA2 (פקודת 0xD7 עם DA2)
        // ...
        
        onProgress("שולח hash לכתובת 0x${java.lang.Long.toHexString(parts.da1Address + hashAddr)}...")
        if (!bootTo(connection, epOut, epIn, parts.da1Address + hashAddr, newHash)) {
            connection.close()
            return "שגיאה: שליחת hash נכשלה."
        }

        onProgress("מריץ DA2 בכתובת 0x${java.lang.Long.toHexString(parts.da2Address)}...")
        if (!bootTo(connection, epOut, epIn, parts.da2Address, parts.da2)) {
            connection.close()
            return "שגיאה: הרצת DA2 נכשלה."
        }

        connection.close()
        return "הצלחה! Carbonara הורצה (ללא patch)."
    }
}
