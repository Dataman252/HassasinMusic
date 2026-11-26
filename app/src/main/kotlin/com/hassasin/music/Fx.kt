package com.hassasin.music

import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The full effects suite (the same families as ViPER4Android FX, built here from scratch):
 * playback gain control, convolver, bass, clarity, spectrum extension, tube simulator, AnalogX,
 * dynamic system, field surround, differential surround, headphone surround, reverberation,
 * auditory protection (crossfeed), FET compressor, master gain and limiter.
 * All values are plain numbers so the screens can show and save them.
 */
@Serializable
data class FxSettings(
    // Playback gain control (AGC)
    val agcOn: Boolean = false, val agcTargetDb: Double = -16.0, val agcMaxGainDb: Double = 9.0, val agcSpeed: Double = 3.0,
    // Convolver (impulse response .wav)
    val convOn: Boolean = false, val convFile: String = "", val convMix: Double = 1.0, val convGainDb: Double = 0.0,
    // Bass: natural (shelf + harmonics), pure (focused peak), subwoofer (added low band)
    val bassOn: Boolean = false, val bassMode: String = "natural", val bassFreq: Double = 70.0, val bassGainDb: Double = 6.0,
    // Clarity: natural (shelf), ozone (exciter), xhifi (air + presence)
    val clarityOn: Boolean = false, val clarityMode: String = "natural", val clarity: Double = 4.0,
    // Spectrum extension (generated upper harmonics)
    val spectrumOn: Boolean = false, val spectrum: Double = 3.0,
    // Tube simulator (even harmonics, warmth)
    val tubeOn: Boolean = false, val tube: Double = 3.0,
    // AnalogX (analog stage saturation)
    val analogOn: Boolean = false, val analogClass: String = "A", val analog: Double = 3.0,
    // Dynamic system (bass that follows loudness)
    val dynOn: Boolean = false, val dynBass: Double = 4.0, val dynSense: Double = 5.0,
    // Field surround (wider stereo, with centre kept)
    val fieldOn: Boolean = false, val fieldWidth: Double = 4.0, val fieldMid: Double = 5.0,
    // Differential surround (Haas delay)
    val diffOn: Boolean = false, val diffMs: Double = 8.0,
    // Headphone surround (out-of-head)
    val vheOn: Boolean = false, val vhe: Double = 2.0,
    // Reverberation
    val reverbOn: Boolean = false, val roomSize: Double = 4.0, val damping: Double = 5.0, val reverbWet: Double = 2.0, val reverbWidth: Double = 8.0,
    // Auditory system protection (crossfeed)
    val cureOn: Boolean = false, val cure: Double = 2.0,
    // FET compressor
    val compOn: Boolean = false, val compThresholdDb: Double = -18.0, val compRatio: Double = 3.0, val compAttackMs: Double = 10.0,
    val compReleaseMs: Double = 120.0, val compKneeDb: Double = 6.0, val compMakeupDb: Double = 0.0, val compAutoMakeup: Boolean = true,
    // Master
    val masterGainDb: Double = 0.0, val limiterCeilDb: Double = -0.3,
) {
    fun anyOn() = agcOn || (convOn && convFile.isNotBlank()) || bassOn || clarityOn || spectrumOn || tubeOn || analogOn || dynOn ||
        fieldOn || diffOn || vheOn || reverbOn || cureOn || compOn || abs(masterGainDb) > 0.01
}

/** HiBy MSEB-style tuning: eight plain-language sliders, each −5..+5. */
@Serializable
data class Mseb(
    val on: Boolean = false,
    val temperature: Double = 0.0, // cool ↔ warm
    val bassExtension: Double = 0.0, // how deep the bass goes
    val bassTexture: Double = 0.0, // tight ↔ thick
    val vocal: Double = 0.0, // male (chest) ↔ female (bright)
    val sibilance: Double = 0.0, // softer ↔ sharper "s"
    val presence: Double = 0.0, // laid back ↔ forward (attack, detail)
    val air: Double = 0.0, // dark ↔ airy top end
    val stage: Double = 0.0, // narrow ↔ wide
) {
    fun filters(): List<EqFilter> {
        if (!on) return emptyList()
        val out = mutableListOf<EqFilter>()
        fun add(f: EqFilter) { if (abs(f.gainDb) > 0.01) out += f }
        add(EqFilter("LSC", 250.0, temperature * 0.6, 0.6)); add(EqFilter("HSC", 2500.0, -temperature * 0.6, 0.6))
        add(EqFilter("LSC", 45.0, bassExtension * 1.0, 0.7))
        add(EqFilter("PK", 110.0, bassTexture * 0.8, 1.0)); add(EqFilter("PK", 260.0, bassTexture * 0.4, 1.2))
        add(EqFilter("PK", 350.0, -vocal * 0.5, 1.1)); add(EqFilter("PK", 2600.0, vocal * 0.6, 1.2))
        add(EqFilter("PK", 7000.0, sibilance * 0.8, 3.0))
        add(EqFilter("PK", 3200.0, presence * 0.6, 1.4))
        add(EqFilter("HSC", 12000.0, air * 0.9, 0.7))
        return out
    }
    fun anyOn() = on && listOf(temperature, bassExtension, bassTexture, vocal, sibilance, presence, air, stage).any { abs(it) > 0.01 }
}

private fun dbToGain(db: Double) = 10.0.pow(db / 20.0)

/** Mono one-pole low-pass, used for envelopes and gentle splits. */
private class OnePole(cutHz: Double, rate: Int) {
    private val a = exp(-2 * PI * cutHz / rate)
    private var z = 0.0
    fun lp(x: Double): Double { z = (1 - a) * x + a * z; return z }
}

/** A delay line in samples. */
private class Delay(maxSamples: Int) {
    private val buf = DoubleArray(max(1, maxSamples))
    private var i = 0
    fun push(x: Double, d: Int): Double {
        val out = buf[(i - d + buf.size * 4) % buf.size]
        buf[i] = x; i = (i + 1) % buf.size
        return out
    }
}

/** Freeverb-style reverb: 8 comb filters and 4 all-passes per side. */
private class Reverb(rate: Int) {
    private val scale = rate / 44100.0
    private val combL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale).toInt()) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379, 1445, 1514, 1580, 1640).map { Comb((it * scale).toInt()) }
    private val apL = intArrayOf(556, 441, 341, 225).map { AllPass((it * scale).toInt()) }
    private val apR = intArrayOf(579, 464, 364, 248).map { AllPass((it * scale).toInt()) }
    var room = 0.8; var damp = 0.5

    private inner class Comb(n: Int) {
        val b = DoubleArray(max(1, n)); var i = 0; var store = 0.0
        fun process(x: Double): Double {
            val y = b[i]; store = y * (1 - damp) + store * damp
            b[i] = x + store * room; i = (i + 1) % b.size; return y
        }
    }
    private inner class AllPass(n: Int) {
        val b = DoubleArray(max(1, n)); var i = 0
        fun process(x: Double): Double { val bo = b[i]; val y = -x + bo; b[i] = x + bo * 0.5; i = (i + 1) % b.size; return y }
    }

    fun process(l: Double, r: Double, out: DoubleArray) {
        val input = (l + r) * 0.015
        var ol = 0.0; var or = 0.0
        for (c in combL) ol += c.process(input)
        for (c in combR) or += c.process(input)
        for (a in apL) ol = a.process(ol)
        for (a in apR) or = a.process(or)
        out[0] = ol; out[1] = or
    }
}

/** Loaded impulse responses, by file path. */
object Impulses {
    private val cache = HashMap<String, Impulse?>()
    fun get(path: String): Impulse? = synchronized(cache) { cache.getOrPut(path) { WavReader.read(File(path)) } }
}

/**
 * Runs the enabled effects on one stereo sample at a time, in 64-bit floating point.
 * Built for one sample rate and one set of settings; the engine rebuilds it when either changes.
 */
class FxChain(private val s: FxSettings, private val rate: Int) {
    private fun lp(hz: Double) = Biquad.design(EqFilter("LP", hz, 0.0, 0.707), rate)
    private fun hp(hz: Double) = Biquad.design(EqFilter("HP", hz, 0.0, 0.707), rate)

    // AGC
    private val agcEnv = OnePole(1.5, rate)
    private var agcGain = 1.0
    private val agcSlew = 1.0 / (rate * (6.0 - s.agcSpeed.coerceIn(1.0, 5.0)) * 0.4)

    // Convolver
    private val conv: Convolver? = if (s.convOn && s.convFile.isNotBlank()) Impulses.get(s.convFile)?.let { Convolver(it, rate) }?.also {
        it.mix = s.convMix.toFloat().coerceIn(0f, 1f); it.gain = dbToGain(s.convGainDb).toFloat()
    } else null
    private val convOut = FloatArray(2)

    // Bass
    private val bassLp = lp(s.bassFreq.coerceIn(20.0, 200.0))
    private val bassShelf = Biquad.design(EqFilter("LSC", s.bassFreq, s.bassGainDb, 0.7), rate)
    private val bassPeak = Biquad.design(EqFilter("PK", s.bassFreq, s.bassGainDb, 0.8), rate)
    private val bassG = dbToGain(s.bassGainDb) - 1

    // Clarity
    private val clarityShelf = Biquad.design(EqFilter("HSC", if (s.clarityMode == "xhifi") 8000.0 else 4000.0, s.clarity * 0.9, 0.7), rate)
    private val clarityPres = Biquad.design(EqFilter("PK", 3000.0, s.clarity * 0.3, 1.2), rate)
    private val clarityHp = hp(3000.0)

    // Spectrum extension
    private val specHp1 = hp(5000.0)
    private val specHp2 = hp(9000.0)

    // Tube / Analog
    private val dcL = OnePole(10.0, rate); private val dcR = OnePole(10.0, rate)

    // Dynamic system
    private val dynEnv = OnePole(4.0, rate)
    private val dynLp = lp(110.0)

    // Field / differential / VHE / cure
    private val diffDelay = Delay((rate * 0.03).toInt())
    private val vheL = Delay((rate * 0.03).toInt()); private val vheR = Delay((rate * 0.03).toInt())
    private val vheLp = lp(1200.0)
    private val cureLp = lp(700.0)
    private val cureDelay = Delay((rate * 0.001).toInt() + 2)

    // Reverb
    private val reverb = if (s.reverbOn) Reverb(rate).also { it.room = 0.70 + s.roomSize.coerceIn(0.0, 10.0) * 0.028; it.damp = s.damping.coerceIn(0.0, 10.0) * 0.08 } else null
    private val rvOut = DoubleArray(2)

    // Compressor
    private var compEnvDb = -120.0
    private val atk = exp(-1.0 / (rate * s.compAttackMs.coerceAtLeast(0.1) / 1000.0))
    private val rel = exp(-1.0 / (rate * s.compReleaseMs.coerceAtLeast(1.0) / 1000.0))
    private val makeup = dbToGain(if (s.compAutoMakeup) -s.compThresholdDb * (1 - 1 / s.compRatio.coerceAtLeast(1.0)) * 0.5 else s.compMakeupDb)

    private val master = dbToGain(s.masterGainDb)

    fun process(inL: Double, inR: Double, out: DoubleArray) {
        var l = inL; var r = inR

        if (s.agcOn) {
            val level = sqrt(agcEnv.lp((l * l + r * r) * 0.5)).coerceAtLeast(1e-6)
            val want = dbToGain(s.agcTargetDb - 20 * log10(level)).coerceIn(dbToGain(-12.0), dbToGain(s.agcMaxGainDb))
            agcGain += (want - agcGain) * agcSlew * 50
            l *= agcGain; r *= agcGain
        }

        conv?.let { it.process(l.toFloat(), r.toFloat(), convOut); l = convOut[0].toDouble(); r = convOut[1].toDouble() }

        if (s.bassOn) when (s.bassMode) {
            "pure" -> { l = bassPeak.process(l, 0); r = bassPeak.process(r, 1) }
            "sub" -> { val low = bassLp.process((l + r) * 0.5, 0) * bassG; l += low; r += low }
            else -> {
                l = bassShelf.process(l, 0); r = bassShelf.process(r, 1)
                val low = bassLp.process((l + r) * 0.5, 1); val harm = low * abs(low) * 0.6 * (s.bassGainDb / 12.0)
                l += harm; r += harm
            }
        }

        if (s.clarityOn) when (s.clarityMode) {
            "ozone" -> {
                val hl = clarityHp.process(l, 0); val hr = clarityHp.process(r, 1); val k = s.clarity * 0.08
                l += tanh(hl * 4) * k * 0.25; r += tanh(hr * 4) * k * 0.25
            }
            "xhifi" -> { l = clarityPres.process(clarityShelf.process(l, 0), 0); r = clarityPres.process(clarityShelf.process(r, 1), 1) }
            else -> { l = clarityShelf.process(l, 0); r = clarityShelf.process(r, 1) }
        }

        if (s.spectrumOn) {
            val hl = specHp1.process(l, 0); val hr = specHp1.process(r, 1)
            val k = s.spectrum * 0.06
            l += specHp2.process(hl * abs(hl) * 8, 0) * k; r += specHp2.process(hr * abs(hr) * 8, 1) * k
        }

        if (s.tubeOn) {
            val k = s.tube * 0.05
            l += (l * l) * k; r += (r * r) * k
            l -= dcL.lp(l); r -= dcR.lp(r)
            l = tanh(l * (1 + k)) / (1 + k * 0.5); r = tanh(r * (1 + k)) / (1 + k * 0.5)
        }

        if (s.analogOn) {
            val d = 1 + s.analog * 0.25
            fun stage(x: Double) = when (s.analogClass) {
                "AB" -> if (x >= 0) tanh(x * d) / tanh(d) else tanh(x * d * 0.85) / tanh(d * 0.85)
                "B" -> tanh(x * d) / tanh(d) * (1 - 0.02 * s.analog * exp(-abs(x) * 40))
                else -> tanh(x * d) / tanh(d)
            }
            val mix = s.analog / 10.0
            l = l * (1 - mix) + stage(l) * mix; r = r * (1 - mix) + stage(r) * mix
        }

        if (s.dynOn) {
            val level = sqrt(dynEnv.lp((l * l + r * r) * 0.5))
            val quiet = (1 - min(1.0, level * (2 + s.dynSense))).coerceIn(0.0, 1.0)
            val low = dynLp.process((l + r) * 0.5, 0) * s.dynBass * 0.12 * (0.4 + quiet)
            l += low; r += low
        }

        if (s.fieldOn) {
            val m = (l + r) * 0.5 * (0.6 + s.fieldMid * 0.08); val sd = (l - r) * 0.5 * (1 + s.fieldWidth * 0.15)
            l = m + sd; r = m - sd
        }

        if (s.diffOn) {
            r = diffDelay.push(r, (rate * s.diffMs.coerceIn(0.0, 25.0) / 1000.0).toInt())
        }

        if (s.vheOn) {
            val lvl = s.vhe.coerceIn(1.0, 5.0)
            val d = (rate * (0.00025 + lvl * 0.00012)).toInt()
            val xl = vheLp.process(vheR.push(r, d), 0) * 0.18 * lvl
            val xr = vheLp.process(vheL.push(l, d), 1) * 0.18 * lvl
            l = (l + xl) / (1 + 0.12 * lvl); r = (r + xr) / (1 + 0.12 * lvl)
        }

        if (reverb != null) {
            reverb.process(l, r, rvOut)
            val wet = s.reverbWet.coerceIn(0.0, 10.0) / 10.0
            val w = s.reverbWidth.coerceIn(0.0, 10.0) / 10.0
            val wl = rvOut[0] * (0.5 + w / 2) + rvOut[1] * (0.5 - w / 2)
            val wr = rvOut[1] * (0.5 + w / 2) + rvOut[0] * (0.5 - w / 2)
            l = l * (1 - wet * 0.4) + wl * wet; r = r * (1 - wet * 0.4) + wr * wet
        }

        if (s.cureOn) {
            val feed = when (s.cure.toInt()) { 1 -> 0.25; 3 -> 0.5; else -> 0.35 }
            val d = (rate * 0.0003).toInt()
            val xl = cureLp.process(cureDelay.push(r, d), 0) * feed
            val xr = cureLp.process(l, 1) * feed
            l = (l + xl) / (1 + feed * 0.6); r = (r + xr) / (1 + feed * 0.6)
        }

        if (s.compOn) {
            val peak = max(abs(l), abs(r)).coerceAtLeast(1e-9)
            val inDb = 20 * log10(peak)
            compEnvDb = if (inDb > compEnvDb) atk * compEnvDb + (1 - atk) * inDb else rel * compEnvDb + (1 - rel) * inDb
            val t = s.compThresholdDb; val k = s.compKneeDb.coerceAtLeast(0.0); val ratio = s.compRatio.coerceAtLeast(1.0)
            val over = compEnvDb - t
            val reduce = when {
                k > 0 && abs(over) <= k / 2 -> (1 / ratio - 1) * (over + k / 2).pow(2) / (2 * k)
                over > 0 -> over * (1 / ratio - 1)
                else -> 0.0
            }
            val g = dbToGain(reduce) * makeup
            l *= g; r *= g
        }

        out[0] = l * master; out[1] = r * master
    }
}

/**
 * The visualizer's ears: the sinks hand over the sound just before it is heard; this keeps a short
 * timeline of 48-band spectra so the screen can show exactly what is playing now.
 */
object Viz {
    const val BANDS = 48
    private const val N = 1024
    private val ring = FloatArray(N)
    private var fill = 0
    private val fft = Fft(N)
    private val re = FloatArray(N); private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / (N - 1))).toFloat() }
    private val frames = ArrayDeque<Pair<Long, FloatArray>>()
    @Volatile var enabled = true
    /** Paused or stopped: nothing is heard, so the visualizer lies flat. */
    @Volatile var playing = false
        private set

    fun setPlaying(on: Boolean) {
        playing = on
        if (!on) synchronized(frames) { frames.clear() }
    }

    /** Mono samples (−1..1) that will be heard after [delayMs]. */
    fun push(samples: FloatArray, count: Int, rate: Int, delayMs: Long) {
        if (!enabled || !playing) return
        var i = 0
        while (i < count) {
            ring[fill++] = samples[i++]
            if (fill == N) {
                fill = 0
                analyse(rate, System.currentTimeMillis() + delayMs - (N * 1000L / rate.coerceAtLeast(1)) * 0 )
            }
        }
    }

    private fun analyse(rate: Int, at: Long) {
        for (k in 0 until N) { re[k] = ring[k] * window[k]; im[k] = 0f }
        fft.transform(re, im, false)
        val out = FloatArray(BANDS)
        val lo = ln(40.0); val hi = ln(min(16000.0, rate / 2.0 - 1))
        for (b in 0 until BANDS) {
            val f0 = exp(lo + (hi - lo) * b / BANDS); val f1 = exp(lo + (hi - lo) * (b + 1) / BANDS)
            val k0 = (f0 * N / rate).toInt().coerceIn(1, N / 2 - 1); val k1 = (f1 * N / rate).toInt().coerceIn(k0 + 1, N / 2)
            var sum = 0.0
            for (k in k0 until k1) sum += (re[k] * re[k] + im[k] * im[k]).toDouble()
            val db = 10 * log10(sum / (k1 - k0) + 1e-12)
            out[b] = ((db + 20) / 70).toFloat().coerceIn(0f, 1f)
        }
        synchronized(frames) {
            frames.addLast(at to out)
            while (frames.size > 120) frames.removeFirst()
        }
    }

    /** The spectrum being heard right now (null when nothing is playing). */
    fun now(): FloatArray? {
        if (!playing) return null
        val t = System.currentTimeMillis()
        synchronized(frames) {
            while (frames.size > 1 && frames.elementAt(1).first <= t) frames.removeFirst()
            val f = frames.firstOrNull() ?: return null
            return if (t - f.first > 400) null else f.second
        }
    }
}


/** Passes sound through unchanged and lets the visualizer listen (Android output path). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VizTap : androidx.media3.common.audio.BaseAudioProcessor() {
    private var buf = FloatArray(0)

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != androidx.media3.common.C.ENCODING_PCM_16BIT && e != androidx.media3.common.C.ENCODING_PCM_FLOAT) return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val n = inputBuffer.remaining()
        val fmt = inputAudioFormat
        if (Viz.enabled && fmt.channelCount > 0) {
            val dup = inputBuffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val float = fmt.encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT
            val frames = n / ((if (float) 4 else 2) * fmt.channelCount)
            if (buf.size < frames) buf = FloatArray(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until fmt.channelCount) sum += if (float) dup.float else dup.short / 32768f
                buf[f] = sum / fmt.channelCount
            }
            Viz.push(buf, frames, fmt.sampleRate, 220)
        }
        val out = replaceOutputBuffer(n)
        out.put(inputBuffer)
        out.flip()
    }
}
