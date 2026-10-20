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

    // פירוק ה-DA - גרסה חדשה שמחפשת את המחרוזת MTK_DOWNLOAD_AGENT
    private fun parseDa(data: ByteArray, onProgress: (String) -> Unit): DaParts? {
        if (data.size < 0x6C) {
            onProgress("!! DA קטן מדי (${data.size} בייט)")
            return null
        }

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
        
        if (magicOffset == -1) {
            onProgress("!! לא נמצאה המחרוזת MTK_DOWNLOAD_AGENT")
            return null
        }
        onProgress("נמצאה המחרוזת MTK_DOWNLOAD_AGENT ב-offset 0x${Integer.toHexString(magicOffset)}")

        // הדפסת 256 הבייטים הראשונים של הקובץ כ-HEX כדי שנוכל לפענח את המבנה
        val dumpLen = minOf(256, data.size)
        val sb = StringBuilder()
        for (i in 0 until dumpLen) {
            sb.append(String.format(Locale.US, "%02X", data[i]))
            if ((i + 1) % 16 == 0) sb.append("\n") else sb.append(" ")
        }
        onProgress("=== HEX DUMP של 256 הבייטים הראשונים ===\n$sb")
        
        // עצירה כאן - אנחנו רוצים לראות את ה-HEX לפני שנמשיך
        return null
    }

    // שליחת פקודת 0xD4 (WRITE32) עם כתובת ונתונים
    private fun write32(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long, data: ByteArray): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD4.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size / 4).array()
        if (connection.bulkTransfer(epOut, sizeBytes, 4, 2000) < 0) return false
        
        val ack = ByteArray(1)
        connection.bulkTransfer(epIn, ack, 1, 2000)
        
        var offset = 0
        while (offset < data.size) {
            val chunk = if (offset + 4 <= data.size) data.copyOfRange(offset, offset + 4) else byteArrayOf(0, 0, 0, 0)
            if (connection.bulkTransfer(epOut, chunk, 4, 2000) < 0) return false
            offset += 4
        }
        return true
    }

    // שליחת פקודת 0xD5 (JUMP_DA) עם כתובת
    private fun jumpTo(connection: android.hardware.usb.UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, addr: Long): Boolean {
        if (connection.bulkTransfer(epOut, byteArrayOf(0xD5.toByte()), 1, 2000) < 0) return false
        val addrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(addr.toInt()).array()
        if (connection.bulkTransfer(epOut, addrBytes, 4, 2000) < 0) return false
        val ack = ByteArray(2)
        connection.bulkTransfer(epIn, ack, 2, 2000)
        return true
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
        return "הצלחה בפירוק (לא אמור להגיע לכאן כרגע)"
    }
}
