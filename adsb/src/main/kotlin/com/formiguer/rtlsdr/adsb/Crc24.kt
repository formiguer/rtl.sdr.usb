package com.formiguer.rtlsdr.adsb

/** CRC de 24 bits de Mode S. El resto sobre un mensaje DF17 completo (con paridad) debe ser 0. */
object Crc24 {
    private const val POLY = 0xFFF409

    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 16
            repeat(8) {
                c = if ((c and 0x800000) != 0) (c shl 1) xor POLY else c shl 1
                c = c and 0xFFFFFF
            }
            t[i] = c
        }
    }

    fun remainder(data: ByteArray, length: Int = data.size): Int {
        var c = 0
        for (k in 0 until length) {
            c = ((c shl 8) xor table[((c shr 16) xor (data[k].toInt() and 0xFF)) and 0xFF]) and 0xFFFFFF
        }
        return c
    }
}
