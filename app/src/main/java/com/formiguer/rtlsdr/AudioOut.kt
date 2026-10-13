package com.formiguer.rtlsdr

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.formiguer.rtlsdr.adsb.AmChannel
import com.formiguer.rtlsdr.adsb.AudioSink

/** Reproduce el audio PCM 16 bits mono a 48 kHz del receptor de banda aérea. */
class AudioOut : AudioSink {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var volume = 0.8f

    @Synchronized
    fun start() {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(
            AmChannel.AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack(
            AudioManager.STREAM_MUSIC, AmChannel.AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, 16384), AudioTrack.MODE_STREAM
        )
        t.setStereoVolume(volume, volume)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: IllegalStateException) {
        }
        t.release()
    }

    fun setVolume(v: Float) {
        volume = v
        try {
            track?.setStereoVolume(v, v)
        } catch (_: IllegalStateException) {
        }
    }

    override fun write(pcm: ShortArray, count: Int) {
        try {
            track?.write(pcm, 0, count, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: IllegalStateException) {
        }
    }
}
