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
    private lateinit var tvLog: TextView
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"

    private var payloadBytes: ByteArray? = null
    private var daBytes: ByteArray? = null
    private var kamakiriDone = false
    private var isPolling = false

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
                        
                        if (kamakiriDone && device.productId == 0x0003) {
                            addLog(">> זוהה BROM! מנסה לשלוח DA...")
                            if (usbManager.hasPermission(device)) {
                                executeDaOnReconnect(device)
                            } else {
                                requestPermissionFast(device, true)
                            }
                        } else if (!kamakiriDone) {
                            tvStatus.text = "מבקש הרשאה..."
                            requestPermissionFast(device, false)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    addLog("<< מכשיר התנתק.")
                    if (!kamakiriDone) {
                        tvStatus.text = "המכשיר נותק."
                        btnHandshake.isEnabled = false
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        addLog(">> הרשאה אושרה.")
                        if (kamakiriDone && device.productId == 0x0003) {
                            executeDaOnReconnect(device)
                        } else if (!kamakiriDone) {
                            executeKamakiri(device)
                        }
                    } else {
                        addLog("!! הרשאה נדחתה.")
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
        tvLog = findViewById(R.id.tvLog)
        btnHandshake = findViewById(R.id.btnHandshake)
        btnHandshake.isEnabled = false
        tvStatus.text = "טוען קבצים..."

        Thread {
            try {
                addLog("טוען Payload...")
                assets.open("mt6761_payload.bin").use { payloadBytes = it.readBytes() }
                addLog("טוען DA (CRYPTO20)...")
                assets.open("DA_SWSEC_CRYPTO20.bin").use { daBytes = it.readBytes() }
                addLog("הקבצים נטענו בהצלחה.")
                runOnUiThread { tvStatus.text = "מוכן. ממתין למכשיר..."; btnHandshake.isEnabled = true }
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
                    if (kamakiriDone) executeDaOnReconnect(device) else executeKamakiri(device)
                } else requestPermissionFast(device, kamakiriDone)
            } else addLog("!! לא נמצא מכשיר.")
        }
    }

    private fun requestPermissionFast(device: UsbDevice, isDaPhase: Boolean) {
        if (usbManager.hasPermission(device)) {
            if (isDaPhase) executeDaOnReconnect(device) else executeKamakiri(device)
            return
        }
        addLog("מבקש הרשאה...")
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeKamakiri(device: UsbDevice) {
        val pBytes = payloadBytes ?: return
        tvStatus.text = "מריץ Kamakiri..."
        addLog("מתחיל Kamakiri...")
        btnHandshake.isEnabled = false

        Thread {
            val result = MtkBromConnection.runKamakiriOnly(usbManager, device, pBytes) { addLog(it) }
            runOnUiThread {
                if (result == "RECONNECT") {
                    kamakiriDone = true
                    addLog("== Kamakiri הסתיים. מתחיל לחפש את BROM...")
                    tvStatus.text = "מחפש BROM..."
                    startPollingForBrom()
                } else {
                    addLog("== סיום: $result"); tvStatus.text = result; btnHandshake.isEnabled = true
                }
            }
        }.start()
    }

    private fun startPollingForBrom() {
        if (isPolling) return
        isPolling = true

        Thread {
            var attempts = 0
            while (isPolling && attempts < 100) { // 10 seconds max
                val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D && it.productId == 0x0003 }
                if (device != null) {
                    if (usbManager.hasPermission(device)) {
                        addLog(">> לופ: מצאתי BROM עם הרשאה! שולח DA...")
                        isPolling = false
                        runOnUiThread { executeDaOnReconnect(device) }
                        return@Thread
                    } else {
                        addLog(">> לופ: מצאתי BROM, מבקש הרשאה...")
                        isPolling = false
                        runOnUiThread { requestPermissionFast(device, true) }
                        return@Thread
                    }
                }
                Thread.sleep(100)
                attempts++
            }
            if (attempts >= 100) {
                isPolling = false
                runOnUiThread {
                    addLog("!! לופ: לא נמצא BROM תוך 10 שניות.")
                    kamakiriDone = false
                    tvStatus.text = "לא נמצא BROM."
                    btnHandshake.isEnabled = true
                }
            }
        }.start()
    }

    private fun executeDaOnReconnect(device: UsbDevice) {
        val dBytes = daBytes ?: return
        tvStatus.text = "שולח DA..."
        addLog("מתחיל שליחת DA...")
        btnHandshake.isEnabled = false

        Thread {
            val result = MtkBromConnection.sendDaOnReconnect(usbManager, device, dBytes) { addLog(it) }
            runOnUiThread {
                addLog("== סיום: $result"); tvStatus.text = result
                btnHandshake.isEnabled = true
                if (!result.contains("הצלחה")) {
                    kamakiriDone = false
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        unregisterReceiver(usbReceiver)
    }
}
