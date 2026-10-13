package com.formiguer.rtlsdr.driver

/** Acceso USB mínimo que necesita el driver (en Android lo implementa UsbDeviceConnection). */
interface UsbIo {
    /** @return bytes transferidos o un valor negativo si falla. */
    fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        data: ByteArray, length: Int, timeoutMs: Int,
    ): Int

    /** Lectura bulk del endpoint 0x81 (IQ). @return bytes leídos o negativo si falla. */
    fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int
}
