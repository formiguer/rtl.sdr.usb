package com.formiguer.rtlsdr.adsb

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Servidor de swap-turnos simulado: valida la firma igual que api/rutas/radar.php. */
class FakeSwapServer(private val key: String, private val serverClockOffsetSec: Long = 0) {
    class Req(val ts: Long, val sig: String, val body: String, val xrw: String?, val path: String)

    val requests = CopyOnWriteArrayList<Req>()
    @Volatile var ver = false
    @Volatile var lastTs = 0L
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    private fun now() = System.currentTimeMillis() / 1000 + serverClockOffsetSec

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun reply(ex: HttpExchange, code: Int, json: String) {
        val b = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val ts = ex.requestHeaders.getFirst("X-Radar-Time")?.toLongOrNull() ?: 0
        val sig = ex.requestHeaders.getFirst("X-Radar-Sig") ?: ""
        requests.add(Req(ts, sig, body, ex.requestHeaders.getFirst("X-Requested-With"), ex.requestURI.toString()))
        val n = now()
        when {
            abs(n - ts) > 120 -> reply(ex, 401, "{\"error\":\"El reloj de la tablet no coincide con el del servidor\",\"hora_servidor\":$n}")
            sig != RadarUploader.sign(key, "$ts.$body") -> reply(ex, 401, "{\"error\":\"Firma no v\\u00e1lida: revisa la clave\"}")
            ts <= lastTs -> reply(ex, 200, "{\"ok\":true,\"ignorado\":true,\"ver\":$ver,\"hora\":$n}")
            else -> {
                lastTs = ts
                val count = Regex("\"hex\"").findAll(body).count()
                reply(ex, 200, "{\"ok\":true,\"ver\":$ver,\"hora\":$n,\"aviones\":$count}")
            }
        }
    }

    fun stop() = server.stop(0)
}

class RadarUploaderTest {
    private val key = "0123456789abcdef0123456789abcdef"

    private fun tracker(): AircraftTracker = AircraftTracker().also {
        it.onMessage(hex("8D4840D6202CC371C32CE0576098"), System.currentTimeMillis())
        it.onMessage(hex("8D40621D58C382D690C8AC2863A7"), System.currentTimeMillis())
    }

    @Test
    fun signatureMatchesPhpFormula() {
        // Misma fórmula que hash_hmac('sha256', $ts . '.' . $raw, $clave) en radar.php; vector de prueba conocido
        // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog"): vector estándar publicado
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", RadarUploader.sign("key", "The quick brown fox jumps over the lazy dog"))
        assertEquals("\"a\\\"b\\\\c\"", RadarUploader.jsonString("a\"b\\c"))
    }

    @Test
    fun sendsSignedAircraftAndReadsViewerFlag() {
        val srv = FakeSwapServer(key)
        try {
            val up = RadarUploader(tracker(), { RadarUploader.Config("http://127.0.0.1:${srv.port}/swap/", key) }, { true }, { "PMI" })
            srv.ver = true
            val r = up.sendOnce(RadarUploader.Config("http://127.0.0.1:${srv.port}/swap/", key))
            assertTrue(r.ok, r.message)
            assertTrue(r.ver, "el servidor avisa de que alguien mira")
            val req = srv.requests.last()
            assertEquals("swap-turnos", req.xrw)
            assertTrue(req.path.contains("r=radar/subir"), req.path)
            assertTrue(req.body.contains("\"nombre\":\"PMI\"") && req.body.contains("40621D"), req.body)
            assertEquals(RadarUploader.sign(key, "${req.ts}.${req.body}"), req.sig)
        } finally { srv.stop() }
    }

    @Test
    fun wrongKeyIsReportedNotCrashing() {
        val srv = FakeSwapServer(key)
        try {
            val up = RadarUploader(tracker(), { null }, { true })
            val r = up.sendOnce(RadarUploader.Config("http://127.0.0.1:${srv.port}", "otra-clave"))
            assertTrue(!r.ok && r.message.contains("Firma"), r.message)
        } finally { srv.stop() }
    }

    @Test
    fun correctsTabletClockUsingServerTime() {
        val srv = FakeSwapServer(key, serverClockOffsetSec = 3600) // el servidor va una hora por delante
        try {
            val up = RadarUploader(tracker(), { null }, { true })
            val r = up.sendOnce(RadarUploader.Config("http://127.0.0.1:${srv.port}", key))
            assertTrue(r.ok, "tras corregir el reloj debe funcionar: ${r.message}")
            assertEquals(2, srv.requests.size, "primer intento rechazado y reintento corregido")
            // y los envíos siguientes ya salen con la hora corregida
            val r2 = up.sendOnce(RadarUploader.Config("http://127.0.0.1:${srv.port}", key))
            assertTrue(r2.ok && srv.requests.size == 3, r2.message)
        } finally { srv.stop() }
    }

    @Test
    fun pauseSendsHeartbeatWithoutAircraft() {
        val srv = FakeSwapServer(key)
        try {
            val up = RadarUploader(tracker(), { null }, { false })
            val r = up.sendOnce(RadarUploader.Config("http://127.0.0.1:${srv.port}", key), adsb = false)
            assertTrue(r.ok, r.message)
            assertEquals("{\"estado\":\"pausa\"}", srv.requests.last().body)
        } finally { srv.stop() }
    }

    @Test
    fun loopAdaptsRateToViewers() {
        val srv = FakeSwapServer(key)
        try {
            val statuses = CopyOnWriteArrayList<String>()
            val up = RadarUploader(
                tracker(), { RadarUploader.Config("http://127.0.0.1:${srv.port}", key) }, { true }, { "PMI" },
                activeMs = 1100, idleMs = 3000, pauseMs = 3000, onStatus = { t, _ -> statuses.add(t) },
            )
            srv.ver = false
            up.start()
            Thread.sleep(2500)
            val idle = srv.requests.size            // sin espectadores: va despacio (≈1 envío en 2,5 s)
            srv.ver = true
            Thread.sleep(500)                         // deja pasar el envío en curso
            val before = srv.requests.size
            Thread.sleep(3500)
            val active = srv.requests.size - before   // con espectadores: ≈3 envíos en 3,5 s
            up.stop()
            assertTrue(idle in 1..2, "ritmo lento sin espectadores: $idle")
            assertTrue(active >= 2, "ritmo rápido con espectadores: $active")
            assertTrue(statuses.any { it.contains("aviones") }, statuses.joinToString())
        } finally { srv.stop() }
    }

    @Test
    fun disabledConfigSendsNothing() {
        val srv = FakeSwapServer(key)
        try {
            val up = RadarUploader(tracker(), { null }, { true })
            up.start(); Thread.sleep(600); up.stop()
            assertEquals(0, srv.requests.size)
        } finally { srv.stop() }
    }
}
