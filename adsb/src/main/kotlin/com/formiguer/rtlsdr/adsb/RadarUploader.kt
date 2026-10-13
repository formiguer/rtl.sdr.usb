package com.formiguer.rtlsdr.adsb

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envía los aviones recibidos al servidor de swap-turnos (ruta radar/subir) para verlos en el panel del administrador.
 *
 * Seguridad: la clave no viaja. Cada envío lleva la hora y una firma HMAC-SHA256 de "hora.cuerpo" calculada con la clave
 * (cabeceras X-Radar-Time y X-Radar-Sig); el servidor rechaza firmas falsas, horas repetidas y relojes desviados.
 * Carga: el servidor responde "ver" = alguien está mirando el mapa; si no, se envía mucho más despacio.
 */
class RadarUploader(
    private val tracker: AircraftTracker,
    private val config: () -> Config?,
    private val isAdsbActive: () -> Boolean,
    private val stationName: () -> String = { "" },
    /** Batería de la tablet (nivel 0-100 y si está cargando); null si no se conoce. El servidor avisa si baja del 20 %. */
    private val battery: () -> Battery? = { null },
    private val activeMs: Long = 2_000,
    private val idleMs: Long = 10_000,
    private val pauseMs: Long = 15_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStatus: (String, Boolean) -> Unit = { _, _ -> },
) {
    data class Battery(val level: Int, val charging: Boolean)

    /** [baseUrl]: carpeta de la web (p. ej. http://servidor/swap_repo_v1); [key]: clave generada en el servidor. */
    data class Config(val baseUrl: String, val key: String) {
        /** El «código de conexión» que se copia desde el servidor: dirección y clave en un solo texto. */
        fun toCode(): String = if (baseUrl.trim().trimEnd('/') == DEFAULT_SERVER && Regex("^[0-9]{6}$").matches(key.trim())) key.trim() else baseUrl.trim().trimEnd('/') + "|" + key.trim()
    }

    class Result(val ok: Boolean, val ver: Boolean, val message: String, val aircraft: Int = 0)

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Diferencia (s) entre el reloj del servidor y el de la tablet, aprendida de las respuestas 401. */
    @Volatile private var offsetSec = 0L

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "radar-uploader").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var lastOk = 0L
        while (running) {
            var wait = 2_000L
            val cfg = config()
            if (cfg != null && cfg.baseUrl.isNotBlank() && cfg.key.isNotBlank()) {
                val adsb = isAdsbActive()
                val res = sendOnce(cfg, adsb)
                val now = clock()
                if (res.ok) lastOk = now
                wait = when {
                    !res.ok -> 10_000L
                    !adsb -> pauseMs
                    res.ver -> activeMs
                    else -> idleMs
                }
                val ago = if (lastOk > 0) (now - lastOk) / 1000 else null
                onStatus(
                    if (res.ok) "Enviando al servidor: ${if (adsb) "${res.aircraft} aviones" else "en pausa (banda aérea)"}" +
                        (if (res.ver) " · alguien mira el mapa" else "")
                    else "Servidor: ${res.message}" + (ago?.let { " (último envío correcto hace $it s)" } ?: ""),
                    !res.ok,
                )
            }
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** Un envío (también sirve para probar la conexión desde los ajustes). */
    fun sendOnce(cfg: Config, adsb: Boolean = isAdsbActive()): Result {
        val now = clock()
        val bat = battery()?.let { ",\"bateria\":${it.level.coerceIn(0, 100)},\"cargando\":${it.charging}" } ?: ""
        val body = if (adsb) {
            "{\"estacion\":{\"nombre\":${jsonString(stationName())},\"mensajes\":${tracker.totalMessages},\"ultimo_s\":${tracker.secondsSinceLastMessage(now) ?: "null"}}," +
                "\"aviones\":${tracker.toJson(now)}$bat}"
        } else {
            "{\"estado\":\"pausa\"$bat}"
        }
        var res = post(cfg, body)
        val serverTime = res.serverTime
        if (!res.ok && serverTime != null) {
            // Reloj de la tablet desviado: se corrige con la hora del servidor y se reintenta una vez
            offsetSec = serverTime - now / 1000
            res = post(cfg, body)
        }
        return Result(res.ok, res.ver, res.message, if (res.ok && adsb) res.aircraft else 0)
    }

    private class Raw(val ok: Boolean, val ver: Boolean, val message: String, val aircraft: Int, val serverTime: Long?)

    /** Cookie «__test» que piden algunos hostings gratuitos (ByetHost/iFastNet) para dejar pasar a un cliente que sí ejecuta JavaScript. */
    @Volatile private var hostCookie: String? = null

    private fun post(cfg: Config, body: String, firstTry: Boolean = true): Raw {
        val ts = clock() / 1000 + offsetSec
        val url = cfg.baseUrl.trim().trimEnd('/') + "/api/index.php?r=radar/subir" + if (hostCookie != null) "&i=1" else ""
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            // Algunos hostings gratuitos (p. ej. ByetHost) devuelven 403 a clientes sin aspecto de navegador
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("X-Requested-With", "swap-turnos")
            hostCookie?.let { c.setRequestProperty("Cookie", "__test=$it") }
            c.setRequestProperty("X-Radar-Time", ts.toString())
            c.setRequestProperty("X-Radar-Sig", sign(cfg.key, "$ts.$body"))
            // Sin modo «streaming»: con él Java no entrega el cuerpo de una respuesta 401 y no se vería la hora del servidor
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val serverTime = Regex("\"hora_servidor\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
            if (firstTry && !text.contains("\"ok\"") && !text.contains("\"error\"")) {
                // Página de reto del hosting («necesita JavaScript»): se resuelve el cálculo de la cookie y se reintenta una vez
                solveHostChallenge(text)?.let { hostCookie = it; return post(cfg, body, false) }
            }
            return if (code in 200..299 && text.contains("\"ok\":true")) {
                Raw(
                    true, text.contains("\"ver\":true"), "ok",
                    Regex("\"aviones\":(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0, null,
                )
            } else {
                val err = Regex("\"error\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)
                // Sin JSON de la API (p. ej. página de bloqueo del hosting): se enseña el código y un trozo del texto para poder diagnosticarlo
                val msg = if (err != null) unescape(err) else "HTTP $code" + snippet(text) + diag(c, firstTry)
                Raw(false, false, msg, 0, serverTime)
            }
        } catch (e: IOException) {
            return Raw(false, false, e.message ?: e.javaClass.simpleName, 0, null)
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        /** Servidor de swap-turnos al que se envía cuando el código son solo 6 cifras. */
        const val DEFAULT_SERVER = "http://blas.byethost7.com/swap_repo_v1"

        /**
         * Interpreta el código de conexión: 6 cifras (servidor por defecto) o, para otro servidor, «dirección|código».
         * El código puede ser también una clave larga hexadecimal (16-64) de versiones anteriores. Null si no tiene formato válido.
         */
        fun parseCode(code: String): Config? {
            val text = code.trim().replace("\\s".toRegex(), "")
            if (Regex("^[0-9]{6}$").matches(text)) return Config(DEFAULT_SERVER, text)
            val cut = text.lastIndexOf('|')
            if (cut <= 0) return null
            val url = text.substring(0, cut).trimEnd('/')
            val key = text.substring(cut + 1)
            val okUrl = Regex("^https?://[^/\\s|]+(/[^\\s|]*)?$").matches(url)
            val okKey = Regex("^[0-9]{6}$").matches(key) || Regex("^[0-9a-fA-F]{16,64}$").matches(key)
            return if (okUrl && okKey) Config(url, key.lowercase()) else null
        }

        fun sign(key: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }

        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 7.1.1; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0 Mobile Safari/537.36 rtl-sdr-radar/1"

        /**
         * Reto anti-bots de iFastNet: la página trae tres números hexadecimales a, b y c; la cookie «__test» es el
         * AES-128-CBC descifrado de c con clave a e IV b, en hexadecimal. Devuelve null si la página no es ese reto.
         */
        fun solveHostChallenge(page: String): String? {
            if (!page.contains("slowAES")) return null
            val nums = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)").findAll(page).map { it.groupValues[1] }.toList()
            if (nums.size < 3) return null
            fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes(nums[0]), "AES"), IvParameterSpec(bytes(nums[1])))
                cipher.doFinal(bytes(nums[2])).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                null
            }
        }

        /** Datos de la respuesta para diagnosticar bloqueos del hosting: servidor, redirección y si ya se usaba la cookie del reto. */
        private fun diag(c: HttpURLConnection, firstTry: Boolean): String {
            val parts = ArrayList<String>()
            c.getHeaderField("Server")?.let { parts.add("servidor=$it") }
            c.getHeaderField("Location")?.let { parts.add("redirige a $it") }
            c.getHeaderField("Content-Type")?.let { parts.add("tipo=${it.take(30)}") }
            parts.add(if (firstTry) "1.er intento" else "2.º intento con cookie")
            return " [" + parts.joinToString(", ") + "]"
        }

        private fun snippet(text: String): String {
            val t = text.replace(Regex("<script.*?</script>|<style.*?</style>|<[^>]*>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("\\s+"), " ").trim()
            return if (t.isEmpty()) " (respuesta vacía: la bloquea el hosting o un cortafuegos)" else " — " + t.take(160)
        }

        private fun unescape(s: String): String =
            Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\/", "/")
    }
}
