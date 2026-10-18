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
    
    private var payloadBytes: ByteArray? = null
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
                        executeBypassFlow(device)
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
        tvStatus.text = "טוען קבצים לזיכרון..."

        // טוען את שני הקבצים ברקע
        Thread {
            try {
                val payloadStream: InputStream = assets.open("mt6761_payload.bin")
                payloadBytes = payloadStream.readBytes()
                payloadStream.close()

                val daStream: InputStream = assets.open("MTK_AllInOne_DA.bin")
                daBytes = daStream.readBytes()
                daStream.close()

                runOnUiThread {
                    tvStatus.text = "הקבצים נטענו. ממתין למכשיר..."
                    btnHandshake.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "שגיאה קריטית: קבצים חסרים ב-assets." }
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
                if (usbManager.hasPermission(device)) executeBypassFlow(device)
                else requestPermissionFast(device)
            } else {
                tvStatus.text = "לא נמצא מכשיר MediaTek."
            }
        }
    }

    private fun requestPermissionFast(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            executeBypassFlow(device)
            return
        }
        tvStatus.text = "מבקש הרשאה..."
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun executeBypassFlow(device: UsbDevice) {
        val pBytes = payloadBytes
        val dBytes = daBytes
        if (pBytes == null || dBytes == null) {
            tvStatus.text = "המתן, הקבצים עדיין נטענים..."
            return
        }

        tvStatus.text = "מתחיל פריצה..."
        btnHandshake.isEnabled = false

        Thread {
            val resultMessage = MtkBromConnection.executeBypassAndSendDa(
                usbManager, device, pBytes, dBytes
            ) { progressMsg ->
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
