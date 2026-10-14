package com.formiguer.rtlsdr

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.webkit.JavascriptInterface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.formiguer.rtlsdr.adsb.AircraftTracker
import com.formiguer.rtlsdr.adsb.IqSource
import com.formiguer.rtlsdr.adsb.Mode
import com.formiguer.rtlsdr.adsb.RadarUploader
import com.formiguer.rtlsdr.adsb.RadioReceiver
import com.formiguer.rtlsdr.adsb.RegistrationDb
import com.formiguer.rtlsdr.adsb.SatelliteTiles
import com.formiguer.rtlsdr.adsb.RtlTcpClient
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var map: WebView
    private lateinit var adsbView: LinearLayout
    private lateinit var air: AirbandPanel
    private lateinit var tabAdsb: Button
    private lateinit var tabAir: Button
    private lateinit var external: CheckBox
    private lateinit var chrome: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences
    private var fullscreen = false

    private val tracker = AircraftTracker()
    private val satellite by lazy { SatelliteTiles(java.io.File(filesDir, "satelite")) }
    private val audioOut = AudioOut()
    private lateinit var radio: RadioReceiver
    private lateinit var uploader: RadarUploader
    @Volatile private var tab = TAB_ADSB
    @Volatile private var radarStatus = ""
    @Volatile private var radarError = false
    private lateinit var txBtn: Button

    @Volatile private var linkStatus = RadioReceiver.Status.CONNECTING
    @Volatile private var linkMessage: String? = null
    @Volatile private var useExternal = false
    @Volatile private var permissionRequested = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // permiso concedido/denegado o dongle conectado/desconectado
            permissionRequested = false
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getPreferences(Context.MODE_PRIVATE)
        useExternal = prefs.getBoolean(KEY_EXTERNAL, false)

        // Base de matrículas (assets/matriculas.bin): se carga aparte para no retrasar el arranque.
        Thread {
            try {
                tracker.registrations = RegistrationDb(assets.open("matriculas.bin").use { it.readBytes() })
            } catch (_: Exception) {
            }
        }.start()

        radio = RadioReceiver(
            tracker,
            audio = audioOut,
            onAirband = { info -> runOnUiThread { air.update(info) } },
            sourceFactory = ::createSource,
        ) { st, msg ->
            linkStatus = st
            linkMessage = msg
        }

        uploader = RadarUploader(
            tracker,
            config = {
                if (prefs.getBoolean(KEY_RADAR_ON, false)) RadarUploader.parseCode(prefs.getString(KEY_RADAR_CODE, "").orEmpty()) else null
            },
            isAdsbActive = { tab == TAB_ADSB && linkStatus == RadioReceiver.Status.STREAMING },
            stationName = { "Tablet" },
            battery = ::readBattery,
        ) { text, error ->
            radarStatus = text
            radarError = error
        }

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
        }
        map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(MapBridge(), "AndroidBridge")
            // Las imágenes de satélite guardadas se sirven como https://sat.local/z/x/y.jpg
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (u.host != "sat.local") return null
                    val p = u.pathSegments
                    val f = if (p.size == 3) {
                        val z = p[0].toIntOrNull(); val x = p[1].toIntOrNull(); val y = p[2].removeSuffix(".jpg").toIntOrNull()
                        if (z != null && x != null && y != null) satellite.file(z, x, y) else null
                    } else null
                    return if (f != null && f.isFile) WebResourceResponse("image/jpeg", null, f.inputStream())
                    else WebResourceResponse("image/jpeg", null, 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            loadUrl("file:///android_asset/map/index.html")
        }
        adsbView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        air = AirbandPanel(this, radio, audioOut, prefs)

        tabAdsb = Button(this).apply { text = "ADS-B (aviones)"; setOnClickListener { selectTab(TAB_ADSB) } }
        tabAir = Button(this).apply { text = "Banda aérea (audio)"; setOnClickListener { selectTab(TAB_AIR) } }
        val serverBtn = Button(this).apply { text = "☁ Servidor"; setOnClickListener { showRadarDialog() } }
        txBtn = Button(this).apply { setOnClickListener { toggleTransmit() } }
        updateTxButton()
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabAdsb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(tabAir, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(txBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(serverBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        external = CheckBox(this).apply {
            text = "Usar un driver externo (rtl_tcp) en vez del driver USB integrado"
            textSize = 13f
            isChecked = useExternal
            setOnCheckedChangeListener { _, checked ->
                useExternal = checked
                prefs.edit().putBoolean(KEY_EXTERNAL, checked).apply()
                radio.stop()
                radio.start()
            }
        }
        val content = FrameLayout(this).apply {
            addView(adsbView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(air, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(external, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chrome, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        selectTab(TAB_ADSB)

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
    }

    /** Botón rápido: parar/reanudar el envío al servidor sin perder el código (el mapa local sigue igual). */
    private fun toggleTransmit() {
        if (prefs.getString(KEY_RADAR_CODE, "").isNullOrBlank()) {
            showRadarDialog()
            return
        }
        prefs.edit().putBoolean(KEY_RADAR_ON, !prefs.getBoolean(KEY_RADAR_ON, false)).apply()
        updateTxButton()
    }

    private fun updateTxButton() {
        txBtn.text = if (prefs.getBoolean(KEY_RADAR_ON, false)) "📡 Transmitiendo" else "🚫 Solo local"
    }

    /** Nivel de batería y si está cargando (el intent «pegajoso» de Android, sin permisos). */
    private fun readBattery(): RadarUploader.Battery? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return RadarUploader.Battery(level * 100 / scale, st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    /** Elige el origen de IQ: driver USB integrado (por defecto) o servidor rtl_tcp externo. */
    private fun createSource(): IqSource {
        if (useExternal) return RtlTcpClient()
        val device = RtlSdrDevice.find(usbManager)
            ?: throw IOException("No se ve el RTL-SDR por USB (¿cable OTG? ¿alimentación?)")
        if (!usbManager.hasPermission(device)) {
            runOnUiThread { requestUsbPermission(device) }
            throw IOException("Esperando el permiso USB…")
        }
        return UsbIqSource.open(usbManager, device) { Log.i(TAG, it) }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (permissionRequested) return
        permissionRequested = true
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, pending)
    }

    /** Ajustes del envío de aviones al servidor de swap-turnos: un único «código de conexión» que se copia desde el radar. */
    private fun showRadarDialog() {
        val code = EditText(this).apply {
            hint = "Código de 6 cifras"
            inputType = InputType.TYPE_CLASS_NUMBER
            // Para otro servidor se admite «dirección|código»; con 6 cifras se usa el servidor de siempre
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            setText(prefs.getString(KEY_RADAR_CODE, ""))
        }
        val on = CheckBox(this).apply {
            text = "Enviar los aviones al servidor"
            isChecked = prefs.getBoolean(KEY_RADAR_ON, false)
        }
        val result = TextView(this).apply { textSize = 13f; setPadding(0, 16, 0, 0) }
        val help = TextView(this).apply {
            textSize = 13f
            text = "Escribe el código de 6 cifras que elegiste en swap-turnos (Más secciones → RADAR → ☁ Tablet). " +
                "Sin ese código nadie puede enviar aviones a tu servidor."
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(help); addView(code); addView(on); addView(result)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Mapa en el servidor")
            .setView(box)
            .setPositiveButton("Guardar", null)
            .setNeutralButton("Probar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.show()
        // «Guardar» y «Probar» comprueban el código antes de seguir (y «Probar» no cierra la ventana)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = code.text.toString().trim()
            if (text.isNotEmpty() && RadarUploader.parseCode(text) == null) {
                result.text = "✖ El código son 6 cifras."
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_RADAR_CODE, text).putBoolean(KEY_RADAR_ON, on.isChecked && text.isNotEmpty()).apply()
            updateTxButton()
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cfg = RadarUploader.parseCode(code.text.toString())
            if (cfg == null) {
                result.text = "✖ Escribe primero el código de 6 cifras."
                return@setOnClickListener
            }
            result.text = "Probando…"
            Thread {
                val r = uploader.sendOnce(cfg)
                runOnUiThread {
                    result.text = if (r.ok) "✔ Conectado. El servidor ha recibido ${r.aircraft} aviones."
                    else "✖ ${r.message}"
                }
            }.start()
        }
    }

    /** Puente con el mapa web: ajustes persistentes y pantalla completa. */
    private inner class MapBridge {
        @JavascriptInterface
        fun loadSettings(): String? = prefs.getString(KEY_MAP_SETTINGS, null)

        @JavascriptInterface
        fun saveSettings(json: String) {
            prefs.edit().putString(KEY_MAP_SETTINGS, json).apply()
        }

        @JavascriptInterface
        fun satInfo(): String {
            val (n, bytes) = satellite.stored()
            return "{\"tiles\":$n,\"bytes\":$bytes,\"running\":${satellite.running},\"done\":${satellite.done}," +
                "\"total\":${satellite.total},\"failed\":${satellite.failed}}"
        }

        @JavascriptInterface
        fun satDownload(lat: Double, lon: Double) {
            if (satellite.running) return
            Thread { satellite.download(SatelliteTiles.plan(lat, lon)) }.start()
        }

        @JavascriptInterface
        fun satCancel() = satellite.cancel()

        @JavascriptInterface
        fun satClear() {
            if (!satellite.running) satellite.clear()
        }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            runOnUiThread { applyFullscreen(on) }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreen(on: Boolean) {
        fullscreen = on
        chrome.visibility = if (on) View.GONE else View.VISIBLE
        status.visibility = if (on) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // La tecla Atrás sale primero de la pantalla completa del mapa
    override fun onBackPressed() {
        if (fullscreen) {
            applyFullscreen(false)
            map.evaluateJavascript("if (window.RadarMap) RadarMap.setFullscreenState(false);", null)
        } else {
            super.onBackPressed()
        }
    }

    private fun selectTab(t: Int) {
        if (fullscreen) applyFullscreen(false)
        tab = t
        adsbView.visibility = if (t == TAB_ADSB) View.VISIBLE else View.GONE
        air.visibility = if (t == TAB_AIR) View.VISIBLE else View.GONE
        tabAdsb.isEnabled = t != TAB_ADSB
        tabAir.isEnabled = t != TAB_AIR
        // Un solo dongle: cada pestaña re-sintoniza la radio (1090 MHz o banda aérea)
        radio.mode = if (t == TAB_ADSB) Mode.ADSB else Mode.AIRBAND
        if (t == TAB_AIR) audioOut.start() else audioOut.stop()
        refresh()
    }

    // El receptor funciona solo mientras la app está en primer plano (el dongle queda libre al salir).
    override fun onResume() {
        super.onResume()
        radio.start()
        uploader.start()
        if (tab == TAB_AIR) audioOut.start()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        uploader.stop()
        radio.stop()
        audioOut.stop()
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
        val streaming = linkStatus == RadioReceiver.Status.STREAMING
        val link = when (linkStatus) {
            RadioReceiver.Status.CONNECTING -> "Conectando…"
            RadioReceiver.Status.WAITING ->
                "Sin datos: ${linkMessage ?: "sin respuesta"}" +
                    if (useExternal) ". Abre la app del driver, pulsa Start y vuelve aquí." else ""
            RadioReceiver.Status.STREAMING -> "Recibiendo por ${radio.sourceDescription}"
        }
        if (tab == TAB_AIR) {
            air.setLink("$dongle · $link", error = !streaming)
            return
        }
        var text = if (streaming) {
            val aircraft = tracker.snapshot(now)
            val kb = radio.bytesReceived.get() / 1024
            "$dongle\n$link · $kb kB · mensajes ADS-B: ${tracker.totalMessages} · " +
                "aviones: ${aircraft.size} (${aircraft.count { it.lat != null }} en el mapa)"
        } else {
            "$dongle\n$link"
        }
        if (radarStatus.isNotEmpty() && prefs.getBoolean(KEY_RADAR_ON, false)) {
            text += "\n☁ " + radarStatus
        }
        status.text = text
        status.setTextColor(if (streaming) Color.rgb(27, 94, 32) else Color.rgb(176, 0, 32))
        val lastAge = tracker.secondsSinceLastMessage(now)
        map.evaluateJavascript(
            "if (window.RadarMap) RadarMap.update(${tracker.toJson(now)}, {lastDataAge: ${lastAge ?: "null"}});", null
        )
    }

    private companion object {
        const val TAG = "RtlSdrDriver"
        const val KEY_EXTERNAL = "use_external_driver"
        const val KEY_MAP_SETTINGS = "map_settings"
        const val KEY_RADAR_CODE = "radar_code"
        const val KEY_RADAR_ON = "radar_on"
        const val ACTION_USB_PERMISSION = "com.formiguer.rtlsdr.USB_PERMISSION"
        const val TAB_ADSB = 0
        const val TAB_AIR = 1
    }
}
