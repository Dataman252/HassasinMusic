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
 * mixer (44.1 kHz into a 48 kHz phone) are converted here with a Kaiser-windowed sinc, in 64-bit, so
 * Android's own (often simpler) resampler has nothing left to do. Off by default (Settings).
 * Filters (Audiophile settings): Sharp 48 taps, Steep 128 taps, Soft 24 taps (least ringing) and
 * Minimum phase (the Sharp filter turned minimum-phase: no ringing before a sound, only after).
 * When it makes 16-bit it dithers: flat TPDF, noise-shaped TPDF, or none.
 */
@OptIn(UnstableApi::class)
class AppResampler(
    context: Context,
    private val floatSink: Boolean,
    private val engine: SoundEngine,
    /** USB output: the rate to send a song at (its own when the DAC takes it). Null = Android's output (to the mixer rate). */
    private val usbTarget: ((Int) -> Int)? = null,
) : BaseAudioProcessor() {
    private val mixerRate = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    companion object {
        const val PHASES = 512       // table resolution between input samples
        fun bessel0(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (k < 40) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; k++ }; return s }

        /** Taps on each side, Kaiser beta and cutoff (share of Nyquist) for each filter. */
        fun design(filter: String): Triple<Int, Double, Double> = when (filter) {
            "steep" -> Triple(64, 12.0, 0.985)
            "soft" -> Triple(12, 6.0, 0.90)
            else -> Triple(24, 8.6, 0.97)
        }

        fun label(filter: String): String = when (filter) {
            "steep" -> "128-tap steep sinc"
            "soft" -> "24-tap soft sinc"
            "minphase" -> "48-tap minimum-phase sinc"
            else -> "48-tap sinc"
        }

        /** Built tables, by filter and rates (building the minimum-phase one takes a moment). */
        private val tables = HashMap<String, DoubleArray>()

        fun table(filter: String, inRate: Int, outRate: Int): DoubleArray = synchronized(tables) {
            tables.getOrPut("$filter $inRate $outRate") { build(filter, inRate, outRate) }
        }

        private fun build(filter: String, inRate: Int, outRate: Int): DoubleArray {
            val (half, beta, cut) = design(filter)
            val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate) * cut
            val norm = bessel0(beta)
            fun proto(x: Double): Double {
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val r = x / half
                return if (kotlin.math.abs(r) >= 1) 0.0 else sinc * bessel0(beta * sqrt(1 - r * r)) / norm
            }
            val taps = 2 * half
            val table = DoubleArray((PHASES + 1) * taps)
            if (filter == "minphase") {
                val g = MinPhase.convert(DoubleArray(taps * PHASES + 1) { n -> proto((n - half * PHASES).toDouble() / PHASES) })
                // Tap k reads the sample (taps-1-k) whole samples before the newest one, plus the phase.
                for (p in 0..PHASES) for (k in 0 until taps) table[p * taps + k] = g.getOrElse((taps - 1 - k) * PHASES + p) { 0.0 }
            } else {
                for (p in 0..PHASES) {
                    val frac = p.toDouble() / PHASES
                    for (k in 0 until taps) table[p * taps + k] = proto((k - half + 1) - frac)
                }
            }
            for (p in 0..PHASES) {
                var sum = 0.0
                for (k in 0 until taps) sum += table[p * taps + k]
                if (sum != 0.0) for (k in 0 until taps) table[p * taps + k] /= sum
            }
            return table
        }
    }

    private var HALF = 24
    private var dither = "tpdf"
    private val err1 = DoubleArray(2)
    private val err2 = DoubleArray(2)

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
        val usb = usbTarget != null
        if (!usb && (!AppState.settings.value.appResampler || AppState.settings.value.output == "offload")) return AudioProcessor.AudioFormat.NOT_SET
        val enc = inputAudioFormat.encoding
        if (enc !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        val target = usbTarget?.invoke(inputAudioFormat.sampleRate) ?: mixerRate
        if (target <= 0 || inputAudioFormat.sampleRate == target) return AudioProcessor.AudioFormat.NOT_SET
        ch = inputAudioFormat.channelCount
        inRate = inputAudioFormat.sampleRate
        outRate = target
        step = inRate.toDouble() / outRate
        // The 16-bit sink takes only 16-bit, unless the effects engine (which takes float) comes next.
        val s = AppState.settings.value
        floatOut = usb || (floatSink && s.outDepth != "16") || engine.wantsActive()
        dither = s.dither
        val filter = s.resamplerFilter
        HALF = design(filter).first
        table = table(filter, inRate, outRate)
        err1.fill(0.0); err2.fill(0.0)
        return AudioProcessor.AudioFormat(outRate, ch, if (floatOut) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT)
    }

    /** One sample to 16-bit, with the chosen dither (noise shaping feeds the error back, 2nd order). */
    private fun to16(x: Double, c: Int): Short {
        val v = x * 32767.0
        val y = when (dither) {
            "off" -> Math.round(v).toDouble()
            "shaped" -> {
                val u = v - 2 * err1[c] + err2[c]
                val q = Math.round(u + (noise() - noise()) * 0.5).toDouble()
                err2[c] = err1[c]; err1[c] = q - u
                q
            }
            else -> Math.round(v + noise() - noise()).toDouble()
        }
        return y.coerceIn(-32768.0, 32767.0).toInt().toShort()
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
                else out.putShort(to16(acc, c))
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
