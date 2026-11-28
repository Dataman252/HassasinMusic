package com.hassasin.music

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

// ---------------------------------------------------------------- Pure mode

/** Pure mode: every app effect, crossfade, ReplayGain and speed off at once; turning it off brings them back. */
object Pure {
    fun isOn(s: AppSettings = AppState.settings.value) = s.pureBackup != null

    fun set(on: Boolean) {
        val s = AppState.settings.value
        if (on == isOn(s)) return
        if (on) AppState.update {
            it.copy(
                pureBackup = PureBackup(it.sound, it.crossfadeSec, it.replayGain, it.speed),
                sound = SoundSettings(limiter = it.sound.limiter), crossfadeSec = 0, replayGain = "off", speed = 1f,
            )
        } else AppState.update { st ->
            val b = st.pureBackup ?: return@update st
            st.copy(pureBackup = null, sound = b.sound, crossfadeSec = b.crossfadeSec, replayGain = b.replayGain, speed = b.speed)
        }
        SoundState.set(AppState.settings.value.sound)
        Speed.apply()
        Playback.rebuildAudio("pure mode")
    }
}

// ---------------------------------------------------------------- Purity

/** How untouched the sound is right now: 0 = bit-perfect, 1 = clean (only converted), 2 = processed (EQ/effects), -1 = unknown. */
data class Purity(val level: Int, val title: String, val why: String)

fun purityOf(context: Context, st: PlayState, s: AppSettings): Purity {
    val steps = OutputChain.steps(context, st, s)
    if (steps.isEmpty()) return Purity(-1, "Play a song", "The check runs on the song playing")
    val dsp = steps.firstOrNull { it.title == "App DSP" }
    val out = steps.firstOrNull { it.title == "Output" }
    val effects = Eq.anyEffect(s.sound, st.usb)
    return when {
        effects -> Purity(2, "Processed", "EQ or effects are shaping the sound: ${dsp?.detail ?: ""}")
        DsdPlayback.converted(st) -> Purity(1, "Clean", DsdPlayback.describe())
        dsp?.state != ChainStep.CHANGED && out?.state == ChainStep.UNTOUCHED -> Purity(0, "Bit-perfect", "Every sample reaches the output exactly as it is in the file")
        else -> Purity(1, "Clean", listOfNotNull(dsp?.takeIf { it.state == ChainStep.CHANGED }?.detail, out?.takeIf { it.state != ChainStep.UNTOUCHED }?.detail).joinToString("; ").ifBlank { "Converted on the way, nothing added" })
    }
}

@Composable
fun PurityCard() {
    val context = LocalContext.current
    val st by Playback.state.collectAsState()
    val s by AppState.settings.collectAsState()
    val path by AudioPathInfo.path.collectAsState()
    val p = remember(st, s, path) { purityOf(context, st, s) }
    // Status colours: green = untouched, amber = converted, grey = processed by choice.
    val col = when (p.level) { 0 -> C.Ok; 1 -> C.Amber; else -> Color.White.copy(alpha = 0.55f) }
    Column(Modifier.fillMaxWidth().raised().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SIGNAL PURITY", style = eyebrow())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(10.dp))
            Text(p.title, style = display(22.sp))
        }
        Text(p.why, style = body(12.sp, color = C.TextMid))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Bit-perfect" to C.Ok, "Clean" to C.Amber, "Processed" to Color.White.copy(alpha = 0.55f)).forEach { (l, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp))
                    Text(l, style = body(11.sp, color = C.TextLow))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Best-quality setup

/** One recommended change, with why. */
data class RecLine(val title: String, val why: String)

data class Recommendation(
    val output: String, val dvc: Boolean, val resampler: Boolean, val pureSuggested: Boolean,
    val lines: List<RecLine>, val notes: List<String>, val bluetooth: Boolean = false,
)

object QualitySetup {
    /** What the phone is playing through now: speaker, wired, usb or bt. */
    fun detect(context: Context): String = when (AudioPathInfo.deviceShort(context)) {
        "Bluetooth" -> "bt"
        "USB" -> "usb"
        "Wired" -> "wired"
        else -> if (Playback.state.value.dacPresent) "usb" else "speaker"
    }

    fun recommend(context: Context, dev: String): Recommendation {
        val mixer = AudioPathInfo.mixerRate(context).takeIf { it > 0 } ?: 48000
        val mix = "${khz(mixer)} kHz"
        val st = Playback.state.value
        return when (dev) {
            "usb" -> Recommendation(
                "usb", dvc = false, resampler = false, pureSuggested = true,
                listOf(
                    RecLine("Output: Untouched (USB DAC)", "The app's own USB driver sends every song to your DAC at its own rate and bit depth. Android's mixer is skipped."),
                    RecLine("Volume: in the DAC", "The DAC sets the volume itself, the cleanest way. The app's volume control is not needed."),
                    RecLine("Crossfade off", "Songs play gapless and untouched."),
                ),
                listOfNotNull(
                    if (!st.dacPresent) "Plug in your DAC first; until then the app plays as Phone." else null,
                    "When Android asks to allow access to the DAC, tap OK.",
                ),
            )
            "wired" -> if (PhoneDirectSession.available == true) Recommendation(
                "direct", dvc = true, resampler = false, pureSuggested = true,
                listOf(
                    RecLine("Output: Direct (phone)", "Skips Android's mixer and the phone's sound processing; the app writes straight to the audio hardware at $mix."),
                    RecLine("Direct Volume Control on", "The app sets the volume in 64-bit; nothing is lost at low volume."),
                    RecLine("Crossfade off", "Songs play gapless and untouched."),
                ),
                listOf("Songs at $mix arrive bit-perfect; others are converted by the app's own high-quality converter."),
            ) else Recommendation(
                "hires", dvc = true, resampler = true, pureSuggested = true,
                listOf(
                    RecLine("Output: Hi-Res", "Songs go to Android in 32-bit float, never squeezed to 16-bit."),
                    RecLine("App resampler on", "This phone mixes everything at $mix. The app converts 44.1 kHz songs itself with a high-quality filter, so Android's simpler converter has nothing to do."),
                    RecLine("Direct Volume Control on", "The app sets the volume in 64-bit; Android's volume stays at full, so no detail is lost at low volume."),
                    RecLine("Crossfade off", "Songs play gapless and untouched."),
                ),
                listOf("This phone does not allow bit-perfect output through its own jack. For bit-perfect sound, use a USB DAC."),
            )
            "bt" -> Recommendation(
                "tuned", dvc = false, resampler = false, pureSuggested = false,
                listOf(
                    RecLine("Output: Phone", "Bluetooth re-encodes all sound (LDAC, aptX, AAC or SBC), so the Hi-Res paths add nothing here."),
                    RecLine("Volume: the headphones'", "Bluetooth headphones sound best with their own volume control, so the app's volume control stays off."),
                ),
                listOf("Best Bluetooth quality: Developer options → Bluetooth audio codec → LDAC, then LDAC playback quality → Optimised for audio quality (990 kbps). Apps cannot change this themselves."),
                bluetooth = true,
            )
            else -> Recommendation(
                "tuned", dvc = false, resampler = true, pureSuggested = false,
                listOf(
                    RecLine("Output: Phone", "The phone's own speaker tuning makes its small speakers sound fuller and clearer."),
                    RecLine("App resampler on", "44.1 kHz songs are converted to the phone's $mix by the app's high-quality filter."),
                ),
                listOf("EQ and effects are fine here: speakers gain from a little shaping."),
            )
        }
    }

    fun apply(context: Context, dev: String, r: Recommendation, pure: Boolean) {
        AppState.update { it.copy(appResampler = r.resampler, setupDone = true, setupDevice = dev, crossfadeSec = if (r.pureSuggested) 0 else it.crossfadeSec) }
        if (AppState.settings.value.dvc != r.dvc) Dvc.enable(context, r.dvc)
        Pure.set(pure)
        Playback.setOutput(r.output)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QualitySetupScreen(nav: Nav) {
    val context = LocalContext.current
    val detected = remember { QualitySetup.detect(context) }
    var dev by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf(0) }
    var pure by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Tour.busyThisSession = true }
    Backdrop(Color(0xFF1B1F4A)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header("Best sound setup", { if (stage > 0) stage-- else nav.back() }) }
            when (stage) {
                0 -> {
                    item { Text("What are you listening with?", style = display(20.sp)) }
                    item { Text("The app checks what this phone can do and sets itself up for the cleanest sound on it.", style = body(13.sp, color = C.TextMid)) }
                    listOf(
                        Triple("usb", "USB DAC", "A DAC or dongle in the USB-C port"),
                        Triple("wired", "Wired earphones", "In the phone's 3.5 mm jack (or a simple adapter)"),
                        Triple("bt", "Bluetooth", "Wireless earbuds, headphones or speaker"),
                        Triple("speaker", "Phone speaker", "No headphones"),
                    ).forEach { (k, title, line) ->
                        item {
                            Row(
                                Modifier.fillMaxWidth().then(if (k == detected) Modifier.amberSelected(RoundedCornerShape(18.dp)) else Modifier.raised(RoundedCornerShape(18.dp)))
                                    .clip(RoundedCornerShape(18.dp)).clickable { dev = k; pure = QualitySetup.recommend(context, k).pureSuggested; stage = 1 }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = body(16.sp, FontWeight.Bold))
                                    Text(line, style = body(12.sp, color = C.TextMid))
                                }
                                if (k == detected) Text("Connected now", style = mono(10.sp, FontWeight.Bold, C.AmberText))
                                Text("  ›", style = body(22.sp, color = C.TextLow))
                            }
                        }
                    }
                }
                1 -> {
                    val d = dev ?: "speaker"
                    val r = QualitySetup.recommend(context, d)
                    item { Text("Recommended for this phone", style = display(20.sp)) }
                    r.lines.forEach { line ->
                        item {
                            Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(line.title, style = body(15.sp, FontWeight.Bold, C.AmberText))
                                Text(line.why, style = body(12.sp, color = C.TextMid))
                            }
                        }
                    }
                    item {
                        SettingRow("Turn off EQ and effects", if (pure) "Pure mode: nothing shapes the sound. Your EQ and effects come back when you turn it off" else "Keep your EQ and effects as they are") {
                            Toggle(pure, "Turn off EQ and effects") { pure = it }
                        }
                    }
                    r.notes.forEach { n -> item { Text(n, style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(horizontal = 4.dp)) } }
                    if (r.bluetooth) item {
                        Chip("Open Developer options", false) {
                            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    item {
                        Box(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(C.Amber).clickable { QualitySetup.apply(context, d, r, pure); stage = 2 },
                            contentAlignment = Alignment.Center,
                        ) { Text("Apply", style = body(16.sp, FontWeight.Bold, C.OnAccent)) }
                    }
                }
                else -> {
                    item { Text("Done. Here is the path your music takes now", style = display(20.sp)) }
                    item { PurityCard() }
                    item { OutputChainCard() }
                    if (Playback.state.value.current == null) item { Text("Play a song to see the check.", style = body(12.sp, color = C.TextMid)) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Audiophile settings", false) { nav.back(); nav.go(Route.SettingsPage("audio")) }
                            Chip("Finish", true) { nav.back() }
                        }
                    }
                }
            }
        }
    }
}

/** When a USB DAC is plugged in and the app is not set up for it: offer the setup once per plug-in. */
@Composable
fun SetupNudge(nav: Nav) {
    val st by Playback.state.collectAsState()
    var shown by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(st.dacPresent) {
        if (!st.dacPresent) { dismissed = false; shown = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(1500)
        val s = AppState.settings.value
        shown = !s.autoTune && s.output != "usb" && s.output != "usb_android" && !Tour.active && !Changelog.showing
    }
    if (!shown || dismissed) return
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().raised(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("USB DAC connected", style = body(15.sp, FontWeight.Bold))
            Text("Set the app up for bit-perfect sound through it?", style = body(13.sp, color = C.TextMid))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Set it up", true) { dismissed = true; nav.go(Route.QualitySetup) }
                Chip("Not now", false) { dismissed = true }
            }
        }
    }
}

// ---------------------------------------------------------------- Test signals

/** Test signals for checking a setup by ear: channels, polarity, a frequency sweep and the noise floor. */
object AudioTests {
    var running by mutableStateOf("")
        private set
    var now by mutableStateOf("")
        private set
    @Volatile private var stop = false
    private var thread: Thread? = null

    private const val RATE = 48000

    fun stop() { stop = true; thread?.join(500); thread = null; running = ""; now = "" }

    fun play(kind: String) {
        stop()
        if (Playback.isReady() && Playback.player.isPlaying) Playback.main { Playback.player.pause() }
        stop = false
        running = kind
        thread = Thread {
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT) * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
            }.getOrNull() ?: run { running = ""; return@Thread }
            track.play()
            // -18 dBFS, and the app's own volume when DVC holds Android's volume at full.
            val level = 0.125f * Dvc.gain()
            val buf = FloatArray(1024 * 2)
            var pink = Pink()
            fun write(seconds: Double, label: String, gen: (t: Double) -> Pair<Float, Float>): Boolean {
                now = label
                val frames = (seconds * RATE).toInt()
                var i = 0
                while (i < frames) {
                    if (stop) return false
                    val n = minOf(1024, frames - i)
                    for (f in 0 until n) {
                        val t = (i + f).toDouble() / RATE
                        // 20 ms fades at both ends: no clicks.
                        val fade = minOf(1.0, t / 0.02, (seconds - t) / 0.02).coerceAtLeast(0.0).toFloat()
                        val (l, r) = gen(t)
                        buf[2 * f] = l * level * fade; buf[2 * f + 1] = r * level * fade
                    }
                    track.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    i += n
                }
                return true
            }
            run {
                when (kind) {
                    "lr" -> repeat(3) {
                        if (!write(0.8, "Left") { _ -> pink.next().let { it to 0f } }) return@run
                        if (!write(0.4, "") { _ -> 0f to 0f }) return@run
                        if (!write(0.8, "Right") { _ -> pink.next().let { 0f to it } }) return@run
                        if (!write(0.6, "") { _ -> 0f to 0f }) return@run
                    }
                    "polarity" -> repeat(2) {
                        if (!write(4.0, "In phase: centred, full bass") { t -> (0.6f * sin(2 * PI * 80 * t).toFloat() + 0.5f * pink.next()).let { it to it } }) return@run
                        if (!write(4.0, "Out of phase: hollow, inside your head") { t -> (0.6f * sin(2 * PI * 80 * t).toFloat() + 0.5f * pink.next()).let { it to -it } }) return@run
                    }
                    "sweep" -> {
                        val dur = 30.0
                        val k = ln(20000.0 / 20.0)
                        var lastShown = -1
                        write(dur, "20 Hz") { t ->
                            // Logarithmic sweep 20 Hz → 20 kHz; phase is the integral of the frequency.
                            val ph = 2 * PI * 20.0 * dur / k * (exp(t / dur * k) - 1)
                            val hz = (20.0 * exp(t / dur * k)).toInt()
                            val shown = if (hz < 1000) hz / 10 * 10 else hz / 100 * 100
                            if (shown != lastShown) { lastShown = shown; now = if (shown < 1000) "$shown Hz" else "%.1f kHz".format(shown / 1000.0) }
                            val v = sin(ph).toFloat() * 0.8f
                            v to v
                        }
                    }
                    "silence" -> write(20.0, "Digital silence: any hiss is your output's noise") { _ -> 0f to 0f }
                }
            }
            runCatching { track.stop(); track.release() }
            running = ""; now = ""
        }.also { it.start() }
    }

    /** Pink noise (Paul Kellet's filter), about -10 dB of full scale. */
    private class Pink {
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
        private val rnd = java.util.Random(7)
        fun next(): Float {
            val w = rnd.nextDouble() * 2 - 1
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            return (p * 0.11).toFloat()
        }
    }
}

@Composable
private fun TestRow(kind: String, title: String, line: String) {
    val on = AudioTests.running == kind
    Column(Modifier.fillMaxWidth().then(if (on) Modifier.amberSelected(RoundedCornerShape(16.dp)) else Modifier.raised()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = body(14.sp, FontWeight.Bold))
                Text(line, style = body(12.sp, color = C.TextMid))
            }
            Chip(if (on) "Stop" else "Play", on) { if (on) AudioTests.stop() else AudioTests.play(kind) }
        }
        if (on && AudioTests.now.isNotBlank()) Text(AudioTests.now, style = mono(16.sp, FontWeight.Bold, C.AmberText))
    }
}

// ---------------------------------------------------------------- The Audiophile page

/** Settings → Audiophile: every control that touches sound quality, in signal order, file to ears. */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.audiophileItems(nav: Nav, s: AppSettings, ctx: Context) {
    item { PurityCard() }
    item {
        SettingRow("Pure mode", if (Pure.isOn(s)) "On: EQ, effects, crossfade, ReplayGain and speed are off. Turn it off to bring them all back" else "Turn off everything that changes the sound in one tap (your settings are kept)") {
            Toggle(Pure.isOn(s), "Pure mode") { Pure.set(it) }
        }
    }
    item { LabRow(nav) }

    item { Text("1 · SOURCE", style = eyebrow(), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    item {
        ChoiceRow("Crossfade", listOf(0, 2, 4, 6, 8, 12).map { it.toString() to (if (it == 0) "Off (gapless)" else "$it s") }, s.crossfadeSec.toString(),
            "Off keeps songs gapless and untouched. Crossfade mixes the end of one song into the next.") { k -> AppState.update { it.copy(crossfadeSec = k.toInt()) } }
    }
    item {
        ChoiceRow("ReplayGain", listOf("off" to "Off", "track" to "Per song", "album" to "Per album"), s.replayGain,
            "Evens out loudness by turning loud songs down (64-bit). Per album keeps the album's own dynamics. Off is bit-perfect.") { k -> AppState.update { it.copy(replayGain = k) } }
    }
    item {
        ChoiceRow("DSD files", listOf("dop" to "Native to USB DAC (DoP)", "pcm" to "Always convert to PCM"), s.dsdMode,
            if (s.dsdMode == "dop") "With the Untouched USB output and EQ off, DSD goes to your DAC as DoP: the DSD bits arrive untouched and the DAC's DSD light comes on. Otherwise, or if the DAC refuses the rate, the app converts it to 24-bit PCM."
            else "DSD is converted by the app to 24-bit PCM (88.2 kHz for DSD64, 176.4 kHz above), so EQ and effects work on it.") { k -> AppState.update { it.copy(dsdMode = k) }; DsdPlayback.refused.clear(); Playback.rebuildAudio("dsd mode") }
    }
    if (s.speed != 1f) item {
        SettingRow("Speed is ${s.speed}×", "Changing speed re-processes every sample") { Chip("Back to 1×", false) { AppState.update { it.copy(speed = 1f) }; Speed.apply() } }
    }

    item { Text("2 · PROCESSING", style = eyebrow(), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    item { SettingRow("Equalizer, correction & effects", if (Eq.anyEffect(s.sound, false)) "On: the sound is shaped in 64-bit" else "All off") { RoundButton(I.Eq, "Equalizer and effects", { nav.go(Route.Sound(0)) }, 40.dp) } }
    item {
        SettingRow("Headroom", "Lowers the level before EQ and effects so boosts cannot clip: ${"%+.0f".format(s.sound.fx.masterGainDb)} dB") {
            RoundButton(I.Minus, "Less level", { AppState.updateSound { it.copy(fx = it.fx.copy(masterGainDb = (it.fx.masterGainDb - 1).coerceAtLeast(-12.0))) }; SoundState.set(AppState.settings.value.sound) }, 36.dp)
            Spacer(Modifier.width(8.dp))
            RoundButton(I.Plus, "More level", { AppState.updateSound { it.copy(fx = it.fx.copy(masterGainDb = (it.fx.masterGainDb + 1).coerceAtMost(6.0))) }; SoundState.set(AppState.settings.value.sound) }, 36.dp)
        }
    }
    item {
        SettingRow("Peak limiter", if (s.sound.limiter) "Catches peaks over 0 dB after EQ and effects (only works while they are on)" else "Off: boosts can clip; watch the red clipping warning") {
            Toggle(s.sound.limiter, "Peak limiter") { v -> AppState.updateSound { it.copy(limiter = v) }; SoundState.set(AppState.settings.value.sound) }
        }
    }

    item { Text("3 · CONVERSION", style = eyebrow(), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    val mixer = AudioPathInfo.mixerRate(ctx)
    item {
        SettingRow("App resampler", if (s.appResampler) "Songs not at ${khz(mixer)} kHz are converted by the app, not by Android" else "Off: Android converts songs to its ${khz(mixer)} kHz mixer") {
            Toggle(s.appResampler, "App resampler") { v -> AppState.update { it.copy(appResampler = v) }; Playback.rebuildAudio("app resampler") }
        }
    }
    if (s.appResampler) {
        item {
            ChoiceRow("Filter", listOf("sharp" to "Sharp", "steep" to "Steep", "soft" to "Soft", "minphase" to "Minimum phase"), s.resamplerFilter,
                when (s.resamplerFilter) {
                    "steep" -> "128 taps: keeps everything up to 21.7 kHz with the deepest stopband. Most CPU."
                    "soft" -> "24 taps: rolls off from about 19.8 kHz, with the shortest ringing. Least CPU."
                    "minphase" -> "No ringing before a sound (only after it), like many DACs' \"minimum phase\" filter. Same response as Sharp."
                    else -> "48 taps, linear phase: flat to about 21.3 kHz. The default, a good balance."
                }) { k -> AppState.update { it.copy(resamplerFilter = k) }; Playback.rebuildAudio("resampler filter") }
        }
        item {
            ChoiceRow("Bit depth to Android", listOf("auto" to "Highest possible", "16" to "16-bit"), s.outDepth,
                (if (s.output == "tuned") "Phone output always takes 16-bit, dithered by the app. Hi-Res output takes 32-bit float. " else "Highest possible = 32-bit float with this output. ") +
                    "16-bit is what CDs hold: the app adds dither so quiet sounds fade into soft noise, not distortion.") { k -> AppState.update { it.copy(outDepth = k) }; Playback.rebuildAudio("bit depth") }
        }
        if (s.outDepth == "16" || s.output == "tuned") item {
            ChoiceRow("Dither (16-bit)", listOf("tpdf" to "Flat", "shaped" to "Shaped", "off" to "Off"), s.dither,
                when (s.dither) {
                    "shaped" -> "Moves the dither noise up to the highest frequencies, where ears hear it least."
                    "off" -> "Just rounds. Very quiet passages can sound grainy."
                    else -> "Standard TPDF dither: a very low, even hiss instead of distortion."
                }) { k -> AppState.update { it.copy(dither = k) }; Playback.rebuildAudio("dither") }
        }
    }

    item { Text("4 · OUTPUT", style = eyebrow(), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    item {
        ChoiceRow("USB DAC: rates it lacks", listOf("auto" to "Nearest rate it has", "max" to "Upsample to its highest", "off" to "Leave to Android"), s.usbResample,
            when (s.usbResample) {
                "max" -> "Every song is converted by the app to the DAC's highest rate (more CPU)."
                "off" -> "Songs at a rate the DAC lacks go through Android, which converts them (often to 48 kHz)."
                else -> "Songs at a rate the DAC lacks are converted by the app to its nearest higher rate, so nothing above the song is lost. Songs at rates it has stay bit-perfect."
            }) { k -> AppState.update { it.copy(usbResample = k) }; Playback.rebuildAudio("usb rates") }
    }
    item { SettingRow("Output: ${Outputs.of(s.output).title}", "How the sound leaves the phone: Phone, Hi-Res, Direct, USB DAC or Battery saver") { RoundButton(I.Plug, "Output", { nav.go(Route.Sound(5)) }, 40.dp) } }
    item {
        SettingRow("Direct Volume Control", if (s.dvc) "The app sets the volume in 64-bit; Android's stays at full. Now ${Dvc.label()}" else "Android sets the volume") {
            Toggle(s.dvc, "Direct Volume Control") { v -> Dvc.enable(ctx, v) }
        }
    }
    item { OutputChainCard() }

    item { Text("5 · TEST YOUR SETUP", style = eyebrow(), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    item { Text("Played at a safe level. The music pauses while a test plays.", style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(horizontal = 4.dp)) }
    item { TestRow("lr", "Left and right", "Noise bursts left, then right: check the channels are not swapped") }
    item { TestRow("polarity", "Polarity", "Alternates in phase and out of phase. In phase should sound centred and full") }
    item { TestRow("sweep", "Frequency sweep", "20 Hz to 20 kHz in 30 s: note where you stop hearing it") }
    item { TestRow("silence", "Noise floor", "20 s of digital silence: turn the volume up and listen for hiss. Turn it down before music") }

    item { Text("WHAT ANDROID DOES NOT ALLOW", style = eyebrow(), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    item {
        Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Bit-perfect through the phone's own jack, unless the phone allows Direct output (Direct appears under Output when it does)",
                "Choosing the Bluetooth codec from an app: that is in Developer options",
                "Native DSD through the phone's jack or Bluetooth (it is converted to PCM there), and MQA unfolding",
            ).forEach { Text("• $it", style = body(12.sp, color = C.TextMid)) }
        }
    }
}

/** Audiophile's first row: the connected device and what the test found. */
@Composable
private fun LabRow(nav: Nav) {
    val ctx = LocalContext.current
    val profiles by DeviceLab.profiles.collectAsState()
    val cur = remember(profiles) { DeviceLab.current(ctx) }
    val p = profiles[cur.key]
    val line = when {
        p == null || p.testedAt == 0L -> "${cur.name}: not tested yet. The app tests it and sets itself up for it"
        p.kind == "usb" && p.rates.isNotEmpty() -> "${cur.name}: ${khz(p.rates.min())}–${khz(p.rates.max())} kHz, ${p.bits.maxOrNull() ?: 0}-bit, ${if (p.dopDsd.isEmpty()) "no native DSD" else "DSD${p.dopDsd.max()} native"}"
        else -> "${cur.name}: tested; ${if (p.limits.isEmpty()) "no limits found" else p.limits.first()}"
    }
    SettingRow("Test my setup", line) { RoundButton(I.Plug, "Test my setup", { nav.go(Route.QualitySetup) }, 40.dp) }
}
