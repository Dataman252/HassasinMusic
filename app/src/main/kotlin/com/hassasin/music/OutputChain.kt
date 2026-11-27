package com.hassasin.music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/** One step the sound passes through, with whether it changes the sound there. */
data class ChainStep(val title: String, val detail: String, val state: Int) {
    companion object { const val UNTOUCHED = 0; const val CHANGED = 1; const val NEUTRAL = 2 }
}

/**
 * The output chain: file → decoder → app DSP → Android's path → device, each with its format and
 * whether it changes the sound (green: untouched, amber: changed). Plus live app CPU and battery draw,
 * and a diagnostics report to copy or share (for testing on other phones).
 */
object OutputChain {
    fun steps(context: Context, st: PlayState, s: AppSettings): List<ChainStep> {
        val t = st.current ?: return emptyList()
        val p = AudioPathInfo.path.value
        val out = mutableListOf<ChainStep>()
        out += ChainStep("File", "${t.formatLabel}${if (t.bitrate > 0) " · ${t.bitrate / 1000} kbps" else ""}", ChainStep.NEUTRAL)
        val flacChip = FlacOffloadSink.active
        val (dec, decLong) = AudioPathInfo.decoderLine(p, st.usb)
        out += when {
            flacChip.isNotEmpty() -> ChainStep("Decoder", "Phone's audio chip decodes the FLAC itself (offload)", ChainStep.UNTOUCHED)
            else -> ChainStep("Decoder", "$dec · $decLong", if (dec == "…") ChainStep.NEUTRAL else ChainStep.UNTOUCHED)
        }
        val effects = Eq.anyEffect(s.sound, st.usb) && !(s.output == "offload" && !st.usb)
        val dsp = buildList {
            if (effects) add("EQ / effects (64-bit)")
            if (Dvc.on() && Dvc.step() < Dvc.STEPS) add("volume ${Dvc.label()} (64-bit)")
            val mix = AudioPathInfo.mixerRate(context)
            if (s.appResampler && !st.usb && !st.phoneDirect && s.output != "offload" && t.sampleRate > 0 && mix > 0 && t.sampleRate != mix) add("resampled ${khz(t.sampleRate)} → ${khz(mix)} kHz (app's 48-tap sinc)")
            if (s.speed != 1f && !st.usb && !st.phoneDirect) add("speed ${s.speed}×")
            if (s.replayGain != "off") ReplayGain.describe(t).takeIf { it.isNotBlank() }?.let { add("ReplayGain $it") }
        }
        out += if (dsp.isEmpty()) ChainStep("App DSP", "Bypassed: samples pass through unchanged", ChainStep.UNTOUCHED)
        else ChainStep("App DSP", dsp.joinToString(", "), ChainStep.CHANGED)
        val mixer = AudioPathInfo.mixerRate(context)
        out += when {
            st.usb -> ChainStep("Output", "App USB driver → ${UsbLive.path.ifBlank { st.dacName }}", if (st.untouched) ChainStep.UNTOUCHED else ChainStep.CHANGED)
            st.phoneDirect -> ChainStep("Output", "Phone direct (exclusive) · ${khz(st.dacRate)} kHz${if (st.untouched) ", native rate" else ", converted by the app"}", if (st.untouched) ChainStep.UNTOUCHED else ChainStep.CHANGED)
            flacChip.isNotEmpty() -> ChainStep("Output", "Offload · $flacChip (native rate, CPU asleep)", ChainStep.UNTOUCHED)
            p.offload -> ChainStep("Output", "Offload · the audio chip plays the file (native rate)", ChainStep.UNTOUCHED)
            s.output == "usb_android" && AndroidBitPerfect.active.isNotEmpty() -> ChainStep("Output", "Android bit-perfect USB · ${AndroidBitPerfect.active}", ChainStep.UNTOUCHED)
            else -> {
                val resampled = p.trackRate > 0 && mixer > 0 && p.trackRate != mixer
                ChainStep(
                    "Output",
                    "Android mixer · sent as ${AudioPathInfo.encodingName(p.trackEncoding)} ${khz(p.trackRate)} kHz" +
                        if (resampled) " → resampled to ${khz(mixer)} kHz" else if (mixer > 0) " (mixer ${khz(mixer)} kHz)" else "",
                    if (resampled) ChainStep.CHANGED else ChainStep.NEUTRAL,
                )
            }
        }
        out += ChainStep("Device", AudioPathInfo.device(context), ChainStep.NEUTRAL)
        return out
    }

    // ---- live cost ----

    private var lastCpu = 0L
    private var lastAt = 0L

    /** The app's CPU use since the last call, in % of one core. */
    fun cpuPercent(): Double? = runCatching {
        val f = File("/proc/${Process.myPid()}/stat").readText().substringAfterLast(')').trim().split(' ')
        val ticks = f[11].toLong() + f[12].toLong() // utime + stime (fields 14, 15)
        val now = SystemClock.elapsedRealtime()
        // Ticks are 10 ms: (Δticks × 10 ms) / Δt ms × 100 % = Δticks × 1000 / Δt ms.
        val r = if (lastAt > 0) (ticks - lastCpu) * 1000.0 / (now - lastAt).coerceAtLeast(1) else null
        lastCpu = ticks; lastAt = now
        r
    }.getOrNull()

    /** What the whole phone draws from the battery right now, in mA (null while charging or unknown). */
    fun batteryMa(context: Context): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (bm.isCharging) return null
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == Int.MIN_VALUE || ua == 0) return null
        // Phones report µA (most) or mA (a few); either way as a draw it is the magnitude.
        val v = kotlin.math.abs(ua)
        return if (v > 20_000) v / 1000 else v
    }

    // ---- diagnostics ----

    fun report(context: Context): String {
        val st = Playback.state.value
        val s = AppState.settings.value
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val sb = StringBuilder()
        sb.appendLine("Hassasin Music ${BuildConfig.VERSION_NAME} — output diagnostics")
        sb.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}, SoC ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}")
        sb.appendLine("Output setting: ${s.output}${if (s.dvc) ", DVC ${Dvc.label()}" else ""}${if (s.crossfadeSec > 0) ", crossfade ${s.crossfadeSec}s" else ""}")
        sb.appendLine("Mixer: ${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz, ${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} frames")
        sb.appendLine()
        sb.appendLine("CHAIN")
        steps(context, st, s).forEach { sb.appendLine("  ${it.title}: ${it.detail}") }
        sb.appendLine()
        sb.appendLine("OFFLOAD (chip decodes)")
        sb.appendLine("  Codecs: ${Outputs.offloadCodecs(context).ifEmpty { listOf("none") }.joinToString()}")
        for (r in listOf(44100, 48000, 96000, 192000)) sb.appendLine("  FLAC ${khz(r)} kHz: ${if (FlacOffloadSink.chipCanPlay(r, 2)) "yes" else "no"}")
        if (FlacOffloadSink.broken) sb.appendLine("  FLAC offload failed this session (fell back to decoding)")
        sb.appendLine()
        sb.appendLine("DIRECT PCM (Android 13+ check)")
        if (Build.VERSION.SDK_INT >= 33) {
            val encs = listOf("16" to AudioFormat.ENCODING_PCM_16BIT, "24" to AudioFormat.ENCODING_PCM_24BIT_PACKED, "32" to AudioFormat.ENCODING_PCM_32BIT, "float" to AudioFormat.ENCODING_PCM_FLOAT)
            for (r in listOf(44100, 48000, 96000, 192000)) {
                val line = encs.joinToString("  ") { (n, e) ->
                    val f = runCatching { AudioFormat.Builder().setEncoding(e).setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build() }.getOrNull()
                    val v = f?.let { runCatching { AudioManager.getDirectPlaybackSupport(it, attrs) }.getOrDefault(-1) } ?: -1
                    "$n:${when { v < 0 -> "?"; v == 0 -> "no"; v and 2 != 0 -> "direct"; v and 1 != 0 -> "offload"; else -> v.toString() }}"
                }
                sb.appendLine("  ${khz(r)} kHz  $line")
            }
        } else sb.appendLine("  (needs Android 13)")
        sb.appendLine("Phone direct (exclusive AAudio): ${when (PhoneDirectSession.available) { true -> "available"; false -> "blocked by this phone"; null -> "not checked" }}")
        sb.appendLine("Android bit-perfect USB: ${AndroidBitPerfect.describe(context) ?: "no USB DAC / needs Android 14"}")
        sb.appendLine()
        sb.appendLine("DEVICES")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d ->
            sb.appendLine("  type ${d.type} ${d.productName}: rates ${d.sampleRates.joinToString("/").ifBlank { "any" }}, encodings ${d.encodings.joinToString("/").ifBlank { "any" }}")
        }
        sb.appendLine()
        cpuPercent()
        sb.appendLine("Battery draw now: ${batteryMa(context)?.let { "$it mA" } ?: "unknown / charging"}")
        return sb.toString()
    }

    fun copy(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hassasin Music diagnostics", text))
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Hassasin Music diagnostics"); putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, "Send diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The chain as a card: one row per step, joined by a line, coloured by what each step does to the sound. */
@Composable
fun OutputChainCard() {
    val context = LocalContext.current
    val st by Playback.state.collectAsState()
    val s by AppState.settings.collectAsState()
    val path by AudioPathInfo.path.collectAsState()
    var cpu by remember { mutableStateOf<Double?>(null) }
    var ma by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { OutputChain.cpuPercent(); while (true) { delay(2000); cpu = OutputChain.cpuPercent(); ma = OutputChain.batteryMa(context) } }
    val steps = remember(st, s, path, FlacOffloadSink.active) { OutputChain.steps(context, st, s) }
    if (steps.isEmpty()) return
    Column(Modifier.fillMaxWidth().raised().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OUTPUT CHAIN", style = eyebrow(), modifier = Modifier.weight(1f))
            Text(
                listOfNotNull(cpu?.let { "CPU %.1f%%".format(it) }, ma?.let { "$it mA" }).joinToString(" · "),
                style = mono(11.sp, color = C.TextMid),
            )
        }
        Spacer(Modifier.height(10.dp))
        steps.forEachIndexed { i, step ->
            val dot = when (step.state) { ChainStep.UNTOUCHED -> C.Ok; ChainStep.CHANGED -> C.Amber; else -> Color.White.copy(alpha = 0.45f) }
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(dot))
                    if (i < steps.lastIndex) Box(Modifier.padding(top = 2.dp).width(2.dp).height(34.dp).background(Color.White.copy(alpha = 0.12f)))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(step.title, style = body(13.sp, FontWeight.Bold))
                    Text(step.detail, style = body(12.sp, color = C.TextMid))
                }
            }
        }
        Text("Green: untouched at that step · amber: the sound is changed there", style = body(11.sp, color = C.TextLow))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Copy diagnostics", false) { OutputChain.copy(context, OutputChain.report(context)) }
            Chip("Share diagnostics", false) { OutputChain.share(context, OutputChain.report(context)) }
        }
    }
}

/** Screen on/off, for saving battery while nobody is looking. */
object Power {
    @Volatile var screenOn = true
}
