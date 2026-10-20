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
        tvStatus.text = "טוען DA..."

        Thread {
            try {
                addLog("טוען DA_SWSEC_CRYPTO20.bin...")
                assets.open("DA_SWSEC_CRYPTO20.bin").use { daBytes = it.readBytes() }
                addLog("נטען בהצלחה (${daBytes?.size} בייט).")
                runOnUiThread { tvStatus.text = "מוכן. ממתין למכשיר..."; btnHandshake.isEnabled = true }
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
                if (usbManager.hasPermission(device)) {
                    if (kamakiriDone) executeCarbonara(device) else executeKamakiri(device)
                } else requestPermission(device, kamakiriDone)
            } else addLog("!! לא נמצא מכשיר.")
        }
    }

    private fun requestPermission(device: UsbDevice, isDaPhase: Boolean) {
        if (usbManager.hasPermission(device)) {
            if (isDaPhase) executeCarbonara(device) else executeKamakiri(device)
            return
        }
        addLog("מבקש הרשאה...")
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeKamakiri(device: UsbDevice) {
        tvStatus.text = "מריץ Kamakiri..."
        addLog("=== שלב א': Kamakiri ===")
        btnHandshake.isEnabled = false

        Thread {
            try {
                // קריאה לפונקציית Kamakiri (צריך להיות בקובץ MtkBromConnection)
                // כאן אני מניח שהיא קיימת - אם לא, נצטרך להוסיף אותה חזרה
                addLog("(Kamakiri יורץ מהגרסה הקודמת - בוא נדלג)")
                // בינתיים, נדלג על Kamakiri ונריץ ישר Carbonara
                // (הערה: זה לא יעבוד בלי Kamakiri, אבל זה שלד לבדיקה)
                runOnUiThread {
                    kamakiriDone = true
                    waitingForBrom = true
                    tvStatus.text = "מחכה ל-BROM..."
                    addLog("ממתין ל-BROM...")
                }
            } catch (e: Exception) {
                addLog("!! שגיאה: ${e.message}")
                runOnUiThread { btnHandshake.isEnabled = true }
            }
        }.start()
    }

    private fun executeCarbonara(device: UsbDevice) {
        val dBytes = daBytes ?: return
        tvStatus.text = "מריץ Carbonara..."
        addLog("=== שלב ב': Carbonara ===")
        btnHandshake.isEnabled = false

        Thread {
            val result = MtkBromConnection.runCarbonara(usbManager, device, dBytes) { addLog(it) }
            runOnUiThread {
                addLog("== סיום: $result")
                tvStatus.text = result
                btnHandshake.isEnabled = true
                kamakiriDone = false
                waitingForBrom = false
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
