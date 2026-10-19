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
    private var kamakiriSuccess = false

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
                        
                        if (kamakiriSuccess) {
                            addLog(">> מכשיר חזר אחרי Kamakiri. ממשיך ל-DA...")
                            requestPermissionFast(device, true)
                        } else {
                            tvStatus.text = "מבקש הרשאה..."
                            requestPermissionFast(device, false)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    addLog("<< מכשיר התנתק.")
                    tvStatus.text = "המכשיר נותק."
                    btnHandshake.isEnabled = false
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        addLog(">> הרשאה אושרה.")
                        if (kamakiriSuccess) {
                            executeDaOnly(device)
                        } else {
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
                val payloadStream: InputStream = assets.open("mt6761_payload.bin")
                payloadBytes = payloadStream.readBytes()
                payloadStream.close()
                
                addLog("טוען DA...")
                val daStream: InputStream = assets.open("DA_SWSEC.bin")
                daBytes = daStream.readBytes()
                daStream.close()

                addLog("הקבצים נטענו בהצלחה.")
                runOnUiThread {
                    tvStatus.text = "מוכן. ממתין למכשיר..."
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                addLog("!! שגיאה בטעינת קבצים: ${e.message}")
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
            addLog("--- לחיצה ידנית על הכפתור ---")
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
            if (device != null) {
                if (usbManager.hasPermission(device)) {
                    if (kamakiriSuccess) executeDaOnly(device) else executeKamakiri(device)
                } else {
                    requestPermissionFast(device, kamakiriSuccess)
                }
            } else {
                addLog("!! לא נמצא מכשיר MediaTek.")
            }
        }
    }

    private fun requestPermissionFast(device: UsbDevice, isDaPhase: Boolean) {
        if (usbManager.hasPermission(device)) {
            if (isDaPhase) executeDaOnly(device) else executeKamakiri(device)
            return
        }
        addLog("מבקש הרשאת USB...")
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeKamakiri(device: UsbDevice) {
        val pBytes = payloadBytes
        if (pBytes == null) {
            addLog("!! קבצים חסרים.")
            return
        }

        tvStatus.text = "מריץ Kamakiri..."
        addLog("מתחיל פריצת Kamakiri...")
        btnHandshake.isEnabled = false

        Thread {
            val resultMessage = MtkBromConnection.runKamakiriExploit(
                usbManager, device, pBytes
            ) { progressMsg -> addLog(progressMsg) }
            
            runOnUiThread {
                if (resultMessage == "SUCCESS") {
                    kamakiriSuccess = true
                    addLog("== Kamakiri הצליח! ממתין לחיבור מחדש...")
                    tvStatus.text = "ממתין לחיבור מחדש..."
                } else {
                    addLog("== סיום: $resultMessage")
                    tvStatus.text = resultMessage
                    btnHandshake.isEnabled = true
                }
            }
        }.start()
    }

    private fun executeDaOnly(device: UsbDevice) {
        val dBytes = daBytes
        if (dBytes == null) {
            addLog("!! קובץ DA חסר.")
            return
        }

        tvStatus.text = "שולח DA..."
        addLog("מתחיל שליחת DA (מכשיר פרוץ)...")
        btnHandshake.isEnabled = false

        Thread {
            val resultMessage = MtkBromConnection.sendDaAfterExploit(
                usbManager, device, dBytes
            ) { progressMsg -> addLog(progressMsg) }
            
            runOnUiThread {
                addLog("== סיום: $resultMessage")
                tvStatus.text = resultMessage
                btnHandshake.isEnabled = true
                // Reset if failed so user can try again
                if (!resultMessage.contains("הצלחה")) {
                    kamakiriSuccess = false
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
