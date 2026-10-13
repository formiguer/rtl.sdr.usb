package com.formiguer.rtlsdr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.formiguer.rtlsdr.adsb.AirbandChannels
import com.formiguer.rtlsdr.adsb.AirbandInfo
import com.formiguer.rtlsdr.adsb.RadioReceiver

/** Pestaña "Banda aérea": sintonía manual 118–137 MHz, escáner, memorias, squelch y ganancia. */
class AirbandPanel(
    context: Context,
    private val radio: RadioReceiver,
    private val audio: AudioOut,
    private val prefs: SharedPreferences,
) : ScrollView(context) {

    private val freqText = TextView(context)
    private val stateText = TextView(context)
    private val linkText = TextView(context)
    private val input = EditText(context)
    private val scanBtn = Button(context)
    private val stepBtn = Button(context)
    private val squelchLabel = TextView(context)
    private val gainLabel = TextView(context)
    private val memoryRow = LinearLayout(context)
    private val activityBox = LinearLayout(context)

    private var channel = prefs.getInt(KEY_CHANNEL, AirbandChannels.parse("118.100") ?: 0)
    private var step25 = prefs.getBoolean(KEY_STEP25, true)
    private val memories = loadMemories()
    private var lastActivityMs = 0L

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        linkText.textSize = 14f
        root.addView(linkText)

        freqText.textSize = 44f
        freqText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        freqText.gravity = Gravity.CENTER
        root.addView(freqText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        stateText.textSize = 16f
        stateText.gravity = Gravity.CENTER
        root.addView(stateText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Sintonía: -1 MHz, -paso, +paso, +1 MHz
        root.addView(row(
            button("◀◀ 1 MHz") { tune(channel - CHANNELS_PER_MHZ) },
            button("◀") { tune(channel - stepChannels()) },
            button("▶") { tune(channel + stepChannels()) },
            button("1 MHz ▶▶") { tune(channel + CHANNELS_PER_MHZ) },
        ))

        input.hint = "Frecuencia (MHz)"
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setSingleLine()
        val go = button("Ir") { goToTyped() }
        root.addView(row(input, go), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        stepBtn.setOnClickListener {
            step25 = !step25
            prefs.edit().putBoolean(KEY_STEP25, step25).apply()
            refreshButtons()
        }
        scanBtn.setOnClickListener { setScan(!radio.scanning) }
        root.addView(row(stepBtn, scanBtn, button("Excluir") { radio.lockoutCurrent() }, button("Guardar") { saveMemory() }))

        squelchLabel.textSize = 14f
        root.addView(squelchLabel)
        root.addView(seek(100, prefs.getInt(KEY_SQUELCH, 0)) { p ->
            prefs.edit().putInt(KEY_SQUELCH, p).apply()
            applySquelch(p)
        })

        root.addView(label("Volumen"))
        root.addView(seek(100, prefs.getInt(KEY_VOLUME, 80)) { p ->
            prefs.edit().putInt(KEY_VOLUME, p).apply()
            audio.setVolume(p / 100f)
        })

        gainLabel.textSize = 14f
        root.addView(gainLabel)
        root.addView(seek(GAINS.size - 1, prefs.getInt(KEY_GAIN, DEFAULT_GAIN_INDEX)) { p ->
            prefs.edit().putInt(KEY_GAIN, p).apply()
            applyGain(p)
        })

        root.addView(label("Memorias (pulsación larga para borrar)"))
        val scroll = HorizontalScrollView(context)
        memoryRow.orientation = LinearLayout.HORIZONTAL
        scroll.addView(memoryRow)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(label("Actividad detectada por el escáner (toca para sintonizar)"))
        activityBox.orientation = LinearLayout.VERTICAL
        root.addView(activityBox)

        applySquelch(prefs.getInt(KEY_SQUELCH, 0))
        applyGain(prefs.getInt(KEY_GAIN, DEFAULT_GAIN_INDEX))
        audio.setVolume(prefs.getInt(KEY_VOLUME, 80) / 100f)
        radio.tuneChannel = channel
        rebuildMemories()
        refreshButtons()
        showIdle()
    }

    /** Estado del enlace con el driver rtl_tcp (lo rellena la actividad). */
    fun setLink(text: String, error: Boolean) {
        linkText.text = text
        linkText.setTextColor(if (error) Color.rgb(176, 0, 32) else Color.rgb(27, 94, 32))
    }

    /** Llamar desde el hilo de la interfaz con cada [AirbandInfo] del receptor. */
    fun update(info: AirbandInfo) {
        val dbText = if (info.levelDb <= -119) "—" else "%.0f dB".format(info.levelDb)
        when {
            info.scanning && !info.listening -> {
                val c = RadioReceiver.WINDOW_CENTERS[info.scanWindow.coerceIn(0, RadioReceiver.WINDOW_CENTERS.size - 1)]
                freqText.text = "ESCANEANDO"
                stateText.text = "%.1f–%.1f MHz".format((c - 450_000) / 1e6, (c + 450_000) / 1e6)
                stateText.setTextColor(Color.DKGRAY)
            }
            info.scanning -> {
                freqText.text = AirbandChannels.name(info.channel)
                stateText.text = "Escuchando · nivel $dbText"
                stateText.setTextColor(if (info.open) Color.rgb(27, 94, 32) else Color.DKGRAY)
            }
            else -> {
                freqText.text = AirbandChannels.name(info.channel)
                stateText.text = (if (info.open) "● SEÑAL" else "○ silencio") + " · nivel $dbText"
                stateText.setTextColor(if (info.open) Color.rgb(27, 94, 32) else Color.DKGRAY)
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastActivityMs > 1000) {
            lastActivityMs = now
            rebuildActivity(now)
        }
    }

    private fun showIdle() {
        freqText.text = AirbandChannels.name(channel)
        stateText.text = "Esperando datos del driver…"
        stateText.setTextColor(Color.DKGRAY)
    }

    private fun tune(ch: Int) {
        if (radio.scanning) setScan(false)
        channel = AirbandChannels.clamp(ch)
        radio.tuneChannel = channel
        prefs.edit().putInt(KEY_CHANNEL, channel).apply()
        freqText.text = AirbandChannels.name(channel)
    }

    private fun setScan(on: Boolean) {
        radio.setScanning(on)
        if (!on) {
            channel = radio.tuneChannel
            prefs.edit().putInt(KEY_CHANNEL, channel).apply()
            freqText.text = AirbandChannels.name(channel)
        }
        refreshButtons()
    }

    private fun goToTyped() {
        val ch = AirbandChannels.parse(input.text.toString())
        if (ch == null) {
            Toast.makeText(context, "Frecuencia fuera de 118.000–136.990 MHz", Toast.LENGTH_SHORT).show()
            return
        }
        tune(ch)
        input.setText("")
    }

    private fun saveMemory() {
        val ch = if (radio.scanning) radio.heardChannel else channel
        if (ch !in memories) {
            memories.add(ch)
            memories.sort()
            prefs.edit().putString(KEY_MEMORIES, memories.joinToString(",")).apply()
            rebuildMemories()
        }
    }

    private fun rebuildMemories() {
        memoryRow.removeAllViews()
        for (ch in memories) {
            val b = Button(context)
            b.text = AirbandChannels.name(ch)
            b.setOnClickListener { tune(ch) }
            b.setOnLongClickListener {
                memories.remove(ch)
                prefs.edit().putString(KEY_MEMORIES, memories.joinToString(",")).apply()
                rebuildMemories()
                true
            }
            memoryRow.addView(b)
        }
    }

    private fun rebuildActivity(now: Long) {
        activityBox.removeAllViews()
        val items = radio.scanLog.recent(8)
        if (items.isEmpty()) {
            activityBox.addView(TextView(context).apply { text = "(nada todavía — activa el escáner)"; textSize = 14f })
            return
        }
        for (e in items) {
            val tv = TextView(context)
            tv.textSize = 18f
            tv.setPadding(0, dp(6), 0, dp(6))
            tv.text = "${AirbandChannels.name(e.channel)} MHz · ${e.count}× · hace ${(now - e.lastMs) / 1000} s"
            tv.setOnClickListener { tune(e.channel) }
            activityBox.addView(tv)
        }
    }

    private fun refreshButtons() {
        stepBtn.text = if (step25) "Paso 25 kHz" else "Paso 8,33 kHz"
        scanBtn.text = if (radio.scanning) "Escáner: ON" else "Escáner: OFF"
    }

    private fun applySquelch(progress: Int) {
        if (progress == 0) {
            radio.squelchDb = Double.NEGATIVE_INFINITY
            squelchLabel.text = "Squelch: desactivado"
        } else {
            val db = -70.0 + progress * 0.6
            radio.squelchDb = db
            squelchLabel.text = "Squelch: %.0f dB (se abre cuando el nivel lo supera)".format(db)
        }
    }

    private fun applyGain(index: Int) {
        val g = GAINS[index.coerceIn(0, GAINS.size - 1)]
        radio.gainTenths = g
        gainLabel.text = "Ganancia: %.1f dB".format(g / 10.0)
    }

    private fun stepChannels() = if (step25) 3 else 1

    private fun loadMemories(): MutableList<Int> =
        (prefs.getString(KEY_MEMORIES, "") ?: "").split(',').mapNotNull { it.toIntOrNull() }
            .filter { it in AirbandChannels.MIN_CHANNEL..AirbandChannels.MAX_CHANNEL }.toMutableList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(12), 0, 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun seek(maxValue: Int, initial: Int, onChange: (Int) -> Unit) = SeekBar(context).apply {
        max = maxValue
        progress = initial
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private companion object {
        const val KEY_CHANNEL = "air_channel"
        const val KEY_STEP25 = "air_step25"
        const val KEY_SQUELCH = "air_squelch"
        const val KEY_VOLUME = "air_volume"
        const val KEY_GAIN = "air_gain"
        const val KEY_MEMORIES = "air_memories"
        const val CHANNELS_PER_MHZ = 120

        /** Pasos de ganancia del R820T en décimas de dB. */
        val GAINS = intArrayOf(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
            280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
        )
        const val DEFAULT_GAIN_INDEX = 22 // 40,2 dB
    }
}
