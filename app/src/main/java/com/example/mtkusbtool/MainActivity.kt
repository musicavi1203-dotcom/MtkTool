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

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"
    
    // משתנה גלובלי לשמירת הקובץ בזיכרון
    private var daBytes: ByteArray? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device.vendorId == 0x0E8D) {
                        requestPermissionFast(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    tvStatus.text = "המכשיר נותק."
                    btnHandshake.isEnabled = false
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        executeDaFlow(device)
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
        tvStatus.text = "טוען קובץ DA לזיכרון..."

        // טוען את הקובץ ברקע מיד עם עליית האפליקציה
        Thread {
            try {
                val inputStream: InputStream = assets.open("MTK_AllInOne_DA.bin")
                daBytes = inputStream.readBytes()
                inputStream.close()
                runOnUiThread {
                    tvStatus.text = "הקובץ נטען. ממתין למכשיר..."
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "שגיאה קריטית: הקובץ MTK_AllInOne_DA.bin לא נמצא ב-assets." }
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
                if (usbManager.hasPermission(device)) executeDaFlow(device)
                else requestPermissionFast(device)
            } else {
                tvStatus.text = "לא נמצא מכשיר MediaTek."
            }
        }
    }

    private fun requestPermissionFast(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            executeDaFlow(device)
            return
        }
        tvStatus.text = "מבקש הרשאה..."
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeDaFlow(device: UsbDevice) {
        val bytes = daBytes
        if (bytes == null) {
            tvStatus.text = "המתן, הקובץ עדיין נטען..."
            return
        }

        tvStatus.text = "מתחיל תהליך..."
        btnHandshake.isEnabled = false

        Thread {
            val resultMessage = MtkBromConnection.sendDaFile(usbManager, device, bytes) { progressMsg ->
                runOnUiThread { tvStatus.text = progressMsg }
            }
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
