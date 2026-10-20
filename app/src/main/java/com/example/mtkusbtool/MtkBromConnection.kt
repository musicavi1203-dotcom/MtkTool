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

    // פונקציה חדשה: סורקת את כל רשומות השבבים ומוצאת את 0x766
    private fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x100) {
            onProgress("!! DA קטן מדי")
            return null
        }

        // מחרוזת החיפוש
        val magicString = "MTK_DOWNLOAD_AGENT".toByteArray()
        var magicOffset = -1
        outer@ for (i in 0..data.size - magicString.size) {
            for (j in magicString.indices) {
                if (data[i + j] != magicString[j]) continue@outer
            }
            magicOffset = i
            break
        }
        if (magicOffset == -1) { onProgress("!! לא נמצא MTK_DOWNLOAD_AGENT"); return null }
        onProgress("נמצאה מחרוזת MTK_DOWNLOAD_AGENT ב-offset 0x${Integer.toHexString(magicOffset)}")

        // קריאת ה-header (0x70 ו-0x74)
        val chipCount = ByteBuffer.wrap(data, 0x70, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val magic = ByteBuffer.wrap(data, 0x74, 4).order(ByteOrder.LITTLE_ENDIAN).int
        
        onProgress("Chip count: $chipCount, Magic: 0x${Integer.toHexString(magic)}")

        // מעבר על כל רשומות השבבים (כל רשומה 28 בייטים, מתחיל ב-0x78)
        for (i in 0 until chipCount) {
            val entryOffset = 0x78 + i * 28
            if (entryOffset + 28 > data.size) break
            
            val buf = ByteBuffer.wrap(data, entryOffset, 28).order(ByteOrder.LITTLE_ENDIAN)
            val m_bbchip = buf.int
            val m_da1_offset = buf.int
            val m_da1_length = buf.int
            val m_da1_addr = buf.int.toLong() and 0xFFFFFFFFL
            val m_da2_offset = buf.int
            val m_da2_length = buf.int
            val m_da2_addr = buf.int.toLong() and 0xFFFFFFFFL
            
            onProgress("שבב $i: hwcode=0x${Integer.toHexString(m_bbchip)}, da1_off=0x${Integer.toHexString(m_da1_offset)}, da1_len=${m_da1_length}, da2_off=0x${Integer.toHexString(m_da2_offset)}, da2_len=${m_da2_length}")

            // בדיקה אם זה השבב שלנו (0x766)
            if (m_bbchip == 0x766) {
                onProgress("מצאתי את השבב 0x766 (MT6761) ברשומה $i!")
                
                if (m_da1_offset + m_da1_length <= data.size && m_da2_offset + m_da2_length <= data.size) {
                    val da1 = data.copyOfRange(m_da1_offset, m_da1_offset + m_da1_length)
                    val da2 = data.copyOfRange(m_da2_offset, m_da2_offset + m_da2_length)
                    return DaParts(da1, m_da1_offset, m_da1_length, m_da1_addr, da2, m_da2_offset, m_da2_length, m_da2_addr)
                } else {
                    onProgress("!! גדלים לא תקינים (da1 $m_da1_offset+$m_da1_length, da2 $m_da2_offset+$m_da2_length, total ${data.size})")
                }
            }
        }

        onProgress("!! לא נמצאה רשומה לשבב 0x766")
        return null
    }

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

        onProgress("=== שלב 1: פירוק ה-DA ===")
        val parts = parseDa(daBytes, onProgress)
        if (parts == null) {
            connection.close()
            return "שגיאה: פירוק ה-DA נכשל."
        }

        onProgress("DA1 size: ${parts.da1.size}, DA2 size: ${parts.da2.size}")
        connection.close()
        return "הצלחה בפירוק! DA1 (${parts.da1.size} בייט), DA2 (${parts.da2.size} בייט)."
    }
}
