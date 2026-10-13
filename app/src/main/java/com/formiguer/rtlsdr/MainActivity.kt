package com.formiguer.rtlsdr

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var map: WebView

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) refreshDevice() else setStatus("Permiso USB denegado", error = true)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDevice()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
        }
        map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadUrl("file:///android_asset/map.html")
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        refreshDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshDevice()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        map.destroy()
        super.onDestroy()
    }

    private fun refreshDevice() {
        val device = RtlSdrDevice.find(usbManager)
        when {
            device == null -> setStatus(
                "RTL-SDR no detectado. Dispositivos USB vistos:\n" + RtlSdrDevice.listAll(usbManager),
                error = true
            )
            !usbManager.hasPermission(device) -> {
                setStatus("RTL-SDR ${RtlSdrDevice.describe(device)} detectado, pidiendo permiso…")
                requestPermission(device)
            }
            else -> setStatus("RTL-SDR ${RtlSdrDevice.describe(device)} listo (fase 1: detección USB)")
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, pending)
    }

    private fun setStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) Color.rgb(176, 0, 32) else Color.rgb(27, 94, 32))
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.formiguer.rtlsdr.USB_PERMISSION"
    }
}
