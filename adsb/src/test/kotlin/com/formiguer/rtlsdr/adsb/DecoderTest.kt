package com.formiguer.rtlsdr.adsb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecoderTest {
    private val ident = hex("8D4840D6202CC371C32CE0576098")
    private val even = hex("8D40621D58C382D690C8AC2863A7")
    private val odd = hex("8D40621D58C386435CC412692AD6")
    private val velocity = hex("8D485020994409940838175B284F")

    @Test
    fun crcValidAndCorrupted() {
        for (m in listOf(ident, even, odd, velocity)) assertEquals(0, Crc24.remainder(m))
        val bad = ident.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertNotEquals(0, Crc24.remainder(bad))
    }

    @Test
    fun callsign() {
        val t = AircraftTracker()
        assertTrue(t.onMessage(ident, 1000))
        val a = t.snapshot(1000).single()
        assertEquals("4840D6", a.hex)
        assertEquals("KLM1023", a.callsign)
    }

    @Test
    fun positionAndAltitude() {
        val t = AircraftTracker()
        t.onMessage(even, 1000)
        t.onMessage(odd, 2000) // el impar es el más reciente
        val a = t.snapshot(2000).single()
        assertEquals(38000, a.altitudeFt)
        val lat = assertNotNull(a.lat)
        val lon = assertNotNull(a.lon)
        println("posición (impar reciente): $lat, $lon")
        assertEquals(52.26578, lat, 1e-3)
        assertEquals(3.93919, lon, 1e-3)

        val t2 = AircraftTracker()
        t2.onMessage(odd, 1000)
        t2.onMessage(even, 2000) // el par es el más reciente
        val b = t2.snapshot(2000).single()
        println("posición (par reciente): ${b.lat}, ${b.lon}")
        assertEquals(52.2572, assertNotNull(b.lat), 1e-3)
        assertEquals(3.91937, assertNotNull(b.lon), 1e-3)
    }

    @Test
    fun cprPairTooOldIsIgnored() {
        val t = AircraftTracker(cprPairMs = 10_000)
        t.onMessage(even, 1000)
        t.onMessage(odd, 30_000)
        assertEquals(null, t.snapshot(30_000).single().lat)
    }

    @Test
    fun velocity() {
        val t = AircraftTracker()
        t.onMessage(velocity, 1000)
        val a = t.snapshot(1000).single()
        assertEquals(159.0, assertNotNull(a.speedKt), 1.0)
        assertEquals(182.88, assertNotNull(a.trackDeg), 0.1)
        assertEquals(-832, a.vrateFpm)
    }

    @Test
    fun staleAircraftAreRemoved() {
        val t = AircraftTracker(ttlMs = 60_000)
        t.onMessage(ident, 1000)
        assertEquals(1, t.snapshot(30_000).size)
        assertEquals(0, t.snapshot(100_000).size)
    }

    @Test
    fun jsonContainsFields() {
        val t = AircraftTracker()
        t.onMessage(ident, 1000)
        t.onMessage(even, 1000)
        t.onMessage(odd, 1500)
        val json = t.toJson(1500)
        println(json)
        assertTrue(json.contains("\"hex\":\"4840D6\"") && json.contains("\"flight\":\"KLM1023\""))
        assertTrue(json.contains("\"alt\":38000"))
    }

    @Test
    fun demodulatesSyntheticSignalInOneChunk() {
        val got = ArrayList<ByteArray>()
        val d = Demodulator { got.add(it) }
        val iq = Synth.iq(listOf(ident, even, odd, velocity))
        d.process(iq, iq.size)
        assertEquals(4, got.size)
        assertTrue(got[0].contentEquals(ident) && got[3].contentEquals(velocity))
    }

    @Test
    fun demodulatesAcrossArbitraryChunkBoundaries() {
        val iq = Synth.iq(List(20) { listOf(ident, even, odd, velocity)[it % 4] }, gap = 50)
        for (chunk in listOf(2, 10, 98, 1000, 4096)) {
            var count = 0
            val d = Demodulator { count++ }
            var off = 0
            while (off < iq.size) {
                val n = minOf(chunk, iq.size - off)
                d.process(iq.copyOfRange(off, off + n), n)
                off += n
            }
            assertEquals(20, count, "chunk=$chunk")
        }
    }

    @Test
    fun noiseOnlyProducesNothing() {
        var count = 0
        val d = Demodulator { count++ }
        val rnd = java.util.Random(7)
        val noise = ByteArray(2_000_000) { (127 + rnd.nextInt(41) - 20).toByte() }
        d.process(noise, noise.size)
        assertEquals(0, count)
    }

    @Test
    fun toleratesModerateNoise() {
        val got = ArrayList<ByteArray>()
        val d = Demodulator { got.add(it) }
        val iq = Synth.iq(listOf(ident, even, odd, velocity), amp = 60, noise = 12, seed = 3)
        d.process(iq, iq.size)
        assertEquals(4, got.size)
    }
}
