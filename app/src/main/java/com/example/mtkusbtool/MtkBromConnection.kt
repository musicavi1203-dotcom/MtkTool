package com.example.mtkusbtool

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    // פונקציה חדשה לפרסור חכם - סורקת אחרי 0xdada
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) {
            onProgress("!! DA קטן מדי (${data.size} בייט)")
            return null
        }

        // בדיקת חתימת MTK_DOWNLOAD_AGENT
        val magicString = "MTK_DOWNLOAD_AGENT".toByteArray()
        if (indexOf(data, magicString) == -1) {
            onProgress("!! לא נמצאה מחרוזת MTK_DOWNLOAD_AGENT")
            return null
        }
        onProgress("נמצאה מחרוזת MTK_DOWNLOAD_AGENT")

        // סריקה אחרי כל מופע של 0xdada
        val magicBytes = byteArrayOf(0xda.toByte(), 0xda.toByte())
        var searchOffset = 0
        var foundCount = 0

        while (searchOffset < data.size - 20) {
            val idx = indexOf(data.copyOfRange(searchOffset, data.size), magicBytes)
            if (idx == -1) break
            val magicOffset = searchOffset + idx

            // קריאת 20 הבייטים של הרשומה
            val buf = ByteBuffer.wrap(data, magicOffset, 20).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.short.toInt() and 0xFFFF
            val hwCode = buf.short.toInt() and 0xFFFF
            val hwSubCode = buf.short.toInt() and 0xFFFF
            val hwVersion = buf.short.toInt() and 0xFFFF
            val swVersion = buf.short.toInt() and 0xFFFF
            val reserved1 = buf.short.toInt() and 0xFFFF
            val pagesize = buf.short.toInt() and 0xFFFF
            val reserved3 = buf.short.toInt() and 0xFFFF
            val entryRegionIndex = buf.short.toInt() and 0xFFFF
            val entryRegionCount = buf.short.toInt() and 0xFFFF

            if (hwCode == 0x717) {
                onProgress("מצאתי את MT6761 (hwCode 0x717) ב-offset 0x${Integer.toHexString(magicOffset)}!")
                val regions = mutableListOf<DaRegion>()
                var regionOffset = magicOffset + 20
                for (i in 0 until entryRegionCount) {
                    if (regionOffset + 20 > data.size) break
                    val rb = ByteBuffer.wrap(data, regionOffset, 20).order(ByteOrder.LITTLE_ENDIAN)
                    val mBuf = rb.int
                    val mLen = rb.int
                    val mStartAddr = rb.int.toLong() and 0xFFFFFFFFL
                    val mStartOffset = rb.int
                    val mSigLen = rb.int
                    regions.add(DaRegion(mBuf, mLen, mStartAddr, mStartOffset, mSigLen))
                    onProgress("  Region $i: len=0x${Integer.toHexString(mLen)}, addr=0x${java.lang.Long.toHexString(mStartAddr)}, offset=0x${Integer.toHexString(mStartOffset)}")
                    regionOffset += 20
                }
                if (regions.size >= 3) {
                    val da1 = regions[1]
                    val da2 = regions[2]
                    onProgress("DA1: offset=0x${Integer.toHexString(da1.offset)}, len=0x${Integer.toHexString(da1.len)}, addr=0x${java.lang.Long.toHexString(da1.addr)}")
                    onProgress("DA2: offset=0x${Integer.toHexString(da2.offset)}, len=0x${Integer.toHexString(da2.len)}, addr=0x${java.lang.Long.toHexString(da2.addr)}")
                    return DaParts(
                        data.copyOfRange(da1.offset, da1.offset + da1.len), da1.offset, da1.len, da1.addr,
                        data.copyOfRange(da2.offset, da2.offset + da2.len), da2.offset, da2.len, da2.addr
                    )
                }
            }

            searchOffset = magicOffset + 2 // ממשיכים לחפש הלאה
        }

        onProgress("!! לא נמצאה רשומה לשבב 0x717 (MT6761) בקובץ")
        return null
    }

    // פונקציה ראשית לבדיקה (כרגע רק מפרסרת ומדפיסה)
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
            return "שגיאה: פירוק ה-DA נכשל."
        }

        onProgress("הפירוק הצליח! DA1: ${parts.da1.size} בייט, DA2: ${parts.da2.size} בייט.")
        connection.close()
        return "הצלחה בפירוק."
    }
}
