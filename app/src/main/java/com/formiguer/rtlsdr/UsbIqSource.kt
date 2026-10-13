package com.formiguer.rtlsdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.formiguer.rtlsdr.adsb.IqSource
import com.formiguer.rtlsdr.adsb.RtlTcpClient
import com.formiguer.rtlsdr.driver.RtlSdrDriver
import com.formiguer.rtlsdr.driver.UsbIo
import java.io.IOException
import java.nio.ByteBuffer

/** [UsbIo] sobre una conexión USB de Android. */
class AndroidUsbIo(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
) : UsbIo {
    override fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        data: ByteArray, length: Int, timeoutMs: Int,
    ): Int = connection.controlTransfer(requestType, request, value, index, data, length, timeoutMs)

    override fun bulkRead(buffer: ByteArray, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(endpoint, buffer, length, timeoutMs)
}

/**
 * Fuente de IQ con el driver USB integrado: abre el RTL-SDR directamente (sin app externa)
 * y lee con varias peticiones USB en vuelo para no perder muestras.
 */
class UsbIqSource private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: android.hardware.usb.UsbInterface,
    private val endpoint: UsbEndpoint,
    private val driver: RtlSdrDriver,
) : IqSource {

    @Volatile private var closing = false
    private val requests = java.util.concurrent.CopyOnWriteArrayList<UsbRequest>()

    override val description: String get() = "USB directo (driver integrado)"

    override fun setFrequency(hz: Int) {
        driver.setCenterFreq(hz.toLong())
    }

    override fun setSampleRate(hz: Int) {
        driver.setSampleRate(hz.toLong())
    }

    override fun setGainMode(manual: Boolean) {
        driver.setTunerGainMode(manual)
    }

    override fun setGain(tenthsDb: Int) {
        driver.setTunerGain(tenthsDb)
    }

    override fun setAgcMode(on: Boolean) {
        driver.setAgcMode(on)
    }

    override fun run(isActive: () -> Boolean, onReady: () -> Unit, onData: (ByteArray, Int) -> Unit) {
        try {
            onReady()
            driver.resetBuffer()

            val buffers = Array(IN_FLIGHT) { ByteBuffer.allocateDirect(REQUEST_SIZE) }
            for (i in 0 until IN_FLIGHT) {
                val r = UsbRequest()
                if (!r.initialize(connection, endpoint)) throw IOException("No se pudo preparar la lectura USB")
                r.clientData = i
                requests.add(r)
                buffers[i].clear()
                if (!r.queue(buffers[i], REQUEST_SIZE)) throw IOException("No se pudo encolar la lectura USB")
            }

            val out = ByteArray(RtlTcpClient.BLOCK)
            var fill = 0
            var empty = 0
            while (isActive() && !closing) {
                val done = connection.requestWait() ?: throw IOException("Lectura USB interrumpida")
                val i = done.clientData as Int
                val buf = buffers[i]
                val got = buf.position()
                if (got <= 0) {
                    if (++empty > 50) throw IOException("El dongle no envía datos")
                } else {
                    empty = 0
                    buf.flip()
                    while (buf.hasRemaining()) {
                        val n = minOf(buf.remaining(), out.size - fill)
                        buf.get(out, fill, n)
                        fill += n
                        if (fill == out.size) {
                            onData(out, fill)
                            fill = 0
                        }
                    }
                }
                buf.clear()
                if (!isActive() || closing) break
                if (!done.queue(buf, REQUEST_SIZE)) throw IOException("No se pudo volver a encolar la lectura USB")
            }
        } finally {
            release()
        }
    }

    override fun close() {
        closing = true
        for (r in requests) {
            try {
                r.cancel()
            } catch (_: Exception) {
            }
        }
    }

    private fun release() {
        closing = true
        for (r in requests) {
            try {
                r.cancel()
                r.close()
            } catch (_: Exception) {
            }
        }
        requests.clear()
        try {
            driver.close()
        } catch (_: Exception) {
        }
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        connection.close()
    }

    companion object {
        private const val IN_FLIGHT = 8
        private const val REQUEST_SIZE = 16384

        /** Abre el dongle y lo inicializa. Lanza IOException con un mensaje legible si falla. */
        fun open(manager: UsbManager, device: UsbDevice, log: (String) -> Unit): UsbIqSource {
            val connection = manager.openDevice(device) ?: throw IOException("No se pudo abrir el dispositivo USB")
            try {
                val iface = device.getInterface(0)
                if (!connection.claimInterface(iface, true)) throw IOException("No se pudo reclamar la interfaz USB")
                var endpoint: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                        endpoint = ep
                        break
                    }
                }
                if (endpoint == null) throw IOException("El dongle no tiene endpoint de datos")
                val driver = RtlSdrDriver(AndroidUsbIo(connection, endpoint), log)
                driver.open()
                return UsbIqSource(connection, iface, endpoint, driver)
            } catch (e: Exception) {
                connection.close()
                throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
