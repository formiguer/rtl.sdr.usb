package com.formiguer.rtlsdr.adsb

import com.formiguer.rtlsdr.driver.RtlSdrDriver
import com.formiguer.rtlsdr.driver.UsbIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Registra las transferencias de control y responde como un RTL2832U con R820T2 (igual que tools/ref-transcript). */
class RecorderIo : UsbIo {
    val lines = ArrayList<String>()
    private val desired = intArrayOf(0x96, 0x00, 0x50, 0x00, 0x15, 0x00, 0x00, 0x00)

    private fun bitrev(b: Int): Int {
        var r = 0
        for (i in 0 until 8) if ((b and (1 shl i)) != 0) r = r or (1 shl (7 - i))
        return r
    }

    override fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, length: Int, timeoutMs: Int,
    ): Int {
        val head = "rt=%02x req=%d val=%04x idx=%04x len=%d".format(requestType, request, value and 0xFFFF, index and 0xFFFF, length)
        if ((requestType and 0x80) != 0) {
            java.util.Arrays.fill(data, 0, length, 0)
            if ((index shr 8) == 6 && value == 0x34) {
                for (i in 0 until minOf(length, 8)) data[i] = bitrev(desired[i]).toByte()
            }
            lines.add("IN  $head ->" + (0 until length).joinToString("") { " %02x".format(data[it].toInt() and 0xFF) })
        } else {
            lines.add("OUT $head <-" + (0 until length).joinToString("") { " %02x".format(data[it].toInt() and 0xFF) })
        }
        return length
    }

    override fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int = 0
}

class DriverTranscriptTest {
    @Test
    fun kotlinDriverMatchesLibrtlsdrByteForByte() {
        val io = RecorderIo()
        val d = RtlSdrDriver(io)
        d.open()
        io.lines.add("--- tuner=5")
        fun step(title: String, f: () -> Unit) { io.lines.add("--- $title"); f() }
        step("set_sample_rate 2000000") { d.setSampleRate(2_000_000) }
        step("gain mode manual") { d.setTunerGainMode(true) }
        step("gain 496") { d.setTunerGain(496) }
        step("agc off") { d.setAgcMode(false) }
        step("center freq 1090000000") { d.setCenterFreq(1_090_000_000) }
        step("reset buffer") { d.resetBuffer() }
        step("airband: rate 1200000") { d.setSampleRate(1_200_000) }
        step("gain 402") { d.setTunerGain(402) }
        step("freq 118300000") { d.setCenterFreq(118_300_000) }
        step("freq 131725000") { d.setCenterFreq(131_725_000) }
        step("freq 136450000") { d.setCenterFreq(136_450_000) }
        step("gain 0") { d.setTunerGain(0) }
        step("gain mode auto") { d.setTunerGainMode(false) }
        step("close") { d.close() }

        val expected = javaClass.getResourceAsStream("/librtlsdr_r820t_transcript.txt")!!
            .bufferedReader().readLines()
        val actual = io.lines
        val n = minOf(expected.size, actual.size)
        for (i in 0 until n) {
            if (expected[i] != actual[i]) {
                val ctx = (maxOf(0, i - 3)..minOf(n - 1, i + 1)).joinToString("\n") { k ->
                    (if (k == i) ">> " else "   ") + "#$k esperado: ${expected[k]}\n      " + "   obtenido: ${actual[k]}"
                }
                throw AssertionError("Primera diferencia en la línea $i:\n$ctx")
            }
        }
        assertEquals(expected.size, actual.size, "número de transferencias")
        assertTrue(expected.size > 300)
    }

    @Test
    fun rejectsUnsupportedSampleRates() {
        val d = RtlSdrDriver(RecorderIo())
        d.open()
        assertTrue(d.setSampleRate(100_000) != 0)
        assertTrue(d.setSampleRate(500_000) != 0)
        assertTrue(d.setSampleRate(4_000_000) != 0)
        assertEquals(0, d.setSampleRate(1_200_000))
        assertTrue(d.sampleRate in 1_199_990..1_200_010)
    }
}
