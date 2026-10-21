package com.example.mtkusbtool

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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

    // פונקציה חדשה לניתוח הקובץ - HEX, חיפוש טקסט, בדיקת דחיסה ואנטרופיה
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x100) {
            onProgress("!! DA קטן מדי")
            return null
        }

        // 1. הדפסת 128 הבייטים הראשונים (הכותרת)
        onProgress("--- 128 בייטים ראשונים (Header) ---")
        val sb = StringBuilder()
        for (i in 0 until 128) {
            sb.append(String.format(Locale.US, "%02X", data[i]))
            if ((i + 1) % 16 == 0) sb.append("\n") else sb.append(" ")
        }
        onProgress(sb.toString())

        // 2. חיפוש מחרוזות טקסטואליות
        onProgress("--- חיפוש מחרוזות טקסטואליות ---")
        val strings = listOf("MT6761", "6761", "0x717", "MTK_DA_v6", "MTK_DOWNLOAD_AGENT")
        for (s in strings) {
            val bytes = s.toByteArray()
            val idx = indexOf(data, bytes)
            if (idx != -1) {
                onProgress("נמצאה המחרוזת \"$s\" ב-offset 0x${Integer.toHexString(idx)}")
            } else {
                onProgress("המחרוזת \"$s\" לא נמצאה")
            }
        }

        // 3. בדיקת חתימת דחיסה (ZIP / GZIP)
        onProgress("--- בדיקת דחיסה ---")
        if (data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()) { // "PK"
            onProgress("!! הקובץ נראה כמו ZIP (דחוס)")
        } else if (data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
            onProgress("!! הקובץ נראה כמו GZIP (דחוס)")
        } else {
            onProgress("הקובץ לא נראה דחוס (ZIP/GZIP)")
        }

        // 4. בדיקת אנטרופיה (הצפנה)
        onProgress("--- בדיקת אנטרופיה (הצפנה) ---")
        val counts = IntArray(256)
        for (b in data) {
            counts[b.toInt() and 0xFF]++
        }
        var entropy = 0.0
        val len = data.size.toDouble()
        for (c in counts) {
            if (c > 0) {
                val p = c / len
                entropy -= p * (Math.log(p) / Math.log(2.0))
            }
        }
        onProgress("אנטרופיה: ${String.format(Locale.US, "%.2f", entropy)} (0-8. אם >7.5, ייתכן שהקובץ מוצפן או דחוס)")

        return null // עצירה כדי שנראה את הניתוח
    }

    // פונקציה ראשית לבדיקה
    fun runCarbonara(
        usbManager: UsbManager,
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        val connection = usbManager.openDevice(device) ?: return "שגיאה: פתיחת חיבור נכשלה."

        onProgress("=== שלב 1: ניתוח ה-DA ===")
        val parts = parseDa(daBytes, onProgress)
        if (parts == null) {
            connection.close()
            return "שגיאה: ניתוח ה-DA הסתיים (עצרנו כדי לראות את הנתונים)."
        }

        connection.close()
        return "הצלחה."
    }
}
