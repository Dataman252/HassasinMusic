package com.hassasin.music

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val ACTION_USB_PERMISSION = "com.hassasin.music.USB_PERMISSION"

@Composable
fun OutputScreen(nav: Nav, sheet: Boolean = false) {
    val st by Playback.state.collectAsState()
    val s by AppState.settings.collectAsState()
    val context = LocalContext.current
    val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { Playback.onDacMaybeChanged() } }
        val f = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(r, f)
        onDispose { context.unregisterReceiver(r) }
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    ScreenShell(sheet, Color(0xFF3A1E08), C.Amber) {
        LazyColumn(Modifier.fillMaxSize().then(if (sheet) Modifier else Modifier.statusBarsPadding()), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (!sheet) item {
                Header("Output", { nav.back() }) {
                    when {
                        st.usb && st.untouched -> StatusDot("Bit-perfect")
                        st.usb -> StatusDot("32-bit effects", C.Amber, C.AmberText)
                        else -> StatusDot("Phone", C.TextLow, C.TextMid)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutputChainCard()
                    Text("OUTPUT", style = eyebrow())
                    Outputs.shown().filter { !it.more }.forEach { m -> OutputChoice(m, s.output == m.key) { Playback.setOutput(m.key) } }
                    var more by remember { mutableStateOf(Outputs.of(s.output).more) }
                    Text(
                        if (more) "Fewer options" else "More options",
                        style = body(13.sp, FontWeight.Bold, C.AmberText),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { more = !more }.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                    if (more) Outputs.shown().filter { it.more }.forEach { m -> OutputChoice(m, s.output == m.key) { Playback.setOutput(m.key) } }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    VolumeDial(s.dacVolumeDb, s.dacMaxDb, enabled = st.usb) { Playback.setDacVolume(it) }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(I.Minus, "DAC volume down", { Playback.setDacVolume(s.dacVolumeDb - 0.5) }, 44.dp)
                        Text(if (st.usb) "0.5 dB steps · volume keys: 1 dB" else "DAC volume works while USB Direct plays", style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(horizontal = 12.dp))
                        RoundButton(I.Plus, "DAC volume up", { Playback.setDacVolume(s.dacVolumeDb + 0.5) }, 44.dp)
                    }
                    // The DAC card.
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).raised(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Ico(I.Plug, C.AmberText, 20.dp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (st.dacPresent) st.dacName.ifBlank { "USB DAC" } else "No USB DAC plugged in", style = body(15.sp, FontWeight.Bold))
                                Text(
                                    when {
                                        !st.dacPresent -> "Plug in your DAC; the sound moves to it by itself."
                                        !st.dacAllowed -> "Android needs your OK before the app can use it."
                                        st.usb -> st.usbPath
                                        else -> "Ready. Play a song to start USB Direct."
                                    },
                                    style = body(12.sp, color = C.TextMid), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (st.dacPresent && !st.dacAllowed) {
                            Box(
                                Modifier.fillMaxWidth().height(48.dp).amberOrb(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).clickable {
                                    UsbDacProbe.findDac(usb)?.let { d ->
                                        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
                                        usb.requestPermission(d, pi)
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) { Text("Allow USB access", style = body(15.sp, FontWeight.Bold, C.OnAccent)) }
                        }
                        if (st.usb) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatTile(khz(st.dacRate) + "k", "Rate", Modifier.weight(1f))
                            StatTile(st.dacBits.toString(), "Bits", Modifier.weight(1f))
                            StatTile("30ms", "Buffer", Modifier.weight(1f))
                            StatTile(st.gaps.toString(), "Dropouts", Modifier.weight(1f))
                        }
                    }
                    SettingRow("Volume limit", "Highest DAC volume allowed: %.0f dB".format(s.dacMaxDb)) {
                        RoundButton(I.Minus, "Lower the limit", { AppState.update { it.copy(dacMaxDb = (it.dacMaxDb - 1).coerceAtLeast(-60.0), dacVolumeDb = minOf(it.dacVolumeDb, it.dacMaxDb - 1)) }; Playback.setDacVolume(AppState.settings.value.dacVolumeDb) }, 40.dp)
                        Spacer(Modifier.width(8.dp))
                        RoundButton(I.Plus, "Raise the limit", { AppState.update { it.copy(dacMaxDb = (it.dacMaxDb + 1).coerceAtMost(0.0)) } }, 40.dp)
                    }
                    val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    SettingRow(
                        "Open automatically on plug-in",
                        if (micGranted) "Done. Tick “Always” the next time Android offers this app for the DAC."
                        else "DACs with a microphone need the microphone permission before Android lets you tick “Always”. The app never records.",
                    ) {
                        if (!micGranted) Text("Allow", style = body(14.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { micAsk.launch(Manifest.permission.RECORD_AUDIO) }.padding(8.dp))
                        else Ico(I.Check, C.Ok, 20.dp)
                    }
                }
            }
        }
    }
}

/** The DAC volume dial: lit ticks up to the volume, a red mark at the limit. Drag around it to turn. */
@Composable
private fun VolumeDial(db: Double, maxDb: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    val min = -74.0
    val frac = ((db - min) / (0 - min)).toFloat().coerceIn(0f, 1f)
    val cap = ((maxDb - min) / (0 - min)).toFloat()
    val measurer = rememberTextMeasurer()
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier.size(272.dp)
            .pointerInput(Unit) {
                fun valueAt(p: Offset): Double {
                    var a = Math.toDegrees(atan2((p.y - size.height / 2).toDouble(), (p.x - size.width / 2).toDouble()))
                    a = (a - 135 + 360) % 360
                    if (a > 270) a = if (a > 315) 0.0 else 270.0
                    return min + a / 270.0 * (0 - min)
                }
                detectDragGestures { change2, _ -> change(valueAt(change2.position)) }
            }
            .pointerInput(Unit) { detectTapGestures {} }
            .semantics { contentDescription = "DAC volume ${"%.1f".format(db)} dB, limit ${"%.0f".format(maxDb)} dB" },
    ) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1D1F2C), C.Well), center = c - Offset(0f, r * 0.2f), radius = r), r, c)
        drawCircle(C.Amber.copy(alpha = if (enabled) 0.20f else 0.06f), r * 0.75f, c)
        val n = 61
        for (t in 0 until n) {
            val f = t / (n - 1f)
            val a = Math.toRadians(135.0 + f * 270.0)
            val r1 = r * 0.74f; val r2 = if (t % 5 == 0) r * 0.9f else r * 0.84f
            val p1 = Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1)
            val p2 = Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2)
            val lit = f <= frac && enabled
            if (lit) drawLine(C.Amber.copy(alpha = 0.4f), p1, p2, 9f, StrokeCap.Round)
            drawLine(if (lit) C.AmberLight else Color(0xFF2C2F40), p1, p2, if (lit) 4f else 3f, StrokeCap.Round)
        }
        val ca = Math.toRadians(135.0 + cap * 270.0)
        drawLine(C.Limit, Offset(c.x + cos(ca).toFloat() * r * 0.72f, c.y + sin(ca).toFloat() * r * 0.72f), Offset(c.x + cos(ca).toFloat() * r * 0.94f, c.y + sin(ca).toFloat() * r * 0.94f), 5f, StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.6f), r * 0.64f, c + Offset(0f, 6f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF3B3E52), Color(0xFF191B27), Color(0xFF0B0C13)), center = c - Offset(r * 0.1f, r * 0.25f), radius = r * 0.8f), r * 0.62f, c)
        val da = Math.toRadians(135.0 + frac * 270.0)
        val dot = Offset(c.x + cos(da).toFloat() * r * 0.52f, c.y + sin(da).toFloat() * r * 0.52f)
        if (enabled) drawCircle(C.Amber.copy(alpha = 0.45f), 13f, dot)
        drawCircle(if (enabled) Color(0xFFFFE0A8) else C.TextLow, 7f, dot)
        fun text(s: String, style: TextStyle, y: Float) {
            val l = measurer.measure(s, style)
            drawText(l, topLeft = Offset(c.x - l.size.width / 2, y - l.size.height / 2))
        }
        text("DAC VOLUME", TextStyle(fontFamily = F.Body, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.TextMid, letterSpacing = 1.5.sp), c.y - r * 0.2f)
        text("%.1f".format(db).replace("-", "−"), TextStyle(fontFamily = F.Mono, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF7F2E8)), c.y + r * 0.08f)
        text("dB · limit %.0f".format(maxDb).replace("-", "−"), TextStyle(fontFamily = F.Mono, fontSize = 12.sp, color = C.AmberText), c.y + r * 0.3f)
    }
}

@Composable
fun FixArtScreen(nav: Nav, albumKey: String?) {
    val lib by AppState.library.collectAsState()
    if (albumKey == null) return FixArtList(nav, lib)
    val album = remember(lib, albumKey) { AppState.albumByKey(albumKey) } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchNo by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    val candidates by produceState<List<ArtCandidate>?>(null, searchNo) {
        value = null
        value = if (AppState.online()) ArtRepo.candidates(album, query.takeIf { it.isNotBlank() }) else emptyList()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { if (ArtRepo.chooseUri(context, album, uri)) nav.back() else status = "Couldn't read that picture." }
    }
    val choice = ArtRepo.choice(album.key)
    Backdrop(Color(0xFF2A1F5C)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { Header("Fix album art", { nav.back() }) }
            item {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtBox(album, 120.dp, 16.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album.title, style = body(16.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(album.artist, style = body(13.sp, color = C.TextMid), maxLines = 1)
                        val inf = ArtRepo.infoOf(album)
                        Text(inf?.let { "Now: from ${it.source.lowercase()} · ${it.width}×${it.height}" } ?: "Now: no picture", style = mono(11.sp))
                    }
                }
            }
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Pick from gallery", false) { gallery.launch("image/*") }
                        Chip("Keep current", choice?.source == "Keep") { scope.launch { ArtRepo.keep(album); nav.back() } }
                    }
                    if (choice != null && choice.source != "Keep") Chip("Back to the file's own picture", false) { scope.launch { ArtRepo.reset(album) } }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search (song, album, artist)", style = body(14.sp, color = C.TextMid))
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = body(14.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                        }
                        Text("Search", style = body(14.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { searchNo++ }.padding(8.dp))
                    }
                    if (status.isNotBlank()) Text(status, style = body(13.sp, color = C.Limit))
                    Text(
                        when {
                            !AppState.online() -> "Offline: connect to the internet to search for covers."
                            candidates == null -> "Looking for matching albums…"
                            candidates!!.isEmpty() -> "No matches. Try a shorter search."
                            else -> "Matches from Apple and MusicBrainz, best first. Tap a cover to use it."
                        },
                        style = body(13.sp, color = C.TextMid),
                    )
                }
            }
            items(candidates ?: emptyList(), key = { it.releaseId }) { c ->
                val thumb by produceState<Bitmap?>(null, c.thumbUrl) { value = ArtRepo.thumb(c.thumbUrl) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).raised().clip(RoundedCornerShape(16.dp))
                        .clickable {
                            status = "Downloading…"
                            scope.launch { if (ArtRepo.chooseCandidate(album, c)) nav.back() else status = "That release has no cover picture." }
                        }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF151922)), contentAlignment = Alignment.Center) {
                        val b = thumb
                        if (b != null) Image(b.asImageBitmap(), c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text("No cover", style = body(10.sp, color = C.TextLow))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.artist, style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(c.date, if (c.trackCount > 0) "${c.trackCount} tracks" else "").filter { it.isNotBlank() }.joinToString(" · "), style = mono(10.sp))
                    }
                    Text("${(c.score * 100).roundToInt()}%", style = mono(13.sp, FontWeight.Bold, if (c.score >= 0.85) C.OkText else C.AmberText))
                }
            }
        }
    }
}

@Composable
private fun FixArtList(nav: Nav, lib: LibraryData) {
    val status by AppState.artStatus.collectAsState()
    val version = ArtRepo.version
    val problems by produceState<List<ArtProblem>?>(null, lib.albums, version) { value = ArtRepo.problems(AppState.artTargets()) }
    Backdrop(Color(0xFF2A1F5C)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { Header("Fix art", { nav.back() }) }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when (val p = problems) { null -> "Checking ${lib.albums.size} albums…"; else -> if (p.isEmpty()) "Every album has a good picture." else "${p.size} album${if (p.size == 1) "" else "s"} to look at" },
                        style = display(15.sp, FontWeight.Medium),
                    )
                    Text("Checks: no picture, a tiny picture, or the same picture on albums with different names (often a wrong cover). Fixed pictures are kept in the app; your music files are never changed.", style = body(12.sp, color = C.TextMid).copy(lineHeight = 18.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Fix all automatically", false) { AppState.findMissingArt(problems?.map { it.album.key }?.toSet() ?: emptySet()) }
                        Chip("Missing only", false) { AppState.findMissingArt() }
                    }
                    Text("Automatic fixing only uses sure matches (85% or more). Open an album to pick from all matches.", style = body(12.sp, color = C.TextMid))
                    if (status.isNotBlank()) Text(status, style = body(12.sp, color = C.AmberText))
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(problems ?: emptyList(), key = { it.album.key }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Route.FixArt(p.album.key)) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtBox(p.album, 56.dp, 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.album.title, style = body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.reason, style = body(12.sp, color = C.AmberText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Ico(I.Back, C.TextLow, 18.dp, Modifier.semantics { contentDescription = "Open" })
                }
            }
        }
    }
}

/** One settings page and what it holds, for the section list and the search. */
private data class SettingsSection(val key: String, val title: String, val about: String, val icon: String, val words: List<String>)

private val SECTIONS = listOf(
    SettingsSection("audio", "Audiophile", "Purity check, pure mode, resampler filter, dither, test tones", I.HiRes, listOf("Audiophile", "Bit-perfect", "Purity", "Pure mode", "Best sound setup", "Setup", "Resampler filter", "Minimum phase", "Dither", "Bit depth", "Headroom", "Limiter", "Test tones", "Left and right", "Polarity", "Frequency sweep", "Noise floor")),
    SettingsSection("look", "Look", "Theme, visualizer, seek bar, cover", I.Image, listOf("Look", "Glow", "Soft", "Flow", "Theme", "Visualizer", "Seek bar", "Cover on song change", "Cover animation", "Cover sharpness")),
    SettingsSection("playback", "Playback", "Crossfade, ReplayGain, headphones", I.Play, listOf("Crossfade", "ReplayGain", "Loudness", "Resume with headphones", "Shortest song", "Gapless")),
    SettingsSection("sound", "Sound & output", "Output, DVC, resampler, EQ", I.Eq, listOf("Output", "USB DAC", "Bit-perfect", "Direct Volume Control", "DVC", "Volume", "App resampler", "Resampling", "Equalizer", "Effects", "Output chain", "Battery")),
    SettingsSection("lyrics", "Lyrics & covers", "Online look-ups, Hinglish, album pictures", I.Lyrics, listOf("Lyrics online", "Hinglish", "Album art online", "Covers from Apple", "Album pictures", "Fix covers", "Wi-Fi only")),
    SettingsSection("library", "Library", "Folders, playlists, reading the library", I.Library, listOf("Music folders", "Hidden folders", "Read the library again", "Refresh", "Playlists")),
    SettingsSection("lock", "Lock screen & notification", "Lock screen player, notification card, widget", I.Clock, listOf("Lock screen player", "Notification player", "Second line", "Live lyrics", "Notification buttons", "Widget")),
    SettingsSection("about", "About", "Updates, what's new, help improve, credits", I.Star, listOf("Updates", "What's new", "Changelog", "Help improve", "Diagnostics", "Report", "Share the app", "Support the developer", "Credits")),
)

@Composable
fun SettingsScreen(nav: Nav) {
    var query by remember { mutableStateOf("") }
    Backdrop(Color(0xFF1B1F4A)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Header("Settings", { nav.back() }) }
            item {
                Row(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(I.Search, C.TextMid, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search settings", style = body(14.sp, color = C.TextMid))
                        androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true, textStyle = body(14.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                    }
                    if (query.isNotEmpty()) Text("Clear", style = body(13.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { query = "" }.padding(6.dp))
                }
            }
            if (query.isBlank()) {
                SECTIONS.forEach { sec ->
                    item {
                        // Audiophile stands out: the place for the best sound, first and lit in the accent.
                        val star = sec.key == "audio"
                        val shape = RoundedCornerShape(if (star) 20.dp else 16.dp)
                        Row(
                            Modifier.fillMaxWidth().then(if (star) Modifier.amberSelected(shape) else Modifier.raised()).clip(shape).clickable { nav.go(Route.SettingsPage(sec.key)) }.padding(if (star) 18.dp else 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(if (star) 46.dp else 38.dp).clip(RoundedCornerShape(12.dp)).background(if (star) C.Amber else C.Amber.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                Ico(sec.icon, if (star) C.OnAccent else C.AmberText, if (star) 24.dp else 20.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                if (star) Text("FOR THE BEST SOUND", style = mono(10.sp, FontWeight.Bold, C.AmberText))
                                Text(sec.title, style = if (star) display(19.sp) else body(15.sp, FontWeight.Bold))
                                Text(sec.about, style = body(12.sp, color = if (star) C.TextHi.copy(alpha = 0.8f) else C.TextMid))
                            }
                            Text("›", style = body(22.sp, color = if (star) C.AmberText else C.TextLow))
                        }
                    }
                }
            } else {
                val q = query.trim().lowercase()
                val hits = SECTIONS.flatMap { sec -> sec.words.filter { it.lowercase().contains(q) }.map { it to sec } }
                if (hits.isEmpty()) item { Text("Nothing matches “$query”.", style = body(13.sp, color = C.TextMid)) }
                hits.forEach { (word, sec) ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().raised().clip(RoundedCornerShape(14.dp)).clickable { nav.go(Route.SettingsPage(sec.key)) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(word, style = body(14.sp, FontWeight.Bold))
                                Text("In ${sec.title}", style = body(12.sp, color = C.TextMid))
                            }
                            Text("›", style = body(20.sp, color = C.TextLow))
                        }
                    }
                }
            }
            item { Text("Hassasin Music ${BuildConfig.VERSION_NAME}", style = mono(11.sp, color = C.TextLow), modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
        }
    }
}

@Composable
fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, about: String? = null, pick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = body(14.sp, FontWeight.Bold))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, label) -> Chip(label, selected == k) { pick(k) } }
        }
        about?.let { Text(it, style = body(12.sp, color = C.TextMid)) }
    }
}

/** One section of Settings. */
@Composable
fun SettingsPageScreen(nav: Nav, key: String) {
    val s by AppState.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    val sec = SECTIONS.firstOrNull { it.key == key } ?: SECTIONS.first()
    Backdrop(Color(0xFF1B1F4A)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Header(sec.title, { nav.back() }) }
            when (key) {
                "look" -> {
                    item {
                        Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Look", style = body(14.sp, FontWeight.Bold))
                            Segmented(listOf("Glow", "Soft", "Flow"), when (s.theme) { "soft" -> 1; "flow" -> 2; else -> 0 }) { i -> AppState.update { it.copy(theme = listOf("glow", "soft", "flow")[i]) } }
                            Text("Glow takes its light from each cover. Soft is matte and calm, with an indigo accent. Flow fills the screen with the cover and tints everything in its colour.", style = body(12.sp, color = C.TextMid))
                        }
                    }
                    item { ChoiceRow("Visualizer", listOf("off" to "Off", "wave" to "Wave", "bars" to "Bars", "mirror" to "Mirror"), s.vizStyle) { k -> AppState.update { it.copy(vizStyle = k) } } }
                    item { ChoiceRow("Seek bar", SeekStyles.ALL, s.seekStyle, "Sits behind the play controls; tap or drag anywhere on it to move through the song.") { k -> AppState.update { it.copy(seekStyle = k) } } }
                    item { ChoiceRow("Cover on song change", listOf("slide" to "Slide", "fade" to "Fade", "zoom" to "Zoom", "flip" to "Page turn", "mask" to "Circle reveal", "wipe" to "Straight reveal"), s.coverAnim) { k -> AppState.update { it.copy(coverAnim = k) } } }
                    item { Column(Modifier.fillMaxWidth().raised().padding(14.dp)) { CoverSharpness(s.artSharpness) } }
                }
                "playback" -> {
                    item {
                        ChoiceRow("Crossfade between songs", listOf(0, 2, 4, 6, 8, 12).map { it.toString() to (if (it == 0) "Off" else "$it s") }, s.crossfadeSec.toString(),
                            "The end of a song fades out and the next fades in. Songs from one album play straight on. Not applied in bit-perfect USB mode.") { k -> AppState.update { it.copy(crossfadeSec = k.toInt()) } }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("ReplayGain", style = body(14.sp, FontWeight.Bold))
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("off" to "Off", "track" to "Per song", "album" to "Per album").forEach { (k, label) -> Chip(label, s.replayGain == k) { AppState.update { it.copy(replayGain = k) } } }
                            }
                            if (s.replayGain != "off") Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Pre-amp ${"%+.0f".format(s.replayGainPreampDb)} dB", style = body(13.sp), modifier = Modifier.weight(1f))
                                RoundButton(I.Minus, "Pre-amp down", { AppState.update { it.copy(replayGainPreampDb = (it.replayGainPreampDb - 1).coerceAtLeast(-15.0)) } }, 36.dp)
                                Spacer(Modifier.width(8.dp))
                                RoundButton(I.Plus, "Pre-amp up", { AppState.update { it.copy(replayGainPreampDb = (it.replayGainPreampDb + 1).coerceAtMost(6.0)) } }, 36.dp)
                            }
                            Text("Plays songs (or whole albums) at the same loudness, from the ReplayGain values in their tags. Only turns down, never past a song's peak.", style = body(12.sp, color = C.TextMid))
                        }
                    }
                    item { SettingRow("Resume with headphones", "Start again when headphones or Bluetooth reconnect within 30 minutes of being unplugged") { Toggle(s.autoResume, "Resume with headphones") { v -> AppState.update { it.copy(autoResume = v) } } } }
                    item {
                        SettingRow("Shortest song", "Leave out files under ${s.minDurationSec} s (voice notes, ringtones)") {
                            RoundButton(I.Minus, "Shorter", { AppState.update { it.copy(minDurationSec = (it.minDurationSec - 15).coerceAtLeast(0)) } }, 40.dp)
                            Spacer(Modifier.width(8.dp))
                            RoundButton(I.Plus, "Longer", { AppState.update { it.copy(minDurationSec = (it.minDurationSec + 15).coerceAtMost(300)) } }, 40.dp)
                        }
                    }
                    item { Text("Speed, pitch and the sleep timer are in the player's ⋯ menu.", style = body(12.sp, color = C.TextLow), modifier = Modifier.padding(horizontal = 4.dp)) }
                }
                "sound" -> {
                    item { SettingRow("Best sound setup", "Answer one question; the app sets itself up for the cleanest sound on this phone") { RoundButton(I.Plug, "Best sound setup", { nav.go(Route.QualitySetup) }, 40.dp) } }
                    item { SettingRow("Audiophile", "Every quality control in one place, with a purity check and test tones") { RoundButton(I.Eq, "Audiophile", { nav.go(Route.SettingsPage("audio")) }, 40.dp) } }
                    item { SettingRow("Output", "How the sound leaves the phone, and the output chain") { RoundButton(I.Plug, "Output", { nav.go(Route.Sound(5)) }, 40.dp) } }
                    item { SettingRow("Equalizer & effects", "EQ, bass and tone, tuning, effects, earphone correction") { RoundButton(I.Eq, "Equalizer and effects", { nav.go(Route.Sound(0)) }, 40.dp) } }
                    item {
                        SettingRow(
                            "Direct Volume Control (DVC)",
                            if (s.dvc) "The app sets the volume in 64-bit; Android's media volume stays at maximum. Volume keys: ${Dvc.label()}"
                            else "Let the app control the volume in 64-bit instead of Android (cleaner, more EQ headroom)",
                        ) { Toggle(s.dvc, "Direct Volume Control") { v -> Dvc.enable(ctx, v) } }
                    }
                    item {
                        SettingRow("App resampler", if (s.appResampler) "Songs are converted to the phone's ${AudioPathInfo.mixerRate(ctx) / 1000} kHz in the app (48-tap sinc, 64-bit), so Android does not resample" else "Use the app's high-quality converter instead of Android's when a song's rate differs from the phone's") {
                            Toggle(s.appResampler, "App resampler") { v -> AppState.update { it.copy(appResampler = v) }; Playback.rebuildAudio("app resampler") }
                        }
                    }
                }
                "audio" -> audiophileItems(nav, s, ctx)
                "lyrics" -> {
                    item { SettingRow("Find lyrics online", "From LRCLIB, when the file has none") { Toggle(s.autoLyrics, "Find lyrics online") { on -> AppState.update { it.copy(autoLyrics = on) } } } }
                    item { SettingRow("Hinglish", "Show lyrics in Indian scripts and Urdu in Roman letters") { Toggle(s.lyricsHinglish, "Hinglish") { on -> AppState.update { it.copy(lyricsHinglish = on) } } } }
                    item { SettingRow("Find album art online", "Missing pictures from MusicBrainz, sure matches only") { Toggle(s.autoArt, "Find album art online") { on -> AppState.update { it.copy(autoArt = on) }; if (on) AppState.findMissingArt() } } }
                    item { SettingRow("Covers from Apple's catalogue", "Best for Indian releases. Apple's terms allow its artwork for promoting its store; fine for personal use") { Toggle(s.artFromApple, "Covers from Apple's catalogue") { on -> AppState.update { it.copy(artFromApple = on) } } } }
                    item { SettingRow("Album pictures", "See and fix covers that are missing or wrong") { RoundButton(I.Image, "Album pictures", { nav.go(Route.FixArt()) }, 40.dp) } }
                    item { SettingRow("Online on Wi-Fi only", "No art or lyrics look-ups on mobile data") { Toggle(s.onlineOnWifiOnly, "Online on Wi-Fi only") { on -> AppState.update { it.copy(onlineOnWifiOnly = on) } } } }
                }
                "library" -> {
                    item { SettingRow("Music folders", if (s.musicFolders.isEmpty()) "Playing from every folder" else "Playing from ${s.musicFolders.size} chosen folder${if (s.musicFolders.size == 1) "" else "s"}") { RoundButton(I.Folder, "Music folders", { nav.go(Route.Folders) }, 40.dp) } }
                    item { SettingRow("Hidden folders", s.hiddenFolders.joinToString(", ")) {} }
                    item {
                        SettingRow("Read the library again", if (scanning) "Reading…" else "After adding or deleting music") {
                            RoundButton(I.Reset, "Read the library again", { scanning = true; scope.launch { AppState.rescan(ctx); scanning = false } }, 40.dp)
                        }
                    }
                    item { SettingRow("Playlists", "Make, import and export playlists") { RoundButton(I.Playlist, "Playlists", { nav.go(Route.Playlists) }, 40.dp) } }
                }
                "lock" -> {
                    item { LockScreenRow(s.lockScreen) }
                    item { ChoiceRow("Notification: second line", listOf("quality" to "Artist · quality", "lyrics" to "Live lyrics", "artist" to "Artist only"), s.notifLine) { k -> AppState.update { it.copy(notifLine = k) } } }
                    item { ChoiceRow("Notification: buttons", NotifButtons.CHOICES, s.notifButtons, "Your phone draws this card itself; the app fills it in. Songs without a picture get a cover made for them.") { k -> AppState.update { it.copy(notifButtons = k) } } }
                    item { Text("Widget: long-press your home screen, choose Widgets and find Hassasin Music.", style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(horizontal = 4.dp)) }
                }
                else -> {
                    item { UpdateRow() }
                    item { HelpImproveRow() }
                    item { SettingRow("Take the tour", "A 30-second walk around the player") { RoundButton(I.Play, "Take the tour", { nav.tab(Route.Player); Tour.start() }, 40.dp) } }
                    item { SettingRow("Best sound setup", "Set the app up for your headphones, DAC or speaker") { RoundButton(I.Plug, "Best sound setup", { nav.go(Route.QualitySetup) }, 40.dp) } }
                    item { SettingRow("What's new", "Changes in every version") { RoundButton(I.Star, "What's new", { nav.go(Route.Credits) }, 40.dp) } }
                    item { SettingRow("Share the app", "Send friends the download link") { RoundButton(I.Share, "Share the app", { ShareApp.share(ctx) }, 40.dp) } }
                    item { SettingRow("Support the developer", "A coffee by UPI keeps new versions coming") { RoundButton(I.Star, "Support the developer", { Donate.showing = true }, 40.dp) } }
                    item { SettingRow("Credits", "Hassasin Music ${BuildConfig.VERSION_NAME} · who and what made it") { RoundButton(I.Star, "Credits", { nav.go(Route.Credits) }, 40.dp) } }
                }
            }
        }
    }
}


/** Cover sharpness slider with a live preview of the playing song's cover. */
@Composable
private fun CoverSharpness(saved: Int) {
    var v by remember(saved) { mutableStateOf(saved.toFloat()) }
    val album = albumOf(Playback.state.collectAsState().value.current)
    val raw by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, album?.key) { value = album?.let { ArtRepo.bitmap(it, 360) } }
    val shown by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, raw, v.toInt() / 10) {
        val b = raw
        value = if (b == null || v.toInt() == 0) b else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Sharpen.process(b, v / 100f) }
    }
    Text("Cover sharpness", style = body(14.sp, FontWeight.Bold))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            shown?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Ico(I.Note, C.TextLow, 28.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(when { v.toInt() == 0 -> "As is"; v < 0 -> "Softer (${-v.toInt()}%)"; else -> "Sharper (+${v.toInt()}%)" }, style = body(13.sp, FontWeight.SemiBold, if (v.toInt() == 0) C.TextMid else C.AmberText))
            androidx.compose.material3.Slider(
                value = v, onValueChange = { v = (it / 10f).roundToInt() * 10f }, valueRange = -100f..200f,
                onValueChangeFinished = { AppState.update { it.copy(artSharpness = v.toInt()) } },
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.Amber, activeTrackColor = C.Amber, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
                modifier = Modifier.semantics { contentDescription = "Cover sharpness" },
            )
            Row {
                Text("Softer", style = body(11.sp, color = C.TextLow), modifier = Modifier.weight(1f))
                Text("Reset", style = body(11.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { v = 0f; AppState.update { it.copy(artSharpness = 0) } }.padding(horizontal = 6.dp))
                Text("Sharper", style = body(11.sp, color = C.TextLow), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
    Text("Brings out detail in small or soft covers on the player and lock screen. The pictures themselves are not changed.", style = body(12.sp, color = C.TextMid))
}

/** Lock-screen player switch; asks for Android's "full-screen" permission when it is missing. */
@Composable
private fun LockScreenRow(on: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowed by remember { mutableStateOf(LockScreen.canShow(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { allowed = LockScreen.canShow(context); onPauseOrDispose { } }
    SettingRow(
        "Lock screen player",
        when { !on -> "Off: Android's own lock-screen controls only"; allowed -> "The player, in your look, over the lock screen while music plays"; else -> "Needs Android's \"full-screen notifications\" permission: tap Allow" },
    ) {
        if (on && !allowed) {
            Chip("Allow", false) { LockScreen.askPermission(context) }
            Spacer(Modifier.width(8.dp))
        }
        Toggle(on, "Lock screen player") { v -> AppState.update { it.copy(lockScreen = v) } }
    }
}


/** One output on the Output tab: a radio card with what it does and what it keeps. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OutputChoice(m: OutputMode, on: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth()
            .then(if (on) Modifier.amberSelected(shape) else Modifier.raised(shape, 6.dp))
            .clip(shape).clickable(onClick = onPick)
            .padding(14.dp)
            .semantics { contentDescription = "${m.title}${if (on) ", selected" else ""}" },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Amber else Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(C.OnAccent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.title, style = body(15.sp, FontWeight.Bold, if (on) C.AmberText else C.TextHi))
            Text(m.line, style = body(12.sp, color = C.TextMid).copy(lineHeight = 17.sp))
            val ctx = LocalContext.current
            val st by Playback.state.collectAsState()
            val here = remember(m.key, st.usb, st.dacPresent, st.dacAllowed) { Outputs.onThisPhone(ctx, m.key, st) }
            if (here.isNotBlank()) Text(here, style = body(12.sp, FontWeight.SemiBold, if (on) C.AmberText else C.TextHi.copy(alpha = 0.8f)).copy(lineHeight = 17.sp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (m.tags + if (!m.effects) listOf("No app effects") else emptyList()).forEach { tag ->
                    Text(
                        tag, style = mono(10.sp, FontWeight.Bold, if (tag == "No app effects") C.TextLow else C.AmberText),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (tag == "No app effects") Color.White.copy(alpha = 0.06f) else C.Amber.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
