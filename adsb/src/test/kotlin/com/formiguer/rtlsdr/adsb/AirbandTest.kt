package com.formiguer.rtlsdr.adsb

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Genera IQ u8 a 1,2 Msps con portadoras AM en las frecuencias (offset respecto al centro del RTL). */
object AmSynth {
    class Carrier(val offsetHz: Double, val amp: Double, val toneHz: Double = 1000.0, val depth: Double = 0.5)

    fun iq(samples: Int, carriers: List<Carrier>, noise: Double = 0.01, dc: Double = 0.0, seed: Long = 5, start: Long = 0): ByteArray {
        val fs = 1_200_000.0
        val rnd = Random(seed)
        val out = ByteArray(samples * 2)
        for (idx in 0 until samples) {
            val n = (start + idx).toDouble()
            var i = dc
            var q = dc
            for (c in carriers) {
                val env = c.amp * (1 + c.depth * cos(2 * PI * c.toneHz * n / fs))
                val ph = 2 * PI * c.offsetHz * n / fs
                i += env * cos(ph)
                q += env * sin(ph)
            }
            i += rnd.nextGaussian() * noise
            q += rnd.nextGaussian() * noise
            out[2 * idx] = (127.5 + 127.0 * i.coerceIn(-1.0, 1.0)).toInt().toByte()
            out[2 * idx + 1] = (127.5 + 127.0 * q.coerceIn(-1.0, 1.0)).toInt().toByte()
        }
        return out
    }
}

class AirbandTest {
    private fun goertzel(x: ShortArray, from: Int, to: Int, freq: Double, fs: Double = 48000.0): Double {
        val w = 2 * PI * freq / fs
        val coeff = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (k in from until to) {
            val s0 = x[k] / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        val p = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return sqrt(p) * 2 / (to - from)
    }

    @Test
    fun channelNamesAndCarriers() {
        assertEquals("118.000", AirbandChannels.name(0))
        assertEquals("118.010", AirbandChannels.name(1))
        assertEquals("118.015", AirbandChannels.name(2))
        assertEquals("118.025", AirbandChannels.name(3))
        assertEquals("136.975", AirbandChannels.name(2277))
        assertEquals("136.990", AirbandChannels.name(AirbandChannels.MAX_CHANNEL))
        assertEquals(118_000_000L, AirbandChannels.carrierHz(0))
        assertEquals(118_008_333L, AirbandChannels.carrierHz(1))
        assertEquals(118_025_000L, AirbandChannels.carrierHz(3))
        assertEquals(136_975_000L, AirbandChannels.carrierHz(2277))
    }

    @Test
    fun channelParsing() {
        assertEquals(0, AirbandChannels.parse("118.000"))
        assertEquals(0, AirbandChannels.parse("118.005"))
        assertEquals(1, AirbandChannels.parse("118,010"))
        assertEquals(2, AirbandChannels.parse("118.015"))
        assertEquals(AirbandChannels.parse("118.100"), AirbandChannels.parse("118100"))
        assertEquals(AirbandChannels.parse("123.45"), AirbandChannels.parse("123.450 MHz"))
        assertEquals(null, AirbandChannels.parse("117.9"))
        assertEquals(null, AirbandChannels.parse("140.0"))
        assertEquals(null, AirbandChannels.parse("hola"))
        for (ch in listOf(0, 1, 2, 3, 100, 1234, 2277, 2279)) {
            assertEquals(ch, AirbandChannels.parse(AirbandChannels.name(ch)), "ch=$ch")
        }
        // 25 kHz: cada tres canales de 8,33 kHz
        assertEquals(AirbandChannels.parse("121.500"), AirbandChannels.parse("121.5"))
        assertEquals(0, (AirbandChannels.carrierHz(AirbandChannels.parse("121.500")!!) - 121_500_000L).toInt())
    }

    @Test
    fun amChannelRecoversTone() {
        val am = AmChannel()
        // portadora en -200 kHz (el RTL está 200 kHz por encima), tono 1 kHz al 50 %
        val iq = AmSynth.iq(240_000, listOf(AmSynth.Carrier(-200_000.0, 0.3)))
        am.process(iq, iq.size)
        val n = am.audioLen
        assertTrue(abs(n - 9600) <= 5, "muestras de audio: $n")
        val tone = goertzel(am.audio, n / 2, n, 1000.0)
        val other = goertzel(am.audio, n / 2, n, 2500.0)
        println("tono 1k=$tone  2.5k=$other  nivel=${am.level}")
        assertEquals(0.45, tone, 0.1)   // 0,5 de modulación × 0,9
        assertTrue(other < tone / 30, "ruido en otras frecuencias")
        assertEquals(0.3, am.level, 0.05)
    }

    @Test
    fun amChannelRejectsAdjacentChannels() {
        val am = AmChannel()
        val iq = AmSynth.iq(
            240_000,
            listOf(
                AmSynth.Carrier(-200_000.0, 0.06, 1000.0),
                AmSynth.Carrier(-200_000.0 + 25_000.0, 0.2, 2000.0), // vecinos 10 dB más fuertes
                AmSynth.Carrier(-200_000.0 - 25_000.0, 0.2, 3000.0),
                AmSynth.Carrier(-200_000.0 + 8_333.0, 0.0, 2200.0),
            ),
        )
        am.process(iq, iq.size)
        val n = am.audioLen
        val wanted = goertzel(am.audio, n / 2, n, 1000.0)
        val leak2 = goertzel(am.audio, n / 2, n, 2000.0)
        val leak3 = goertzel(am.audio, n / 2, n, 3000.0)
        println("deseado=$wanted vecino+25k=$leak2 vecino-25k=$leak3")
        assertTrue(leak2 < wanted / 30 && leak3 < wanted / 30)
    }

    @Test
    fun amChannelNoiseOnlyHasLowLevel() {
        val am = AmChannel()
        val iq = AmSynth.iq(120_000, emptyList(), noise = 0.02)
        am.process(iq, iq.size)
        println("nivel de ruido=${am.level}")
        assertTrue(am.level < 0.05)
    }

    @Test
    fun amChannelContinuesAcrossBlocks() {
        val iq = AmSynth.iq(240_000, listOf(AmSynth.Carrier(-200_000.0, 0.3)))
        val one = AmChannel().also { it.process(iq, iq.size) }
        val whole = one.audio.copyOf(one.audioLen)
        val chunked = AmChannel()
        val parts = ArrayList<Short>()
        var off = 0
        while (off < iq.size) {
            val len = minOf(32768, iq.size - off)
            chunked.process(iq.copyOfRange(off, off + len), len)
            for (k in 0 until chunked.audioLen) parts.add(chunked.audio[k])
            off += len
        }
        assertTrue(abs(whole.size - parts.size) <= 3)
        val tone = goertzel(parts.toShortArray(), parts.size / 2, parts.size, 1000.0)
        assertEquals(0.45, tone, 0.1)
    }

    @Test
    fun scannerFindsCarrierAndSnapsToChannel() {
        val center = 124_000_000L
        // portadora en 124.1250 MHz → canal 8,33 kHz más cercano (124.125 = 25 kHz)
        val off = 124_125_000.0 - center
        val iq = AmSynth.iq(16384, listOf(AmSynth.Carrier(off, 0.2)), noise = 0.01, dc = 0.03)
        val det = assertNotNull(BandScanner().analyze(iq, iq.size, center, 12.0))
        println("detección: ${AirbandChannels.name(det.channel)} snr=${det.snrDb} amp=${det.amplitude}")
        assertEquals(124_125_000L, det.freqHz)
        assertEquals(0.2, det.amplitude, 0.08)
        assertTrue(det.snrDb > 20)
    }

    @Test
    fun scannerPicksStrongestAndHonoursLockout() {
        val center = 131_000_000L
        val f1 = AirbandChannels.carrierHz(AirbandChannels.parse("131.300")!!)
        val f2 = AirbandChannels.carrierHz(AirbandChannels.parse("130.800")!!)
        val iq = AmSynth.iq(
            16384,
            listOf(AmSynth.Carrier((f1 - center).toDouble(), 0.3), AmSynth.Carrier((f2 - center).toDouble(), 0.1)),
        )
        val scanner = BandScanner()
        val first = assertNotNull(scanner.analyze(iq, iq.size, center, 12.0))
        assertEquals(f1, first.freqHz)
        val second = assertNotNull(scanner.analyze(iq, iq.size, center, 12.0) { it == first.channel })
        assertEquals(f2, second.freqHz)
    }

    @Test
    fun scannerIgnoresNoiseAndDcSpike() {
        val iq = AmSynth.iq(16384, emptyList(), noise = 0.02, dc = 0.1)
        assertEquals(null, BandScanner().analyze(iq, iq.size, 125_000_000L, 12.0))
    }

    @Test
    fun scannerResolves8_33kHzNeighbours() {
        val center = 120_000_000L
        val ch = AirbandChannels.parse("120.015")!! // portadora 120.016667
        val carrier = AirbandChannels.carrierHz(ch)
        val iq = AmSynth.iq(16384, listOf(AmSynth.Carrier((carrier - center).toDouble(), 0.2)))
        val det = assertNotNull(BandScanner().analyze(iq, iq.size, center, 12.0))
        assertEquals(ch, det.channel)
    }

    @Test
    fun scanLogOrdersByRecency() {
        val log = ScanLog()
        log.record(5, 1000); log.record(7, 2000); log.record(5, 3000)
        val r = log.recent(10)
        assertEquals(listOf(5, 7), r.map { it.channel })
        assertEquals(2, r[0].count)
    }
}
