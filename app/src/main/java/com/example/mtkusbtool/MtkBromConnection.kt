package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x100) return null

        // חיפוש המחרוזת MTK_DOWNLOAD_AGENT
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

        // חיפוש 0x766 בפורמט Little Endian
        val targetLE = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x766).array()
        var hwcodeOffset = -1
        outer@ for (i in 0..data.size - 4) {
            for (j in 0..3) {
                if (data[i + j] != targetLE[j]) continue@outer
            }
            hwcodeOffset = i
            break
        }
        
        // אם לא נמצא LE, ננסה BE
        if (hwcodeOffset == -1) {
            val targetBE = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x766).array()
            outer@ for (i in 0..data.size - 4) {
                for (j in 0..3) {
                    if (data[i + j] != targetBE[j]) continue@outer
                }
                hwcodeOffset = i
                break
            }
        }

        if (hwcodeOffset == -1) {
            onProgress("!! לא נמצא hwcode 0x766 בקובץ (לא LE ולא BE)")
            return null
        }
        onProgress("נמצא hwcode 0x766 ב-offset 0x${Integer.toHexString(hwcodeOffset)}")

        // הדפסת 128 בייטים סביב ה-hwcode
        val start = maxOf(0, hwcodeOffset - 64)
        val end = minOf(data.size, hwcodeOffset + 64)
        val sb = StringBuilder()
        for (i in start until end) {
            if (i == hwcodeOffset) sb.append(" >> ")
            sb.append(String.format(Locale.US, "%02X", data[i]))
            if ((i - start + 1) % 16 == 0 && i > start) sb.append("\n")
            else sb.append(" ")
        }
        onProgress("=== HEX סביב hwcode 0x766 ===\n$sb")

        return null // עצירה כדי לראות את ה-HEX
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
            return "שגיאה: פירוק ה-DA נכשל (עצרנו כדי לראות את ה-HEX)."
        }

        connection.close()
        return "הצלחה בפירוק!"
    }
}
