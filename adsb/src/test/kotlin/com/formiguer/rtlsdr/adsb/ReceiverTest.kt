package com.formiguer.rtlsdr.adsb

import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Servidor rtl_tcp falso: lee comandos, guarda la sintonía y emite IQ acorde (con ritmo). */
class FakeRtlTcp(
    private val carriers: List<Double>, // frecuencias absolutas (Hz) de portadoras AM de 1 kHz
    private val amp: Double = 0.25,
    private val adsbIq: ByteArray? = null,
    private val paceMs: Long = 3,
) {
    val server = ServerSocket(0)
    val commands = CopyOnWriteArrayList<Pair<Int, Int>>()
    @Volatile var freq = 100_000_000
    @Volatile var rate = 2_000_000
    @Volatile private var alive = true

    fun start() {
        Thread {
            try {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    out.write(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte()))
                    out.write(ByteArray(8))
                    Thread {
                        try {
                            val inp = DataInputStream(s.getInputStream())
                            while (alive) {
                                val c = inp.readUnsignedByte()
                                val p = inp.readInt()
                                commands.add(c to p)
                                if (c == RtlTcpClient.CMD_FREQUENCY) freq = p
                                if (c == RtlTcpClient.CMD_SAMPLE_RATE) rate = p
                            }
                        } catch (_: Exception) {
                        }
                    }.also { it.isDaemon = true }.start()
                    var n = 0L
                    val samples = RtlTcpClient.BLOCK / 2
                    while (alive) {
                        val block = if (rate == AmChannel.SAMPLE_RATE) {
                            val cs = carriers.map { AmSynth.Carrier(it - freq, amp) }
                                .filter { abs(it.offsetHz) < 550_000 }
                            AmSynth.iq(samples, cs, start = n, seed = n)
                        } else {
                            adsbIq?.copyOf(RtlTcpClient.BLOCK) ?: ByteArray(RtlTcpClient.BLOCK) { 127 }
                        }
                        n += samples
                        out.write(block)
                        out.flush()
                        if (paceMs > 0) Thread.sleep(paceMs)
                    }
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        alive = false
        try { server.close() } catch (_: Exception) {}
    }
}

class CollectingSink : AudioSink {
    val samples = ArrayList<Short>()
    override fun write(pcm: ShortArray, count: Int) {
        synchronized(samples) { for (k in 0 until count) samples.add(pcm[k]) }
    }

    fun snapshot(): ShortArray = synchronized(samples) { samples.toShortArray() }

    fun tone(freq: Double, lastSamples: Int = 4800): Double {
        val x = snapshot()
        if (x.size < lastSamples) return 0.0
        val w = 2 * PI * freq / 48000.0
        val coeff = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (k in x.size - lastSamples until x.size) {
            val s0 = x[k] / 32768.0 + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) * 2 / lastSamples
    }
}

class ReceiverTest {
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
    }

    @Test
    fun endToEndAdsbWithFakeRtlTcpServer() {
        val iq = Synth.iq(
            listOf(hex("8D4840D6202CC371C32CE0576098"), hex("8D40621D58C382D690C8AC2863A7"),
                hex("8D40621D58C386435CC412692AD6")), gap = 400
        )
        val padded = iq.copyOf(((iq.size / RtlTcpClient.BLOCK) + 1) * RtlTcpClient.BLOCK)
        val fake = FakeRtlTcp(emptyList(), adsbIq = padded, paceMs = 0)
        fake.start()

        val tracker = AircraftTracker()
        val statuses = CopyOnWriteArrayList<RadioReceiver.Status>()
        val rx = RadioReceiver(tracker, "127.0.0.1", fake.server.localPort) { st, _ -> statuses.add(st) }
        rx.start()
        val ok = waitFor(5000) { tracker.snapshot(System.currentTimeMillis()).any { it.lat != null } }
        rx.stop(); fake.stop()

        assertTrue(ok, "no se decodificó ninguna posición")
        assertEquals(
            listOf(
                RtlTcpClient.CMD_SAMPLE_RATE to 2_000_000, RtlTcpClient.CMD_FREQUENCY to 1_090_000_000,
                RtlTcpClient.CMD_GAIN_MODE to 1, RtlTcpClient.CMD_GAIN to 496, RtlTcpClient.CMD_AGC_MODE to 0,
            ),
            fake.commands.take(5)
        )
        assertTrue(RadioReceiver.Status.STREAMING in statuses)
        val ac = tracker.snapshot(System.currentTimeMillis()).first { it.hex == "40621D" }
        assertEquals(38000, ac.altitudeFt)
    }

    @Test
    fun reportsWaitingWhenNoServer() {
        val port = ServerSocket(0).use { it.localPort } // puerto libre y cerrado
        val statuses = CopyOnWriteArrayList<RadioReceiver.Status>()
        val rx = RadioReceiver(AircraftTracker(), "127.0.0.1", port) { st, _ -> statuses.add(st) }
        rx.start()
        val ok = waitFor(4000) { RadioReceiver.Status.WAITING in statuses }
        rx.stop()
        assertTrue(ok)
    }

    @Test
    fun airbandManualTuneProducesAudio() {
        val ch = AirbandChannels.parse("118.100")!!
        val carrier = AirbandChannels.carrierHz(ch).toDouble()
        val fake = FakeRtlTcp(listOf(carrier), paceMs = 2)
        fake.start()
        val sink = CollectingSink()
        val rx = RadioReceiver(AircraftTracker(), "127.0.0.1", fake.server.localPort, audio = sink, settleMs = 20) { _, _ -> }
        rx.mode = Mode.AIRBAND
        rx.tuneChannel = ch
        rx.start()
        val ok = waitFor(6000) { sink.snapshot().size > 24000 }
        val tone = sink.tone(1000.0)
        rx.stop(); fake.stop()

        assertTrue(ok, "no llegó audio")
        println("tono 1 kHz en el audio recibido: $tone")
        assertTrue(tone > 0.3, "tono=$tone")
        assertTrue(RtlTcpClient.CMD_SAMPLE_RATE to 1_200_000 in fake.commands)
        assertTrue(RtlTcpClient.CMD_FREQUENCY to (carrier.toLong() + AmChannel.OFFSET_HZ).toInt() in fake.commands)
    }

    @Test
    fun airbandSquelchMutesWhenNoCarrier() {
        val ch = AirbandChannels.parse("120.500")!!
        val fake = FakeRtlTcp(emptyList(), paceMs = 2) // solo ruido
        fake.start()
        val sink = CollectingSink()
        val rx = RadioReceiver(AircraftTracker(), "127.0.0.1", fake.server.localPort, audio = sink, settleMs = 20) { _, _ -> }
        rx.mode = Mode.AIRBAND
        rx.tuneChannel = ch
        rx.squelchDb = -30.0 // el ruido está muy por debajo
        rx.start()
        val ok = waitFor(6000) { sink.snapshot().size > 24000 }
        val peak = sink.snapshot().takeLast(24000).maxOfOrNull { abs(it.toInt()) } ?: 0
        rx.stop(); fake.stop()
        assertTrue(ok)
        assertEquals(0, peak)
    }

    @Test
    fun scannerFindsAndListensToCarrierAnywhereInBand() {
        val ch = AirbandChannels.parse("125.450")!!
        val carrier = AirbandChannels.carrierHz(ch).toDouble()
        val fake = FakeRtlTcp(listOf(carrier), paceMs = 3)
        fake.start()
        val sink = CollectingSink()
        val infos = CopyOnWriteArrayList<AirbandInfo>()
        val rx = RadioReceiver(
            AircraftTracker(), "127.0.0.1", fake.server.localPort, audio = sink,
            onAirband = { infos.add(it) }, settleMs = 40,
        ) { _, _ -> }
        rx.mode = Mode.AIRBAND
        rx.setScanning(true)
        rx.start()
        val found = waitFor(20000) { infos.any { it.listening && it.channel == ch } }
        val heardAudio = waitFor(3000) { sink.tone(1000.0, 2400) > 0.3 }
        val logged = rx.scanLog.recent(5).map { it.channel }
        rx.stop(); fake.stop()

        assertTrue(found, "el escáner no llegó a ${AirbandChannels.name(ch)}; vio ${infos.map { it.scanWindow }.distinct()}")
        assertTrue(heardAudio, "sin audio tras detectar")
        assertTrue(ch in logged)
    }

    @Test
    fun scannerLockoutSkipsChannelAndFindsNext() {
        val ch1 = AirbandChannels.parse("121.700")!!
        val ch2 = AirbandChannels.parse("127.300")!!
        val fake = FakeRtlTcp(listOf(AirbandChannels.carrierHz(ch1).toDouble(), AirbandChannels.carrierHz(ch2).toDouble()), paceMs = 3)
        fake.start()
        val infos = CopyOnWriteArrayList<AirbandInfo>()
        val rx = RadioReceiver(AircraftTracker(), "127.0.0.1", fake.server.localPort,
            onAirband = { infos.add(it) }, settleMs = 40) { _, _ -> }
        rx.mode = Mode.AIRBAND
        rx.setScanning(true)
        rx.start()
        assertTrue(waitFor(20000) { infos.any { it.listening } }, "no encontró nada")
        val first = infos.first { it.listening }.channel
        rx.lockoutCurrent()
        val other = if (first == ch1) ch2 else ch1
        val ok = waitFor(25000) { infos.any { it.listening && it.channel == other } }
        rx.stop(); fake.stop()
        assertTrue(ok, "no pasó al otro canal")
        assertTrue(first in rx.lockedChannels)
    }
}
