package com.example.mtkusbtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var btnRequestPermission: Button
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        tvStatus.text = getString(R.string.status_permission_granted)
                        btnHandshake.isEnabled = true
                    } else {
                        tvStatus.text = "הרשאה נדחתה."
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    tvStatus.text = getString(R.string.status_waiting)
                    btnHandshake.isEnabled = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvStatus = findViewById(R.id.tvStatus)
        btnRequestPermission = findViewById(R.id.btnRequestPermission)
        btnHandshake = findViewById(R.id.btnHandshake)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        btnRequestPermission.setOnClickListener { requestPermission() }
        btnHandshake.setOnClickListener { doHandshake() }
    }

    private fun requestPermission() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
        if (device == null) {
            tvStatus.text = "לא נמצא מכשיר MediaTek."
            return
        }
        if (usbManager.hasPermission(device)) {
            tvStatus.text = getString(R.string.status_permission_granted)
            btnHandshake.isEnabled = true
        } else {
            val intent = Intent(ACTION_USB_PERMISSION)
            val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun doHandshake() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D } ?: return
        tvStatus.text = "מבצע לחיצת יד..."
        btnHandshake.isEnabled = false

        // שימוש ב-Thread כדי לא לתקוע את ה-UI
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
