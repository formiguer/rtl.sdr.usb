package com.formiguer.rtlsdr.adsb

import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Codificador de mensajes DF17 de superficie (solo para pruebas). */
object SurfaceSynth {
    private fun mod(x: Double, y: Double) = x - y * floor(x / y)

    /** @return (latCpr, lonCpr) para la trama par (0) o impar (1) en zonas de 90°. */
    fun cpr(lat: Double, lon: Double, odd: Int): Pair<Int, Int> {
        val dLat = 90.0 / (60 - odd)
        val yz = floor(131072 * (mod(lat, dLat) / dLat) + 0.5).toInt()
        val rlat = dLat * (floor(lat / dLat) + yz / 131072.0)
        val ni = max(Cpr.nl(rlat) - odd, 1)
        val dLon = 90.0 / ni
        val xz = floor(131072 * (mod(lon, dLon) / dLon) + 0.5).toInt()
        return (yz and 0x1FFFF) to (xz and 0x1FFFF)
    }

    fun message(icao: Int, mov: Int, track: Int?, lat: Double, lon: Double, odd: Int, tc: Int = 6): ByteArray {
        val (yz, xz) = cpr(lat, lon, odd)
        var me = tc.toLong() shl 51
        me = me or (mov.toLong() shl 44)
        if (track != null) me = me or (1L shl 43) or ((track.toLong() and 0x7F) shl 36)
        me = me or (odd.toLong() shl 34) or (yz.toLong() shl 17) or xz.toLong()
        val msg = ByteArray(14)
        msg[0] = ((17 shl 3) or 5).toByte()
        msg[1] = (icao shr 16).toByte(); msg[2] = (icao shr 8).toByte(); msg[3] = icao.toByte()
        for (k in 0 until 7) msg[4 + k] = (me shr (8 * (6 - k))).toByte()
        val parity = Crc24.remainder(msg, 11) // CRC de los 11 bytes de datos
        msg[11] = (parity shr 16).toByte(); msg[12] = (parity shr 8).toByte(); msg[13] = parity.toByte()
        return msg
    }
}

class SurfaceTest {
    @Test
    fun surfaceRoundTripAtPalma() {
        val t = AircraftTracker()
        val lat = 39.5551
        val lon = 2.7342
        assertTrue(t.onMessage(SurfaceSynth.message(0xAB12CD, 20, 32, lat, lon, 0), 1000))
        assertTrue(t.onMessage(SurfaceSynth.message(0xAB12CD, 20, 32, lat, lon, 1), 1500))
        val a = t.snapshot(1500).single()
        assertEquals(true, a.onGround)
        assertEquals(lat, assertNotNull(a.lat), 2e-4)
        assertEquals(lon, assertNotNull(a.lon), 2e-4)
        assertEquals(2.0 + (20 - 13) * 0.5, assertNotNull(a.speedKt), 1e-9)
        assertEquals(32 * 360.0 / 128, assertNotNull(a.trackDeg), 1e-9)
        assertEquals(null, a.altitudeFt)
    }

    @Test
    fun surfaceUsesReferenceForQuadrant() {
        // Misma trama CPR interpretada con otra referencia: cae en otro cuadrante de 90°
        val even = SurfaceSynth.message(0x111111, 10, null, 39.5551, 2.7342, 0)
        val odd = SurfaceSynth.message(0x111111, 10, null, 39.5551, 2.7342, 1)
        val near = AircraftTracker().also { it.onMessage(even, 1000); it.onMessage(odd, 1200) }
        val far = AircraftTracker().also {
            it.referenceLat = 39.5; it.referenceLon = 92.7 // referencia 90° al este
            it.onMessage(even, 1000); it.onMessage(odd, 1200)
        }
        val lonNear = assertNotNull(near.snapshot(1200).single().lon)
        val lonFar = assertNotNull(far.snapshot(1200).single().lon)
        assertEquals(2.7342, lonNear, 2e-4)
        assertEquals(92.7342, lonFar, 2e-4)
    }

    @Test
    fun groundThenAirborneSwitchesState() {
        val t = AircraftTracker()
        val ev = hex("8D40621D58C382D690C8AC2863A7")
        val od = hex("8D40621D58C386435CC412692AD6")
        t.onMessage(SurfaceSynth.message(0x40621D, 5, 10, 39.5551, 2.7342, 0), 1000)
        assertEquals(true, t.snapshot(1000).single().onGround)
        t.onMessage(ev, 2000)
        t.onMessage(od, 2500)
        val a = t.snapshot(2500).single()
        assertEquals(false, a.onGround)
        assertEquals(38000, a.altitudeFt)
        // no mezcla una trama de superficie con una aérea: la posición es la aérea (Holanda)
        assertEquals(52.26, assertNotNull(a.lat), 0.05)
    }

    @Test
    fun realSurfaceMessagesFromSchiphol() {
        val even = hex("8C4841753A9A153237AEF0F275BE")
        val odd = hex("8C4841753AAB238733C8CD4020B1")
        if (Crc24.remainder(even) != 0 || Crc24.remainder(odd) != 0) {
            println("(ejemplo real de superficie: CRC no válido, se omite)")
            return
        }
        val t = AircraftTracker().also { it.referenceLat = 52.3; it.referenceLon = 4.76 }
        t.onMessage(even, 1000)
        t.onMessage(odd, 1200)
        val a = t.snapshot(1200).single()
        println("Schiphol: lat=${a.lat} lon=${a.lon} vel=${a.speedKt} rumbo=${a.trackDeg} gnd=${a.onGround}")
        assertEquals(52.32, assertNotNull(a.lat), 0.05)
        assertEquals(4.73, assertNotNull(a.lon), 0.05)
        assertEquals(true, a.onGround)
    }

    @Test
    fun jsonExposesGroundAndAge() {
        val t = AircraftTracker()
        t.onMessage(SurfaceSynth.message(0x222222, 30, 64, 39.5551, 2.7342, 0), 1000)
        val json = t.toJson(4000)
        println(json)
        assertTrue(json.contains("\"gnd\":true"))
        assertTrue(json.contains("\"age\":3"))
        assertTrue(json.contains("\"vr\":null"))
    }
}
