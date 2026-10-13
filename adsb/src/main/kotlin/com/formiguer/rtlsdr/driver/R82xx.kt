package com.formiguer.rtlsdr.driver

/**
 * Sintonizador Rafael Micro R820T / R820T2.
 * Port a Kotlin de tuner_r82xx.c de librtlsdr (osmocom/rtl-sdr, GPL-2.0-or-later),
 * basado a su vez en el driver de Linux r820t.c (Mauro Carvalho Chehab) y en el trabajo de Steve Markgraf.
 */
internal class R82xx(
    private val i2cWrite: (addr: Int, buf: ByteArray, len: Int) -> Int,
    private val i2cRead: (addr: Int, buf: ByteArray, len: Int) -> Int,
    private val xtal: Long,
    private val i2cAddr: Int = R820T_I2C_ADDR,
    private val log: (String) -> Unit = {},
) {
    private val regs = IntArray(NUM_REGS)
    private val buf = ByteArray(NUM_REGS + 1)
    private val rdBuf = ByteArray(NUM_REGS + 1)
    private val maxI2cMsgLen = 8

    private var xtalCapSel = XTAL_HIGH_CAP_0P
    private var filCalCode = 0
    private var input = 0
    var hasLock = false
        private set
    private var initDone = false
    private var type = 0
    private var bw = 0
    var intFreq = 0
        private set

    // ---------- I2C y registros sombra ----------

    private fun shadowStore(reg: Int, v: IntArray, len0: Int) {
        var r = reg - REG_SHADOW_START
        var len = len0
        var off = 0
        if (r < 0) {
            len += r
            off = -r
            r = 0
        }
        if (len <= 0) return
        if (len > NUM_REGS - r) len = NUM_REGS - r
        for (i in 0 until len) regs[r + i] = v[off + i]
    }

    private fun shadowEqual(reg: Int, v: IntArray, len: Int): Boolean {
        val r = reg - REG_SHADOW_START
        if (r < 0 || len < 0 || len > NUM_REGS - r) return false
        for (i in 0 until len) if (regs[r + i] != v[i]) return false
        return true
    }

    private fun write(reg0: Int, v: IntArray, len0: Int): Int {
        // evita escribir registros sin cambios (es lento)
        if (shadowEqual(reg0, v, len0)) return 0
        shadowStore(reg0, v, len0)

        var reg = reg0
        var len = len0
        var pos = 0
        do {
            val size = if (len > maxI2cMsgLen - 1) maxI2cMsgLen - 1 else len
            buf[0] = reg.toByte()
            for (i in 0 until size) buf[1 + i] = v[pos + i].toByte()
            val rc = i2cWrite(i2cAddr, buf, size + 1)
            if (rc != size + 1) {
                log("r82xx: i2c wr failed=$rc reg=%02x len=$size".format(reg))
                return if (rc < 0) rc else -1
            }
            reg += size
            len -= size
            pos += size
        } while (len > 0)
        return 0
    }

    private fun writeReg(reg: Int, v: Int): Int = write(reg, intArrayOf(v and 0xFF), 1)

    private fun readCacheReg(reg: Int): Int {
        val r = reg - REG_SHADOW_START
        return if (r in 0 until NUM_REGS) regs[r] else -1
    }

    private fun writeRegMask(reg: Int, v: Int, bitMask: Int): Int {
        val rc = readCacheReg(reg)
        if (rc < 0) return rc
        val nv = (rc and bitMask.inv()) or (v and bitMask)
        return write(reg, intArrayOf(nv and 0xFF), 1)
    }

    private fun bitrev(b: Int): Int {
        val lut = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
        return (lut[b and 0xf] shl 4) or lut[(b and 0xff) shr 4]
    }

    private fun read(reg: Int, out: IntArray, len: Int): Int {
        buf[0] = reg.toByte()
        var rc = i2cWrite(i2cAddr, buf, 1)
        if (rc != 1) {
            log("r82xx: i2c wr failed=$rc reg=%02x len=1".format(reg))
            return if (rc < 0) rc else -1
        }
        rc = i2cRead(i2cAddr, rdBuf, len)
        if (rc != len) {
            log("r82xx: i2c rd failed=$rc reg=%02x len=$len".format(reg))
            return if (rc < 0) rc else -1
        }
        for (i in 0 until len) out[i] = bitrev(rdBuf[i].toInt() and 0xFF)
        return 0
    }

    // ---------- sintonía ----------

    private fun setMux(freq0: Long): Int {
        val freq = freq0 / 1_000_000
        var i = 0
        while (i < FREQ_RANGES.size - 1) {
            if (freq < FREQ_RANGES[i + 1][0]) break
            i++
        }
        val range = FREQ_RANGES[i]
        val openD = range[1]
        val rfMuxPloy = range[2]
        val tfC = range[3]
        val cap20 = range[4]
        val cap10 = range[5]
        val cap0 = range[6]

        var rc = writeRegMask(0x17, openD, 0x08)
        if (rc < 0) return rc
        rc = writeRegMask(0x1a, rfMuxPloy, 0xc3)
        if (rc < 0) return rc
        rc = writeReg(0x1b, tfC)
        if (rc < 0) return rc

        val v = when (xtalCapSel) {
            XTAL_LOW_CAP_30P, XTAL_LOW_CAP_20P -> cap20 or 0x08
            XTAL_LOW_CAP_10P -> cap10 or 0x08
            XTAL_HIGH_CAP_0P -> cap0 or 0x00
            else -> cap0 or 0x08
        }
        rc = writeRegMask(0x10, v, 0x0b)
        if (rc < 0) return rc
        rc = writeRegMask(0x08, 0x00, 0x3f)
        if (rc < 0) return rc
        return writeRegMask(0x09, 0x00, 0x3f)
    }

    private fun maskReg8(reg: Int, v: Int, mask: Int): Int = ((reg and mask.inv()) or (v and mask)) and 0xFF

    private fun setPll(freq: Long): Int {
        var mixDiv = 2
        var divBuf: Int
        var divNum = 0
        var vcoPowerRef = 2
        val refdiv2 = 0
        val vcoMin = 1_770_000L // kHz
        val vcoMax = vcoMin * 2
        val data = IntArray(5)
        val r = IntArray(7)

        val freqKhz = (freq + 500) / 1000
        val pllRef = xtal

        // autotune PLL = 128 kHz
        var rc = writeRegMask(0x1a, 0x00, 0x0c)
        if (rc < 0) return rc

        // regs 0x10..0x16
        for (i in 0 until 7) r[i] = regs[0x10 - REG_SHADOW_START + i]
        r[0] = maskReg8(r[0], refdiv2, 0x10)
        // corriente del VCO = 100
        r[2] = maskReg8(r[2], 0x80, 0xe0)

        // divisor
        while (mixDiv <= 64) {
            if ((freqKhz * mixDiv) >= vcoMin && (freqKhz * mixDiv) < vcoMax) {
                divBuf = mixDiv
                while (divBuf > 2) {
                    divBuf = divBuf shr 1
                    divNum++
                }
                break
            }
            mixDiv = mixDiv shl 1
        }

        rc = read(0x00, data, 5)
        if (rc < 0) return rc

        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum = (divNum - 1) and 0xFF
        else if (vcoFineTune < vcoPowerRef) divNum = (divNum + 1) and 0xFF

        r[0] = maskReg8(r[0], (divNum shl 5) and 0xFF, 0xe0)

        val vcoFreq = freq * mixDiv
        val vcoDiv = (pllRef + 65536L * vcoFreq) / (2 * pllRef)
        val nint = ((vcoDiv / 65536).toInt()) and 0xFF // uint8_t en el original
        val sdm = (vcoDiv % 65536).toInt()

        if (nint > (128 / vcoPowerRef) - 1) {
            log("[R82XX] No valid PLL values for $freq Hz!")
            return -1
        }

        val ni = ((nint - 13) / 4) and 0xFF
        val si = (nint - 4 * ni - 13) and 0xFF
        r[4] = (ni + (si shl 6)) and 0xFF

        // pw_sdm
        r[2] = maskReg8(r[2], if (sdm == 0) 0x08 else 0x00, 0x08)
        r[5] = sdm and 0xff
        r[6] = (sdm shr 8) and 0xFF

        rc = write(0x10, r, 7)
        if (rc < 0) return rc

        for (i in 0 until 2) {
            // ¿PLL enganchado?
            rc = read(0x00, data, 3)
            if (rc < 0) return rc
            if ((data[2] and 0x40) != 0) break
            if (i == 0) {
                // no enganchó: más corriente en el VCO
                rc = writeRegMask(0x12, 0x60, 0xe0)
                if (rc < 0) return rc
            }
        }

        if ((data[2] and 0x40) == 0) {
            log("[R82XX] PLL not locked!")
            hasLock = false
            return 0
        }
        hasLock = true

        // autotune PLL = 8 kHz
        return writeRegMask(0x1a, 0x08, 0x08)
    }

    private fun sysfreqSel(freq: Long, tunerType: Int, delsys: Int): Int {
        val mixerTop: Int
        val lnaTop: Int
        val cpCur: Int
        val divBufCur: Int
        val lnaVthL: Int
        val mixerVthL: Int
        val airCable1In: Int
        val cable2In: Int
        val lnaDischarge: Int
        val filterCur: Int
        // delsys == SYS_DVBT (1): se usa solo el caso general del driver original
        when (delsys) {
            SYS_DVBT -> {
                if (freq == 506_000_000L || freq == 666_000_000L || freq == 818_000_000L) {
                    mixerTop = 0x14; lnaTop = 0xe5; cpCur = 0x28; divBufCur = 0x20
                } else {
                    mixerTop = 0x24; lnaTop = 0xe5; cpCur = 0x38; divBufCur = 0x30
                }
                lnaVthL = 0x53; mixerVthL = 0x75; airCable1In = 0x00; cable2In = 0x00
                lnaDischarge = 14; filterCur = 0x40
            }
            else -> {
                mixerTop = 0x24; lnaTop = 0xe5; lnaVthL = 0x53; mixerVthL = 0x75
                airCable1In = 0x00; cable2In = 0x00; lnaDischarge = 14
                cpCur = 0x38; divBufCur = 0x30; filterCur = 0x40
            }
        }

        var rc = writeRegMask(0x1d, lnaTop, 0xc7)
        if (rc < 0) return rc
        rc = writeRegMask(0x1c, mixerTop, 0xf8)
        if (rc < 0) return rc
        rc = writeReg(0x0d, lnaVthL)
        if (rc < 0) return rc
        rc = writeReg(0x0e, mixerVthL)
        if (rc < 0) return rc

        input = airCable1In

        rc = writeRegMask(0x05, airCable1In, 0x60)
        if (rc < 0) return rc
        rc = writeRegMask(0x06, cable2In, 0x08)
        if (rc < 0) return rc
        rc = writeRegMask(0x11, cpCur, 0x38)
        if (rc < 0) return rc
        rc = writeRegMask(0x17, divBufCur, 0x30)
        if (rc < 0) return rc
        rc = writeRegMask(0x0a, filterCur, 0x60)
        if (rc < 0) return rc

        // LNA
        if (tunerType != TUNER_ANALOG_TV) {
            rc = writeRegMask(0x1d, 0, 0x38); if (rc < 0) return rc
            rc = writeRegMask(0x1c, 0, 0x04); if (rc < 0) return rc
            rc = writeRegMask(0x06, 0, 0x40); if (rc < 0) return rc
            rc = writeRegMask(0x1a, 0x30, 0x30); if (rc < 0) return rc
            rc = writeRegMask(0x1d, 0x18, 0x38); if (rc < 0) return rc
            rc = writeRegMask(0x1c, mixerTop, 0x04); if (rc < 0) return rc
            rc = writeRegMask(0x1e, lnaDischarge, 0x1f); if (rc < 0) return rc
            rc = writeRegMask(0x1a, 0x20, 0x30); if (rc < 0) return rc
        } else {
            rc = writeRegMask(0x06, 0, 0x40); if (rc < 0) return rc
            rc = writeRegMask(0x1d, lnaTop, 0x38); if (rc < 0) return rc
            rc = writeRegMask(0x1c, mixerTop, 0x04); if (rc < 0) return rc
            rc = writeRegMask(0x1e, lnaDischarge, 0x1f); if (rc < 0) return rc
            rc = writeRegMask(0x1a, 0x00, 0x30); if (rc < 0) return rc
            rc = writeRegMask(0x10, 0x00, 0x04); if (rc < 0) return rc
        }
        return 0
    }

    private fun setTvStandard(bw0: Int, tunerType: Int, delsys: Int): Int {
        val ifKhz = 3570
        val filtCalLo = 56000L // 52000 -> 56000
        val filtGain = 0x10
        val imgR = 0x00
        val filtQ = 0x10
        val hpCor = 0x6b
        val extEnable = 0x60
        val loopThrough = 0x01
        val ltAtt = 0x00
        val fltExtWidest = 0x00
        val polyfilCur = 0x60
        val data = IntArray(5)

        // registros sombra a su valor inicial
        for (i in INIT_ARRAY.indices) regs[i] = INIT_ARRAY[i]

        var rc = writeRegMask(0x0c, 0x00, 0x0f)
        if (rc < 0) return rc
        rc = writeRegMask(0x13, VER_NUM, 0x3f)
        if (rc < 0) return rc

        if (tunerType != TUNER_ANALOG_TV) {
            rc = writeRegMask(0x1d, 0x00, 0x38)
            if (rc < 0) return rc
        }
        intFreq = ifKhz * 1000

        // la calibración del filtro se fuerza siempre (solo se llama una vez)
        for (i in 0 until 2) {
            rc = writeRegMask(0x0b, hpCor, 0x60); if (rc < 0) return rc
            rc = writeRegMask(0x0f, 0x04, 0x04); if (rc < 0) return rc
            rc = writeRegMask(0x10, 0x00, 0x03); if (rc < 0) return rc

            rc = setPll(filtCalLo * 1000)
            if (rc < 0 || !hasLock) return rc

            rc = writeRegMask(0x0b, 0x10, 0x10); if (rc < 0) return rc
            rc = writeRegMask(0x0b, 0x00, 0x10); if (rc < 0) return rc
            rc = writeRegMask(0x0f, 0x00, 0x04); if (rc < 0) return rc

            rc = read(0x00, data, 5)
            if (rc < 0) return rc

            filCalCode = data[4] and 0x0f
            if (filCalCode != 0 && filCalCode != 0x0f) break
        }
        if (filCalCode == 0x0f) filCalCode = 0

        rc = writeRegMask(0x0a, filtQ or filCalCode, 0x1f); if (rc < 0) return rc
        rc = writeRegMask(0x0b, hpCor, 0xef); if (rc < 0) return rc
        rc = writeRegMask(0x07, imgR, 0x80); if (rc < 0) return rc
        rc = writeRegMask(0x06, filtGain, 0x30); if (rc < 0) return rc
        rc = writeRegMask(0x1e, extEnable, 0x60); if (rc < 0) return rc
        rc = writeRegMask(0x05, loopThrough, 0x80); if (rc < 0) return rc
        rc = writeRegMask(0x1f, ltAtt, 0x80); if (rc < 0) return rc
        rc = writeRegMask(0x0f, fltExtWidest, 0x80); if (rc < 0) return rc
        rc = writeRegMask(0x19, polyfilCur, 0x60); if (rc < 0) return rc

        type = tunerType
        bw = bw0
        @Suppress("UNUSED_VARIABLE") val d = delsys
        return 0
    }

    // ---------- API pública ----------

    fun init(): Int {
        xtalCapSel = XTAL_HIGH_CAP_0P
        regs.fill(0)
        write(0x05, INIT_ARRAY, INIT_ARRAY.size)

        var rc = setTvStandard(3, TUNER_DIGITAL_TV, 0)
        if (rc < 0) {
            log("r82xx_init: failed=$rc")
            return rc
        }
        rc = sysfreqSel(0, TUNER_DIGITAL_TV, SYS_DVBT)
        initDone = true
        if (rc < 0) log("r82xx_init: failed=$rc")
        return rc
    }

    fun standby(): Int {
        if (!initDone) return 0
        var rc = writeReg(0x06, 0xb1); if (rc < 0) return rc
        rc = writeReg(0x05, 0xa0); if (rc < 0) return rc
        rc = writeReg(0x07, 0x3a); if (rc < 0) return rc
        rc = writeReg(0x08, 0x40); if (rc < 0) return rc
        rc = writeReg(0x09, 0xc0); if (rc < 0) return rc
        rc = writeReg(0x0a, 0x36); if (rc < 0) return rc
        rc = writeReg(0x0c, 0x35); if (rc < 0) return rc
        rc = writeReg(0x0f, 0x68); if (rc < 0) return rc
        rc = writeReg(0x11, 0x03); if (rc < 0) return rc
        rc = writeReg(0x17, 0xf4); if (rc < 0) return rc
        rc = writeReg(0x19, 0x0c)
        type = -1 // fuerza calibración inicial
        return rc
    }

    /** Sintoniza [freq] Hz (RF); la LO se desplaza el IF configurado. */
    fun setFreq(freq: Long): Int {
        val loFreq = freq + intFreq
        var rc = setMux(loFreq)
        if (rc >= 0) {
            rc = setPll(loFreq)
            // Con R820T/R820T2 estándar no hay más pasos (solo el R828D conmuta entradas)
            if (rc >= 0 && !hasLock) { /* igual que el original: se devuelve rc */ }
        }
        if (rc < 0) log("r82xx_set_freq: failed=$rc")
        return rc
    }

    /** [manual]=true: LNA+mezclador al [gain] más cercano (décimas de dB); false: AGC del sintonizador. */
    fun setGain(manual: Boolean, gain: Int): Int {
        var rc: Int
        if (manual) {
            var totalGain = 0
            var mixIndex = 0
            var lnaIndex = 0
            val data = IntArray(4)

            rc = writeRegMask(0x05, 0x10, 0x10); if (rc < 0) return rc // LNA auto off
            rc = writeRegMask(0x07, 0, 0x10); if (rc < 0) return rc // mezclador auto off
            rc = read(0x00, data, 4); if (rc < 0) return rc
            rc = writeRegMask(0x0c, 0x08, 0x9f); if (rc < 0) return rc // VGA fija 16,3 dB

            for (i in 0 until 15) {
                if (totalGain >= gain) break
                totalGain += LNA_GAIN_STEPS[++lnaIndex]
                if (totalGain >= gain) break
                totalGain += MIXER_GAIN_STEPS[++mixIndex]
            }
            rc = writeRegMask(0x05, lnaIndex, 0x0f); if (rc < 0) return rc
            rc = writeRegMask(0x07, mixIndex, 0x0f); if (rc < 0) return rc
        } else {
            rc = writeRegMask(0x05, 0, 0x10); if (rc < 0) return rc // LNA
            rc = writeRegMask(0x07, 0x10, 0x10); if (rc < 0) return rc // mezclador
            rc = writeRegMask(0x0c, 0x0b, 0x9f); if (rc < 0) return rc // VGA 26,5 dB
        }
        return 0
    }

    /** @return la frecuencia intermedia a configurar en el demodulador (Hz), o <0 si falla. */
    fun setBandwidth(bw0: Int, @Suppress("UNUSED_PARAMETER") rate: Long): Int {
        var bw = bw0
        var realBw = 0
        val reg0a: Int
        var reg0b: Int

        if (bw > 7_000_000) {
            reg0a = 0x10; reg0b = 0x0b; intFreq = 4_570_000
        } else if (bw > 6_000_000) {
            reg0a = 0x10; reg0b = 0x2a; intFreq = 4_570_000
        } else if (bw > IF_LOW_PASS_BW[0] + FILT_HP_BW1 + FILT_HP_BW2) {
            reg0a = 0x10; reg0b = 0x6b; intFreq = 3_570_000
        } else {
            reg0a = 0x00; reg0b = 0x80; intFreq = 2_300_000

            if (bw > IF_LOW_PASS_BW[0] + FILT_HP_BW1) {
                bw -= FILT_HP_BW2
                intFreq += FILT_HP_BW2
                realBw += FILT_HP_BW2
            } else {
                reg0b = reg0b or 0x20
            }

            if (bw > IF_LOW_PASS_BW[0]) {
                bw -= FILT_HP_BW1
                intFreq += FILT_HP_BW1
                realBw += FILT_HP_BW1
            } else {
                reg0b = reg0b or 0x40
            }

            // filtro paso bajo
            var i = 0
            while (i < IF_LOW_PASS_BW.size) {
                if (bw > IF_LOW_PASS_BW[i]) break
                i++
            }
            --i
            reg0b = reg0b or (15 - i)
            realBw += IF_LOW_PASS_BW[i]

            intFreq -= realBw / 2
        }

        var rc = writeRegMask(0x0a, reg0a, 0x10)
        if (rc < 0) return rc
        rc = writeRegMask(0x0b, reg0b, 0xef)
        if (rc < 0) return rc
        return intFreq
    }

    companion object {
        const val R820T_I2C_ADDR = 0x34
        const val R82XX_CHECK_ADDR = 0x00
        const val R82XX_CHECK_VAL = 0x69
        const val IF_FREQ = 3_570_000

        private const val REG_SHADOW_START = 5
        private const val NUM_REGS = 30
        private const val VER_NUM = 49

        private const val TUNER_ANALOG_TV = 2
        private const val TUNER_DIGITAL_TV = 3
        private const val SYS_DVBT = 1

        private const val XTAL_LOW_CAP_30P = 0
        private const val XTAL_LOW_CAP_20P = 1
        private const val XTAL_LOW_CAP_10P = 2
        private const val XTAL_LOW_CAP_0P = 3
        private const val XTAL_HIGH_CAP_0P = 4

        private const val FILT_HP_BW1 = 350_000
        private const val FILT_HP_BW2 = 380_000

        // En el original es uint8_t[NUM_REGS] con 27 valores: los 3 últimos registros se inicializan a 0.
        private val INIT_ARRAY = intArrayOf(
            0x83, 0x32, 0x75, // 05 a 07
            0xc0, 0x40, 0xd6, 0x6c, // 08 a 0b
            0xf5, 0x63, 0x75, 0x68, // 0c a 0f
            0x6c, 0x83, 0x80, 0x00, // 10 a 13
            0x0f, 0x00, 0xc0, 0x30, // 14 a 17
            0x48, 0xcc, 0x60, 0x00, // 18 a 1b
            0x54, 0xae, 0x4a, 0xc0, // 1c a 1f
            0x00, 0x00, 0x00, // 20 a 22
        )

        private val LNA_GAIN_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)
        private val MIXER_GAIN_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)
        private val IF_LOW_PASS_BW = intArrayOf(
            1_700_000, 1_600_000, 1_550_000, 1_450_000, 1_200_000, 900_000, 700_000, 550_000, 450_000, 350_000,
        )

    // freq(MHz), open_d, rf_mux_ploy, tf_c, xtal_cap20p, xtal_cap10p, xtal_cap0p  (generado desde tuner_r82xx.c)
    private val FREQ_RANGES = arrayOf(
        intArrayOf(0, 0x08, 0x02, 0xdf, 0x02, 0x01, 0x00),
        intArrayOf(50, 0x08, 0x02, 0xbe, 0x02, 0x01, 0x00),
        intArrayOf(55, 0x08, 0x02, 0x8b, 0x02, 0x01, 0x00),
        intArrayOf(60, 0x08, 0x02, 0x7b, 0x02, 0x01, 0x00),
        intArrayOf(65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00),
        intArrayOf(70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
        intArrayOf(75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
        intArrayOf(80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
        intArrayOf(90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
        intArrayOf(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
        intArrayOf(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
        intArrayOf(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
        intArrayOf(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00),
        intArrayOf(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
        intArrayOf(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
        intArrayOf(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
        intArrayOf(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00),
        intArrayOf(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
        intArrayOf(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
        intArrayOf(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
        intArrayOf(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
    )
    }
}
