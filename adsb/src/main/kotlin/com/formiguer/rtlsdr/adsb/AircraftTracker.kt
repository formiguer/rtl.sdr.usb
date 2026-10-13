package com.formiguer.rtlsdr.adsb

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class Aircraft(val icao: Int) {
    val hex: String get() = "%06X".format(icao)
    var callsign: String? = null
    var altitudeFt: Int? = null
    var lat: Double? = null
    var lon: Double? = null
    var speedKt: Double? = null
    var trackDeg: Double? = null
    var vrateFpm: Int? = null
    var messages = 0
    var lastSeenMs = 0L

    internal var evenLat = 0
    internal var evenLon = 0
    internal var evenTime = -1L
    internal var oddLat = 0
    internal var oddLon = 0
    internal var oddTime = -1L
}

/** Interpreta mensajes DF17 (ADS-B) y mantiene la lista de aviones vistos. */
class AircraftTracker(
    private val ttlMs: Long = 60_000,
    private val cprPairMs: Long = 10_000,
) {
    private val aircraft = HashMap<Int, Aircraft>()

    @get:Synchronized
    var totalMessages = 0L
        private set

    /** @return true si el mensaje era un DF17 válido (CRC correcto). */
    @Synchronized
    fun onMessage(msg: ByteArray, nowMs: Long): Boolean {
        if (msg.size < 14) return false
        if (((msg[0].toInt() and 0xFF) shr 3) != 17) return false
        if (Crc24.remainder(msg, 14) != 0) return false

        totalMessages++
        val icao = ((msg[1].toInt() and 0xFF) shl 16) or
            ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        val ac = aircraft.getOrPut(icao) { Aircraft(icao) }
        ac.lastSeenMs = nowMs
        ac.messages++

        var me = 0L
        for (k in 4..10) me = (me shl 8) or (msg[k].toLong() and 0xFF)
        when (val tc = (me shr 51).toInt() and 0x1F) {
            in 1..4 -> decodeIdentification(ac, me)
            in 9..18, in 20..22 -> decodePosition(ac, me, tc, nowMs)
            19 -> decodeVelocity(ac, me)
        }
        return true
    }

    private fun decodeIdentification(ac: Aircraft, me: Long) {
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val c = ((me shr (42 - 6 * i)) and 0x3F).toInt()
            sb.append(CHARSET[c])
        }
        val name = sb.toString().replace('#', ' ').replace('_', ' ').trim()
        if (name.isNotEmpty()) ac.callsign = name
    }

    private fun decodePosition(ac: Aircraft, me: Long, tc: Int, nowMs: Long) {
        if (tc <= 18) {
            val ac12 = ((me shr 36) and 0xFFF).toInt()
            if ((ac12 and 0x10) != 0) { // bit Q: incrementos de 25 ft
                val n = ((ac12 and 0xFE0) shr 1) or (ac12 and 0xF)
                ac.altitudeFt = n * 25 - 1000
            }
        }
        val odd = ((me shr 34) and 1L) == 1L
        val latCpr = ((me shr 17) and 0x1FFFF).toInt()
        val lonCpr = (me and 0x1FFFF).toInt()
        if (odd) {
            ac.oddLat = latCpr; ac.oddLon = lonCpr; ac.oddTime = nowMs
        } else {
            ac.evenLat = latCpr; ac.evenLon = lonCpr; ac.evenTime = nowMs
        }
        if (ac.evenTime >= 0 && ac.oddTime >= 0 && abs(ac.evenTime - ac.oddTime) <= cprPairMs) {
            Cpr.global(ac.evenLat, ac.evenLon, ac.oddLat, ac.oddLon, ac.oddTime > ac.evenTime)
                ?.let { (la, lo) -> ac.lat = la; ac.lon = lo }
        }
    }

    private fun decodeVelocity(ac: Aircraft, me: Long) {
        val subtype = ((me shr 48) and 0x7).toInt()
        if (subtype != 1 && subtype != 2) return // solo velocidad respecto al suelo
        val dew = ((me shr 42) and 1L) == 1L
        val vew = ((me shr 32) and 0x3FF).toInt()
        val dns = ((me shr 31) and 1L) == 1L
        val vns = ((me shr 21) and 0x3FF).toInt()
        if (vew == 0 || vns == 0) return
        val mult = if (subtype == 2) 4 else 1
        var ew = ((vew - 1) * mult).toDouble()
        var ns = ((vns - 1) * mult).toDouble()
        if (dew) ew = -ew
        if (dns) ns = -ns
        ac.speedKt = hypot(ew, ns)
        var track = Math.toDegrees(atan2(ew, ns))
        if (track < 0) track += 360.0
        ac.trackDeg = track

        val vr = ((me shr 10) and 0x1FF).toInt()
        if (vr != 0) {
            val rate = (vr - 1) * 64
            ac.vrateFpm = if (((me shr 19) and 1L) == 1L) -rate else rate
        }
    }

    /** Aviones vistos en los últimos [ttlMs] ms; elimina los demás. */
    @Synchronized
    fun snapshot(nowMs: Long): List<Aircraft> {
        aircraft.values.removeAll { nowMs - it.lastSeenMs > ttlMs }
        return aircraft.values.toList()
    }

    /** Lista en JSON para el mapa: [{hex, flight, lat, lon, alt, speed, track}]. */
    @Synchronized
    fun toJson(nowMs: Long): String {
        val sb = StringBuilder("[")
        var first = true
        for (a in snapshot(nowMs)) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"hex\":\"").append(a.hex).append('"')
            sb.append(",\"flight\":").append(a.callsign?.let { "\"$it\"" } ?: "null")
            sb.append(",\"lat\":").append(a.lat ?: "null")
            sb.append(",\"lon\":").append(a.lon ?: "null")
            sb.append(",\"alt\":").append(a.altitudeFt ?: "null")
            sb.append(",\"speed\":").append(a.speedKt?.let { Math.round(it) } ?: "null")
            sb.append(",\"track\":").append(a.trackDeg?.let { Math.round(it) } ?: "null")
            sb.append('}')
        }
        return sb.append(']').toString()
    }

    private companion object {
        const val CHARSET = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######"
    }
}
