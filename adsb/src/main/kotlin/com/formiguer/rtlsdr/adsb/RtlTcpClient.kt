package com.formiguer.rtlsdr.adsb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Cliente del protocolo rtl_tcp (servidor que expone el RTL-SDR por TCP). */
class RtlTcpClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 1234,
    private val frequencyHz: Int = 1_090_000_000,
    private val sampleRate: Int = 2_000_000,
    private val gainTenthsDb: Int = 496,
) {
    @Volatile
    private var socket: Socket? = null

    /**
     * Conecta, configura el sintonizador y entrega bloques de IQ a [onData] hasta que
     * [isActive] devuelva false o falle la conexión (IOException).
     */
    fun run(isActive: () -> Boolean, onData: (ByteArray, Int) -> Unit) {
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 3000
            val input = s.getInputStream()
            val header = ByteArray(12)
            readFully(input, header, header.size, isActive)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0") {
                throw IOException("El puerto $port no es un servidor rtl_tcp")
            }
            configure(s.getOutputStream())

            val buf = ByteArray(BLOCK)
            while (isActive()) {
                if (!readFully(input, buf, buf.size, isActive)) break
                onData(buf, buf.size)
            }
        } finally {
            close()
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun configure(out: OutputStream) {
        send(out, CMD_SAMPLE_RATE, sampleRate)
        send(out, CMD_FREQUENCY, frequencyHz)
        send(out, CMD_GAIN_MODE, 1) // manual
        send(out, CMD_GAIN, gainTenthsDb)
        send(out, CMD_AGC_MODE, 0)
    }

    private fun send(out: OutputStream, cmd: Int, param: Int) {
        out.write(
            byteArrayOf(
                cmd.toByte(),
                (param ushr 24).toByte(), (param ushr 16).toByte(),
                (param ushr 8).toByte(), param.toByte(),
            )
        )
        out.flush()
    }

    /** @return false si se pidió parar antes de completar el bloque. */
    private fun readFully(input: InputStream, buf: ByteArray, size: Int, isActive: () -> Boolean): Boolean {
        var off = 0
        while (off < size) {
            if (!isActive()) return false
            val n = try {
                input.read(buf, off, size - off)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) throw EOFException("El driver cerró la conexión")
            off += n
        }
        return true
    }

    companion object {
        const val CMD_FREQUENCY = 0x01
        const val CMD_SAMPLE_RATE = 0x02
        const val CMD_GAIN_MODE = 0x03
        const val CMD_GAIN = 0x04
        const val CMD_AGC_MODE = 0x08
        const val BLOCK = 32768 // par: no parte pares I/Q
    }
}
