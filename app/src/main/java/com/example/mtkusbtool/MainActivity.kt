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
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"
    private var payloadBytes: ByteArray? = null
    
    private val daFiles = listOf(
        "DA_PL_NO_CERT_V6.bin",
        "DA_SWSEC.bin",
        "DA_SWSEC.1b523f48.bin",
        "DA_SWSEC_CRYPTO20.5bed6638.bin",
        "DA_PL_CRYPTO20.997123da.bin",
        "DA_PL.c95b62a2.bin",
        "MTK_AllInOne_DA.1d5fdec4.bin",
        "MTK_AllInOne_DA.bin",
        "DA_SWSEC_CRYPTO20.bin",
        "MT6761.bin"
    )
    private var currentDaIndex = 0
    private var daBytes: ByteArray? = null

    private var isRunning = false
    private var waitingForReconnect = false
    private var currentPhase = "kamakiri"

    private val logFile: File by lazy { File(filesDir, "mtk_tool.log") }

    private fun addLog(message: String) {
        try {
            logFile.appendText("$message\n")
        } catch (e: Exception) {}

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

        if (logFile.exists()) logFile.delete()

        tvStatus.text = "טוען קבצים..."
        Thread {
            try {
                addLog("טוען brom_defuse.bin...")
                assets.open("brom_defuse.bin").use { payloadBytes = it.readBytes() }
                addLog("נטען Payload: ${payloadBytes?.size} בייט.")
                
                loadNextDaFile() 
                
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
            addLog("--- מתחיל תהליך מלא (קובץ DA נוכחי: ${daFiles[currentDaIndex]}) ---")
            waitingForReconnect = false
            currentPhase = "kamakiri"
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
            if (device != null) {
                requestPermission(device, "kamakiri")
            } else addLog("!! לא נמצא מכשיר.")
        }
    }

    private fun loadNextDaFile() {
        if (currentDaIndex >= daFiles.size) {
            addLog("!! כל קבצי ה-DA נוסו ונכשלו. המכשיר כנראה חסום ב-eFuse.")
            return
        }
        val fileName = daFiles[currentDaIndex]
        addLog("טוען קובץ DA: $fileName")
        try {
            assets.open(fileName).use { daBytes = it.readBytes() }
            addLog("נטען DA: ${daBytes?.size} בייט.")
        } catch (e: Exception) {
            addLog("!! שגיאה בטעינת $fileName: ${e.message}")
            daBytes = null
            currentDaIndex++
            loadNextDaFile()
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
        val dBytes = daBytes
        if (dBytes == null) {
            addLog("!! אין קובץ DA טעון.")
            isRunning = false
            btnHandshake.isEnabled = true
            return
        }
        if (isRunning) return
        isRunning = true
        tvStatus.text = "שולח DA1 (${daFiles[currentDaIndex]})..."
        btnHandshake.isEnabled = false

        Thread {
            try {
                val result = MtkBromConnection.runDa1Phase(usbManager, device, dBytes, null) { addLog(it) }
                runOnUiThread {
                    addLog("== סיום DA1 (${daFiles[currentDaIndex]}): $result")
                    
                    if (!result.contains("הצלחה")) {
                        addLog(">>> הקובץ הנוכחי נכשל. מנסה את הקובץ הבא...")
                        currentDaIndex++
                        if (currentDaIndex < daFiles.size) {
                            loadNextDaFile()
                            waitingForReconnect = true
                            tvStatus.text = "נכשל. ממתין לחיבור מחדש לקובץ הבא..."
                            addLog("אנא נתק וחבר מחדש את המכשיר.")
                        } else {
                            tvStatus.text = "כל הקבצים נכשלו."
                            addLog("!! כל קבצי ה-DA נוסו. המכשיר חסום.")
                            btnHandshake.isEnabled = true
                        }
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
