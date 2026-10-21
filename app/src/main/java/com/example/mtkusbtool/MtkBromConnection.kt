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

    // סורק את כל הקובץ אחרי רצף DA DA ומוצא רשומה עם hwcode 0x717
    fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x100) return null

        onProgress("סורק את הקובץ (${data.size} בייט) אחרי רשומות DA DA...")

        val magicDa = byteArrayOf(0xDA.toByte(), 0xDA.toByte())
        var searchOffset = 0
        var foundCount = 0

        while (searchOffset < data.size - 12) {
            val idx = indexOf(data.copyOfRange(searchOffset, data.size), magicDa)
            if (idx == -1) break
            val entryOffset = searchOffset + idx
            searchOffset = entryOffset + 2

            // קריאת כותרת הרשומה (12 בייטים)
            val buf = ByteBuffer.wrap(data, entryOffset, 12).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.short.toInt() and 0xFFFF
            val hwCode = buf.short.toInt() and 0xFFFF
            val hwSubCode = buf.short.toInt() and 0xFFFF
            val hwVer = buf.short.toInt() and 0xFFFF
            val swVer = buf.short.toInt() and 0xFFFF
            val numRegions = buf.short.toInt() and 0xFFFF

            // סינון: רק רשומות עם hwcode 0x717 ומספר regions הגיוני
            if (hwCode == 0x717 && numRegions in 1..10) {
                foundCount++
                onProgress("\n>>> מצאנו Entry של MT6761 ב-offset 0x${Integer.toHexString(entryOffset)}!")
                onProgress("    hwCode=0x${Integer.toHexString(hwCode)}, subCode=0x${Integer.toHexString(hwSubCode)}, hwVer=0x${Integer.toHexString(hwVer)}, swVer=0x${Integer.toHexString(swVer)}, regions=$numRegions")

                // קריאת האזורים (Regions)
                val regions = mutableListOf<DaRegion>()
                var regionOffset = entryOffset + 12
                for (r in 0 until numRegions) {
                    if (regionOffset + 20 > data.size) break
                    val rb = ByteBuffer.wrap(data, regionOffset, 20).order(ByteOrder.LITTLE_ENDIAN)
                    val mBuf = rb.int
                    val mLen = rb.int
                    val mAddr = rb.int.toLong() and 0xFFFFFFFFL
                    val mOffset = rb.int
                    val mSigLen = rb.int
                    regions.add(DaRegion(mBuf, mLen, mAddr, mOffset, mSigLen))
                    onProgress("    Region $r: buf=0x${Integer.toHexString(mBuf)}, len=0x${Integer.toHexString(mLen)} (${mLen} בייט), addr=0x${java.lang.Long.toHexString(mAddr)}, offset=0x${Integer.toHexString(mOffset)}, sigLen=0x${Integer.toHexString(mSigLen)}")
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
            }
        }

        onProgress("\nסריקה הסתיימה. נמצאו $foundCount מועמדים ל-MT6761 (עם regions הגיוני).")
        if (foundCount == 0) {
            onProgress("!! לא נמצאה אף רשומת MT6761 (0x717) בקובץ הזה.")
        }
        return null
    }

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
