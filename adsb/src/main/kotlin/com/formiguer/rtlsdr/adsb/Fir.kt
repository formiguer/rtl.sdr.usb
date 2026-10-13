package com.formiguer.rtlsdr.adsb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Fir {
    /** Paso bajo sinc con ventana Blackman, ganancia en continua = 1. */
    fun lowpass(taps: Int, cutoffHz: Double, sampleRate: Double): FloatArray {
        val d = DoubleArray(taps)
        val m = (taps - 1) / 2.0
        val fc = cutoffHz / sampleRate
        var sum = 0.0
        for (n in 0 until taps) {
            val x = n - m
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * n / (taps - 1)) + 0.08 * cos(4 * PI * n / (taps - 1))
            d[n] = sinc * w
            sum += d[n]
        }
        return FloatArray(taps) { (d[it] / sum).toFloat() }
    }
}

/** FIR complejo (I/Q reales) con diezmado: solo calcula una de cada [decim] salidas. */
class DecimatingFir(private val h: FloatArray, private val decim: Int) {
    private val n = h.size
    private val hi = FloatArray(n * 2)
    private val hq = FloatArray(n * 2)
    private var pos = 0
    private var phase = 0

    var outI = 0f
        private set
    var outQ = 0f
        private set

    fun reset() {
        hi.fill(0f); hq.fill(0f); pos = 0; phase = 0
    }

    /** Añade una muestra; devuelve true cuando hay una salida nueva en [outI]/[outQ]. */
    fun push(i: Float, q: Float): Boolean {
        hi[pos] = i; hi[pos + n] = i
        hq[pos] = q; hq[pos + n] = q
        pos++
        if (pos == n) pos = 0
        if (++phase < decim) return false
        phase = 0
        var si = 0f
        var sq = 0f
        for (k in 0 until n) {
            si += h[k] * hi[pos + k]
            sq += h[k] * hq[pos + k]
        }
        outI = si
        outQ = sq
        return true
    }
}
