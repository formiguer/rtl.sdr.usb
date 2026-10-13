package com.formiguer.rtlsdr.driver

import java.io.IOException

/**
 * Driver del RTL2832U + sintonizador R820T/R820T2 sobre un [UsbIo].
 * Port a Kotlin de librtlsdr (osmocom/rtl-sdr, GPL-2.0-or-later) limitado al camino R820T/R820T2.
 */
class RtlSdrDriver(
    private val io: UsbIo,
    private val log: (String) -> Unit = {},
) {
    private var rtlXtal = DEF_RTL_XTAL_FREQ
    private var tunXtal = DEF_RTL_XTAL_FREQ
    private var rate = 0L
    private var freq = 0L
    private var bw = 0L
    private val corr = 0 // ppm
    private var tuner: R82xx? = null

    var isOpen = false
        private set

    /** Frecuencia de muestreo real configurada (Hz). */
    val sampleRate: Long get() = rate

    // ---------- acceso a registros ----------

    private fun readArray(block: Int, addr: Int, array: ByteArray, len: Int): Int =
        io.controlTransfer(CTRL_IN, 0, addr, block shl 8, array, len, CTRL_TIMEOUT)

    private fun writeArray(block: Int, addr: Int, array: ByteArray, len: Int): Int =
        io.controlTransfer(CTRL_OUT, 0, addr, (block shl 8) or 0x10, array, len, CTRL_TIMEOUT)

    private fun i2cReadReg(i2cAddr: Int, reg: Int): Int {
        val r = byteArrayOf(reg.toByte())
        writeArray(IICB, i2cAddr, r, 1)
        val data = ByteArray(1)
        readArray(IICB, i2cAddr, data, 1)
        return data[0].toInt() and 0xFF
    }

    private fun readReg(block: Int, addr: Int, len: Int): Int {
        val data = ByteArray(2)
        val r = io.controlTransfer(CTRL_IN, 0, addr, block shl 8, data, len, CTRL_TIMEOUT)
        if (r < 0) log("readReg failed with $r")
        return ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
    }

    private fun writeReg(block: Int, addr: Int, value: Int, len: Int): Int {
        val data = ByteArray(2)
        data[0] = (if (len == 1) value and 0xff else value shr 8).toByte()
        data[1] = (value and 0xff).toByte()
        val r = io.controlTransfer(CTRL_OUT, 0, addr, (block shl 8) or 0x10, data, len, CTRL_TIMEOUT)
        if (r < 0) log("writeReg failed with $r")
        return r
    }

    private fun demodReadReg(page: Int, addr: Int, len: Int): Int {
        val data = ByteArray(2)
        val r = io.controlTransfer(CTRL_IN, 0, (addr shl 8) or 0x20, page, data, len, CTRL_TIMEOUT)
        if (r < 0) log("demodReadReg failed with $r")
        return ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
    }

    private fun demodWriteReg(page: Int, addr: Int, value: Int, len: Int): Int {
        val data = ByteArray(2)
        data[0] = (if (len == 1) value and 0xff else value shr 8).toByte()
        data[1] = (value and 0xff).toByte()
        val r = io.controlTransfer(CTRL_OUT, 0, (addr shl 8) or 0x20, 0x10 or page, data, len, CTRL_TIMEOUT)
        if (r < 0) log("demodWriteReg failed with $r")
        demodReadReg(0x0a, 0x01, 1)
        return if (r == len) 0 else -1
    }

    private fun setI2cRepeater(on: Boolean) {
        demodWriteReg(1, 0x01, if (on) 0x18 else 0x10, 1)
    }

    private fun setFir(): Int {
        val fir = IntArray(20)
        // formato: int8_t[8]
        for (i in 0 until 8) fir[i] = FIR_DEFAULT[i]
        // formato: int12_t[8]
        var i = 0
        while (i < 8) {
            val v0 = FIR_DEFAULT[8 + i]
            val v1 = FIR_DEFAULT[8 + i + 1]
            fir[8 + i * 3 / 2] = v0 shr 4
            fir[8 + i * 3 / 2 + 1] = (v0 shl 4) or ((v1 shr 8) and 0x0f)
            fir[8 + i * 3 / 2 + 2] = v1
            i += 2
        }
        for (k in fir.indices) {
            if (demodWriteReg(1, 0x1c + k, fir[k] and 0xFF, 1) != 0) return -1
        }
        return 0
    }

    private fun initBaseband() {
        // USB
        writeReg(USBB, USB_SYSCTL, 0x09, 1)
        writeReg(USBB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)

        // encender el demodulador
        writeReg(SYSB, DEMOD_CTL_1, 0x22, 1)
        writeReg(SYSB, DEMOD_CTL, 0xe8, 1)

        // reset del demodulador (bit 3, soft_rst)
        demodWriteReg(1, 0x01, 0x14, 1)
        demodWriteReg(1, 0x01, 0x10, 1)

        // sin inversión espectral ni rechazo de canal adyacente
        demodWriteReg(1, 0x15, 0x00, 1)
        demodWriteReg(1, 0x16, 0x0000, 2)

        // borrar el desplazamiento DDC y la frecuencia IF
        for (i in 0 until 6) demodWriteReg(1, 0x16 + i, 0x00, 1)

        setFir()

        // modo SDR, sin DAGC (bit 5)
        demodWriteReg(0, 0x19, 0x05, 1)

        // registros de la FSM
        demodWriteReg(1, 0x93, 0xf0, 1)
        demodWriteReg(1, 0x94, 0x0f, 1)

        // sin AGC (en_dagc, bit 0)
        demodWriteReg(1, 0x11, 0x00, 1)

        // sin bucle AGC de RF ni IF
        demodWriteReg(1, 0x04, 0x00, 1)

        // sin filtro PID
        demodWriteReg(0, 0x61, 0x60, 1)

        // opt_adc_iq = 0
        demodWriteReg(0, 0x06, 0x80, 1)

        // Zero-IF (en_bbin), cancelación de continua e I/Q
        demodWriteReg(1, 0xb1, 0x1b, 1)

        // sin salida de reloj de 4,096 MHz en TP_CK0
        demodWriteReg(0, 0x0d, 0x83, 1)
    }

    private fun xtalFreq(base: Long): Long = (base * (1.0 + corr / 1e6)).toLong()

    private fun setIfFreq(freqHz: Long): Int {
        val rtlXtalCorr = xtalFreq(rtlXtal)
        val ifFreq = (-((freqHz.toDouble() * TWO_POW_22) / rtlXtalCorr.toDouble())).toInt()
        var r = demodWriteReg(1, 0x19, (ifFreq shr 16) and 0x3f, 1)
        r = r or demodWriteReg(1, 0x1a, (ifFreq shr 8) and 0xff, 1)
        r = r or demodWriteReg(1, 0x1b, ifFreq and 0xff, 1)
        return r
    }

    private fun setSampleFreqCorrection(ppm: Int): Int {
        val offs = (ppm * (-1) * TWO_POW_24 / 1_000_000).toInt().toShort().toInt()
        var r = demodWriteReg(1, 0x3f, offs and 0xff, 1)
        r = r or demodWriteReg(1, 0x3e, (offs shr 8) and 0x3f, 1)
        return r
    }

    // ---------- tuner (envoltorios de librtlsdr) ----------

    private fun tunerSetBw(bwHz: Int): Int {
        val t = tuner ?: return -1
        var r = t.setBandwidth(bwHz, rate)
        if (r < 0) return r
        r = setIfFreq(r.toLong())
        if (r != 0) return r
        return setCenterFreqInternal(freq)
    }

    private fun setCenterFreqInternal(f: Long): Int {
        val t = tuner ?: return -1
        setI2cRepeater(true)
        val r = t.setFreq(f)
        setI2cRepeater(false)
        freq = if (r == 0) f else 0
        return r
    }

    // ---------- API pública ----------

    /** Abre el dispositivo: inicializa el demodulador y el sintonizador. Lanza IOException si falla. */
    fun open() {
        // escritura de prueba
        if (writeReg(USBB, USB_SYSCTL, 0x09, 1) < 0) {
            throw IOException("El dongle no responde al primer comando USB")
        }
        initBaseband()

        // buscar el sintonizador
        setI2cRepeater(true)
        if (i2cReadReg(E4K_I2C_ADDR, E4K_CHECK_ADDR) == E4K_CHECK_VAL) {
            setI2cRepeater(false)
            throw IOException("Sintonizador Elonics E4000 no soportado todavía")
        }
        if (i2cReadReg(FC0013_I2C_ADDR, FC0013_CHECK_ADDR) == FC0013_CHECK_VAL) {
            setI2cRepeater(false)
            throw IOException("Sintonizador Fitipower FC0013 no soportado todavía")
        }
        if (i2cReadReg(R82xx.R820T_I2C_ADDR, R82xx.R82XX_CHECK_ADDR) != R82xx.R82XX_CHECK_VAL) {
            val r828d = i2cReadReg(R828D_I2C_ADDR, R82xx.R82XX_CHECK_ADDR) == R82xx.R82XX_CHECK_VAL
            setI2cRepeater(false)
            throw IOException(
                if (r828d) "Sintonizador R828D no soportado todavía"
                else "Sintonizador no reconocido (se esperaba R820T/R820T2)"
            )
        }
        log("Sintonizador Rafael Micro R820T/R820T2 detectado")

        tunXtal = rtlXtal
        // desactivar Zero-IF, solo entrada ADC en fase, IF de 3,57 MHz y espectro invertido
        demodWriteReg(1, 0xb1, 0x1a, 1)
        demodWriteReg(0, 0x08, 0x4d, 1)
        setIfFreq(R82xx.IF_FREQ.toLong())
        demodWriteReg(1, 0x15, 0x01, 1)

        val t = R82xx(
            i2cWrite = { addr, buf, len -> writeArray(IICB, addr, buf, len) },
            i2cRead = { addr, buf, len -> readArray(IICB, addr, buf, len) },
            xtal = xtalFreq(tunXtal),
            log = log,
        )
        tuner = t
        val r = t.init()
        setI2cRepeater(false)
        if (r < 0) throw IOException("No se pudo inicializar el sintonizador R820T (código $r)")
        isOpen = true
    }

    /** Frecuencia de muestreo en Hz (225 001–300 000 o 900 001–3 200 000). */
    fun setSampleRate(sampRate: Long): Int {
        if (sampRate <= 225_000 || sampRate > 3_200_000 || (sampRate in 300_001..900_000)) {
            log("Invalid sample rate: $sampRate Hz")
            return -22
        }
        var rsampRatio = ((rtlXtal.toDouble() * TWO_POW_22) / sampRate.toDouble()).toLong()
        rsampRatio = rsampRatio and 0x0ffffffcL
        val realRsampRatio = rsampRatio or ((rsampRatio and 0x08000000L) shl 1)
        val realRate = (rtlXtal.toDouble() * TWO_POW_22) / realRsampRatio.toDouble()
        rate = realRate.toLong()

        if (tuner != null) {
            setI2cRepeater(true)
            tunerSetBw(if (bw > 0) bw.toInt() else rate.toInt())
            setI2cRepeater(false)
        }

        var r = demodWriteReg(1, 0x9f, ((rsampRatio shr 16) and 0xffff).toInt(), 2)
        r = r or demodWriteReg(1, 0xa1, (rsampRatio and 0xffff).toInt(), 2)
        r = r or setSampleFreqCorrection(corr)

        // reset del demodulador (bit 3, soft_rst)
        r = r or demodWriteReg(1, 0x01, 0x14, 1)
        r = r or demodWriteReg(1, 0x01, 0x10, 1)
        return r
    }

    fun setCenterFreq(f: Long): Int = setCenterFreqInternal(f)

    /** Ganancia manual del sintonizador en décimas de dB (se redondea al paso más cercano). */
    fun setTunerGain(gainTenths: Int): Int {
        val t = tuner ?: return -1
        setI2cRepeater(true)
        val r = t.setGain(true, gainTenths)
        setI2cRepeater(false)
        return r
    }

    /** true = manual, false = AGC del sintonizador. */
    fun setTunerGainMode(manual: Boolean): Int {
        val t = tuner ?: return -1
        setI2cRepeater(true)
        val r = t.setGain(manual, 0)
        setI2cRepeater(false)
        return r
    }

    /** AGC digital del RTL2832U. */
    fun setAgcMode(on: Boolean): Int = demodWriteReg(0, 0x19, if (on) 0x25 else 0x05, 1)

    /** Vacía el búfer del endpoint de datos; llamar antes de empezar a leer. */
    fun resetBuffer() {
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x0000, 2)
    }

    /** Lee IQ (u8 entrelazado). @return bytes leídos o <0 si hay error. */
    fun read(buffer: ByteArray, length: Int, timeoutMs: Int = 1000): Int = io.bulkRead(buffer, length, timeoutMs)

    fun close() {
        if (!isOpen) return
        isOpen = false
        setI2cRepeater(true)
        tuner?.standby()
        setI2cRepeater(false)
        writeReg(SYSB, DEMOD_CTL, 0x20, 1) // apagar demodulador y ADC
    }

    companion object {
        const val DEF_RTL_XTAL_FREQ = 28_800_000L
        private const val TWO_POW_22 = 4194304.0
        private const val TWO_POW_24 = 16777216.0

        private const val CTRL_IN = 0xC0 // vendor | device | in
        private const val CTRL_OUT = 0x40 // vendor | device | out
        private const val CTRL_TIMEOUT = 300

        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158
        private const val DEMOD_CTL = 0x3000
        private const val DEMOD_CTL_1 = 0x300b

        private const val USBB = 1
        private const val SYSB = 2
        private const val IICB = 6

        private const val E4K_I2C_ADDR = 0xc8
        private const val E4K_CHECK_ADDR = 0x02
        private const val E4K_CHECK_VAL = 0x40
        private const val FC0013_I2C_ADDR = 0xc6
        private const val FC0013_CHECK_ADDR = 0x00
        private const val FC0013_CHECK_VAL = 0xa3
        private const val R828D_I2C_ADDR = 0x74

        private val FIR_DEFAULT = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53, // 8 bits con signo
            101, 156, 215, 273, 327, 372, 404, 421, // 12 bits con signo
        )
    }
}
