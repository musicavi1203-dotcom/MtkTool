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
    private var kamakiriDone = false
    private var waitingForBrom = false

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
                        
                        if (waitingForBrom && device.productId == 0x0003) {
                            waitingForBrom = false
                            addLog(">> זוהה BROM! מריץ Carbonara...")
                            if (usbManager.hasPermission(device)) {
                                executeCarbonara(device)
                            } else {
                                requestPermission(device, true)
                            }
                        } else if (!kamakiriDone) {
                            requestPermission(device, false)
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
                        if (kamakiriDone || waitingForBrom) executeCarbonara(device)
                        else executeKamakiri(device)
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
        tvStatus.text = "טוען קבצים..."

        Thread {
            try {
                val daFiles = listOf("MTK_AllInOne_DA.bin", "DA_SWSEC_CRYPTO20.bin", "DA_SWSEC.bin")
                for (name in daFiles) {
                    try {
                        addLog("=== טוען $name ===")
                        assets.open(name).use { 
                            val bytes = it.readBytes()
                            addLog("גודל: ${bytes.size} בייט")
                            val analysis = MtkBromConnection.analyzeDa(bytes) { addLog(it) }
                            addLog(analysis)
                        }
                    } catch (e: Exception) {
                        addLog("!! לא ניתן לקרוא את $name: ${e.message}")
                    }
                }
                runOnUiThread { tvStatus.text = "הניתוח הסתיים. ראה לוג." }
            } catch (e: Exception) {
                addLog("!! שגיאה כללית: ${e.message}")
            }
        }.start()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        btnHandshake.setOnClickListener {
            // כפתור לא בשימוש כרגע, הניתוח אוטומטי
            addLog("--- לחיצה ידנית (אין פעולה כרגע) ---")
        }
    }

    // פונקציות אלו לא בשימוש כרגע, אבל משאירים אותן כדי שהקוד יעבור קומפילציה
    private fun requestPermission(device: UsbDevice, isDaPhase: Boolean) {}
    private fun executeKamakiri(device: UsbDevice) {}
    private fun executeCarbonara(device: UsbDevice) {}

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
