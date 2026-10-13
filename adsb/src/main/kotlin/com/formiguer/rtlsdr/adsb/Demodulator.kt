package com.formiguer.rtlsdr.adsb

import kotlin.math.abs

/**
 * Demodulador Mode S para IQ de 8 bits sin signo a 2 Msps (formato de rtl_tcp).
 * Detecta preámbulos y entrega solo mensajes DF17 de 112 bits con CRC correcto.
 */
class Demodulator(private val onMessage: (ByteArray) -> Unit) {
    private val lut = IntArray(129 * 129) { idx ->
        val i = idx / 129
        val q = idx % 129
        Math.round(Math.sqrt((i * i + q * q).toDouble()) * 360).toInt()
    }
    private var mag = IntArray(0)
    private val tail = IntArray(WINDOW)
    private var tailLen = 0

    /** Procesa [length] bytes de IQ entrelazado (I, Q, I, Q…); [length] debe ser par. */
    fun process(data: ByteArray, length: Int) {
        val n = length / 2
        val total = tailLen + n
        if (mag.size < total) mag = IntArray(total + total / 2)
        val m = mag
        System.arraycopy(tail, 0, m, 0, tailLen)

        var p = 0
        for (k in 0 until n) {
            val i = abs((data[p].toInt() and 0xFF) - 127)
            val q = abs((data[p + 1].toInt() and 0xFF) - 127)
            p += 2
            m[tailLen + k] = lut[i * 129 + q]
        }

        val limit = total - WINDOW
        var j = 0
        while (j < limit) {
            if (!preamble(m, j)) {
                j++
                continue
            }
            val msg = decode(m, j)
            if (msg != null) {
                onMessage(msg)
                j += WINDOW
            } else {
                j++
            }
        }
        tailLen = total - j
        System.arraycopy(m, j, tail, 0, tailLen)
    }

    private fun preamble(m: IntArray, j: Int): Boolean {
        if (!(m[j] > m[j + 1] && m[j + 1] < m[j + 2] && m[j + 2] > m[j + 3] && m[j + 3] < m[j] &&
                m[j + 4] < m[j] && m[j + 5] < m[j] && m[j + 6] < m[j] &&
                m[j + 7] > m[j + 8] && m[j + 8] < m[j + 9] && m[j + 9] > m[j + 6])
        ) return false
        val high = (m[j] + m[j + 2] + m[j + 7] + m[j + 9]) / 6
        if (m[j + 4] >= high || m[j + 5] >= high) return false
        if (m[j + 11] >= high || m[j + 12] >= high || m[j + 13] >= high || m[j + 14] >= high) return false
        return true
    }

    private fun decode(m: IntArray, j: Int): ByteArray? {
        val msg = ByteArray(14)
        var prev = 0
        for (b in 0 until 112) {
            val low = m[j + PREAMBLE + 2 * b]
            val high = m[j + PREAMBLE + 2 * b + 1]
            val bit = when {
                b > 0 && abs(low - high) < 256 -> prev
                low == high -> return null
                low > high -> 1
                else -> 0
            }
            if (bit == 1) msg[b shr 3] = (msg[b shr 3].toInt() or (0x80 shr (b and 7))).toByte()
            prev = bit
            if (b == 4 && ((msg[0].toInt() and 0xFF) shr 3) != 17) return null
        }
        return if (Crc24.remainder(msg, 14) == 0) msg else null
    }

    companion object {
        const val PREAMBLE = 16
        const val WINDOW = PREAMBLE + 2 * 112
    }
}
