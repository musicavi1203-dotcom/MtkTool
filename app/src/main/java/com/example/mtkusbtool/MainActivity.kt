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
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"
    private var daBytes: ByteArray? = null
    private var isRunning = false

    private fun addLog(message: String) {
        runOnUiThread {
            tvLog.append("$message\n")
            val sv = tvLog.parent as android.view.View
            sv.post { sv.scrollTo(0, sv.bottom) }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device.vendorId == 0x0E8D) {
                        addLog(">> מכשיר התחבר. PID: 0x${String.format(Locale.US, "%04X", device.productId)}")
                        requestPermission(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> addLog("<< מכשיר התנתק.")
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        executeFlow(device)
                    } else addLog("!! הרשאה נדחתה.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnHandshake = findViewById(R.id.btnHandshake)
        btnHandshake.isEnabled = false
        tvStatus.text = "טוען DA..."

        Thread {
            try {
                addLog("טוען MTK_DA_V5.bin (21.4MB)...")
                assets.open("MTK_DA_V5.bin").use { daBytes = it.readBytes() }
                addLog("נטען בהצלחה (${daBytes?.size} בייט).")
                runOnUiThread { tvStatus.text = "מוכן. חבר את המכשיר."; btnHandshake.isEnabled = true }
            } catch (e: Exception) {
                addLog("!! שגיאה: ${e.message}")
                runOnUiThread { tvStatus.text = "שגיאת קבצים." }
            }
        }.start()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        btnHandshake.setOnClickListener {
            addLog("--- לחיצה ידנית ---")
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
            if (device != null) {
                if (usbManager.hasPermission(device)) executeFlow(device)
                else requestPermission(device)
            } else addLog("!! לא נמצא מכשיר.")
        }
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) { executeFlow(device); return }
        addLog("מבקש הרשאה...")
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeFlow(device: UsbDevice) {
        val dBytes = daBytes ?: return
        if (isRunning) return
        isRunning = true
        tvStatus.text = "מריץ תהליך..."
        btnHandshake.isEnabled = false

        Thread {
            try {
                val result = MtkBromConnection.runFullFlow(usbManager, device, dBytes) { addLog(it) }
                runOnUiThread {
                    addLog("== סיום: $result")
                    tvStatus.text = result
                    isRunning = false
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog("!! שגיאה: ${e.message}")
                    tvStatus.text = "שגיאה: ${e.message}"
                    isRunning = false
                    btnHandshake.isEnabled = true
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
