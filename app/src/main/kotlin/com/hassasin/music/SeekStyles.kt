package com.hassasin.music

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The seek bar styles; all of them sit behind the play controls. */
object SeekStyles {
    val ALL = listOf(
        "wave" to "Waveform",
        "mirror" to "Mirrored",
        "line" to "Thin line",
        "capsule" to "Capsule",
        "ring" to "Ring",
        "dots" to "Dot spectrum",
        "squiggle" to "Wavy line",
    )
    fun label(key: String) = ALL.firstOrNull { it.first == key }?.second ?: "Waveform"
}

/**
 * The area behind the play controls: the seek bar is drawn here in the chosen style, and a tap or a
 * sideways drag anywhere outside the buttons seeks. Times sit in the bottom corners.
 */
@Composable
fun SeekZone(
    style: String, peaks: FloatArray?, fraction: Float, playing: Boolean, positionMs: Long, durationMs: Long,
    onSeek: (Float) -> Unit, controls: @Composable BoxScope.() -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = drag ?: fraction
    val phase = rememberInfiniteTransition(label = "seek").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    Box(
        Modifier.fillMaxWidth().height(132.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .semantics { contentDescription = "Seek bar, ${fmtTime(positionMs)} of ${fmtTime(durationMs)}" },
    ) {
        val accent = C.Amber
        val rest = if (Look.soft) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.15f)
        val ph = if (playing && style == "squiggle") phase.value else 0f
        Canvas(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            val w = size.width; val h = size.height
            fun peak(i: Int, n: Int) = peaks?.let { it[(i * it.size / n).coerceIn(0, it.lastIndex)] } ?: 0.35f
            when (style) {
                "mirror" -> {
                    val n = 56; val gap = 2.dp.toPx(); val bw = (w - gap * (n - 1)) / n; val mid = h * 0.56f
                    for (i in 0 until n) {
                        val v = 0.15f + 0.85f * peak(i, n); val up = v * mid * 0.92f
                        val c = if ((i + 0.5f) / n <= shown) accent else rest
                        drawRoundRect(c, Offset(i * (bw + gap), mid - up), Size(bw, up), CornerRadius(bw / 2))
                        drawRoundRect(c.copy(alpha = c.alpha * 0.45f), Offset(i * (bw + gap), mid + 2f), Size(bw, up * 0.55f), CornerRadius(bw / 2))
                    }
                }
                "line" -> {
                    val y = h - 6.dp.toPx(); val sw = 4.dp.toPx()
                    drawLine(rest, Offset(0f, y), Offset(w, y), sw, StrokeCap.Round)
                    drawLine(accent, Offset(0f, y), Offset(w * shown, y), sw, StrokeCap.Round)
                    drawCircle(accent.copy(alpha = 0.35f), 14.dp.toPx(), Offset(w * shown, y))
                    drawCircle(C.AmberLight, 8.dp.toPx(), Offset(w * shown, y))
                }
                "capsule" -> {
                    val ch = h * 0.78f; val top = (h - ch) / 2; val r = CornerRadius(ch / 2)
                    drawRoundRect(Color.Black.copy(alpha = if (Look.soft) 0.25f else 0.32f), Offset(0f, top), Size(w, ch), r)
                    drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0.55f)), endX = w * shown), Offset(0f, top), Size(maxOf(ch, w * shown), ch), r)
                }
                "ring" -> {
                    val rad = min(w, h) * 0.46f; val c = Offset(w / 2, h / 2); val sw = 6.dp.toPx()
                    drawCircle(rest, rad, c, style = Stroke(sw))
                    drawArc(accent.copy(alpha = 0.35f), -90f, 360f * shown, false, Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2), style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
                    drawArc(accent, -90f, 360f * shown, false, Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2), style = Stroke(sw, cap = StrokeCap.Round))
                }
                "dots" -> {
                    val n = 44; val step = w / n; val rows = 10; val dy = h / (rows + 1)
                    for (i in 0 until n) {
                        val k = (peak(i, n) * rows).toInt().coerceAtLeast(1)
                        val c = if ((i + 0.5f) / n <= shown) accent else rest
                        for (j in 0 until k) drawCircle(c, 2.4.dp.toPx(), Offset(i * step + step / 2, h - dy * (j + 0.6f)))
                    }
                }
                "squiggle" -> {
                    val y = h - 6.dp.toPx(); val px = w * shown
                    val path = Path().apply {
                        moveTo(0f, y)
                        var x = 0f
                        while (x <= px) { lineTo(x, y + sin(x / 9f + ph) * 4.dp.toPx()); x += 3f }
                    }
                    drawPath(path, accent, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
                    drawLine(rest, Offset(px + 6.dp.toPx(), y), Offset(w, y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(C.AmberLight, Offset(px, y - 10.dp.toPx()), Offset(px, y + 10.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                }
                else -> { // "wave": the song's loudness shape, full height, behind the buttons
                    val n = 56; val gap = 2.dp.toPx(); val bw = (w - gap * (n - 1)) / n
                    for (i in 0 until n) {
                        val v = 0.12f + 0.88f * peak(i, n); val bh = v * h * 0.95f
                        val played = (i + 0.5f) / n <= shown
                        drawRoundRect(if (played) accent.copy(alpha = 0.85f) else rest, Offset(i * (bw + gap), (h - bh) / 2), Size(bw, bh), CornerRadius(bw / 2))
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.Center, content = controls)
        Text(fmtTime(if (drag != null) (shown * durationMs).toLong() else positionMs), style = mono(11.sp, color = C.TextMid), modifier = Modifier.align(Alignment.BottomStart))
        Text("-" + fmtTime(durationMs - (shown * durationMs).toLong()), style = mono(11.sp, color = C.TextMid), modifier = Modifier.align(Alignment.BottomEnd))
    }
    @Suppress("UNUSED_VARIABLE") val unused = cos(0.0)
}

/** Everything about the file's quality, read from the file itself. */
data class QualityInfo(
    val codec: String, val codecLong: String, val lossless: Boolean, val sampleRate: Int, val bits: Int,
    val channels: Int, val kbps: Int, val sizeMb: Double, val container: String,
) {
    /** "FLAC · 24-bit · 44.1 kHz · 1,411 kbps" */
    val short: String
        get() = listOfNotNull(
            codec,
            bits.takeIf { it > 0 }?.let { "$it-bit" },
            sampleRate.takeIf { it > 0 }?.let { "${khz(it)} kHz" },
            kbps.takeIf { it > 0 }?.let { "%,d kbps".format(Locale.US, it) },
        ).joinToString(" · ")
}

object Quality {
    private val cache = HashMap<Long, QualityInfo>()

    private val names = mapOf(
        "flac" to ("FLAC" to "Free Lossless Audio Codec"), "alac" to ("ALAC" to "Apple Lossless"),
        "raw" to ("WAV" to "Uncompressed PCM"), "wav" to ("WAV" to "Uncompressed PCM"),
        "mpeg" to ("MP3" to "MPEG Layer III"), "mp4a-latm" to ("AAC" to "Advanced Audio Coding"),
        "opus" to ("Opus" to "Opus"), "vorbis" to ("Vorbis" to "Ogg Vorbis"), "ac3" to ("AC-3" to "Dolby Digital"),
        "eac3" to ("E-AC-3" to "Dolby Digital Plus"), "x-ape" to ("APE" to "Monkey's Audio"), "x-ms-wma" to ("WMA" to "Windows Media Audio"),
        "dsd" to ("DSD" to "Direct Stream Digital"),
    )

    /** Reads the file's own format (cached). Bit depth comes from the header for FLAC/WAV. */
    fun of(t: Track): QualityInfo = cache.getOrPut(t.id) {
        var mime = t.mime; var rate = t.sampleRate; var ch = 2; var br = t.bitrate
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(t.path)
                (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }?.let { f ->
                    mime = f.getString(MediaFormat.KEY_MIME) ?: mime
                    if (rate == 0 && f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) br = f.getInteger(MediaFormat.KEY_BIT_RATE)
                }
            } finally { ex.release() }
        }
        // The file's own signature first: some phones report FLAC as "raw" audio.
        val sniffed = runCatching {
            java.io.RandomAccessFile(t.path, "r").use { f -> val b = ByteArray(12); f.readFully(b); String(b, Charsets.ISO_8859_1) }
        }.getOrDefault("")
        val sub = when {
            sniffed.startsWith("fLaC") -> "flac"
            sniffed.startsWith("RIFF") -> "wav"
            sniffed.startsWith("OggS") -> mime.substringAfter('/').lowercase(Locale.ROOT).let { if (it == "raw") "opus" else it }
            sniffed.startsWith("ID3") -> "mpeg"
            else -> mime.substringAfter('/').lowercase(Locale.ROOT)
        }
        val (short, long) = names[sub] ?: (t.ext.ifBlank { sub.uppercase(Locale.ROOT) } to sub.uppercase(Locale.ROOT))
        val lossless = short in setOf("FLAC", "ALAC", "WAV", "APE", "DSD")
        val size = File(t.path).length().takeIf { it > 0 } ?: t.sizeBytes
        val kbps = if (br > 0) br / 1000 else if (t.durationMs > 0) (size * 8 / t.durationMs).toInt() else 0
        QualityInfo(short, long, lossless, rate, t.bits, ch, kbps, size / 1048576.0, t.ext)
    }
}
