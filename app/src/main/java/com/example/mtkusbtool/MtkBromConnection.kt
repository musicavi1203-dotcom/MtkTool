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

    // פונקציית עזר לחיפוש מערך בתוך מערך (למקרה הצורך)
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

    // פונקציית הפירוס המרכזית - מבוססת על מפרט V5/V6
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x18) {
            onProgress("!! הקובץ קטן מדי (${data.size} בייט)")
            return null
        }

        // 1. קריאת הכותרת (Header)
        // מחרוזת של 16 בייטים (MTK_DOWNLOAD_AGENT או MTK_DA_v6)
        val magic = String(data, 0, 16, Charsets.US_ASCII).trim('\u0000')
        onProgress("Header Magic: $magic")
        
        val buf = ByteBuffer.wrap(data, 0x10, 8).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.int
        val numEntries = buf.int
        onProgress("Version: $version, Num Entries: $numEntries")

        if (!magic.contains("MTK_DOWNLOAD_AGENT") && !magic.contains("MTK_DA_v6")) {
            onProgress("!! הכותרת לא מזוהה. ייתכן שהקובץ מוצפן, דחוס או בפורמט אחר.")
            return null
        }

        // 2. מעבר על רשומות השבבים (Entries)
        var offset = 0x18
        for (e in 0 until numEntries) {
            if (offset + 12 > data.size) break
            
            val entryBuf = ByteBuffer.wrap(data, offset, 12).order(ByteOrder.LITTLE_ENDIAN)
            val entryMagic = entryBuf.short.toInt() and 0xFFFF
            val hwCode = entryBuf.short.toInt() and 0xFFFF
            val hwSubCode = entryBuf.short.toInt() and 0xFFFF
            val hwVer = entryBuf.short.toInt() and 0xFFFF
            val swVer = entryBuf.short.toInt() and 0xFFFF
            val numRegions = entryBuf.short.toInt() and 0xFFFF

            // לפעמים יש רשומות ריקות (padding), נדלג עליהן
            if (entryMagic == 0) {
                offset += 12
                continue
            }

            onProgress("Entry $e: magic=0x${Integer.toHexString(entryMagic)}, hwCode=0x${Integer.toHexString(hwCode)}, regions=$numRegions")

            // 3. אם מצאנו את השבב שלנו (MT6761)
            if (entryMagic == 0xdada && hwCode == 0x717) {
                onProgress("  >>> מצאנו את MT6761 (0x717)! <<<")
                val regions = mutableListOf<DaRegion>()
                var regionOffset = offset + 12
                
                for (r in 0 until numRegions) {
                    if (regionOffset + 20 > data.size) break
                    val rb = ByteBuffer.wrap(data, regionOffset, 20).order(ByteOrder.LITTLE_ENDIAN)
                    val mBuf = rb.int
                    val mLen = rb.int
                    val mAddr = rb.int.toLong() and 0xFFFFFFFFL
                    val mOffset = rb.int
                    val mSigLen = rb.int
                    regions.add(DaRegion(mBuf, mLen, mAddr, mOffset, mSigLen))
                    onProgress("    Region $r: len=${mLen}, addr=0x${java.lang.Long.toHexString(mAddr)}, offset=${mOffset}")
                    regionOffset += 20
                }
                
                if (regions.size >= 3) {
                    val da1 = regions[1]
                    val da2 = regions[2]
                    onProgress("  DA1: offset=${da1.offset}, len=${da1.len}, addr=0x${java.lang.Long.toHexString(da1.addr)}")
                    onProgress("  DA2: offset=${da2.offset}, len=${da2.len}, addr=0x${java.lang.Long.toHexString(da2.addr)}")
                    
                    if (da1.offset + da1.len <= data.size && da2.offset + da2.len <= data.size) {
                        return DaParts(
                            data.copyOfRange(da1.offset, da1.offset + da1.len), da1.offset, da1.len, da1.addr,
                            data.copyOfRange(da2.offset, da2.offset + da2.len), da2.offset, da2.len, da2.addr
                        )
                    } else {
                        onProgress("  !! ה-offsets וה-lengths חורגים מגודל הקובץ")
                    }
                }
            }

            // 4. דילוג לרשומה הבאה
            offset += 12 + (numRegions * 20)
        }

        onProgress("!! לא נמצאה רשומה לשבב 0x717 בקובץ.")
        return null
    }

    // פונקציה ראשית לבדיקה
    fun runCarbonara(
        usbManager: UsbManager,
        device: UsbDevice,
        daBytes: ByteArray,
        onProgress: (String) -> Unit
    ): String {
        onProgress("=== שלב 1: ניתוח ה-DA ===")
        val parts = parseDa(daBytes, onProgress)
        if (parts == null) {
            return "הניתוח הסתיים. ראה את הממצאים בלוג למעלה."
        }

        onProgress("\n=== הצלחה! נמצאו DA1 ו-DA2 ===")
        onProgress("DA1: ${parts.da1.size} בייט, DA2: ${parts.da2.size} בייט")
        return "הצלחה בפירוק!"
    }
}
