package com.hassasin.music

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One equalizer filter: a peak, a low shelf or a high shelf (the same three AutoEq uses). */
@Serializable
data class EqFilter(
    val type: String = "PK", // PK, LSC, HSC
    val fc: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
    val on: Boolean = true,
)

/** A headphone correction from the AutoEq database. */
@Serializable
data class AutoEqProfile(
    val name: String,
    val source: String,
    val preampDb: Double,
    val filters: List<EqFilter>,
    /** Folder in the AutoEq results, for the measurement chart. */
    val path: String = "",
)

@Serializable
data class SoundSettings(
    /** Master switch for the equalizer, tone, width and balance. */
    val eqOn: Boolean = false,
    val bands: List<Double> = List(10) { 0.0 },
    val preset: String = "Flat",
    val parametric: List<EqFilter> = emptyList(),
    val bassDb: Double = 0.0,
    /** A bass character on top of the EQ ("Off", "Sub rumble", "Deep", "Punchy", ...). */
    val bassStyle: String = "Off",
    /** How strong the bass style is, in dB at its peak. */
    val bassAmount: Double = 6.0,
    val trebleDb: Double = 0.0,
    /** -1 = left only, 0 = centre, 1 = right only. */
    val balance: Double = 0.0,
    /** 1 = as recorded, 0 = mono, up to 2 = wider. */
    val width: Double = 1.0,
    val limiter: Boolean = true,
    val autoEq: AutoEqProfile? = null,
    val autoEqOn: Boolean = false,
    /** Apply the headphone correction only while playing through the USB DAC. */
    val autoEqOnlyUsb: Boolean = true,
    /** The effects suite (convolver, bass, clarity, surround, reverb, compressor...). */
    val fx: FxSettings = FxSettings(),
    /** HiBy MSEB-style tuning sliders. */
    val mseb: Mseb = Mseb(),
)

object Eq {
    val BAND_HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val BAND_Q = 1.41

    val PRESETS: LinkedHashMap<String, List<Double>> = linkedMapOf(
        "Flat" to List(10) { 0.0 },
        "Bass boost" to listOf(6.0, 5.0, 3.5, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        "Warm" to listOf(3.0, 2.5, 1.5, 0.5, 0.0, -0.5, -1.0, -1.5, -2.0, -2.5),
        "Vocal" to listOf(-2.0, -1.5, -0.5, 1.0, 2.5, 3.0, 2.5, 1.0, 0.0, -0.5),
        "Rock" to listOf(4.0, 3.0, 1.5, -0.5, -1.0, -0.5, 1.0, 2.5, 3.5, 3.5),
        "Jazz" to listOf(3.0, 2.0, 1.0, 1.5, -1.0, -1.0, 0.0, 1.0, 2.0, 2.5),
        "Classical" to listOf(3.0, 2.0, 1.0, 0.0, 0.0, 0.0, -1.0, -1.0, 0.5, 2.0),
        "Treble boost" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.5, 3.0, 4.5, 5.5),
        "Loudness" to listOf(5.0, 3.5, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0, 3.0, 4.0),
    )

    /**
     * Bass characters. Each is a small set of filters scaled by the amount:
     * where the boost sits decides the feel (rumble is felt below 40 Hz, punch lives around 80-100 Hz).
     */
    data class BassStyle(val name: String, val about: String, val shape: List<EqFilter>)

    val BASS_STYLES = listOf(
        BassStyle("Off", "No extra bass", emptyList()),
        BassStyle("Sub rumble", "Very low 25-45 Hz: the rumble you feel more than hear", listOf(EqFilter("PK", 32.0, 1.0, 0.9), EqFilter("LSC", 45.0, 0.35, 0.7))),
        BassStyle("Deep", "A wide, smooth lift under 60 Hz", listOf(EqFilter("LSC", 60.0, 1.0, 0.7))),
        BassStyle("808", "The long hip-hop/trap kick: low and focused around 45-70 Hz", listOf(EqFilter("PK", 48.0, 1.0, 1.5), EqFilter("PK", 72.0, 0.45, 1.1))),
        BassStyle("Punchy", "Kick-drum punch around 90 Hz, less mud", listOf(EqFilter("PK", 90.0, 0.85, 1.4), EqFilter("PK", 260.0, -0.25, 1.0))),
        BassStyle("Tight", "Firm low end without boom", listOf(EqFilter("PK", 55.0, 0.8, 1.2), EqFilter("PK", 160.0, -0.4, 1.2))),
        BassStyle("Boom", "Big, round mid-bass around 120 Hz", listOf(EqFilter("PK", 120.0, 1.0, 0.9))),
        BassStyle("Warm", "Gentle, full body for vocals and old songs", listOf(EqFilter("LSC", 180.0, 0.6, 0.6), EqFilter("PK", 400.0, -0.15, 1.0))),
    )

    fun bassFilters(s: SoundSettings): List<EqFilter> {
        val style = BASS_STYLES.firstOrNull { it.name == s.bassStyle } ?: return emptyList()
        if (s.bassAmount <= 0.01) return emptyList()
        return style.shape.map { it.copy(gainDb = it.gainDb * s.bassAmount) }
    }

    /** Every filter the settings ask for, in processing order. */
    fun filters(s: SoundSettings, usb: Boolean): List<EqFilter> {
        val out = mutableListOf<EqFilter>()
        if (autoEqActive(s, usb)) s.autoEq?.filters?.filter { it.on }?.let { out += it }
        if (s.eqOn) {
            s.bands.forEachIndexed { i, g -> if (abs(g) > 0.01) out += EqFilter("PK", BAND_HZ[i], g, BAND_Q) }
            s.parametric.filter { it.on && abs(it.gainDb) > 0.01 }.forEach { out += it }
            if (abs(s.bassDb) > 0.01) out += EqFilter("LSC", 100.0, s.bassDb, 0.707)
            out += bassFilters(s)
            out += s.mseb.filters()
            if (abs(s.trebleDb) > 0.01) out += EqFilter("HSC", 10000.0, s.trebleDb, 0.707)
        }
        return out
    }

    fun autoEqActive(s: SoundSettings, usb: Boolean) = s.autoEqOn && s.autoEq != null && (!s.autoEqOnlyUsb || usb)

    /** True when anything at all would change the sound. */
    fun anyEffect(s: SoundSettings, usb: Boolean): Boolean =
        filters(s, usb).isNotEmpty() || (s.eqOn && (abs(s.balance) > 0.001 || abs(s.width - 1.0) > 0.001 || s.fx.anyOn() || s.mseb.anyOn()))

    /** The combined response in dB at [hz] (both the equalizer and the correction). */
    fun responseDb(filters: List<EqFilter>, hz: Double, rate: Int = 48000): Double =
        filters.sumOf { Biquad.design(it, rate).magnitudeDb(hz, rate) }

    /** Pre-amp that keeps the loudest boost from clipping. */
    fun autoPreampDb(filters: List<EqFilter>): Double {
        var peak = 0.0
        var f = 20.0
        while (f <= 20000.0) { peak = max(peak, responseDb(filters, f)); f *= 1.035 }
        return -peak
    }

    /** Log-spaced frequencies for drawing response curves. */
    fun curveFreqs(n: Int = 96): DoubleArray = DoubleArray(n) { 20.0 * 1000.0.pow(it / (n - 1.0)) }
}

/** RBJ cookbook biquad, processed in double precision (transposed direct form II). */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() { z1.fill(0.0); z2.fill(0.0) }

    fun magnitudeDb(hz: Double, rate: Int): Double {
        val w = 2 * PI * hz / rate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * log10(mag.coerceAtLeast(1e-9))
    }

    companion object {
        fun design(f: EqFilter, rate: Int): Biquad {
            val fc = f.fc.coerceIn(10.0, rate * 0.45)
            val a = 10.0.pow(f.gainDb / 40.0)
            val w0 = 2 * PI * fc / rate
            val cw = cos(w0); val sw = sin(w0)
            val q = f.q.coerceAtLeast(0.05)
            val alpha = sw / (2 * q)
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (f.type) {
                "LSC" -> {
                    val sa = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + sa)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - sa)
                    a0 = (a + 1) + (a - 1) * cw + sa
                    a1 = -2 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - sa
                }
                "LP" -> {
                    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                "HP" -> {
                    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                "HSC" -> {
                    val sa = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + sa)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - sa)
                    a0 = (a + 1) - (a - 1) * cw + sa
                    a1 = 2 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - sa
                }
                else -> {
                    b0 = 1 + alpha * a
                    b1 = -2 * cw
                    b2 = 1 - alpha * a
                    a0 = 1 + alpha / a
                    a1 = -2 * cw
                    a2 = 1 - alpha / a
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/**
 * Watches the sound just before the limiter: any sample over 0 dBFS means the EQ or effects push
 * the music past full scale (clipping, or the limiter squashing it). The Sound sheet shows a red
 * warning while this happens and offers to lower the master gain by the amount needed.
 */
object ClipMeter {
    /** The worst overshoot in dB above full scale in the last few seconds (0 = none). */
    @Volatile var overDb = 0.0
        private set
    @Volatile var lastAt = 0L
        private set
    /** Overshoots smaller than this are ignored (rounding noise, not audible). */
    const val MIN_DB = 0.1
    const val HOLD_MS = 3000L

    fun report(peak: Double) {
        val db = 20 * log10(peak)
        if (db < MIN_DB) return
        val now = System.currentTimeMillis()
        overDb = if (now - lastAt > HOLD_MS) db else max(overDb, db)
        lastAt = now
    }

    /** The overshoot to show now, or 0 when nothing clipped in the last few seconds. */
    fun now(): Double = if (System.currentTimeMillis() - lastAt <= HOLD_MS) overDb else 0.0

    fun clear() { overDb = 0.0; lastAt = 0L }
}

/** The settings every engine reads; bumping [version] makes running engines pick up changes. */
object SoundState {
    @Volatile var settings = SoundSettings()
        private set
    @Volatile var version = 0L
        private set

    fun set(s: SoundSettings) { if (s != settings) ClipMeter.clear(); settings = s; version++ }
}

/**
 * The sound engine: equalizer, parametric filters, headphone correction, tone, width, balance,
 * automatic pre-amp and a limiter, all in 64-bit floating point.
 *
 * [forUsb] true: outputs 32-bit float for the USB DAC. False (Android's output): outputs 16-bit
 * with TPDF dither. When no effect is on, the engine takes itself out of the chain.
 */
@OptIn(UnstableApi::class)
class SoundEngine(private val forUsb: Boolean) : BaseAudioProcessor() {

    private var seenVersion = -1L
    private var filters: Array<Biquad> = emptyArray()
    private var preGain = 1.0
    private var balL = 1.0
    private var balR = 1.0
    private var width = 1.0
    private var limiter = true
    private var ceiling = 0.97
    private var fx: FxChain? = null
    private val fxOut = DoubleArray(2)
    private var bypass = false
    private var env = 1.0
    private var rand = 12345L

    // Offload hands the compressed file to the phone's chip, so the app cannot process it: effects pause there.
    fun wantsActive(): Boolean = Eq.anyEffect(SoundState.settings, forUsb) && (forUsb || AppState.settings.value.output != "offload")

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!wantsActive()) return AudioProcessor.AudioFormat.NOT_SET
        val enc = inputAudioFormat.encoding
        if (inputAudioFormat.channelCount !in 1..2 ||
            enc !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        seenVersion = -1
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate, inputAudioFormat.channelCount,
            if (forUsb) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
        )
    }

    private fun refresh() {
        val v = SoundState.version
        if (v == seenVersion) return
        seenVersion = v
        val s = SoundState.settings
        val rate = inputAudioFormat.sampleRate
        val list = Eq.filters(s, forUsb)
        val old = filters
        filters = list.map { Biquad.design(it, rate) }.toTypedArray()
        if (old.size != filters.size) filters.forEach { it.reset() }
        preGain = 10.0.pow(Eq.autoPreampDb(list) / 20.0)
        val eq = s.eqOn
        val bal = if (eq) s.balance.coerceIn(-1.0, 1.0) else 0.0
        balL = if (bal > 0) 1 - bal else 1.0
        balR = if (bal < 0) 1 + bal else 1.0
        width = if (eq) (s.width + if (s.mseb.on) s.mseb.stage * 0.08 else 0.0).coerceIn(0.0, 2.0) else 1.0
        limiter = s.limiter
        ceiling = 10.0.pow(s.fx.limiterCeilDb.coerceIn(-12.0, 0.0) / 20.0).coerceAtMost(0.995)
        fx = if (eq && s.fx.anyOn()) FxChain(s.fx, rate) else null
        bypass = !Eq.anyEffect(s, forUsb)
    }

    private fun read(buf: ByteBuffer, enc: Int): Double = when (enc) {
        C.ENCODING_PCM_16BIT -> buf.short / 32768.0
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0
        }
        C.ENCODING_PCM_FLOAT -> buf.float.toDouble()
        else -> buf.int / 2147483648.0
    }

    private fun noise(): Double {
        rand = rand * 6364136223846793005L + 1442695040888963407L
        return ((rand ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        refresh()
        val enc = inputAudioFormat.encoding
        val ch = inputAudioFormat.channelCount
        val bytesPerSample = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frames = inputBuffer.remaining() / (bytesPerSample * ch)
        val outBytes = if (forUsb) 4 else 2
        val out = replaceOutputBuffer(frames * ch * outBytes).order(ByteOrder.LITTLE_ENDIAN)
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val release = 1.0 - 1.0 / (0.08 * inputAudioFormat.sampleRate)
        var over = 0.0
        for (f in 0 until frames) {
            var l = read(inp, enc)
            var r = if (ch == 2) read(inp, enc) else l
            if (!bypass) {
                l *= preGain; r *= preGain
                for (b in filters) { l = b.process(l, 0); r = b.process(r, 1) }
                fx?.let { it.process(l, r, fxOut); l = fxOut[0]; r = fxOut[1] }
                if (width != 1.0 && ch == 2) {
                    val m = (l + r) * 0.5; val s = (l - r) * 0.5 * width
                    l = m + s; r = m - s
                }
                l *= balL; r *= balR
                val peak = max(abs(l), abs(r))
                if (peak > over) over = peak
                if (limiter) {
                    val target = if (peak > ceiling) ceiling / peak else 1.0
                    env = if (target < env) target else env * release + (1 - release) * target
                    l *= env; r *= env
                }
            }
            if (forUsb) {
                out.putFloat(l.toFloat()); if (ch == 2) out.putFloat(r.toFloat())
            } else {
                val dl = (l * 32767.0 + noise() - noise()).coerceIn(-32768.0, 32767.0)
                out.putShort(Math.round(dl).toInt().toShort())
                if (ch == 2) {
                    val dr = (r * 32767.0 + noise() - noise()).coerceIn(-32768.0, 32767.0)
                    out.putShort(Math.round(dr).toInt().toShort())
                }
            }
        }
        out.flip()
        if (over > 1.0) ClipMeter.report(over)
    }

    override fun onFlush() {
        filters.forEach { it.reset() }
        env = 1.0
        seenVersion = -1
    }

    override fun onReset() {
        filters = emptyArray()
        seenVersion = -1
    }
}
