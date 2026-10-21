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
    private var payloadBytes: ByteArray? = null
    private var daBytes: ByteArray? = null
    private var isRunning = false
    private var waitingForReconnect = false
    private var currentPhase = "kamakiri" // "kamakiri" או "da1"

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
                        val pidStr = String.format(Locale.US, "0x%04X", device.productId)
                        addLog(">> מכשיר התחבר. PID: $pidStr")
                        
                        if (waitingForReconnect && device.productId == 0x0003) {
                            addLog(">>> BROM חזר! מנסה לתפוס...")
                            requestPermission(device, "da1")
                        } else if (!waitingForReconnect && !isRunning) {
                            requestPermission(device, "kamakiri")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    addLog("<< מכשיר התנתק.")
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        if (currentPhase == "da1") {
                            waitingForReconnect = false
                            executeDa1Phase(device)
                        } else {
                            executeKamakiriPhase(device)
                        }
                    } else {
                        addLog("!! הרשאה נדחתה.")
                        // אם זו הרשאה ל-DA1, נשארים ב-waitingForReconnect כדי לנסות שוב
                        if (currentPhase != "da1") {
                            waitingForReconnect = false
                            btnHandshake.isEnabled = true
                        }
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
                addLog("טוען mt6761_payload.bin...")
                assets.open("mt6761_payload.bin").use { payloadBytes = it.readBytes() }
                addLog("נטען Payload: ${payloadBytes?.size} בייט.")
                addLog("טוען MTK_DA_V5.bin...")
                assets.open("MTK_DA_V5.bin").use { daBytes = it.readBytes() }
                addLog("נטען DA: ${daBytes?.size} בייט.")
                runOnUiThread { tvStatus.text = "מוכן. חבר את המכשיר (כבוי)."; btnHandshake.isEnabled = true }
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
            addLog("--- מתחיל תהליך מלא ---")
            waitingForReconnect = false
            currentPhase = "kamakiri"
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
            if (device != null) {
                requestPermission(device, "kamakiri")
            } else addLog("!! לא נמצא מכשיר.")
        }
    }

    private fun requestPermission(device: UsbDevice, phase: String) {
        currentPhase = phase
        if (usbManager.hasPermission(device)) {
            if (phase == "da1") {
                waitingForReconnect = false
                executeDa1Phase(device)
            } else {
                executeKamakiriPhase(device)
            }
            return
        }
        addLog("מבקש הרשאה (${if (phase == "da1") "DA1" else "Kamakiri"})...")
        val pi = PendingIntent.getBroadcast(this, if (phase == "da1") 1 else 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeKamakiriPhase(device: UsbDevice) {
        val pBytes = payloadBytes ?: return
        if (isRunning) return
        isRunning = true
        tvStatus.text = "מריץ Kamakiri..."
        btnHandshake.isEnabled = false

        Thread {
            try {
                val result = MtkBromConnection.runKamakiriPhase(usbManager, device, pBytes) { addLog(it) }
                runOnUiThread {
                    addLog("== סיום Kamakiri: $result")
                    if (result == "KAMAKIRI_DONE") {
                        waitingForReconnect = true
                        tvStatus.text = "ממתין ל-reconnect..."
                        addLog("ממתין לחיבור מחדש של המכשיר...")
                    } else {
                        tvStatus.text = result
                        btnHandshake.isEnabled = true
                    }
                    isRunning = false
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

    private fun executeDa1Phase(device: UsbDevice) {
        val dBytes = daBytes ?: return
        if (isRunning) return
        isRunning = true
        tvStatus.text = "שולח DA1..."
        btnHandshake.isEnabled = false

        Thread {
            try {
                val result = MtkBromConnection.runDa1Phase(usbManager, device, dBytes) { addLog(it) }
                runOnUiThread {
                    addLog("== סיום DA1: $result")
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
