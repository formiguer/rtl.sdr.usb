package com.formiguer.rtlsdr.adsb

/**
 * Canales de la banda aérea VHF (118.000–136.990 MHz) con rejilla de 8,33 kHz.
 * Un "canal" es un índice entero: portadora = 118 MHz + canal × 8,333 kHz.
 * Los nombres de 8,33 kHz siguen la convención aeronáutica (118.005, 118.010, 118.015…).
 */
object AirbandChannels {
    const val BASE_HZ = 118_000_000L
    const val MIN_CHANNEL = 0
    const val MAX_CHANNEL = 2279 // 136.990
    const val SPACING_HZ = 25_000.0 / 3.0

    fun carrierHz(channel: Int): Long = BASE_HZ + Math.round(channel * SPACING_HZ)

    /** Canal más cercano a [hz], o null si queda fuera de la banda. */
    fun channelOfHz(hz: Double): Int? {
        val ch = Math.round((hz - BASE_HZ) / SPACING_HZ).toInt()
        return if (ch in MIN_CHANNEL..MAX_CHANNEL) ch else null
    }

    fun clamp(channel: Int): Int = channel.coerceIn(MIN_CHANNEL, MAX_CHANNEL)

    /** Nombre en kHz sobre 118000, p. ej. canal 0 → 0 (118.000), canal 1 → 10 (118.010). */
    private fun nameOffsetKHz(channel: Int): Int {
        val block = channel / 3
        return block * 25 + when (channel % 3) { 0 -> 0; 1 -> 10; else -> 15 }
    }

    /** Nombre del canal en MHz, p. ej. "118.010". */
    fun name(channel: Int): String {
        val khz = 118_000 + nameOffsetKHz(channel)
        return "%d.%03d".format(khz / 1000, khz % 1000)
    }

    /** Acepta "118.100", "118,1", "118100" (kHz) o "123.45". Devuelve null si no es válido. */
    fun parse(text: String): Int? {
        val t = text.trim().replace(',', '.').removeSuffix("MHz").removeSuffix("mhz").trim()
        if (t.isEmpty()) return null
        val khz: Long = if (t.contains('.')) {
            val v = t.toDoubleOrNull() ?: return null
            Math.round(v * 1000)
        } else {
            val v = t.toLongOrNull() ?: return null
            if (v >= 100_000) v else v * 1000
        }
        val off = khz - 118_000
        if (off < 0) return null
        val block = (off / 25).toInt()
        val pos = when ((off % 25).toInt()) {
            0, 5 -> 0
            10 -> 1
            15, 20 -> 2
            else -> {
                // frecuencia que no es un nombre de canal: portadora más cercana
                return channelOfHz((khz * 1000).toDouble())
            }
        }
        val ch = block * 3 + pos
        return if (ch in MIN_CHANNEL..MAX_CHANNEL) ch else null
    }
}
