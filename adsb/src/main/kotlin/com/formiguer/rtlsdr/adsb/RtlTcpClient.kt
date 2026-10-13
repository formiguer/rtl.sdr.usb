package com.formiguer.rtlsdr.adsb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Cliente del protocolo rtl_tcp (servidor que expone el RTL-SDR por TCP).
 * Los comandos se encolan desde cualquier hilo y se envían desde el hilo de lectura.
 */
class RtlTcpClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 1234,
) : IqSource {
    override val description: String get() = "driver externo rtl_tcp ($host:$port)"

    @Volatile
    private var socket: Socket? = null
    private val pending = ConcurrentLinkedQueue<ByteArray>()

    override fun setFrequency(hz: Int) = queue(CMD_FREQUENCY, hz)
    override fun setSampleRate(hz: Int) = queue(CMD_SAMPLE_RATE, hz)
    override fun setGainMode(manual: Boolean) = queue(CMD_GAIN_MODE, if (manual) 1 else 0)
    override fun setGain(tenthsDb: Int) = queue(CMD_GAIN, tenthsDb)
    override fun setAgcMode(on: Boolean) = queue(CMD_AGC_MODE, if (on) 1 else 0)

    private fun queue(cmd: Int, param: Int) {
        pending.add(
            byteArrayOf(
                cmd.toByte(),
                (param ushr 24).toByte(), (param ushr 16).toByte(),
                (param ushr 8).toByte(), param.toByte(),
            )
        )
    }

    /**
     * Conecta y entrega bloques de IQ a [onData] hasta que [isActive] devuelva false o falle la
     * conexión (IOException). [onReady] se llama tras la cabecera, antes de leer datos, para
     * que el llamante encole la configuración inicial.
     */
    override fun run(isActive: () -> Boolean, onReady: () -> Unit, onData: (ByteArray, Int) -> Unit) {
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 3000
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val header = ByteArray(12)
            readFully(input, header, header.size, isActive)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0") {
                throw IOException("El puerto $port no es un servidor rtl_tcp")
            }
            onReady()
            flush(out)

            val buf = ByteArray(BLOCK)
            while (isActive()) {
                if (!readFully(input, buf, buf.size, isActive)) break
                onData(buf, buf.size)
                flush(out)
            }
        } finally {
            close()
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun flush(out: OutputStream) {
        var wrote = false
        while (true) {
            val cmd = pending.poll() ?: break
            out.write(cmd)
            wrote = true
        }
        if (wrote) out.flush()
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
