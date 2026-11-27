package com.hassasin.music

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun EqScreen(nav: Nav, sheet: Boolean = false, section: String = "all") {
    val showEq = section != "bass"
    val showBass = section != "eq"
    val settings by AppState.settings.collectAsState()
    val st by Playback.state.collectAsState()
    val s = settings.sound
    var tab by remember { mutableStateOf(0) }
    val result = remember(s, st.usb) { Eq.filters(s, st.usb || !s.autoEqOnlyUsb) }
    val correction = remember(s, st.usb) { if (Eq.autoEqActive(s, st.usb || !s.autoEqOnlyUsb)) s.autoEq?.filters?.filter { it.on } else null }
    val preamp = remember(result) { Eq.autoPreampDb(result) }
    ScreenShell(sheet, Color(0xFF2B1A0C), C.Amber) {
        LazyColumn(Modifier.fillMaxSize().then(if (sheet) Modifier else Modifier.statusBarsPadding()), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (!sheet) item {
                Header("Equalizer", { nav.back() }) { Toggle(s.eqOn, "Equalizer") { on -> AppState.updateSound { it.copy(eqOn = on) } } }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (showEq) Segmented(listOf("Graphic · 10 bands", "Parametric"), tab) { tab = it }
                    Column(Modifier.fillMaxWidth().recessed(RoundedCornerShape(20.dp)).padding(12.dp)) {
                        Row {
                            Text("RESPONSE", style = mono(10.sp), modifier = Modifier.weight(1f))
                            Text("PRE-AMP ${"%.1f".format(preamp + 0.0).replace("-0.0", "0.0")} dB AUTO", style = mono(10.sp))
                        }
                        Spacer(Modifier.height(6.dp))
                        ResponseCurve(result, correction)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Legend("Result", C.Amber, false)
                            if (correction != null) Legend("${s.autoEq?.name?.substringBefore(" (")} correction", C.Violet, true)
                        }
                    }
                    if (!s.eqOn) Text("Sound effects are off. Turn them on with the switch at the top to hear these settings.", style = body(13.sp, color = C.TextMid))
                }
            }
            if (!showEq) {
            } else if (tab == 0) {
                item {
                    val all = Eq.PRESETS.keys.toList() + settings.userPresets.keys
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(all) { name ->
                            Chip(name, s.preset == name) {
                                val bands = Eq.PRESETS[name] ?: settings.userPresets[name] ?: return@Chip
                                AppState.updateSound { it.copy(bands = bands, preset = name, eqOn = true) }
                            }
                        }
                        item {
                            Chip("Save as preset", false) {
                                val n = (1..99).first { "My preset $it" !in settings.userPresets }
                                AppState.update { it.copy(userPresets = it.userPresets + ("My preset $n" to it.sound.bands), sound = it.sound.copy(preset = "My preset $n")) }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        s.bands.forEachIndexed { i, g ->
                            EqFader(g, Eq.BAND_LABELS[i], { v ->
                                AppState.updateSound { it.copy(bands = it.bands.toMutableList().also { b -> b[i] = v }, preset = "Custom", eqOn = true) }
                            }, Modifier.weight(1f))
                        }
                    }
                }
            } else {
                item { ParametricList(s) }
            }
            if (showBass) item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("BASS STYLE", style = eyebrow(), modifier = Modifier.padding(horizontal = 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Eq.BASS_STYLES) { b ->
                            Chip(b.name, s.bassStyle == b.name) { AppState.updateSound { it.copy(bassStyle = b.name, eqOn = it.eqOn || b.name != "Off") } }
                        }
                    }
                    val style = Eq.BASS_STYLES.firstOrNull { it.name == s.bassStyle }
                    if (style != null && style.name != "Off") Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(style.about, style = body(12.sp, color = C.TextMid))
                        Stepper("Amount", "+%.1f dB".format(s.bassAmount), { AppState.updateSound { it.copy(bassAmount = (it.bassAmount - 0.5).coerceAtLeast(0.5)) } }, { AppState.updateSound { it.copy(bassAmount = (it.bassAmount + 0.5).coerceAtMost(12.0)) } })
                    }
                }
            }
            if (showBass) item {
                Text("TONE", style = eyebrow(), modifier = Modifier.padding(start = 20.dp, top = 18.dp))
            }
            if (showBass) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Knob(((s.bassDb + 12) / 24).toFloat(), "Bass", "%+.1f dB".format(s.bassDb), { v -> AppState.updateSound { it.copy(bassDb = (v * 24 - 12.0).roundHalf(), eqOn = true) } }, { AppState.updateSound { it.copy(bassDb = 0.0) } }, Modifier.weight(1f))
                    Knob(((s.trebleDb + 12) / 24).toFloat(), "Treble", "%+.1f dB".format(s.trebleDb), { v -> AppState.updateSound { it.copy(trebleDb = (v * 24 - 12.0).roundHalf(), eqOn = true) } }, { AppState.updateSound { it.copy(trebleDb = 0.0) } }, Modifier.weight(1f))
                    Knob((s.width / 2).toFloat(), "Width", "${(s.width * 100).roundToInt()} %", { v -> AppState.updateSound { it.copy(width = (v * 2.0 * 20).roundToInt() / 20.0, eqOn = true) } }, { AppState.updateSound { it.copy(width = 1.0) } }, Modifier.weight(1f))
                }
            }
            if (showBass) item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingRow("Balance", if (s.balance == 0.0) "Centre" else if (s.balance < 0) "Left ${(-s.balance * 100).roundToInt()} %" else "Right ${(s.balance * 100).roundToInt()} %") {
                        RoundButton(I.Minus, "Balance left", { AppState.updateSound { it.copy(balance = (it.balance - 0.05).coerceAtLeast(-1.0).round2(), eqOn = true) } }, 40.dp)
                        Spacer(Modifier.width(8.dp))
                        RoundButton(I.Plus, "Balance right", { AppState.updateSound { it.copy(balance = (it.balance + 0.05).coerceAtMost(1.0).round2(), eqOn = true) } }, 40.dp)
                    }
                    SettingRow("Limiter", "Stops loud boosts from clipping") { Toggle(s.limiter, "Limiter") { on -> AppState.updateSound { it.copy(limiter = on) } } }
                    SettingRow("Reset everything", "Flat EQ, tone, width and balance") {
                        RoundButton(I.Reset, "Reset sound", { AppState.updateSound { SoundSettings(autoEq = it.autoEq, autoEqOn = it.autoEqOn, autoEqOnlyUsb = it.autoEqOnlyUsb) } }, 40.dp)
                    }
                    Text("Knobs: drag left or right, double-tap to reset. Faders: drag the metal cap, double-tap to reset. With any effect on, USB Direct still skips Android's mixer but processes in 32-bit, so it is no longer bit-perfect.", style = body(12.sp, color = C.TextMid).copy(lineHeight = 18.sp))
                }
            }
        }
    }
}

private fun Double.roundHalf() = (this * 2).roundToInt() / 2.0
private fun Double.round2() = (this * 100).roundToInt() / 100.0

@Composable
private fun Legend(text: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, 4.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f, if (dashed) StrokeCap.Butt else StrokeCap.Round, if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = body(11.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().raised().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = body(14.sp, FontWeight.Bold))
            if (subtitle.isNotBlank()) Text(subtitle, style = body(12.sp, color = C.TextMid))
        }
        trailing()
    }
}

@Composable
private fun ParametricList(s: SoundSettings) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.parametric.isEmpty()) Text("Add your own filters: a peak, a low shelf or a high shelf at any frequency. They run after the 10 bands.", style = body(13.sp, color = C.TextMid))
        s.parametric.forEachIndexed { i, f ->
            fun set(nf: EqFilter) = AppState.updateSound { it.copy(parametric = it.parametric.toMutableList().also { l -> l[i] = nf }, eqOn = true) }
            Column(Modifier.fillMaxWidth().raised().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("PK" to "Peak", "LSC" to "Low shelf", "HSC" to "High shelf").forEach { (k, label) -> Chip(label, f.type == k) { set(f.copy(type = k)) } }
                    Spacer(Modifier.weight(1f))
                    PlainIconButton(I.Trash, "Delete filter", { AppState.updateSound { it.copy(parametric = it.parametric.filterIndexed { j, _ -> j != i }) } }, C.TextMid, 40.dp, 18.dp)
                }
                Stepper("Frequency", if (f.fc >= 1000) "%.2f kHz".format(f.fc / 1000) else "${f.fc.roundToInt()} Hz", { set(f.copy(fc = (f.fc / 1.06).coerceAtLeast(20.0))) }, { set(f.copy(fc = (f.fc * 1.06).coerceAtMost(20000.0))) })
                Stepper("Gain", "%+.1f dB".format(f.gainDb), { set(f.copy(gainDb = (f.gainDb - 0.5).coerceAtLeast(-15.0))) }, { set(f.copy(gainDb = (f.gainDb + 0.5).coerceAtMost(15.0))) })
                Stepper("Width (Q)", "%.2f".format(f.q), { set(f.copy(q = (f.q / 1.15).coerceAtLeast(0.1))) }, { set(f.copy(q = (f.q * 1.15).coerceAtMost(12.0))) })
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).raised().clip(RoundedCornerShape(16.dp))
                .clickable { AppState.updateSound { it.copy(parametric = it.parametric + EqFilter("PK", 1000.0, 3.0, 1.0), eqOn = true) } },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Ico(I.Plus, C.Amber, 18.dp); Spacer(Modifier.width(8.dp)); Text("Add filter", style = body(14.sp, FontWeight.Bold, C.AmberText)) }
    }
}

@Composable
fun Stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = body(13.sp, color = C.TextMid), modifier = Modifier.weight(1f))
        RoundButton(I.Minus, "$label down", minus, 36.dp)
        Text(value, style = mono(12.sp, FontWeight.Bold, C.AmberText), modifier = Modifier.width(92.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RoundButton(I.Plus, "$label up", plus, 36.dp)
    }
}

@Composable
fun EarphonesScreen(nav: Nav, sheet: Boolean = false) {
    val settings by AppState.settings.collectAsState()
    val s = settings.sound
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val index by produceState<List<AutoEqEntry>?>(null) { value = AutoEq.index(context) }
    var results by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }
    LaunchedEffect(q, index) { delay(250); results = index?.let { AutoEq.search(it, q) } ?: emptyList() }
    val profile = s.autoEq
    val curves by produceState<AutoEq.Curves?>(null, profile?.path) { value = profile?.let { AutoEq.curves(context, it) } }
    ScreenShell(sheet, Color(0xFF0E2A33), C.Teal) {
        LazyColumn(Modifier.fillMaxSize().then(if (sheet) Modifier else Modifier.statusBarsPadding()), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (!sheet) item { Header("Earphones", { nav.back() }) }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))
                            .padding(16.dp),
                    ) {
                        Text("ACTIVE PROFILE", style = eyebrow(Color(0xFFA9E9F0)))
                        Spacer(Modifier.height(4.dp))
                        Text(profile?.name ?: "No correction", style = display(17.sp))
                        Text(profile?.let { "AutoEq correction · measured by ${it.source} · ${it.filters.size} filters" } ?: "Search your earphones below to correct them to a neutral target.", style = body(12.sp, color = Color(0xFFC3D3DA)))
                        if (profile != null) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Correction on", style = body(14.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                                Toggle(s.autoEqOn, "Correction") { on -> AppState.updateSound { it.copy(autoEqOn = on) } }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Only with the USB DAC", style = body(14.sp, FontWeight.Bold))
                                    Text("These earphones are on the DAC; off = always", style = body(12.sp, color = C.TextMid))
                                }
                                Toggle(s.autoEqOnlyUsb, "Only with the USB DAC") { on -> AppState.updateSound { it.copy(autoEqOnlyUsb = on) } }
                            }
                        }
                    }
                    if (profile != null) {
                        Column(Modifier.fillMaxWidth().recessed(RoundedCornerShape(20.dp)).padding(12.dp)) {
                            Text("FREQUENCY RESPONSE · 20 Hz – 20 kHz", style = mono(10.sp))
                            Spacer(Modifier.height(6.dp))
                            val c = curves
                            if (c != null) MeasuredChart(c) else ResponseCurve(profile.filters, null)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (c != null) { Legend("Measured", C.TextLow, false); Legend("Target", C.Violet, true) }
                                Legend(if (c != null) "With correction" else "Correction", C.Amber, false)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ico(I.Search, C.TextMid, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text(if (index == null) "Loading the headphone list…" else "Search ${index?.size ?: 0} headphone profiles", style = body(15.sp, color = C.TextMid))
                            BasicTextField(q, { q = it }, singleLine = true, textStyle = body(15.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (index?.isEmpty() == true) Text("Couldn't download the list. Check the internet connection.", style = body(13.sp, color = C.Limit))
                    if (status.isNotBlank()) Text(status, style = body(13.sp, color = C.TextMid))
                    if (profile != null && q.isBlank()) SettingRow("Remove correction", "Go back to no correction") {
                        RoundButton(I.Close, "Remove correction", { AppState.updateSound { it.copy(autoEq = null, autoEqOn = false) } }, 40.dp)
                    }
                }
            }
            itemsIndexed(results) { _, e ->
                val inUse = profile?.path == e.path
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                        .then(if (inUse) Modifier.amberSelected(RoundedCornerShape(16.dp)) else Modifier.raised())
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            status = "Downloading ${e.name}…"
                            scope.launch {
                                val p = AutoEq.fetch(e)
                                if (p == null) status = "Couldn't download that profile." else {
                                    AppState.updateSound { it.copy(autoEq = p, autoEqOn = true) }
                                    status = ""; q = ""
                                }
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Measured by ${e.source}${if (e.rig.isNotBlank()) " on ${e.rig}" else ""}", style = body(11.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (inUse) "In use" else "Use", style = body(12.sp, FontWeight.Bold, if (inUse) C.AmberText else Color(0xFFA9E9F0)))
                }
            }
        }
    }
}

@Composable
private fun MeasuredChart(c: AutoEq.Curves) {
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val all = c.raw + c.target + c.corrected
        val mid = (all.maxOrNull()!! + all.minOrNull()!!) / 2
        val range = ((all.maxOrNull()!! - all.minOrNull()!!) / 2 + 2).coerceAtLeast(6.0)
        fun x(f: Double) = (ln(f / 20.0) / ln(1000.0) * size.width).toFloat().coerceIn(0f, size.width)
        fun y(d: Double) = (size.height / 2 - (d - mid) / range * (size.height / 2 - 4)).toFloat()
        listOf(100.0, 1000.0, 10000.0).forEach { f -> drawLine(Color(0xFF151922), Offset(x(f), 0f), Offset(x(f), size.height), 1f) }
        fun path(v: DoubleArray) = Path().apply { v.indices.forEach { i -> if (i == 0) moveTo(x(c.freq[i]), y(v[i])) else lineTo(x(c.freq[i]), y(v[i])) } }
        drawPath(path(c.raw), Color(0xFF5F6680), style = Stroke(1.6f))
        drawPath(path(c.target), C.Violet, style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        val p = path(c.corrected)
        drawPath(p, C.Amber.copy(alpha = 0.35f), style = Stroke(9f, cap = StrokeCap.Round))
        drawPath(p, C.AmberLight, style = Stroke(2.6f, cap = StrokeCap.Round))
    }
}


private val SOUND_TABS = listOf("EQ", "Bass & tone", "Tuning", "Effects", "Earphones", "Output")

/**
 * Everything about the sound in one sheet, in the order people adjust it: the EQ, then bass and
 * tone, then the earphone correction, then where the sound goes. One switch turns all effects on/off.
 */
@Composable
fun SoundSheet(nav: Nav, startTab: Int) {
    val settings by AppState.settings.collectAsState()
    val st by Playback.state.collectAsState()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startTab) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sound", style = display(22.sp))
                Text(
                    when {
                        st.usb && st.untouched -> "Bit-perfect to ${st.dacName.ifBlank { "your DAC" }}"
                        st.usb -> "32-bit effects to ${st.dacName.ifBlank { "your DAC" }}"
                        else -> "Phone output"
                    },
                    style = body(12.sp, color = C.TextMid),
                )
            }
            if (tab < 4) Toggle(settings.sound.eqOn, "Sound effects") { on -> AppState.updateSound { it.copy(eqOn = on) } }
        }
        ClipWarning(settings.sound)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(SOUND_TABS) { i, label -> Chip(label, i == tab) { tab = i } }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (targetState > initialState) it / 8 else -it / 8 }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
            },
            modifier = Modifier.weight(1f),
            label = "sound tab",
        ) { t ->
            when (t) {
                0 -> EqScreen(nav, sheet = true, section = "eq")
                1 -> EqScreen(nav, sheet = true, section = "bass")
                2 -> TuningTab()
                3 -> EffectsTab()
                4 -> EarphonesScreen(nav, sheet = true)
                else -> OutputScreen(nav, sheet = true)
            }
        }
    }
}

/**
 * Red warning while the EQ or effects push the sound past full scale. Shows by how much, and
 * lowers the master gain by that amount (rounded up to half a dB) with one tap.
 */
@Composable
fun ClipWarning(s: SoundSettings) {
    var over by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) { while (true) { over = ClipMeter.now(); kotlinx.coroutines.delay(250) } }
    androidx.compose.animation.AnimatedVisibility(
        over > 0.0,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        val cut = kotlin.math.ceil(over * 2) / 2.0
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp)).background(C.Limit.copy(alpha = 0.16f))
                .border(1.dp, C.Limit.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "Clipping warning: lower the sound by %.1f dB".format(cut) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(C.Limit, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Clipping: %.1f dB over".format(over), style = body(14.sp, FontWeight.Bold, C.Limit))
                Text(
                    if (s.limiter) "The limiter is squashing it. Lower the EQ or effects by %.1f dB.".format(cut)
                    else "The sound is distorting. Lower the EQ or effects by %.1f dB.".format(cut),
                    style = body(12.sp, color = C.TextHi),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(C.Limit).clickable {
                    AppState.updateSound { it.copy(eqOn = true, fx = it.fx.copy(masterGainDb = ((it.fx.masterGainDb - cut) * 2).roundToInt() / 2.0)) }
                    ClipMeter.clear(); over = 0.0
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("Lower %.1f dB".format(cut), style = body(13.sp, FontWeight.Bold, Color.White)) }
        }
    }
}
