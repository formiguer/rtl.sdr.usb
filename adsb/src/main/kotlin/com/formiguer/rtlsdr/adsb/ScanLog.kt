package com.formiguer.rtlsdr.adsb

/** Registro de las frecuencias en las que el escáner ha encontrado actividad. */
class ScanLog {
    class Entry(val channel: Int, var count: Int, var lastMs: Long)

    private val entries = HashMap<Int, Entry>()

    @Synchronized
    fun record(channel: Int, nowMs: Long) {
        val e = entries.getOrPut(channel) { Entry(channel, 0, nowMs) }
        e.count++
        e.lastMs = nowMs
    }

    /** Más recientes primero. */
    @Synchronized
    fun recent(limit: Int): List<Entry> =
        entries.values.sortedByDescending { it.lastMs }.take(limit).map { Entry(it.channel, it.count, it.lastMs) }

    @Synchronized
    fun clear() = entries.clear()
}
