package com.formiguer.rtlsdr.adsb

import java.io.IOException
import java.io.InputStream

/**
 * Matrículas por código ICAO de 24 bits (formato MAT1, ver tools/matriculas/build.py): cabecera "MAT1" + nº de registros
 * (uint32 little-endian) + registros de 11 bytes ordenados (3 bytes de código en big-endian + 8 bytes de matrícula con ceros).
 * La búsqueda es binaria y no hace falta nada más que el fichero en memoria.
 */
class RegistrationDb(private val data: ByteArray) {
    val count: Int

    init {
        if (data.size < HEADER || data[0] != 'M'.code.toByte() || data[1] != 'A'.code.toByte() ||
            data[2] != 'T'.code.toByte() || data[3] != '1'.code.toByte()
        ) throw IOException("Base de matrículas no válida")
        count = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) or
            ((data[6].toInt() and 0xFF) shl 16) or ((data[7].toInt() and 0xFF) shl 24)
        if (count < 0 || data.size < HEADER + count.toLong() * RECORD) throw IOException("Base de matrículas incompleta")
    }

    private fun keyAt(i: Int): Int {
        val o = HEADER + i * RECORD
        return ((data[o].toInt() and 0xFF) shl 16) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() and 0xFF)
    }

    /** Matrícula del avión con ese código ICAO, o null si no está en la base. */
    fun lookup(icao: Int): String? {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = keyAt(mid)
            if (k < icao) lo = mid + 1
            else if (k > icao) hi = mid - 1
            else {
                val o = HEADER + mid * RECORD + 3
                var end = 0
                while (end < 8 && data[o + end].toInt() != 0) end++
                return String(data, o, end, Charsets.US_ASCII)
            }
        }
        return null
    }

    companion object {
        private const val HEADER = 8
        private const val RECORD = 11

        fun fromStream(input: InputStream): RegistrationDb = RegistrationDb(input.use { it.readBytes() })

        /** Construye una base en memoria (pruebas y herramientas). */
        fun build(entries: Map<Int, String>): RegistrationDb {
            val keys = entries.keys.sorted()
            val out = ByteArray(HEADER + keys.size * RECORD)
            "MAT1".toByteArray(Charsets.US_ASCII).copyInto(out)
            for (b in 0 until 4) out[4 + b] = (keys.size ushr (8 * b)).toByte()
            keys.forEachIndexed { i, k ->
                val o = HEADER + i * RECORD
                out[o] = (k ushr 16).toByte(); out[o + 1] = (k ushr 8).toByte(); out[o + 2] = k.toByte()
                entries.getValue(k).toByteArray(Charsets.US_ASCII).copyInto(out, o + 3, 0, minOf(8, entries.getValue(k).length))
            }
            return RegistrationDb(out)
        }
    }
}
