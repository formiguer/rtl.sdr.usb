package com.formiguer.rtlsdr.adsb

/** Origen de muestras IQ u8: un driver externo por rtl_tcp o el driver USB integrado. */
interface IqSource {
    /** Texto corto para la interfaz (p. ej. "USB directo"). */
    val description: String

    fun setFrequency(hz: Int)
    fun setSampleRate(hz: Int)
    fun setGainMode(manual: Boolean)
    fun setGain(tenthsDb: Int)
    fun setAgcMode(on: Boolean)

    /**
     * Entrega bloques de IQ a [onData] hasta que [isActive] devuelva false o falle (IOException).
     * [onReady] se llama una vez conectado, antes de leer datos, para enviar la configuración inicial.
     */
    fun run(isActive: () -> Boolean, onReady: () -> Unit, onData: (ByteArray, Int) -> Unit)

    /** Puede llamarse desde otro hilo para interrumpir [run]. */
    fun close()
}
