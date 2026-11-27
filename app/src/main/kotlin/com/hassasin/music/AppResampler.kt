package com.hassasin.music

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's own sample-rate converter for Android's output: songs whose rate differs from the phone's
 * mixer (44.1 kHz into a 48 kHz phone) are converted here with a 48-tap Kaiser-windowed sinc, in 64-bit,
 * so Android's own (often simpler) resampler has nothing left to do. Off by default (Settings).
 */
@OptIn(UnstableApi::class)
class AppResampler(context: Context, private val floatSink: Boolean, private val engine: SoundEngine) : BaseAudioProcessor() {
    private val mixerRate = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    private companion object {
        const val HALF = 24          // taps on each side
        const val PHASES = 512       // table resolution between input samples
        fun bessel0(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (k < 40) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; k++ }; return s }
    }

    private var ch = 2
    private var inRate = 0
    private var outRate = 0
    private var floatOut = true
    private var step = 1.0            // input samples per output sample
    private var table = DoubleArray(0) // [phase][tap]
    private var hist = Array(2) { DoubleArray(0) }
    private var histLen = 0
    private var pos = 0.0              // position of the next output, in input samples from hist[0]
    private var rand = 4242L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!AppState.settings.value.appResampler || AppState.settings.value.output == "offload") return AudioProcessor.AudioFormat.NOT_SET
        val enc = inputAudioFormat.encoding
        if (enc !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate == mixerRate) return AudioProcessor.AudioFormat.NOT_SET
        ch = inputAudioFormat.channelCount
        inRate = inputAudioFormat.sampleRate
        outRate = mixerRate
        step = inRate.toDouble() / outRate
        // The 16-bit sink takes only 16-bit, unless the effects engine (which takes float) comes next.
        floatOut = floatSink || engine.wantsActive()
        buildTable()
        return AudioProcessor.AudioFormat(outRate, ch, if (floatOut) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT)
    }

    private fun buildTable() {
        val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate) * 0.97
        val beta = 8.6
        val norm = bessel0(beta)
        table = DoubleArray((PHASES + 1) * 2 * HALF)
        for (p in 0..PHASES) {
            val frac = p.toDouble() / PHASES
            var sum = 0.0
            for (k in 0 until 2 * HALF) {
                val x = (k - HALF + 1) - frac
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val r = x / HALF
                val w = if (kotlin.math.abs(r) >= 1) 0.0 else bessel0(beta * sqrt(1 - r * r)) / norm
                table[p * 2 * HALF + k] = sinc * w
                sum += sinc * w
            }
            for (k in 0 until 2 * HALF) table[p * 2 * HALF + k] /= sum
        }
    }

    private fun read(buf: ByteBuffer, enc: Int): Double = when (enc) {
        C.ENCODING_PCM_16BIT -> buf.short / 32768.0
        C.ENCODING_PCM_24BIT -> { val b0 = buf.get().toInt() and 0xFF; val b1 = buf.get().toInt() and 0xFF; val b2 = buf.get().toInt(); ((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0 }
        C.ENCODING_PCM_FLOAT -> buf.float.toDouble()
        else -> buf.int / 2147483648.0
    }

    private fun noise(): Double { rand = rand * 6364136223846793005L + 1442695040888963407L; return (rand ushr 11).toDouble() / (1L shl 53).toDouble() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val enc = inputAudioFormat.encoding
        val bps = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = inp.remaining() / (bps * ch)
        ensure(histLen + frames)
        for (f in 0 until frames) for (c in 0 until ch) hist[c][histLen + f] = read(inp, enc)
        histLen += frames
        produce(false)
    }

    private fun ensure(n: Int) {
        if (hist[0].size >= n) return
        hist = Array(2) { c -> hist.getOrNull(c)?.copyOf(maxOf(n, hist[0].size * 2)) ?: DoubleArray(n) }
    }

    /** Makes every output sample whose taps are all available (or, at the end, pads with silence). */
    private fun produce(end: Boolean) {
        if (end) { ensure(histLen + HALF); for (c in 0 until ch) java.util.Arrays.fill(hist[c], histLen, histLen + HALF, 0.0); histLen += HALF }
        // Outputs whose rightmost tap is inside the history.
        var n = 0
        run { var p = pos; while (floor(p).toInt() + HALF < histLen) { n++; p += step } }
        if (n <= 0) { replaceOutputBuffer(0).flip(); return }
        val out = replaceOutputBuffer(n * ch * if (floatOut) 4 else 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val base = floor(pos).toInt()
            val fr = (pos - base) * PHASES
            val ph = fr.toInt().coerceIn(0, PHASES - 1)
            val a = fr - ph
            val t0 = ph * 2 * HALF; val t1 = (ph + 1) * 2 * HALF
            for (c in 0 until ch) {
                val h = hist[c]
                var acc = 0.0
                var idx = base - HALF + 1
                for (k in 0 until 2 * HALF) {
                    val x = if (idx >= 0) h[idx] else 0.0
                    acc += x * (table[t0 + k] + a * (table[t1 + k] - table[t0 + k]))
                    idx++
                }
                if (floatOut) out.putFloat(acc.toFloat())
                else out.putShort(Math.round((acc * 32767.0 + noise() - noise()).coerceIn(-32768.0, 32767.0)).toInt().toShort())
            }
            pos += step
        }
        out.flip()
        // Drop history no output will need again (keep HALF samples before the next position).
        val drop = (floor(pos).toInt() - HALF).coerceAtLeast(0)
        if (drop > 0) {
            for (c in 0 until ch) System.arraycopy(hist[c], drop, hist[c], 0, histLen - drop)
            histLen -= drop; pos -= drop
        }
    }

    override fun onQueueEndOfStream() { produce(true) }

    override fun onFlush() { histLen = 0; pos = 0.0 }

    override fun onReset() { histLen = 0; pos = 0.0; table = DoubleArray(0) }
}
