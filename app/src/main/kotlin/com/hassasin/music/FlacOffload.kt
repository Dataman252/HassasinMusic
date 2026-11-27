package com.hassasin.music

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * FLAC played by the phone's audio chip (offload), the way Sony's player does it: the compressed file
 * goes to the chip, which decodes it at the song's own rate while the CPU sleeps (the battery win).
 * Media3 cannot do this for FLAC, so this sink takes FLAC itself when Battery saver is chosen, no app
 * effects are on and Android says the chip decodes FLAC; everything else passes through unchanged.
 * If the chip refuses mid-way, FLAC goes back to normal decoding for the rest of the session.
 */
@OptIn(UnstableApi::class)
class FlacOffloadSink(private val inner: AudioSink) : ForwardingAudioSink(inner) {
    companion object {
        private const val TAG = "FlacOffload"
        private const val ENCODING_FLAC = 29 // AudioFormat.ENCODING_FLAC (public from API 35)
        /** Set when the chip refused FLAC: normal decoding until the app restarts. */
        @Volatile var broken = false
        /** "FLAC 96 kHz → audio chip" while active, for the output chain. */
        @Volatile var active: String = ""

        private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

        fun chipFormat(rate: Int, channels: Int): AudioFormat? = runCatching {
            AudioFormat.Builder().setEncoding(ENCODING_FLAC).setSampleRate(rate)
                .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO).build()
        }.getOrNull()

        /** Whether the phone's chip can play this FLAC itself. */
        fun chipCanPlay(rate: Int, channels: Int): Boolean {
            if (Build.VERSION.SDK_INT < 29 || broken) return false
            val f = chipFormat(rate, channels) ?: return false
            return runCatching { AudioManager.isOffloadedPlaybackSupported(f, attrs) }.getOrDefault(false)
        }
    }

    private var track: AudioTrack? = null
    private var rate = 0
    private var header: ByteArray? = null
    private var headerSent = false
    private var startMediaUs = C.TIME_UNSET
    private var playing = false
    private var draining = false
    @Volatile private var ended = false
    private var stopAt = 0L
    private val callbacks = Executors.newSingleThreadExecutor()

    private fun wanted(format: Format): Boolean {
        if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) return false
        val s = AppState.settings.value
        if (s.output != "offload" || Playback.callActive || UsbLive.active) return false
        if (Eq.anyEffect(SoundState.settings, false)) return false
        return chipCanPlay(format.sampleRate, format.channelCount)
    }

    override fun supportsFormat(format: Format): Boolean = wanted(format) || super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (wanted(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!wanted(inputFormat)) { close(); return super.configure(inputFormat, specifiedBufferSize, outputChannels) }
        close()
        val f = chipFormat(inputFormat.sampleRate, inputFormat.channelCount)
        val t = runCatching {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(f!!)
                .setOffloadedPlayback(true).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(256 * 1024).build()
        }.getOrNull()
        if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
            t?.release()
            fail("the chip would not open FLAC ${inputFormat.sampleRate} Hz")
            throw AudioSink.ConfigurationException("FLAC offload refused", inputFormat)
        }
        t.registerStreamEventCallback(callbacks, object : AudioTrack.StreamEventCallback() {
            override fun onPresentationEnded(track: AudioTrack) { ended = true }
        })
        track = t
        rate = inputFormat.sampleRate
        // The stream starts with the file's own "fLaC" + STREAMINFO block, so the chip knows the stream.
        header = inputFormat.initializationData.firstOrNull()
        headerSent = false
        startMediaUs = C.TIME_UNSET
        ended = false; draining = false
        active = "FLAC ${khz(rate)} kHz ${inputFormat.channelCount}ch → audio chip"
        Log.i(TAG, "offloading $active")
    }

    private fun fail(why: String) {
        Log.w(TAG, "$why; using normal decoding")
        broken = true
        active = ""
    }

    private fun close() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        active = ""
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val t = track ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (startMediaUs == C.TIME_UNSET) startMediaUs = presentationTimeUs
        if (!headerSent) {
            header?.let { h -> if (h.size >= 4 && h[0] == 'f'.code.toByte()) t.write(ByteBuffer.wrap(h), h.size, AudioTrack.WRITE_BLOCKING) }
            headerSent = true
        }
        val n = t.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (n < 0) {
            fail("write error $n")
            Playback.rebuildAudioLater("FLAC offload failed")
            buffer.position(buffer.limit())
            return true
        }
        if (playing && t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        return !buffer.hasRemaining()
    }

    override fun play() { val t = track ?: return super.play(); playing = true; t.play() }
    override fun pause() { val t = track ?: return super.pause(); playing = false; t.pause() }

    override fun playToEndOfStream() {
        val t = track ?: return super.playToEndOfStream()
        if (!draining) { draining = true; stopAt = SystemClock.elapsedRealtime(); runCatching { t.stop() } }
    }

    override fun isEnded(): Boolean {
        val t = track ?: return super.isEnded()
        // Ended when the chip says so; a stopped track whose position no longer moves counts too.
        return draining && (ended || (t.playState == AudioTrack.PLAYSTATE_STOPPED && SystemClock.elapsedRealtime() - stopAt > 30_000))
    }

    override fun hasPendingData(): Boolean {
        track ?: return super.hasPendingData()
        return headerSent && !ended
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val t = track ?: return super.getCurrentPositionUs(sourceEnded)
        if (startMediaUs == C.TIME_UNSET || rate == 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val frames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return startMediaUs + frames * 1_000_000L / rate
    }

    override fun flush() {
        val t = track ?: return super.flush()
        runCatching { t.pause(); t.flush() }
        headerSent = false
        startMediaUs = C.TIME_UNSET
        ended = false; draining = false
        if (playing) runCatching { t.play() }
    }

    override fun handleDiscontinuity() { if (track == null) super.handleDiscontinuity() }

    override fun setVolume(volume: Float) { track?.setVolume(volume) ?: super.setVolume(volume) }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { if (track == null) super.setPlaybackParameters(playbackParameters) }
    override fun getPlaybackParameters(): PlaybackParameters = if (track != null) PlaybackParameters.DEFAULT else super.getPlaybackParameters()

    override fun reset() { close(); super.reset() }
    override fun release() { close(); callbacks.shutdown(); super.release() }
}
