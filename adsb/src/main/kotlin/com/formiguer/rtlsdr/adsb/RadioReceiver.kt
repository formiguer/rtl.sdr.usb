package com.formiguer.rtlsdr.adsb

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10

/** Destino del audio demodulado (PCM 16 bits mono a 48 kHz). */
interface AudioSink {
    fun write(pcm: ShortArray, count: Int)
}

enum class Mode { ADSB, AIRBAND }

/** Estado de la pestaña de banda aérea (se publica unas 4 veces por segundo). */
class AirbandInfo(
    val channel: Int,
    val freqHz: Long,
    val levelDb: Double,
    val open: Boolean,
    val scanning: Boolean,
    val listening: Boolean,
    val scanWindow: Int,
)

/**
 * Un único hilo que conecta con rtl_tcp y, según [mode], decodifica ADS-B (1090 MHz) o recibe
 * banda aérea AM (118–137 MHz): sintonía manual o escáner. Reconecta solo.
 */
class RadioReceiver(
    private val tracker: AircraftTracker,
    private val host: String = "127.0.0.1",
    private val port: Int = 1234,
    private val audio: AudioSink? = null,
    private val onAirband: ((AirbandInfo) -> Unit)? = null,
    private val settleMs: Int = 200,
    private val onStatus: (Status, String?) -> Unit,
) {
    enum class Status { CONNECTING, STREAMING, WAITING }

    val bytesReceived = AtomicLong()

    @Volatile var mode = Mode.ADSB
    @Volatile var tuneChannel = 0
    @Volatile var squelchDb = Double.NEGATIVE_INFINITY
    @Volatile var gainTenths = 402
    @Volatile var scanning = false
        private set
    @Volatile var heardChannel = 0
        private set

    val lockedChannels: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val scanLog = ScanLog()

    @Volatile private var skipRequested = false
    @Volatile private var running = false
    @Volatile private var client: RtlTcpClient? = null
    private var thread: Thread? = null

    fun setScanning(on: Boolean) {
        if (!on && scanning) tuneChannel = heardChannel // el escáner se queda donde estaba
        scanning = on
    }

    /** Bloquea el canal que se está escuchando y sigue buscando. */
    fun lockoutCurrent() {
        lockedChannels.add(heardChannel)
        skipRequested = true
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "radio-receiver").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        client?.close()
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            val c = RtlTcpClient(host, port)
            client = c
            val session = Session(c)
            var streaming = false
            onStatus(Status.CONNECTING, null)
            try {
                c.run({ running }, { session.syncConfig() }) { buf, n ->
                    if (!streaming) {
                        streaming = true
                        onStatus(Status.STREAMING, null)
                    }
                    bytesReceived.addAndGet(n.toLong())
                    session.onBlock(buf, n)
                }
            } catch (e: Exception) {
                if (running) onStatus(Status.WAITING, e.message ?: e.javaClass.simpleName)
            }
            if (!running) break
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private inner class Session(private val client: RtlTcpClient) {
        private var adsb = newAdsb()
        private val am = AmChannel()
        private val scanner = BandScanner()

        private var curMode: Mode? = null
        private var curScanning = false
        private var curChannel = -1
        private var curGain = -1
        private var discard = 0

        private var scanIdx = 0
        private var listening = false
        private var refLevel = 0.0
        private var lastCarrier = 0L
        private var squelchOpenUntil = 0L
        private var lastInfo = 0L

        private fun newAdsb() = Demodulator { tracker.onMessage(it, System.currentTimeMillis()) }

        private fun settleBytes(sampleRate: Int) = (sampleRate.toLong() * 2 * settleMs / 1000).toInt()

        fun onBlock(buf: ByteArray, n: Int) {
            syncConfig()
            if (discard > 0) {
                discard -= n
                return
            }
            when (curMode) {
                Mode.ADSB -> adsb.process(buf, n)
                Mode.AIRBAND -> airband(buf, n)
                null -> {}
            }
        }

        fun syncConfig() {
            val m = mode
            if (m != curMode) {
                curMode = m
                when (m) {
                    Mode.ADSB -> {
                        client.setSampleRate(2_000_000)
                        client.setFrequency(1_090_000_000)
                        client.setGainMode(true)
                        client.setGain(496)
                        client.setAgcMode(false)
                        adsb = newAdsb()
                        discard = settleBytes(2_000_000)
                    }
                    Mode.AIRBAND -> {
                        client.setSampleRate(AmChannel.SAMPLE_RATE)
                        client.setGainMode(true)
                        client.setGain(gainTenths)
                        curGain = gainTenths
                        curChannel = -1
                        curScanning = !scanning // fuerza la transición de abajo
                        listening = false
                        am.reset()
                        discard = settleBytes(AmChannel.SAMPLE_RATE)
                    }
                }
            }
            if (m == Mode.AIRBAND) {
                val g = gainTenths
                if (g != curGain) {
                    client.setGain(g)
                    curGain = g
                }
                val sc = scanning
                if (sc != curScanning) {
                    curScanning = sc
                    listening = false
                    if (sc) {
                        scanIdx = 0
                        tuneWindow()
                    } else {
                        retune(tuneChannel)
                    }
                } else if (!sc && tuneChannel != curChannel) {
                    retune(tuneChannel)
                }
            }
        }

        private fun retune(channel: Int) {
            val ch = AirbandChannels.clamp(channel)
            client.setFrequency((AirbandChannels.carrierHz(ch) + AmChannel.OFFSET_HZ).toInt())
            curChannel = ch
            heardChannel = ch
            am.reset()
            refLevel = 0.0
            discard = settleBytes(AmChannel.SAMPLE_RATE)
        }

        private fun tuneWindow() {
            client.setFrequency(WINDOW_CENTERS[scanIdx].toInt())
            discard = settleBytes(AmChannel.SAMPLE_RATE)
        }

        private fun levelDb(level: Double) = 20 * log10(level.coerceAtLeast(1e-6))

        private fun airband(buf: ByteArray, n: Int) {
            val now = System.currentTimeMillis()
            var open = false
            if (!curScanning) {
                skipRequested = false
                am.process(buf, n)
                val sq = squelchDb
                if (sq == Double.NEGATIVE_INFINITY || levelDb(am.level) >= sq) squelchOpenUntil = now + 250
                open = now < squelchOpenUntil
                emitAudio(open)
            } else if (listening) {
                am.process(buf, n)
                val present = am.level > 0.5 * refLevel
                if (present) lastCarrier = now
                if (skipRequested || now - lastCarrier > HOLD_MS) {
                    skipRequested = false
                    listening = false
                    tuneWindow()
                }
                open = present
                emitAudio(open)
            } else {
                skipRequested = false
                val det = scanner.analyze(buf, n, WINDOW_CENTERS[scanIdx], SCAN_THRESHOLD_DB) { it in lockedChannels }
                if (det != null) {
                    scanLog.record(det.channel, now)
                    listening = true
                    lastCarrier = now
                    retune(det.channel)
                    refLevel = det.amplitude
                } else {
                    scanIdx = (scanIdx + 1) % WINDOW_CENTERS.size
                    tuneWindow()
                }
            }
            if (now - lastInfo >= 250) {
                lastInfo = now
                val ch = if (curChannel >= 0) curChannel else tuneChannel
                onAirband?.invoke(
                    AirbandInfo(
                        ch, AirbandChannels.carrierHz(ch), levelDb(am.level), open,
                        curScanning, listening, scanIdx,
                    )
                )
            }
        }

        private fun emitAudio(open: Boolean) {
            val sink = audio ?: return
            if (am.audioLen == 0) return
            if (!open) java.util.Arrays.fill(am.audio, 0, am.audioLen, 0.toShort())
            sink.write(am.audio, am.audioLen)
        }
    }

    companion object {
        /** Centros de las ventanas de 1,2 MHz (zona útil ±450 kHz) que cubren 118–137 MHz. */
        val WINDOW_CENTERS = LongArray(22) { 118_450_000L + it * 900_000L }
        const val SCAN_THRESHOLD_DB = 12.0
        const val HOLD_MS = 1500L
    }
}
