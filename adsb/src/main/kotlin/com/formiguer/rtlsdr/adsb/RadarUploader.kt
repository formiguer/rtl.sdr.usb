package com.formiguer.rtlsdr.adsb

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
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
    private val activeMs: Long = 2_000,
    private val idleMs: Long = 10_000,
    private val pauseMs: Long = 15_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStatus: (String, Boolean) -> Unit = { _, _ -> },
) {
    /** [baseUrl]: carpeta de la web (p. ej. http://servidor/swap_repo_v1); [key]: clave generada en el panel. */
    data class Config(val baseUrl: String, val key: String)

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
        val body = if (adsb) {
            "{\"estacion\":{\"nombre\":${jsonString(stationName())},\"mensajes\":${tracker.totalMessages}}," +
                "\"aviones\":${tracker.toJson(now)}}"
        } else {
            "{\"estado\":\"pausa\"}"
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

    private fun post(cfg: Config, body: String): Raw {
        val ts = clock() / 1000 + offsetSec
        val url = cfg.baseUrl.trim().trimEnd('/') + "/api/index.php?r=radar/subir"
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Requested-With", "swap-turnos")
            c.setRequestProperty("X-Radar-Time", ts.toString())
            c.setRequestProperty("X-Radar-Sig", sign(cfg.key, "$ts.$body"))
            // Sin modo «streaming»: con él Java no entrega el cuerpo de una respuesta 401 y no se vería la hora del servidor
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val serverTime = Regex("\"hora_servidor\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
            return if (code in 200..299 && text.contains("\"ok\":true")) {
                Raw(
                    true, text.contains("\"ver\":true"), "ok",
                    Regex("\"aviones\":(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0, null,
                )
            } else {
                val err = Regex("\"error\":\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "HTTP $code"
                Raw(false, false, unescape(err), 0, serverTime)
            }
        } catch (e: IOException) {
            return Raw(false, false, e.message ?: e.javaClass.simpleName, 0, null)
        } finally {
            c?.disconnect()
        }
    }

    companion object {
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

        private fun unescape(s: String): String =
            Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\/", "/")
    }
}
