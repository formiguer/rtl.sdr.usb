package com.formiguer.rtlsdr.adsb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Busca portadoras AM en una ventana de 1,2 MHz de IQ u8: espectro promediado,
 * suelo de ruido = mediana, y picos por encima de [thresholdDb] sobre ese suelo.
 */
class BandScanner(private val fs: Double = 1_200_000.0, private val n: Int = 4096) {
    class Detection(
        val channel: Int,
        val freqHz: Long,
        val snrDb: Double,
        /** Amplitud estimada de la portadora (0..1 de fondo de escala). */
        val amplitude: Double,
    )

    private val fft = Fft(n)
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val winSum = win.sum()
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val psd = DoubleArray(n)

    /** @return la portadora más fuerte (canal sin bloquear dentro de la banda) o null. */
    fun analyze(
        data: ByteArray,
        length: Int,
        centerHz: Long,
        thresholdDb: Double,
        isLocked: (Int) -> Boolean = { false },
    ): Detection? {
        val frames = (length / 2) / n
        if (frames == 0) return null
        val count = frames * n

        var mi = 0.0
        var mq = 0.0
        for (k in 0 until count) {
            mi += data[2 * k].toInt() and 0xFF
            mq += data[2 * k + 1].toInt() and 0xFF
        }
        mi /= count
        mq /= count // se resta la media: quita el pico de continua del RTL

        psd.fill(0.0)
        for (f in 0 until frames) {
            for (k in 0 until n) {
                val idx = (f * n + k) * 2
                re[k] = ((data[idx].toInt() and 0xFF) - mi) * win[k]
                im[k] = ((data[idx + 1].toInt() and 0xFF) - mq) * win[k]
            }
            fft.transform(re, im)
            for (k in 0 until n) psd[k] += re[k] * re[k] + im[k] * im[k]
        }
        for (k in 0 until n) psd[k] = psd[k] / frames

        val binHz = fs / n
        val half = (USABLE_HZ / binHz).toInt()
        val m = 2 * half + 1
        val p = DoubleArray(m) { u -> psd[(u - half + n) % n] }
        val sorted = p.copyOf().also { it.sort() }
        val median = sorted[m / 2]
        if (median <= 0.0) return null
        val threshold = median * 10.0.pow(thresholdDb / 10.0)

        var best: Detection? = null
        var bestPower = 0.0
        for (u in 1 until m - 1) {
            val off = u - half
            if (abs(off) <= 5) continue
            val v = p[u]
            if (v <= threshold || v <= bestPower) continue
            var isMax = true
            for (d in -8..8) {
                val w = u + d
                if (w in 0 until m && p[w] > v) { isMax = false; break }
            }
            if (!isMax) continue

            // interpolación parabólica sobre el logaritmo para afinar la frecuencia
            val a = ln(p[u - 1].coerceAtLeast(1e-12))
            val b = ln(v)
            val c = ln(p[u + 1].coerceAtLeast(1e-12))
            val denom = a - 2 * b + c
            val delta = if (denom != 0.0) (0.5 * (a - c) / denom).coerceIn(-1.0, 1.0) else 0.0
            val freq = centerHz + (off + delta) * binHz

            val ch = AirbandChannels.channelOfHz(freq) ?: continue
            if (isLocked(ch)) continue
            bestPower = v
            best = Detection(
                channel = ch,
                freqHz = AirbandChannels.carrierHz(ch),
                snrDb = 10 * kotlin.math.log10(v / median),
                amplitude = sqrt(v) / winSum / 128.0,
            )
        }
        return best
    }

    companion object {
        /** Mitad de la zona útil de la ventana (el borde del filtro del RTL cae después). */
        const val USABLE_HZ = 450_000.0
    }
}
