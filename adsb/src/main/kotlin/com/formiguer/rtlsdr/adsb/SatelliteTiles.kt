package com.formiguer.rtlsdr.adsb

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Teselas de satélite guardadas en la tablet para verlas sin conexión.
 * Se descargan una sola vez (con internet) alrededor de un punto y se leen después del disco.
 * Disposición en disco: dir/z/x/y.jpg (esquema XYZ habitual de Web Mercator).
 */
class SatelliteTiles(
    private val dir: File,
    /** Plantilla con {z}, {x} e {y}. */
    private val urlTemplate: String = DEFAULT_URL,
    private val userAgent: String = "rtl.sdr.usb (visor ADS-B personal)",
) {
    data class Tile(val z: Int, val x: Int, val y: Int)

    @Volatile var total = 0; private set
    @Volatile var done = 0; private set
    @Volatile var failed = 0; private set
    @Volatile var running = false; private set
    @Volatile private var cancelled = false

    fun file(z: Int, x: Int, y: Int) = File(dir, "$z/$x/$y.jpg")

    /** Teselas guardadas y su tamaño total en bytes. */
    fun stored(): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        dir.walkTopDown().forEach { if (it.isFile && it.name.endsWith(".jpg")) { n++; bytes += it.length() } }
        return n to bytes
    }

    fun cancel() { cancelled = true }

    fun clear() { dir.deleteRecursively() }

    /** Descarga lo que falta de [plan]; las teselas ya guardadas se saltan, así que se puede reanudar. */
    fun download(plan: List<Tile>) {
        if (running) return
        running = true; cancelled = false; total = plan.size; done = 0; failed = 0
        try {
            for (t in plan) {
                if (cancelled) break
                val f = file(t.z, t.x, t.y)
                if (f.isFile && f.length() > 0) { done++; continue }
                if (fetch(t, f)) done++ else failed++
            }
        } finally {
            running = false
        }
    }

    private fun fetch(t: Tile, target: File): Boolean {
        val url = urlTemplate.replace("{z}", "${t.z}").replace("{x}", "${t.x}").replace("{y}", "${t.y}")
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", userAgent)
                try {
                    if (c.responseCode != 200) return@repeat
                    val bytes = c.inputStream.use { it.readBytes() }
                    if (bytes.size < 100) return@repeat
                    target.parentFile?.mkdirs()
                    val tmp = File(target.path + ".part")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) { tmp.delete(); return false }
                    return true
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                if (attempt < 2) Thread.sleep(300)
            }
        }
        return false
    }

    companion object {
        /** Sentinel-2 sin nubes (EOX), gratis para uso no comercial; llega hasta z13 con buen detalle. */
        const val DEFAULT_URL = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg"
        const val MAX_ZOOM = 13

        /** (zoom, radio en km alrededor del punto): más detalle cuanto más cerca. */
        val ZONES = listOf(3 to 6000.0, 4 to 3000.0, 5 to 1500.0, 6 to 800.0, 7 to 500.0, 8 to 350.0,
            9 to 280.0, 10 to 220.0, 11 to 120.0, 12 to 60.0, 13 to 30.0)

        private fun tx(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()
        private fun ty(lat: Double, z: Int): Int {
            val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
        }

        /** Teselas necesarias alrededor de (lat, lon), de menor a mayor zoom. */
        fun plan(lat: Double, lon: Double, zones: List<Pair<Int, Double>> = ZONES): List<Tile> {
            val out = ArrayList<Tile>()
            for ((z, km) in zones) {
                val dLat = km / 111.32
                val dLon = km / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
                val max = (1 shl z) - 1
                val x0 = tx((lon - dLon).coerceAtLeast(-180.0), z).coerceIn(0, max)
                val x1 = tx((lon + dLon).coerceAtMost(179.999), z).coerceIn(0, max)
                val y0 = ty(lat + dLat, z).coerceIn(0, max)
                val y1 = ty(lat - dLat, z).coerceIn(0, max)
                for (x in x0..x1) for (y in y0..y1) out.add(Tile(z, x, y))
            }
            return out
        }
    }
}
