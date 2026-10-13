package com.formiguer.rtlsdr.adsb

import java.util.concurrent.atomic.AtomicLong

/** Hilo que conecta con rtl_tcp, demodula y alimenta el [AircraftTracker]; reconecta solo. */
class AdsbReceiver(
    private val tracker: AircraftTracker,
    private val host: String = "127.0.0.1",
    private val port: Int = 1234,
    private val onStatus: (Status, String?) -> Unit,
) {
    enum class Status { CONNECTING, STREAMING, WAITING }

    val bytesReceived = AtomicLong()

    @Volatile
    private var running = false

    @Volatile
    private var client: RtlTcpClient? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "adsb-receiver").also {
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
            val demod = Demodulator { tracker.onMessage(it, System.currentTimeMillis()) }
            var streaming = false
            onStatus(Status.CONNECTING, null)
            try {
                c.run({ running }) { buf, n ->
                    if (!streaming) {
                        streaming = true
                        onStatus(Status.STREAMING, null)
                    }
                    bytesReceived.addAndGet(n.toLong())
                    demod.process(buf, n)
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
}
