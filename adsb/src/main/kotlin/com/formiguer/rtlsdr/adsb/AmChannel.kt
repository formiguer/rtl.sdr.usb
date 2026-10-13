package com.formiguer.rtlsdr.adsb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Receptor AM de un canal. Entrada: IQ u8 a 1,2 Msps con el RTL sintonizado
 * [OFFSET_HZ] por encima de la portadora (evita el pico de continua del dongle).
 * Salida: audio PCM 16 bits mono a 48 kHz en [audio] (primeras [audioLen] muestras).
 */
class AmChannel {
    var audio = ShortArray(0)
        private set
    var audioLen = 0
        private set

    /** Amplitud media de la portadora (0..1 de fondo de escala). */
    var level = 0.0
        private set

    private val cosT = FloatArray(6) { cos(2 * PI * it / 6).toFloat() }
    private val sinT = FloatArray(6) { sin(2 * PI * it / 6).toFloat() }
    private val stage1 = DecimatingFir(Fir.lowpass(63, 60_000.0, SAMPLE_RATE.toDouble()), 5)
    private val stage2 = DecimatingFir(Fir.lowpass(255, 5_000.0, SAMPLE_RATE / 5.0), 5)
    private var phase = 0
    private var avg = 0f
    private var primed = false

    fun reset() {
        stage1.reset(); stage2.reset()
        phase = 0; avg = 0f; primed = false; level = 0.0; audioLen = 0
    }

    /** Procesa [length] bytes de IQ (par). */
    fun process(data: ByteArray, length: Int) {
        val n = length / 2
        val maxOut = n / 25 + 2
        if (audio.size < maxOut) audio = ShortArray(maxOut)
        var out = 0
        var p = 0
        for (k in 0 until n) {
            val i = ((data[p].toInt() and 0xFF) - 127.5f) / 128f
            val q = ((data[p + 1].toInt() and 0xFF) - 127.5f) / 128f
            p += 2
            val c = cosT[phase]
            val s = sinT[phase]
            phase = if (phase == 5) 0 else phase + 1
            // la señal está en -OFFSET: multiplicar por e^{+j·2π·n/6} la lleva a 0 Hz
            if (!stage1.push(i * c - q * s, i * s + q * c)) continue
            if (!stage2.push(stage1.outI, stage1.outQ)) continue
            val e = sqrt(stage2.outI * stage2.outI + stage2.outQ * stage2.outQ)
            if (!primed) {
                avg = e
                primed = true
            }
            // detector de portadora simétrico (sin sesgo por la modulación), con ataque
            // inmediato cuando la señal sube de golpe (empieza una transmisión)
            if (e > 2.5f * avg) avg = e else avg += (e - avg) * 0.0005f
            val ref = if (avg < 1e-4f) 1e-4f else avg
            var v = (e - avg) / ref * 0.9f
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            audio[out++] = (v * 32767f).toInt().toShort()
        }
        audioLen = out
        level = avg.toDouble()
    }

    companion object {
        const val SAMPLE_RATE = 1_200_000
        const val OFFSET_HZ = 200_000
        const val AUDIO_RATE = 48_000
    }
}
