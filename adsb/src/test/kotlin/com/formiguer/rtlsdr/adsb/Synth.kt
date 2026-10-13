package com.formiguer.rtlsdr.adsb

import java.util.Random

fun hex(s: String): ByteArray =
    ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Genera IQ u8 a 2 Msps con los mensajes dados (preámbulo + PPM), separados por silencio. */
object Synth {
    fun iq(messages: List<ByteArray>, gap: Int = 300, amp: Int = 100, noise: Int = 3, seed: Long = 1): ByteArray {
        val rnd = Random(seed)
        val samples = ArrayList<Int>()
        repeat(gap) { samples.add(0) }
        for (msg in messages) {
            val pre = IntArray(16)
            for (k in intArrayOf(0, 2, 7, 9)) pre[k] = amp
            pre.forEach { samples.add(it) }
            for (b in 0 until 112) {
                val bit = (msg[b shr 3].toInt() shr (7 - (b and 7))) and 1
                if (bit == 1) { samples.add(amp); samples.add(0) } else { samples.add(0); samples.add(amp) }
            }
            repeat(gap) { samples.add(0) }
        }
        val out = ByteArray(samples.size * 2)
        for ((k, a) in samples.withIndex()) {
            out[2 * k] = (127 + a + rnd.nextInt(2 * noise + 1) - noise).toByte()
            out[2 * k + 1] = (127 + rnd.nextInt(2 * noise + 1) - noise).toByte()
        }
        return out
    }
}
