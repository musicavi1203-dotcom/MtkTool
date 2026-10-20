package com.example.mtkusbtool

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

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

    fun analyzeDa(data: ByteArray, onProgress: (String) -> Unit): String {
        val sb = StringBuilder()
        
        // 1. הדפסת 128 בייטים ראשונים
        sb.append("--- 128 בייטים ראשונים ---\n")
        for (i in 0 until minOf(128, data.size)) {
            sb.append(String.format(Locale.US, "%02X ", data[i]))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        
        // 2. חיפוש המחרוזת MTK_DOWNLOAD_AGENT
        val magic = "MTK_DOWNLOAD_AGENT".toByteArray()
        val magicIdx = indexOf(data, magic)
        if (magicIdx != -1) {
            sb.append("\n--- נמצא MTK_DOWNLOAD_AGENT ב-offset 0x${Integer.toHexString(magicIdx)} ---\n")
            val start = magicIdx
            val end = minOf(data.size, magicIdx + 64)
            for (i in start until end) {
                sb.append(String.format(Locale.US, "%02X ", data[i]))
                if ((i - start + 1) % 16 == 0) sb.append("\n")
            }
        } else {
            sb.append("\n!! לא נמצא MTK_DOWNLOAD_AGENT\n")
        }
        
        // 3. חיפוש hwcodes שונים
        val hwcodes = listOf(0x717, 0x6761, 0x766, 0x6765, 0x6762, 0x6763, 0x6757)
        for (hw in hwcodes) {
            // LE
            val leBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(hw).array()
            val leIdx = indexOf(data, leBytes)
            if (leIdx != -1) {
                sb.append("\n--- נמצא hwcode 0x${Integer.toHexString(hw)} (LE) ב-offset 0x${Integer.toHexString(leIdx)} ---\n")
                val start = maxOf(0, leIdx - 16)
                val end = minOf(data.size, leIdx + 32)
                for (i in start until end) {
                    if (i == leIdx) sb.append(">> ")
                    sb.append(String.format(Locale.US, "%02X ", data[i]))
                    if ((i - start + 1) % 16 == 0) sb.append("\n")
                }
            }
            
            // BE
            val beBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(hw).array()
            val beIdx = indexOf(data, beBytes)
            if (beIdx != -1) {
                sb.append("\n--- נמצא hwcode 0x${Integer.toHexString(hw)} (BE) ב-offset 0x${Integer.toHexString(beIdx)} ---\n")
                val start = maxOf(0, beIdx - 16)
                val end = minOf(data.size, beIdx + 32)
                for (i in start until end) {
                    if (i == beIdx) sb.append(">> ")
                    sb.append(String.format(Locale.US, "%02X ", data[i]))
                    if ((i - start + 1) % 16 == 0) sb.append("\n")
                }
            }
        }
        
        return sb.toString()
    }
}
