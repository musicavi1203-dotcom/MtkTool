package com.example.mtkusbtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"
    
    private var payloadBytes: ByteArray? = null
    private var daBytes: ByteArray? = null
    private var waitingForBrom = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device.vendorId == 0x0E8D) {
                        val pidStr = String.format(Locale.US, "0x%04X", device.productId)
                        if (waitingForBrom) {
                            tvStatus.text = "מכשיר חזר (PID: $pidStr). ממתין 1.5 שניות..."
                            waitingForBrom = false
                            Thread {
                                Thread.sleep(1500)
                                runOnUiThread { requestPermissionFast(device) }
                            }.start()
                        } else {
                            tvStatus.text = "מכשיר זוהה (PID: $pidStr). מבקש הרשאה..."
                            requestPermissionFast(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    tvStatus.text = if (waitingForBrom) "מכשיר קרס. ממתין לאתחול מחדש..." else "המכשיר נותק."
                    if (!waitingForBrom) btnHandshake.isEnabled = false
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        val isBrom = device.productId == 0x0003 || device.productId == 0x0001
                        if (isBrom) executeBypassFlow(device) else crashAndWait()
                    } else {
                        tvStatus.text = "הרשאה נדחתה."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvStatus = findViewById(R.id.tvStatus)
        btnHandshake = findViewById(R.id.btnHandshake)
        btnHandshake.isEnabled = false
        tvStatus.text = "טוען קבצים..."

        Thread {
            try {
                val payloadStream: InputStream = assets.open("mt6761_payload.bin")
                payloadBytes = payloadStream.readBytes()
                payloadStream.close()
                
                val daStream: InputStream = assets.open("DA_SWSEC.bin")
                daBytes = daStream.readBytes()
                daStream.close()
                
                runOnUiThread {
                    tvStatus.text = "הקבצים נטענו. ממתין למכשיר..."
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "שגיאה: קבצים חסרים ב-assets." }
            }
        }.start()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        btnHandshake.setOnClickListener { 
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
            if (device != null) {
                if (usbManager.hasPermission(device)) {
                    val isBrom = device.productId == 0x0003 || device.productId == 0x0001
                    if (isBrom) executeBypassFlow(device) else crashAndWait()
                } else requestPermissionFast(device)
            } else {
                tvStatus.text = "לא נמצא מכשיר MediaTek."
            }
        }
    }

    private fun requestPermissionFast(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            val isBrom = device.productId == 0x0003 || device.productId == 0x0001
            if (isBrom) executeBypassFlow(device) else crashAndWait()
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun crashAndWait() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D } ?: return
        tvStatus.text = "מנסה לקרוס (Crash אגרסיבי)..."
        btnHandshake.isEnabled = false
        waitingForBrom = true

        Thread {
            val success = MtkBromConnection.aggressiveCrash(usbManager, device)
            runOnUiThread {
                if (success) {
                    tvStatus.text = "פקודת קריסה נשלחה. ממתין ל-BROM..."
                } else {
                    tvStatus.text = "שגיאה בקריסה. נסה שוב."
                    waitingForBrom = false
                    btnHandshake.isEnabled = true
                }
            }
        }.start()
    }

    private fun executeBypassFlow(device: UsbDevice) {
        val pBytes = payloadBytes
        val dBytes = daBytes
        if (pBytes == null || dBytes == null) {
            tvStatus.text = "המתן, הקבצים נטענים..."
            return
        }

        tvStatus.text = "מתחיל פריצה..."
        btnHandshake.isEnabled = false

        Thread {
            val resultMessage = MtkBromConnection.executeBypassAndSendDa(
                usbManager, device, pBytes, dBytes
            ) { progressMsg -> runOnUiThread { tvStatus.text = progressMsg } }
            runOnUiThread {
                tvStatus.text = resultMessage
                btnHandshake.isEnabled = true
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
