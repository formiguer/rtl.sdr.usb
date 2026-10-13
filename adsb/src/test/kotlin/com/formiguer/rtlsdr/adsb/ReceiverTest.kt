package com.formiguer.rtlsdr.adsb

import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiverTest {
    /** Servidor rtl_tcp falso: cabecera, lee 5 comandos y emite IQ sintético en bucle. */
    @Test
    fun endToEndWithFakeRtlTcpServer() {
        val server = ServerSocket(0)
        val commands = ArrayList<Pair<Int, Int>>()
        val srv = Thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte()))
                out.write(ByteArray(8))
                val inp = DataInputStream(s.getInputStream())
                repeat(5) { commands.add(inp.readUnsignedByte() to inp.readInt()) }
                val iq = Synth.iq(
                    listOf(hex("8D4840D6202CC371C32CE0576098"), hex("8D40621D58C382D690C8AC2863A7"),
                        hex("8D40621D58C386435CC412692AD6")), gap = 400
                )
                val padded = iq.copyOf(((iq.size / RtlTcpClient.BLOCK) + 1) * RtlTcpClient.BLOCK)
                try {
                    while (true) { out.write(padded); out.flush() }
                } catch (_: Exception) {
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val tracker = AircraftTracker()
        val statuses = ArrayList<AdsbReceiver.Status>()
        val rx = AdsbReceiver(tracker, "127.0.0.1", server.localPort) { st, _ -> synchronized(statuses) { statuses.add(st) } }
        rx.start()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && tracker.snapshot(System.currentTimeMillis()).none { it.lat != null }) {
            Thread.sleep(50)
        }
        rx.stop()
        server.close()
        srv.join(2000)

        assertEquals(
            listOf(
                RtlTcpClient.CMD_SAMPLE_RATE to 2_000_000, RtlTcpClient.CMD_FREQUENCY to 1_090_000_000,
                RtlTcpClient.CMD_GAIN_MODE to 1, RtlTcpClient.CMD_GAIN to 496, RtlTcpClient.CMD_AGC_MODE to 0,
            ),
            commands
        )
        assertTrue(AdsbReceiver.Status.STREAMING in statuses)
        val ac = tracker.snapshot(System.currentTimeMillis()).first { it.hex == "40621D" }
        assertTrue(ac.lat != null && ac.altitudeFt == 38000)
    }

    @Test
    fun reportsWaitingWhenNoServer() {
        val port = ServerSocket(0).use { it.localPort } // puerto libre y cerrado
        val statuses = ArrayList<Pair<AdsbReceiver.Status, String?>>()
        val rx = AdsbReceiver(AircraftTracker(), "127.0.0.1", port) { st, msg -> synchronized(statuses) { statuses.add(st to msg) } }
        rx.start()
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && synchronized(statuses) { statuses.none { it.first == AdsbReceiver.Status.WAITING } }) {
            Thread.sleep(50)
        }
        rx.stop()
        assertTrue(synchronized(statuses) { statuses.any { it.first == AdsbReceiver.Status.WAITING } })
    }
}
