package com.hassasin.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** A labelled slider with its value; snaps to [step]. */
@Composable
fun ParamSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, text: (Double) -> String, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = body(13.sp, color = C.TextMid), modifier = Modifier.weight(1f))
            Text(text(value), style = mono(12.sp, FontWeight.Bold, C.AmberText))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange((Math.round(v / step) * step).coerceIn(range.start, range.endInclusive)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            colors = SliderDefaults.colors(thumbColor = C.AmberLight, activeTrackColor = C.Amber, inactiveTrackColor = C.Track),
        )
    }
}

/** One effect: its name, what it does, an on/off switch, and its controls when on. */
@Composable
private fun FxCard(title: String, about: String, on: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().raised(RoundedCornerShape(20.dp), 6.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = body(15.sp, FontWeight.Bold))
                Text(about, style = body(12.sp, color = C.TextMid))
            }
            Toggle(on, title, onToggle)
        }
        AnimatedVisibility(on, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    }
}

@Composable
private fun ModeChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        options.forEach { (k, label) -> Chip(label, selected == k) { onSelect(k) } }
    }
}

private fun dbText(v: Double) = "%+.1f dB".format(v)
private fun oneOf10(v: Double) = "${v.roundToInt()} / 10"

/** HiBy MSEB-style tuning: shape the sound by character, not by frequency. */
@Composable
fun TuningTab() {
    val settings by AppState.settings.collectAsState()
    val m = settings.sound.mseb
    fun set(f: (Mseb) -> Mseb) = AppState.updateSound { it.copy(mseb = f(it.mseb), eqOn = true) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().raised(RoundedCornerShape(20.dp), 6.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sound character", style = body(15.sp, FontWeight.Bold))
                    Text("Eight sliders that tune by feel. Centre = unchanged.", style = body(12.sp, color = C.TextMid))
                }
                Toggle(m.on, "Sound character") { on -> set { it.copy(on = on) } }
            }
        }
        val rows = listOf(
            Triple("Temperature", "Cool  ↔  Warm", m.temperature) to { v: Double -> set { it.copy(temperature = v) } },
            Triple("Bass extension", "Lighter  ↔  Deeper", m.bassExtension) to { v: Double -> set { it.copy(bassExtension = v) } },
            Triple("Bass texture", "Tight  ↔  Thick", m.bassTexture) to { v: Double -> set { it.copy(bassTexture = v) } },
            Triple("Vocal", "Male (chest)  ↔  Female (bright)", m.vocal) to { v: Double -> set { it.copy(vocal = v) } },
            Triple("Sibilance", "Softer  ↔  Sharper", m.sibilance) to { v: Double -> set { it.copy(sibilance = v) } },
            Triple("Presence", "Laid back  ↔  Forward", m.presence) to { v: Double -> set { it.copy(presence = v) } },
            Triple("Air", "Dark  ↔  Airy", m.air) to { v: Double -> set { it.copy(air = v) } },
            Triple("Soundstage", "Narrow  ↔  Wide", m.stage) to { v: Double -> set { it.copy(stage = v) } },
        )
        items(rows) { (info, change) ->
            val (name, ends, v) = info
            Column(Modifier.fillMaxWidth().raised(RoundedCornerShape(18.dp), 4.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                ParamSlider(name, v, -5.0..5.0, 0.5, { "%+.1f".format(it) }, change)
                Text(ends, style = body(11.sp, color = C.TextLow))
            }
        }
        item { Chip("Reset all to centre", false) { AppState.updateSound { it.copy(mseb = Mseb(on = it.mseb.on)) } } }
    }
}

/** The full effects suite, one card per effect. */
@Composable
fun EffectsTab() {
    val settings by AppState.settings.collectAsState()
    val f = settings.sound.fx
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun set(change: (FxSettings) -> FxSettings) = AppState.updateSound { it.copy(fx = change(it.fx), eqOn = true) }
    val irDir = remember { File(context.filesDir, "irs").apply { mkdirs() } }
    var irs by remember { mutableStateOf(irDir.listFiles()?.filter { it.extension.equals("wav", true) }?.sortedBy { it.name } ?: emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.let { if (it.endsWith(".wav", true)) it else "$it.wav" } ?: "impulse.wav"
                val out = File(irDir, name)
                context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out.takeIf { WavReader.read(it) != null } ?: run { out.delete(); null }
            }
            if (saved != null) { irs = irDir.listFiles()?.filter { it.extension.equals("wav", true) }?.sortedBy { it.name } ?: emptyList(); set { it.copy(convOn = true, convFile = saved.path) } }
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FxCard("Playback gain control", "Evens out loud and quiet songs", f.agcOn, { on -> set { it.copy(agcOn = on) } }) {
                ParamSlider("Target loudness", f.agcTargetDb, -30.0..-6.0, 0.5, ::dbText) { v -> set { it.copy(agcTargetDb = v) } }
                ParamSlider("Most boost", f.agcMaxGainDb, 0.0..20.0, 0.5, ::dbText) { v -> set { it.copy(agcMaxGainDb = v) } }
                ParamSlider("Speed", f.agcSpeed, 1.0..5.0, 1.0, { "${it.roundToInt()} / 5" }) { v -> set { it.copy(agcSpeed = v) } }
            }
        }
        item {
            FxCard("Convolver", "Room, speaker or headphone impulse responses (.wav)", f.convOn, { on -> set { it.copy(convOn = on) } }) {
                Text(if (f.convFile.isBlank()) "No impulse response chosen" else "Using ${File(f.convFile).nameWithoutExtension}", style = body(12.sp, color = C.TextMid))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    Chip("Add .wav file", false) { pick.launch("audio/*") }
                }
                irs.forEach { file -> Chip(file.nameWithoutExtension, f.convFile == file.path) { set { it.copy(convFile = file.path) } } }
                ParamSlider("Mix", f.convMix, 0.0..1.0, 0.05, { "${(it * 100).roundToInt()} %" }) { v -> set { it.copy(convMix = v) } }
                ParamSlider("Level", f.convGainDb, -12.0..12.0, 0.5, ::dbText) { v -> set { it.copy(convGainDb = v) } }
            }
        }
        item {
            FxCard("Bass", "Fuller low end, three characters", f.bassOn, { on -> set { it.copy(bassOn = on) } }) {
                ModeChips(listOf("natural" to "Natural", "pure" to "Pure bass", "sub" to "Subwoofer"), f.bassMode) { k -> set { it.copy(bassMode = k) } }
                ParamSlider("Frequency", f.bassFreq, 30.0..150.0, 5.0, { "${it.roundToInt()} Hz" }) { v -> set { it.copy(bassFreq = v) } }
                ParamSlider("Amount", f.bassGainDb, 0.0..12.0, 0.5, ::dbText) { v -> set { it.copy(bassGainDb = v) } }
            }
        }
        item {
            FxCard("Clarity", "Detail and sparkle", f.clarityOn, { on -> set { it.copy(clarityOn = on) } }) {
                ModeChips(listOf("natural" to "Natural", "ozone" to "OZone", "xhifi" to "X-HiFi"), f.clarityMode) { k -> set { it.copy(clarityMode = k) } }
                ParamSlider("Amount", f.clarity, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(clarity = v) } }
            }
        }
        item {
            FxCard("Spectrum extension", "Rebuilds high harmonics lost in compressed files", f.spectrumOn, { on -> set { it.copy(spectrumOn = on) } }) {
                ParamSlider("Amount", f.spectrum, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(spectrum = v) } }
            }
        }
        item {
            FxCard("Tube simulator", "Warm valve-amp harmonics", f.tubeOn, { on -> set { it.copy(tubeOn = on) } }) {
                ParamSlider("Drive", f.tube, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(tube = v) } }
            }
        }
        item {
            FxCard("AnalogX", "Analog stage colour", f.analogOn, { on -> set { it.copy(analogOn = on) } }) {
                ModeChips(listOf("A" to "Class A", "AB" to "Class AB", "B" to "Class B"), f.analogClass) { k -> set { it.copy(analogClass = k) } }
                ParamSlider("Amount", f.analog, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(analog = v) } }
            }
        }
        item {
            FxCard("Dynamic system", "Bass that grows when music is quiet", f.dynOn, { on -> set { it.copy(dynOn = on) } }) {
                ParamSlider("Bass", f.dynBass, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(dynBass = v) } }
                ParamSlider("Sensitivity", f.dynSense, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(dynSense = v) } }
            }
        }
        item {
            FxCard("Field surround", "Wider stereo, centre kept solid", f.fieldOn, { on -> set { it.copy(fieldOn = on) } }) {
                ParamSlider("Width", f.fieldWidth, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(fieldWidth = v) } }
                ParamSlider("Centre", f.fieldMid, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(fieldMid = v) } }
            }
        }
        item {
            FxCard("Differential surround", "A tiny delay on one side for depth", f.diffOn, { on -> set { it.copy(diffOn = on) } }) {
                ParamSlider("Delay", f.diffMs, 0.0..20.0, 0.5, { "%.1f ms".format(it) }) { v -> set { it.copy(diffMs = v) } }
            }
        }
        item {
            FxCard("Headphone surround", "Sound outside your head", f.vheOn, { on -> set { it.copy(vheOn = on) } }) {
                ParamSlider("Strength", f.vhe, 1.0..5.0, 1.0, { "${it.roundToInt()} / 5" }) { v -> set { it.copy(vhe = v) } }
            }
        }
        item {
            FxCard("Reverberation", "A room around the music", f.reverbOn, { on -> set { it.copy(reverbOn = on) } }) {
                ParamSlider("Room size", f.roomSize, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(roomSize = v) } }
                ParamSlider("Damping", f.damping, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(damping = v) } }
                ParamSlider("Wet", f.reverbWet, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(reverbWet = v) } }
                ParamSlider("Width", f.reverbWidth, 0.0..10.0, 0.5, ::oneOf10) { v -> set { it.copy(reverbWidth = v) } }
            }
        }
        item {
            FxCard("Auditory protection", "Crossfeed: easier on the ears over long sessions", f.cureOn, { on -> set { it.copy(cureOn = on) } }) {
                ModeChips(listOf("1" to "Light", "2" to "Medium", "3" to "Strong"), f.cure.roundToInt().toString()) { k -> set { it.copy(cure = k.toDouble()) } }
            }
        }
        item {
            FxCard("FET compressor", "Controls peaks for a denser sound", f.compOn, { on -> set { it.copy(compOn = on) } }) {
                ParamSlider("Threshold", f.compThresholdDb, -40.0..0.0, 0.5, ::dbText) { v -> set { it.copy(compThresholdDb = v) } }
                ParamSlider("Ratio", f.compRatio, 1.0..20.0, 0.5, { "%.1f : 1".format(it) }) { v -> set { it.copy(compRatio = v) } }
                ParamSlider("Attack", f.compAttackMs, 0.5..100.0, 0.5, { "%.1f ms".format(it) }) { v -> set { it.copy(compAttackMs = v) } }
                ParamSlider("Release", f.compReleaseMs, 10.0..1000.0, 10.0, { "${it.roundToInt()} ms" }) { v -> set { it.copy(compReleaseMs = v) } }
                ParamSlider("Knee", f.compKneeDb, 0.0..12.0, 0.5, { "%.1f dB".format(it) }) { v -> set { it.copy(compKneeDb = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatic make-up gain", style = body(13.sp, color = C.TextMid), modifier = Modifier.weight(1f))
                    Toggle(f.compAutoMakeup, "Automatic make-up gain") { on -> set { it.copy(compAutoMakeup = on) } }
                }
                if (!f.compAutoMakeup) ParamSlider("Make-up gain", f.compMakeupDb, 0.0..24.0, 0.5, ::dbText) { v -> set { it.copy(compMakeupDb = v) } }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().raised(RoundedCornerShape(20.dp), 6.dp).padding(14.dp)) {
                Text("Master", style = body(15.sp, FontWeight.Bold))
                ParamSlider("Output gain", f.masterGainDb, -12.0..12.0, 0.5, ::dbText) { v -> set { it.copy(masterGainDb = v) } }
                ParamSlider("Limiter ceiling", f.limiterCeilDb, -6.0..0.0, 0.1, { "%.1f dB".format(it) }) { v -> set { it.copy(limiterCeilDb = v) } }
            }
        }
        item { Chip("Turn every effect off", false) { AppState.updateSound { it.copy(fx = FxSettings()) } } }
        item { Text("Any effect on means USB Direct processes in 32-bit floating point instead of bit-perfect. Turn them all off to go back to bit-perfect.", style = body(12.sp, color = C.TextMid).copy(lineHeight = 18.sp)) }
    }
}

/**
 * The live visualizer, drawn from the sound being heard right now (also over USB Direct).
 * "wave": a soft filled wave rising from the bottom; "bars": rounded bars; "mirror": bars both ways.
 */
@Composable
fun Visualizer(style: String, color: Color, modifier: Modifier) {
    if (style == "off") return
    var bands by remember { mutableStateOf(FloatArray(Viz.BANDS)) }
    LaunchedEffect(Unit) {
        val smooth = FloatArray(Viz.BANDS)
        while (true) {
            withFrameNanos { }
            val now = Viz.now()
            for (i in smooth.indices) {
                val target = now?.get(i) ?: 0f
                smooth[i] = if (target > smooth[i]) smooth[i] + (target - smooth[i]) * 0.55f else smooth[i] * 0.90f + target * 0.10f
            }
            bands = smooth.copyOf()
        }
    }
    Canvas(modifier) {
        val n = bands.size
        when (style) {
            "bars", "mirror" -> {
                val gap = 3.dp.toPx(); val bw = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val h = bands[i] * size.height * (if (style == "mirror") 0.5f else 0.95f)
                    val top = if (style == "mirror") size.height / 2 - h else size.height - h
                    val hh = if (style == "mirror") h * 2 else h
                    drawRoundRect(Brush.verticalGradient(listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.15f)), startY = top, endY = top + hh), Offset(i * (bw + gap), top), Size(bw, hh.coerceAtLeast(2f)), CornerRadius(bw / 2))
                }
            }
            else -> {
                // A smooth filled wave through the band heights.
                val path = Path().apply {
                    moveTo(0f, size.height)
                    val step = size.width / (n - 1)
                    var px = 0f; var py = size.height - bands[0] * size.height * 0.9f
                    lineTo(px, py)
                    for (i in 1 until n) {
                        val x = i * step; val y = size.height - bands[i] * size.height * 0.9f
                        val cx = (px + x) / 2
                        cubicTo(cx, py, cx, y, x, y)
                        px = x; py = y
                    }
                    lineTo(size.width, size.height); close()
                }
                drawPath(path, Brush.verticalGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0.10f))))
            }
        }
    }
}

/** Choose which folders the library plays from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoldersScreen(nav: Nav) {
    val lib by AppState.library.collectAsState()
    val s by AppState.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var chosen by rememberSaveable { mutableStateOf(s.musicFolders) }
    Backdrop(Color(0xFF1B1F4A)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header("Music folders", { nav.back() }) {
                Chip("Save", false) {
                    AppState.update { it.copy(musicFolders = chosen) }
                    scope.launch { AppState.scan(); nav.back() }
                }
            }
            Text(
                if (chosen.isEmpty()) "Playing from every folder. Pick folders to play only from those." else "Playing only from ${chosen.size} folder${if (chosen.size == 1) "" else "s"}.",
                style = body(13.sp, color = C.TextMid), modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All folders", chosen.isEmpty()) { chosen = emptyList() }
            }
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lib.allFolders) { folder ->
                    val on = folder in chosen
                    Row(
                        Modifier.fillMaxWidth().raised(RoundedCornerShape(16.dp), 4.dp).clip(RoundedCornerShape(16.dp))
                            .clickable { chosen = if (on) chosen - folder else chosen + folder }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(I.Folder, if (on) C.Amber else C.TextMid, 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.substringAfterLast('/'), style = body(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(folder.removePrefix("/storage/emulated/0/"), style = body(11.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Toggle(on, folder) { chosen = if (it) chosen + folder else chosen - folder }
                    }
                }
            }
        }
    }
}
