package com.formiguer.rtlsdr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import com.formiguer.rtlsdr.adsb.AdsbReceiver
import com.formiguer.rtlsdr.adsb.AircraftTracker

class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var map: WebView

    private val tracker = AircraftTracker()
    private var adsb: AdsbReceiver? = null

    @Volatile private var linkStatus = AdsbReceiver.Status.CONNECTING
    @Volatile private var linkMessage: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
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
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    // Se conecta al driver rtl_tcp solo mientras la app está en primer plano.
    override fun onResume() {
        super.onResume()
        adsb = AdsbReceiver(tracker) { st, msg ->
            linkStatus = st
            linkMessage = msg
        }.also { it.start() }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        adsb?.stop()
        adsb = null
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        map.destroy()
        super.onDestroy()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val dongle = RtlSdrDevice.find(usbManager)
            ?.let { "RTL-SDR ${RtlSdrDevice.describe(it)} conectado por USB" }
            ?: "RTL-SDR no visto por USB"
        val streaming = linkStatus == AdsbReceiver.Status.STREAMING
        val link = when (linkStatus) {
            AdsbReceiver.Status.CONNECTING -> "Conectando con el driver rtl_tcp…"
            AdsbReceiver.Status.WAITING ->
                "Sin driver rtl_tcp (${linkMessage ?: "sin respuesta"}). " +
                    "Abre la app del driver RTL-SDR, pulsa Start y vuelve aquí."
            AdsbReceiver.Status.STREAMING -> {
                val aircraft = tracker.snapshot(now)
                val kb = (adsb?.bytesReceived?.get() ?: 0L) / 1024
                "Recibiendo: $kb kB · mensajes ADS-B: ${tracker.totalMessages} · " +
                    "aviones: ${aircraft.size} (${aircraft.count { it.lat != null }} en el mapa)"
            }
        }
        setStatus("$dongle\n$link", error = !streaming)
        map.evaluateJavascript(
            "if (typeof updateAircraft === 'function') updateAircraft(${tracker.toJson(now)});", null
        )
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(if (error) Color.rgb(176, 0, 32) else Color.rgb(27, 94, 32))
    }
}
