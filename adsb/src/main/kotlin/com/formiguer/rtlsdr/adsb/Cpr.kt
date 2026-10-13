package com.formiguer.rtlsdr.adsb

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/** Decodificación global de posición CPR (un par de mensajes par/impar). */
object Cpr {
    private const val NZ = 15.0
    private const val SCALE = 131072.0 // 2^17

    fun nl(lat: Double): Int {
        val a = abs(lat)
        if (a == 0.0) return 59
        if (a == 87.0) return 2
        if (a > 87.0) return 1
        val x = 1 - (1 - cos(Math.PI / (2 * NZ))) / (cos(Math.PI / 180.0 * a).let { it * it })
        return floor(2 * Math.PI / acos(x)).toInt()
    }

    private fun mod(x: Int, y: Int): Int = ((x % y) + y) % y

    /** Devuelve (lat, lon) o null si el par cruza una zona de transición de NL. */
    fun global(
        evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int, latestIsOdd: Boolean,
    ): Pair<Double, Double>? {
        val lat0 = evenLat / SCALE
        val lat1 = oddLat / SCALE
        val lon0 = evenLon / SCALE
        val lon1 = oddLon / SCALE

        val j = floor(59 * lat0 - 60 * lat1 + 0.5).toInt()
        var rlat0 = (360.0 / 60) * (mod(j, 60) + lat0)
        var rlat1 = (360.0 / 59) * (mod(j, 59) + lat1)
        if (rlat0 >= 270) rlat0 -= 360
        if (rlat1 >= 270) rlat1 -= 360
        if (rlat0 < -90 || rlat0 > 90 || rlat1 < -90 || rlat1 > 90) return null
        if (nl(rlat0) != nl(rlat1)) return null

        val lat: Double
        var lon: Double
        if (!latestIsOdd) {
            val n = nl(rlat0)
            val ni = max(n, 1)
            val m = floor(lon0 * (n - 1) - lon1 * n + 0.5).toInt()
            lon = (360.0 / ni) * (mod(m, ni) + lon0)
            lat = rlat0
        } else {
            val n = nl(rlat1)
            val ni = max(n - 1, 1)
            val m = floor(lon0 * (n - 1) - lon1 * n + 0.5).toInt()
            lon = (360.0 / ni) * (mod(m, ni) + lon1)
            lat = rlat1
        }
        if (lon >= 180) lon -= 360
        return lat to lon
    }

    private fun pickQuadrantLat(rlat: Double, ref: Double): Double {
        var best = rlat
        for (k in -2..2) {
            val c = rlat + 90.0 * k
            if (c < -90.0 || c > 90.0) continue
            if (abs(c - ref) < abs(best - ref) || best < -90.0 || best > 90.0) best = c
        }
        return best
    }

    private fun pickQuadrantLon(lon: Double, ref: Double): Double {
        var best = lon
        for (k in -8..8) {
            val c = lon + 90.0 * k
            if (c <= -180.0 || c > 180.0) continue
            if (abs(c - ref) < abs(best - ref) || best <= -180.0 || best > 180.0) best = c
        }
        return best
    }

    /**
     * Decodificación global de posición en superficie. Las zonas son de 90°, así que la solución
     * es ambigua en cuadrantes: se elige la más cercana a la posición de referencia (el aeropuerto).
     */
    fun globalSurface(
        evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int, latestIsOdd: Boolean,
        refLat: Double, refLon: Double,
    ): Pair<Double, Double>? {
        val lat0 = evenLat / SCALE
        val lat1 = oddLat / SCALE
        val lon0 = evenLon / SCALE
        val lon1 = oddLon / SCALE

        val j = floor(59 * lat0 - 60 * lat1 + 0.5).toInt()
        val latE = pickQuadrantLat((90.0 / 60) * (mod(j, 60) + lat0), refLat)
        val latO = pickQuadrantLat((90.0 / 59) * (mod(j, 59) + lat1), refLat)
        if (latE < -90 || latE > 90 || latO < -90 || latO > 90) return null
        if (nl(latE) != nl(latO)) return null

        val lat: Double
        val lon: Double
        if (!latestIsOdd) {
            val n = nl(latE)
            val ni = max(n, 1)
            val m = floor(lon0 * (n - 1) - lon1 * n + 0.5).toInt()
            lon = (90.0 / ni) * (mod(m, ni) + lon0)
            lat = latE
        } else {
            val n = nl(latO)
            val ni = max(n - 1, 1)
            val m = floor(lon0 * (n - 1) - lon1 * n + 0.5).toInt()
            lon = (90.0 / ni) * (mod(m, ni) + lon1)
            lat = latO
        }
        return lat to pickQuadrantLon(lon, refLon)
    }
}
