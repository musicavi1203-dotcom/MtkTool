package com.example.mtkusbtool

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.util.Locale

object MtkBromConnection {

    // מבנה הרשומה של כל אזור (EntryRegion) בקובץ ה-DA
    data class DaRegion(
        val buf: Int,
        val len: Int,
        val addr: Long,
        val offset: Int,
        val sigLen: Int
    )

    data class DaParts(
        val da1: ByteArray,
        val da1Offset: Int,
        val da1Size: Int,
        val da1Address: Long,
        val da2: ByteArray,
        val da2Offset: Int,
        val da2Size: Int,
        val da2Address: Long
    )

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

    // פונקציה חדשה לפרסור - מחפשת את ה-hwcode ומדפיסה HEX
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) return null

        // חיפוש הרצף של ה-hwcode 0x717 (Little Endian: 17 07 00 00)
        val targetHwCodeBytes = byteArrayOf(0x17, 0x07, 0x00, 0x00)
        var searchOffset = 0
        var foundCount = 0

        while (searchOffset < data.size - 4) {
            val idx = indexOf(data.copyOfRange(searchOffset, data.size), targetHwCodeBytes)
            if (idx == -1) break
            val hwCodeOffset = searchOffset + idx
            foundCount++

            onProgress("נמצא hwcode 0x717 ב-offset 0x${Integer.toHexString(hwCodeOffset)}")
            
            // הדפסת 64 בייטים לפני ו-64 בייטים אחרי
            val start = maxOf(0, hwCodeOffset - 64)
            val end = minOf(data.size, hwCodeOffset + 64)
            val sb = StringBuilder()
            for (i in start until end) {
                if (i == hwCodeOffset) sb.append(" >> ")
                sb.append(String.format(Locale.US, "%02X", data[i]))
                if ((i - start + 1) % 16 == 0 && i > start) sb.append("\n")
                else sb.append(" ")
            }
            onProgress("=== HEX סביב hwcode 0x717 (offset 0x${Integer.toHexString(hwCodeOffset)}) ===\n$sb")

            searchOffset = hwCodeOffset + 4
        }

        if (foundCount == 0) {
            onProgress("!! לא נמצא הרצף 17 07 00 00 בקובץ")
        } else {
            onProgress("נמצאו $foundCount מופעים של hwcode 0x717. עצרנו כדי לראות את ה-HEX.")
        }
        
        return null // עצירה כדי שנראה את ה-HEX
    }

    // פונקציה ראשית לבדיקה
    fun runCarbonara(
        usbManager: UsbManager,
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."

        onProgress("=== שלב 1: פירוק ה-DA ===")
        val parts = parseDa(daBytes, onProgress)
        if (parts == null) {
            connection.close()
            return "שגיאה: פירוק ה-DA נכשל (עצרנו כדי לראות את ה-HEX)."
        }

        onProgress("הפירוק הצליח! DA1: ${parts.da1.size} בייט, DA2: ${parts.da2.size} בייט.")
        connection.close()
        return "הצלחה בפירוק."
    }
}
