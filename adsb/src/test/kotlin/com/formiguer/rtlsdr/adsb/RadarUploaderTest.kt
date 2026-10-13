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
            assertTrue(Regex("\"ultimo_s\":\\d+").containsMatchIn(req.body), "lleva los segundos desde el último dato: ${req.body.take(120)}")
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

    @Test
    fun parsesConnectionCode() {
        val key = "0123456789abcdef0123456789abcdef"
        val c = RadarUploader.parseCode("http://blas.byethost7.com/swap_repo_v1|$key")
        assertEquals(RadarUploader.Config("http://blas.byethost7.com/swap_repo_v1", key), c)
        assertEquals(c, RadarUploader.parseCode("  http://blas.byethost7.com/swap_repo_v1/ | ${key.uppercase()} \n"))
        assertEquals("http://blas.byethost7.com/swap_repo_v1|$key", c!!.toCode())
        assertEquals(c, RadarUploader.parseCode(c.toCode()))
        assertEquals(null, RadarUploader.parseCode(""))
        assertEquals(null, RadarUploader.parseCode(key))                              // falta la dirección
        assertEquals(null, RadarUploader.parseCode("http://servidor"))                 // falta la clave
        assertEquals(null, RadarUploader.parseCode("servidor.com|$key"))               // sin http(s)
        assertEquals(null, RadarUploader.parseCode("http://servidor|corta"))            // clave inválida
        assertEquals(null, RadarUploader.parseCode("http://servidor|${key}zz"))         // no es hexadecimal
        assertEquals("http://127.0.0.1:8081", RadarUploader.parseCode("http://127.0.0.1:8081|$key")!!.baseUrl)
        // código de 6 cifras: servidor por defecto, sin dirección
        val six = RadarUploader.parseCode(" 123 456 ")!!
        assertEquals(RadarUploader.DEFAULT_SERVER, six.baseUrl); assertEquals("123456", six.key); assertEquals("123456", six.toCode())
        assertEquals(null, RadarUploader.parseCode("12345")); assertEquals(null, RadarUploader.parseCode("1234567")); assertEquals(null, RadarUploader.parseCode("12345a"))
        assertEquals("999000", RadarUploader.parseCode("http://127.0.0.1:8081|999000")!!.key)
    }

    @Test
    fun sendsBrowserLikeUserAgentAndShowsHostingBlockPages() {
        val seen = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            seen.set(ex.requestHeaders.getFirst("User-Agent"))
            val body = "<html><body><h1>Forbidden</h1><script>x()</script>Access denied by host</body></html>".toByteArray()
            ex.sendResponseHeaders(403, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.start()
        try {
            val up = RadarUploader(tracker(), { null }, { true })
            val r = up.sendOnce(RadarUploader.Config("http://127.0.0.1:${srv.address.port}", "0123456789abcdef0123456789abcdef"))
            assertTrue(!r.ok)
            assertTrue(seen.get()!!.startsWith("Mozilla/5.0"), "User-Agent: ${seen.get()}")
            assertTrue(r.message.startsWith("HTTP 403") && r.message.contains("Access denied by host") && !r.message.contains("<"), r.message)
        } finally { srv.stop(0) }
    }

    @Test
    fun passesTheHostingJavascriptChallenge() {
        // El hosting contesta 200 con una página que calcula la cookie __test en JavaScript; la app debe resolverla y reintentar
        val plain = ByteArray(16) { (it * 7 + 3).toByte() }
        val k = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (200 - it).toByte() }
        val enc = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").also {
            it.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(k, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        }.doFinal(plain)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val page = "<html><body><script type=\"text/javascript\" src=\"/aes.js\"></script><script>function toNumbers(d){}" +
            "var a=toNumbers(\"${hex(k)}\"),b=toNumbers(\"${hex(iv)}\"),c=toNumbers(\"${hex(enc)}\");" +
            "document.cookie=\"__test=\"+toHex(slowAES.decrypt(c,2,a,b));location.href=\"/x?i=1\";</script></body></html>"
        val urls = CopyOnWriteArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            urls.add(ex.requestURI.toString())
            val ok = ex.requestHeaders.getFirst("Cookie") == "__test=${hex(plain)}"
            val body = (if (ok) "{\"ok\":true,\"ver\":false,\"aviones\":0}" else page).toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.start()
        try {
            val up = RadarUploader(tracker(), { null }, { true })
            val cfg = RadarUploader.Config("http://127.0.0.1:${srv.address.port}", "123456")
            val r = up.sendOnce(cfg)
            assertTrue(r.ok, r.message)
            assertTrue(urls.size == 2 && urls[1].endsWith("&i=1"), urls.toString())
            // la cookie se recuerda: el siguiente envío pasa a la primera
            val r2 = up.sendOnce(cfg)
            assertTrue(r2.ok && urls.size == 3, urls.toString())
        } finally { srv.stop(0) }
    }

    @Test
    fun sendsBatteryLevelInBothModes() {
        val key = "0123456789abcdef0123456789abcdef"
        val srv = FakeSwapServer(key)
        try {
            val up = RadarUploader(tracker(), { null }, { true }, { "PMI" }, battery = { RadarUploader.Battery(17, false) })
            val cfg = RadarUploader.Config("http://127.0.0.1:${srv.port}/swap/", key)
            assertTrue(up.sendOnce(cfg, true).ok)
            assertTrue(up.sendOnce(cfg, false).ok)
            val bodies = srv.requests.map { it.body }
            assertTrue(bodies.size == 2 && bodies.all { it.contains("\"bateria\":17,\"cargando\":false") }, bodies.toString())
            val noBat = RadarUploader(tracker(), { null }, { true })
            srv.requests.clear()
            noBat.sendOnce(cfg, false)
            assertTrue(!srv.requests.last().body.contains("bateria"))
        } finally { srv.stop() }
    }
}
