package com.formiguer.rtlsdr.adsb

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegistrationTest {
    private val small = RegistrationDb.build(mapOf(0x000001 to "A1", 0x4840D6 to "PH-KZD", 0x40621D to "G-STBC", 0xFFFFFF to "ZZ-LAST", 0x3C6444 to "D-AIBD"))

    @Test
    fun findsEveryEntryIncludingTheEnds() {
        assertEquals(5, small.count)
        assertEquals("A1", small.lookup(0x000001))
        assertEquals("PH-KZD", small.lookup(0x4840D6))
        assertEquals("G-STBC", small.lookup(0x40621D))
        assertEquals("D-AIBD", small.lookup(0x3C6444))
        assertEquals("ZZ-LAST", small.lookup(0xFFFFFF))
    }

    @Test
    fun missingCodesReturnNull() {
        assertEquals(null, small.lookup(0x000000))
        assertEquals(null, small.lookup(0x4840D7))
        assertEquals(null, small.lookup(0x500000))
        assertEquals(null, RegistrationDb.build(emptyMap()).lookup(0x4840D6))
    }

    @Test
    fun rejectsDamagedFiles() {
        assertFailsWith<IOException> { RegistrationDb(ByteArray(3)) }
        assertFailsWith<IOException> { RegistrationDb("XXXX".toByteArray() + ByteArray(4)) }
        val truncated = ByteArray(8 + 11 - 1).also { "MAT1".toByteArray().copyInto(it); it[4] = 1 }
        assertFailsWith<IOException> { RegistrationDb(truncated) }
    }

    @Test
    fun trackerAddsRegistrationToJson() {
        val t = AircraftTracker()
        t.registrations = small
        t.onMessage(hex("8D4840D6202CC371C32CE0576098"), 1000)   // 4840D6 → PH-KZD
        t.onMessage(hex("8D40621D58C382D690C8AC2863A7"), 1000)   // 40621D → G-STBC
        t.onMessage(hex("8D485020994409940838175B284F"), 1000)   // 485020: no está en la base
        val json = t.toJson(1000)
        assertTrue(json.contains("\"hex\":\"4840D6\",\"flight\":\"KLM1023\",\"reg\":\"PH-KZD\""), json)
        assertTrue(json.contains("\"reg\":\"G-STBC\""), json)
        assertTrue(json.contains("\"hex\":\"485020\",\"flight\":null,\"reg\":null"), json)
    }

    @Test
    fun registrationLoadedAfterSeeingTheAircraftStillApplies() {
        val t = AircraftTracker()
        t.onMessage(hex("8D4840D6202CC371C32CE0576098"), 1000)
        assertTrue(t.toJson(1000).contains("\"reg\":null"))
        t.registrations = small
        assertTrue(t.toJson(1000).contains("\"reg\":\"PH-KZD\""), "al cargar la base después también se resuelve")
    }

    @Test
    fun realDatabaseFile() {
        val f = File("../app/src/main/assets/matriculas.bin")
        if (!f.exists()) { println("(sin fichero real de matrículas: se omite)"); return }
        val db = RegistrationDb.fromStream(f.inputStream())
        println("matrículas en el fichero real: ${db.count}")
        assertTrue(db.count > 400_000, "base completa: ${db.count}")
        assertEquals("PH-KZD", db.lookup(0x4840D6))
        assertEquals("G-STBC", db.lookup(0x40621D))
        assertEquals("D-AIBD", db.lookup(0x3C6444))
        assertEquals("N1", db.lookup(0xA00001))
        assertEquals(null, db.lookup(0x34AABB))
        // todas las matrículas son legibles y están ordenadas por código (la búsqueda binaria lo exige)
        var previous = -1
        var seen = 0
        for (code in 0x000000..0xFFFFFF step 997) {   // muestreo
            val r = db.lookup(code)
            if (r != null) { assertTrue(Regex("[A-Z0-9-]{1,8}").matches(r), r); seen++ }
            assertTrue(code > previous); previous = code
        }
        assertTrue(seen > 300, "muestra con aciertos: $seen")
    }
}
