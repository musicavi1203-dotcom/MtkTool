package com.example.mtkusbtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"

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
                    tvStatus.text = getString(R.string.status_waiting)
                    btnHandshake.isEnabled = false
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    
                    if (granted && device != null) {
                        tvStatus.text = getString(R.string.status_permission_granted)
                        doHandshakeFast(device)
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

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // כפתור ידני למקרה שהמכשיר היה מחובר לפני פתיחת האפליקציה
        btnHandshake.setOnClickListener { 
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
            if (device != null) {
                if (usbManager.hasPermission(device)) doHandshakeFast(device)
                else requestPermissionFast(device)
            }
        }
    }

    private fun requestPermissionFast(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            doHandshakeFast(device)
            return
        }
        tvStatus.text = "מבקש הרשאה..."
        val intent = Intent(ACTION_USB_PERMISSION)
        // שים לב: אנחנו חייבים להשתמש ב-FLAG_MUTABLE כדי שאנדרואיד תדע למי לשלוח את התשובה
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pi)
    }

    private fun doHandshakeFast(device: UsbDevice) {
        tvStatus.text = "מבצע לחיצת יד..."
        btnHandshake.isEnabled = false

        Thread {
            val success = MtkBromConnection.performHandshake(usbManager, device)
            runOnUiThread {
                tvStatus.text = if (success) getString(R.string.status_handshake_success) 
                                else getString(R.string.status_handshake_failed)
                btnHandshake.isEnabled = true
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
