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
    private lateinit var btnRequestPermission: Button
    private lateinit var btnHandshake: Button
    private val ACTION_USB_PERMISSION = "com.example.mtkusbtool.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // מכשיר התחבר! מפעילים את כל התהליך
                    checkForDeviceAndRequestPermission()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    tvStatus.text = getString(R.string.status_waiting)
                    btnHandshake.isEnabled = false
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        tvStatus.text = getString(R.string.status_permission_granted)
                        // הרשאה אושרה - מיד עושים Handshake!
                        doHandshakeAutomatically() 
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
        btnRequestPermission = findViewById(R.id.btnRequestPermission)
        btnHandshake = findViewById(R.id.btnHandshake)

        // הסתרנו את כפתור ההרשאה כי התהליך אוטומטי
        btnRequestPermission.visibility = View.GONE 

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // כפתור ידני רק למקרה שהמכשיר מחובר והתפספס
        btnHandshake.setOnClickListener { checkForDeviceAndRequestPermission() }

        // בדיקה ראשונית אם המכשיר כבר מחובר כשהאפליקציה נפתחה
        checkForDeviceAndRequestPermission()
    }

    private fun checkForDeviceAndRequestPermission() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
        if (device == null) {
            tvStatus.text = "ממתין לחיבור מכשיר MediaTek..."
            return
        }

        if (usbManager.hasPermission(device)) {
            // אם כבר יש הרשאה, פשוט עושים Handshake
            doHandshakeAutomatically()
        } else {
            tvStatus.text = "מכשיר זוהה, מבקש הרשאה..."
            val intent = Intent(ACTION_USB_PERMISSION)
            val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun doHandshakeAutomatically() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D } ?: return
        
        tvStatus.text = "מבצע לחיצת יד אוטומטית..."
        btnHandshake.isEnabled = false

        Thread {
            val success = MtkBromConnection.performHandshake(usbManager, device)
            runOnUiThread {
                tvStatus.text = if (success) getString(R.string.status_handshake_success) 
                                else getString(R.string.status_handshake_failed)
                btnHandshake.isEnabled = true // מאפשר לנסות שוב ידנית אם נכשל
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
