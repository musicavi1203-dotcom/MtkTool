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
            val scrollView = tvLog.parent as android.view.View
            scrollView.post { scrollView.scrollTo(0, scrollView.bottom) }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device.vendorId == 0x0E8D) {
                        val pidStr = String.format(Locale.US, "0x%04X", device.productId)
                        addLog(">> מכשיר התחבר. PID: $pidStr")
                        requestPermission(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    addLog("<< מכשיר התנתק.")
                    if (!isRunning) btnHandshake.isEnabled = true
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        addLog(">> הרשאה אושרה.")
                        executeParse(device)
                    } else {
                        addLog("!! הרשאה נדחתה.")
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
        tvLog = findViewById(R.id.tvLog)
        btnHandshake = findViewById(R.id.btnHandshake)
        btnHandshake.isEnabled = false
        tvStatus.text = "טוען DA..."

        Thread {
            try {
                addLog("טוען MTK_AllInOne_DA.bin...")
                assets.open("MTK_AllInOne_DA.bin").use { daBytes = it.readBytes() }
                addLog("נטען בהצלחה (${daBytes?.size} בייט).")
                runOnUiThread {
                    tvStatus.text = "מוכן. חבר את המכשיר."
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                addLog("!! שגיאה בטעינה: ${e.message}")
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
                if (usbManager.hasPermission(device)) {
                    executeParse(device)
                } else {
                    requestPermission(device)
                }
            } else {
                addLog("!! לא נמצא מכשיר MediaTek.")
            }
        }
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            executeParse(device)
            return
        }
        addLog("מבקש הרשאת USB...")
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeParse(device: UsbDevice) {
        val dBytes = daBytes ?: return
        if (isRunning) return

        isRunning = true
        tvStatus.text = "מפרסר DA..."
        btnHandshake.isEnabled = false

        Thread {
            try {
                val result = MtkBromConnection.runCarbonara(usbManager, device, dBytes) { addLog(it) }
                runOnUiThread {
                    addLog("== סיום: $result")
                    tvStatus.text = result
                    isRunning = false
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog("!! שגיאה: ${e.message}")
                    tvStatus.text = "שגיאה."
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
