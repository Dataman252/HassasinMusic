package com.hassasin.music

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

@Composable
fun RoundButton(icon: String, desc: String, onClick: () -> Unit, size: Dp = 44.dp, tint: Color = C.TextHi, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .raised(CircleShape, 6.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, size * 0.45f) }
}

@Composable
fun PlainIconButton(icon: String, desc: String, onClick: () -> Unit, tint: Color = C.TextMid, size: Dp = 48.dp, iconSize: Dp = 22.dp, filled: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, iconSize, filled = filled) }
}

@Composable
fun Header(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) { RoundButton(I.Back, "Back", onBack); Spacer(Modifier.width(12.dp)) }
        Text(title, style = display(22.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
fun Toggle(on: Boolean, desc: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(54.dp, 32.dp)
            .then(
                if (on) Modifier.shadow(10.dp, CircleShape, ambientColor = C.Amber, spotColor = C.Amber)
                    .background(Brush.verticalGradient(listOf(C.AmberDeep, C.Amber)), CircleShape)
                else Modifier.recessed(CircleShape),
            )
            .clip(CircleShape)
            .clickable { onChange(!on) }
            .semantics { contentDescription = "$desc ${if (on) "on" else "off"}" },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.padding(3.dp).size(26.dp).shadow(4.dp, CircleShape)
                .background(Brush.radialGradient(listOf(Color.White, Color(0xFFC9CCD8))), CircleShape),
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .height(40.dp)
            .then(if (selected) Modifier.amberSelected(shape) else Modifier.raised(shape, 4.dp))
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = body(13.sp, FontWeight.Bold, if (selected) C.AmberText else Color(0xFFD6D8E6))) }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().recessed(RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, o ->
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.weight(1f).height(44.dp)
                    .then(if (i == selected) Modifier.amberSelected(shape) else Modifier)
                    .clip(shape).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, style = body(13.sp, FontWeight.Bold, if (i == selected) C.AmberLight else C.TextMid)) }
        }
    }
}

/** Album art, or a quiet placeholder while it loads / when there is none. */
@Composable
fun ArtBox(album: Album?, size: Dp, corner: Dp = 14.dp, modifier: Modifier = Modifier, hiRes: Size? = null) {
    val shape = RoundedCornerShape(corner)
    val version = ArtRepo.version
    val bmp by produceState<Bitmap?>(album?.let { ArtRepo.cachedBitmap(it) }, album?.key, version) {
        value = album?.let { ArtRepo.bitmap(it, if (size > 200.dp) 1000 else 400) }
    }
    Box(
        modifier.size(size).shadow(12.dp, shape).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF101226)))),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = album?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Ico(I.Note, C.TextLow, size * 0.32f)
        // A sheen from the top-left corner gives the cover its glassy surface.
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), end = Offset(300f, 300f))))
    }
    @Suppress("UNUSED_VARIABLE") val unused = hiRes
}

/** The picture source for a song: its album, or the song itself when it has no album tags. */
fun albumOf(t: Track?): Album? = t?.let { tr -> ArtRepo.artAlbumFor(tr) ?: AppState.library.value.albums.firstOrNull { it.key == tr.albumKey } }

/** Waveform seek bar: bars already played glow amber. Tap or drag to seek. */
@Composable
fun WaveSeek(peaks: FloatArray?, fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = drag ?: fraction
    Canvas(
        modifier.fillMaxWidth().height(44.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .semantics { contentDescription = "Seek bar" },
    ) {
        val n = 56
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val p = peaks?.let { it[(i * it.size / n).coerceIn(0, it.lastIndex)] } ?: 0.25f
            val h = (6.dp.toPx() + p * (size.height - 6.dp.toPx())).coerceAtMost(size.height)
            val x = i * (w + gap)
            val played = (i + 0.5f) / n <= shown
            if (played) drawRoundRect(C.Amber.copy(alpha = 0.35f), Offset(x - 1.5f, (size.height - h) / 2 - 1.5f), Size(w + 3f, h + 3f), CornerRadius(w))
            drawRoundRect(if (played) C.Amber else C.Track, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** A rotary knob with a lit tick ring. Drag up/down to turn; [value] is 0..1. */
@Composable
fun Knob(value: Float, label: String, valueText: String, onChange: (Float) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(
        modifier.raised(RoundedCornerShape(18.dp)).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier.size(78.dp)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
                // Left/right turns the knob, so swiping up/down always scrolls the page.
                .pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> change((v + dx / 400f).coerceIn(0f, 1f)) } }
                .semantics { contentDescription = "$label $valueText" },
        ) {
            val c = center
            val n = 27
            for (t in 0 until n) {
                val f = t / (n - 1f)
                val a = Math.toRadians(135.0 + f * 270.0)
                val r1 = size.minDimension * 0.385f; val r2 = size.minDimension * 0.46f
                val p1 = Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1)
                val p2 = Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2)
                val lit = f <= v
                if (lit) drawLine(C.Amber.copy(alpha = 0.35f), p1, p2, 6f, StrokeCap.Round)
                drawLine(if (lit) C.Amber else Color(0xFF2E3142), p1, p2, 2.5f, StrokeCap.Round)
            }
            drawCircle(C.Well, size.minDimension * 0.29f, c)
            drawCircle(Color.Black.copy(alpha = 0.6f), size.minDimension * 0.25f, c + Offset(0f, 4f))
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF2F3F8), Color(0xFF9DA1B3), Color(0xFF4A4D5C)), center = c + Offset(-6f, -10f), radius = size.minDimension * 0.32f),
                size.minDimension * 0.245f, c,
            )
            val ia = Math.toRadians(135.0 + v * 270.0)
            drawLine(
                Color(0xFF1C1E28),
                Offset(c.x + cos(ia).toFloat() * size.minDimension * 0.08f, c.y + sin(ia).toFloat() * size.minDimension * 0.08f),
                Offset(c.x + cos(ia).toFloat() * size.minDimension * 0.2f, c.y + sin(ia).toFloat() * size.minDimension * 0.2f),
                4f, StrokeCap.Round,
            )
        }
        Text(label, style = body(12.sp, FontWeight.Bold))
        Text(valueText, style = mono(11.sp, color = C.AmberText))
    }
}

/** A vertical EQ fader in a sunken slot: −12..+12 dB. Double-tap resets to 0. */
@Composable
fun EqFader(gain: Double, label: String, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    val g by rememberUpdatedState(gain)
    val change by rememberUpdatedState(onChange)
    var acc by remember { mutableFloatStateOf(0f) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text((if (gain > 0) "+" else "") + "%.1f".format(gain), style = mono(10.sp, color = if (abs(gain) < 0.05) C.TextMid else C.AmberText))
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.width(30.dp).height(170.dp)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0.0) }) }
                .pointerInput(Unit) {
                    // Only a drag that starts on the metal cap moves the fader; anywhere else the page scrolls.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val mid = size.height / 2f
                        val capY = (mid - (g / 12.0 * (mid - 12.dp.toPx())).toFloat())
                        if (kotlin.math.abs(down.position.y - capY) > 26.dp.toPx()) return@awaitEachGesture
                        acc = 0f
                        down.consume()
                        verticalDrag(down.id) { change2 ->
                            val dy = change2.positionChange().y
                            change2.consume()
                            acc += dy
                            val stepPx = size.height / 48f // 0.5 dB steps
                            val steps = (acc / stepPx).toInt()
                            if (steps != 0) { acc -= steps * stepPx; change((g - steps * 0.5).coerceIn(-12.0, 12.0)) }
                        }
                    }
                }
                .semantics { contentDescription = "$label Hz ${"%.1f".format(gain)} dB" },
        ) {
            val cx = size.width / 2
            val slotW = 10.dp.toPx()
            drawRoundRect(Color(0xFF040509), Offset(cx - slotW / 2, 0f), Size(slotW, size.height), CornerRadius(slotW / 2))
            drawRoundRect(Color.Black, Offset(cx - slotW / 2, 0f), Size(slotW, 6f), CornerRadius(slotW / 2))
            val mid = size.height / 2
            val y = (mid - (gain / 12.0 * (mid - 12.dp.toPx())).toFloat())
            val top = minOf(y, mid); val h = abs(y - mid).coerceAtLeast(2f)
            drawRoundRect(C.Amber.copy(alpha = 0.35f), Offset(cx - 4.dp.toPx(), top - 2f), Size(8.dp.toPx(), h + 4f), CornerRadius(8f))
            drawRoundRect(C.Amber, Offset(cx - 2.dp.toPx(), top), Size(4.dp.toPx(), h), CornerRadius(4f))
            val capW = 26.dp.toPx(); val capH = 18.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(cx - capW / 2, y - capH / 2 + 4f), Size(capW, capH), CornerRadius(12f))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFF4F5FA), Color(0xFFA9ADBD), Color(0xFF7E8296)), startY = y - capH / 2, endY = y + capH / 2),
                Offset(cx - capW / 2, y - capH / 2), Size(capW, capH), CornerRadius(12f),
            )
            drawLine(Color(0xFF3A3D4C), Offset(cx - 7.dp.toPx(), y), Offset(cx + 7.dp.toPx(), y), 3f, StrokeCap.Round)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = mono(10.sp))
    }
}

/** Frequency response, 20 Hz–20 kHz, ±[rangeDb]. Solid amber = result; dashed violet = the correction alone. */
@Composable
fun ResponseCurve(result: List<EqFilter>, dashed: List<EqFilter>?, modifier: Modifier = Modifier, rangeDb: Double = 12.0, reference: ((Double) -> Double)? = null) {
    val freqs = remember { Eq.curveFreqs(96) }
    val main = remember(result) { freqs.map { Eq.responseDb(result, it) } }
    val second = remember(dashed) { dashed?.let { d -> freqs.map { Eq.responseDb(d, it) } } }
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        fun x(f: Double) = (ln(f / 20.0) / ln(1000.0) * size.width).toFloat()
        fun y(db: Double) = (size.height / 2 - db / rangeDb * (size.height / 2 - 6)).toFloat()
        listOf(100.0, 1000.0, 10000.0).forEach { f -> drawLine(Color(0xFF151922), Offset(x(f), 0f), Offset(x(f), size.height), 1f) }
        listOf(-rangeDb / 2, 0.0, rangeDb / 2).forEach { d -> drawLine(Color(0xFF1A1C28), Offset(0f, y(d)), Offset(size.width, y(d)), 1f) }
        fun path(v: List<Double>) = Path().apply { v.forEachIndexed { i, d -> val px = x(freqs[i]); val py = y(d.coerceIn(-rangeDb, rangeDb)); if (i == 0) moveTo(px, py) else lineTo(px, py) } }
        reference?.let { r -> drawPath(path(freqs.map(r)), C.TextLow, style = Stroke(1.6f)) }
        second?.let { drawPath(path(it), C.Violet, style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))) }
        val p = path(main)
        val fill = Path().apply { addPath(p); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(C.Amber.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(p, C.Amber.copy(alpha = 0.35f), style = Stroke(9f, cap = StrokeCap.Round))
        drawPath(p, C.AmberLight, style = Stroke(3f, cap = StrokeCap.Round))
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.recessed(RoundedCornerShape(12.dp)).padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = mono(14.sp, FontWeight.Bold, C.AmberLight), maxLines = 1)
        Text(label, style = body(10.sp, color = C.TextMid))
    }
}

@Composable
fun StatusDot(text: String, color: Color = C.Ok, textColor: Color = C.OkText) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).shadow(6.dp, CircleShape, ambientColor = color, spotColor = color).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = body(12.sp, FontWeight.Bold, textColor))
    }
}

data class MenuAction(val label: String, val action: () -> Unit)

@Composable
fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit, actions: List<MenuAction>, showArt: Boolean = true, number: Int? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (playing) Modifier.padding(horizontal = 10.dp).recessed(RoundedCornerShape(18.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = if (playing) 10.dp else 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) ArtBox(albumOf(t), 58.dp, 12.dp)
        else Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            Text(number?.toString() ?: "", style = mono(12.sp, color = if (playing) C.Amber else C.TextLow))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = body(15.sp, FontWeight.SemiBold, if (playing) C.Amber else C.TextHi), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.hiRes) {
                    Text("HI-RES", style = mono(9.sp, FontWeight.Bold, C.AmberText), modifier = Modifier.background(C.Amber.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(listOf(t.artist, fmtTime(t.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            PlainIconButton(I.More, "More for ${t.title}", { menu = true }, filled = false)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF171925)) {
                actions.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label, style = body(14.sp)) }, onClick = { menu = false; a.action() })
                }
            }
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Runs [block] once whenever [key] changes. */
@Composable
fun OnChange(key: Any?, block: suspend () -> Unit) { LaunchedEffect(key) { block() } }
