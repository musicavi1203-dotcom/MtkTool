package com.example.mtkusbtool

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

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

    // ניתוח חכם - מפרש את מה שהוא מוצא
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x100) {
            onProgress("!! DA קטן מדי")
            return null
        }

        onProgress("גודל הקובץ: ${data.size} בייט (${String.format(Locale.US, "%.2f", data.size / 1024.0 / 1024.0)} MB)")

        // ניסיון 1: hwcode כ-4 בייטים Little Endian (17 07 00 00)
        onProgress("\n--- ניסיון 1: 4 בייטים LE (17 07 00 00) ---")
        val le4Bytes = byteArrayOf(0x17, 0x07, 0x00, 0x00)
        val le4Idx = indexOf(data, le4Bytes)
        if (le4Idx != -1) {
            onProgress("נמצא ב-offset 0x${Integer.toHexString(le4Idx)}")
            return tryParseRegion(data, le4Idx, onProgress)
        } else {
            onProgress("לא נמצא")
        }

        // ניסיון 2: hwcode כ-4 בייטים Big Endian (00 00 07 17)
        onProgress("\n--- ניסיון 2: 4 בייטים BE (00 00 07 17) ---")
        val be4Bytes = byteArrayOf(0x00, 0x00, 0x07, 0x17)
        val be4Idx = indexOf(data, be4Bytes)
        if (be4Idx != -1) {
            onProgress("נמצא ב-offset 0x${Integer.toHexString(be4Idx)}")
            return tryParseRegion(data, be4Idx - 4, onProgress) // הרשומה מתחילה 4 בייטים לפני
        } else {
            onProgress("לא נמצא")
        }

        // ניסיון 3: hwcode כ-2 בייטים LE (17 07)
        onProgress("\n--- ניסיון 3: 2 בייטים LE (17 07) ---")
        val le2Bytes = byteArrayOf(0x17, 0x07)
        var searchIdx = 0
        var foundCount = 0
        while (searchIdx < data.size - 2 && foundCount < 3) {
            val idx = indexOf(data.copyOfRange(searchIdx, data.size), le2Bytes)
            if (idx == -1) break
            val absIdx = searchIdx + idx
            onProgress("נמצא ב-offset 0x${Integer.toHexString(absIdx)}")
            foundCount++
            
            // בדיקה האם זה נראה כמו רשומה (20 בייטים אחרי)
            if (absIdx + 22 <= data.size) {
                val buf = ByteBuffer.wrap(data, absIdx + 2, 20).order(ByteOrder.LITTLE_ENDIAN)
                // קריאה של 5 שדות (4 בייטים כל אחד)
                val field1 = buf.int
                val field2 = buf.int
                val field3 = buf.int
                val field4 = buf.int
                val field5 = buf.int
                
                if (field1 in 100..10000000 && field2 in 0x100000..0xFFFFFFF) {
                    onProgress("  נראה כמו רשומת region: len=0x${Integer.toHexString(field1)}, addr=0x${Integer.toHexString(field2)}, offset=0x${Integer.toHexString(field3)}")
                }
            }
            
            searchIdx = absIdx + 2
        }
        if (foundCount == 0) onProgress("לא נמצא")

        // ניסיון 4: חיפוש מחרוזת "6761" (hwcode טקסטואלי)
        onProgress("\n--- ניסיון 4: מחרוזת '6761' ---")
        val strBytes = "6761".toByteArray()
        val strIdx = indexOf(data, strBytes)
        if (strIdx != -1) {
            onProgress("נמצאה המחרוזת '6761' ב-offset 0x${Integer.toHexString(strIdx)}")
        } else {
            onProgress("לא נמצאה")
        }

        // סיכום
        onProgress("\n=== סיכום ===")
        onProgress("לא הצלחנו למצוא את hwcode 0x717 באף אחת מהשיטות.")
        onProgress("המשמעות: הקובץ הזה לא מכיל את המעבד MT6761, או שהוא בפורמט מוצפן/דחוס.")
        onProgress("המלצה: נסה קובץ DA אחר (כמו DA_SWSEC_CRYPTO20.bin).")

        return null
    }

    // ניסיון לפרסר region אחרי מיקום ה-hwcode
    private fun tryParseRegion(data: ByteArray, hwcodeOffset: Int, onProgress: (String) -> Unit): DaParts? {
        // הרשומה של ה-MediaTek כוללת 20 בייטים: magic(2) + hwcode(2) + subcode(2) + version(2) + ... + region_count(2)
        // אחריה יש region_count רשומות של 20 בייטים כל אחת.
        
        if (hwcodeOffset + 20 > data.size) {
            onProgress("!! הרשומה קצרה מדי")
            return null
        }

        val buf = ByteBuffer.wrap(data, hwcodeOffset, 20).order(ByteOrder.LITTLE_ENDIAN)
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

        onProgress("רשומה שפוענחה: magic=0x${Integer.toHexString(magic)}, hwCode=0x${Integer.toHexString(hwCode)}, regions=$entryRegionCount")

        if (entryRegionCount !in 1..10) {
            onProgress("!! מספר regions לא סביר ($entryRegionCount). כנראה לא רשומה אמיתית.")
            return null
        }

        onProgress("מפרסר $entryRegionCount regions...")
        val regions = mutableListOf<DaRegion>()
        var regionOffset = hwcodeOffset + 20
        for (i in 0 until entryRegionCount) {
            if (regionOffset + 20 > data.size) break
            val rb = ByteBuffer.wrap(data, regionOffset, 20).order(ByteOrder.LITTLE_ENDIAN)
            val mBuf = rb.int
            val mLen = rb.int
            val mStartAddr = rb.int.toLong() and 0xFFFFFFFFL
            val mStartOffset = rb.int
            val mSigLen = rb.int
            regions.add(DaRegion(mBuf, mLen, mStartAddr, mStartOffset, mSigLen))
            onProgress("  Region $i: len=0x${Integer.toHexString(mLen)} (${mLen} בייט), addr=0x${java.lang.Long.toHexString(mStartAddr)}, offset=0x${Integer.toHexString(mStartOffset)}")
            regionOffset += 20
        }

        if (regions.size >= 3) {
            val da1 = regions[1]
            val da2 = regions[2]
            onProgress("\n=== נמצאו DA1 ו-DA2! ===")
            onProgress("DA1: offset=0x${Integer.toHexString(da1.offset)}, len=${da1.len} בייט, addr=0x${java.lang.Long.toHexString(da1.addr)}")
            onProgress("DA2: offset=0x${Integer.toHexString(da2.offset)}, len=${da2.len} בייט, addr=0x${java.lang.Long.toHexString(da2.addr)}")
            
            if (da1.offset + da1.len <= data.size && da2.offset + da2.len <= data.size) {
                return DaParts(
                    data.copyOfRange(da1.offset, da1.offset + da1.len), da1.offset, da1.len, da1.addr,
                    data.copyOfRange(da2.offset, da2.offset + da2.len), da2.offset, da2.len, da2.addr
                )
            } else {
                onProgress("!! ה-offsets וה-lengths חורגים מגודל הקובץ")
            }
        }

        return null
    }

    fun runCarbonara(
        usbManager: UsbManager,
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        onProgress("=== שלב 1: ניתוח חכם של ה-DA ===")
        val parts = parseDa(daBytes, onProgress)
        if (parts == null) {
            return "הניתוח הסתיים. ראה את הממצאים בלוג למעלה."
        }

        onProgress("\n=== הצלחה! נמצאו DA1 ו-DA2 ===")
        onProgress("DA1: ${parts.da1.size} בייט, DA2: ${parts.da2.size} בייט")
        return "הצלחה בפירוק!"
    }
}
