package com.hassasin.music

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/** The cover as a bitmap (for the hero and its colour). */
@Composable
fun rememberArtBitmap(album: Album?, size: Int = 1000): Bitmap? {
    val version = ArtRepo.version
    val sharp = AppState.settings.collectAsState().value.artSharpness
    val start = album?.let { ArtRepo.cachedBitmap(it) }?.let { if (sharp == 0) it else Sharpen.cached(album.key, it, sharp) }
    val b by produceState(start, album?.key, version, sharp) {
        val raw = album?.let { ArtRepo.bitmap(it, size) }
        value = if (raw == null || sharp == 0) raw else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Sharpen.apply(album.key, raw, sharp) }
    }
    return b
}

/**
 * Cover sharpness (Settings → Look): an unsharp mask. Above 0 brings out edges (helps small or
 * soft covers), below 0 softens. Results are cached per cover and setting.
 */
object Sharpen {
    private val cache = android.util.LruCache<String, Bitmap>(6)

    // Keyed by the picture itself too: a newly chosen cover for the same album must not get the old one back.
    private fun id(key: String, src: Bitmap, amount: Int) = "$key|${System.identityHashCode(src)}|${src.width}|$amount"

    fun cached(key: String, src: Bitmap, amount: Int): Bitmap? = cache.get(id(key, src, amount))

    fun apply(key: String, src: Bitmap, amount: Int): Bitmap {
        val id = id(key, src, amount)
        cache.get(id)?.let { return it }
        val out = process(src, amount / 100f)
        cache.put(id, out)
        return out
    }

    /** out = original + a × (original − blurred); a in −1..2. */
    fun process(src: Bitmap, a: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // 3×3 box blur, done as two 1-D passes per channel.
        val r = FloatArray(w * h); val g = FloatArray(w * h); val bl = FloatArray(w * h)
        for (i in px.indices) { val c = px[i]; r[i] = ((c shr 16) and 255).toFloat(); g[i] = ((c shr 8) and 255).toFloat(); bl[i] = (c and 255).toFloat() }
        // A wider blur for softening reads better than a 3×3 one.
        val radius = if (a < 0) 2 else 1
        fun blur(ch: FloatArray): FloatArray {
            val tmp = FloatArray(w * h); val res = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var n = 0
                for (d in -radius..radius) { val xx = x + d; if (xx in 0 until w) { s += ch[y * w + xx]; n++ } }
                tmp[y * w + x] = s / n
            }
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var n = 0
                for (d in -radius..radius) { val yy = y + d; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
                res[y * w + x] = s / n
            }
            return res
        }
        val br = blur(r); val bg = blur(g); val bb = blur(bl)
        for (i in px.indices) {
            val nr = (r[i] + a * (r[i] - br[i])).toInt().coerceIn(0, 255)
            val ng = (g[i] + a * (g[i] - bg[i])).toInt().coerceIn(0, 255)
            val nb = (bl[i] + a * (bl[i] - bb[i])).toInt().coerceIn(0, 255)
            px[i] = (px[i] and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * The cover's own glow colour: its most colourful tones, weighted by saturation, lifted so the glow
 * reads on the dark ground. Grey covers give a soft neutral glow.
 */
fun glowColor(b: Bitmap): Color {
    val s = Bitmap.createScaledBitmap(b, 24, 24, true)
    var r = 0.0; var g = 0.0; var bl = 0.0; var w = 0.0
    val hsv = FloatArray(3)
    for (y in 0 until 24) for (x in 0 until 24) {
        val p = s.getPixel(x, y)
        android.graphics.Color.colorToHSV(p, hsv)
        val weight = (hsv[1] * hsv[1] * hsv[2]).toDouble() + 0.02
        r += ((p shr 16) and 0xFF) * weight; g += ((p shr 8) and 0xFF) * weight; bl += (p and 0xFF) * weight; w += weight
    }
    val avg = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
    android.graphics.Color.colorToHSV(avg, hsv)
    hsv[1] = min(1f, hsv[1] * 1.25f)
    hsv[2] = max(0.55f, min(0.9f, hsv[2] * 1.2f))
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** A 48 px, box-blurred copy of the cover, for backgrounds. */
fun softened(src: Bitmap): Bitmap {
    val n = 48
    val small = Bitmap.createScaledBitmap(src, n, n, true)
    val px = IntArray(n * n); small.getPixels(px, 0, n, 0, 0, n, n)
    repeat(3) {
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var r = 0; var g = 0; var b = 0; var c = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (x + dx).coerceIn(0, n - 1); val yy = (y + dy).coerceIn(0, n - 1)
                val p = px[yy * n + xx]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++
            }
            out[y * n + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
        }
        out.copyInto(px)
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}

/** The whole screen behind the player: the cover itself, blurred and dimmed, plus its glow. */
@Composable
fun BoxScope.ImmersiveBackground(art: Bitmap?, glow: () -> Color) {
    Box(Modifier.matchParentSize().background(C.Ground))
    if (Look.soft) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0xFF2C2E33), Color(0xFF222428)))))
        return
    }
    // The cover softened once into a tiny image and stretched: looks like a heavy blur, but costs
    // nothing per frame (a live 60 dp blur over the whole screen made screen changes stutter).
    val soft = remember(art) { art?.let { softened(it).asImageBitmap() } }
    if (soft != null) Image(soft, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.matchParentSize().alpha(0.55f))
    Box(
        Modifier.matchParentSize().drawWithContent {
            drawRect(Brush.radialGradient(listOf(glow().copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.45f), radius = size.width * 1.1f))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.55f), C.Ground.copy(alpha = 0.92f)), startY = size.height * 0.3f))
        },
    )
}

/**
 * The big cover, edge to edge from the top of the screen. Close, the signal path and the queue
 * float over it as small glass pieces; the song's name sits on solid frosted glass over its lower edge.
 */
@Composable
fun ImmersiveHero(
    album: Album?, art: Bitmap?, glow: Color, t: Track, st: PlayState, settings: AppSettings, rating: Int,
    modifier: Modifier, onClose: () -> Unit, onQueue: () -> Unit, onSignal: () -> Unit, onFixArt: () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val side = minOf(maxWidth, maxHeight)
        // The cover, fading into the background at its lower edge.
        Box(
            Modifier.fillMaxWidth().height(side)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.62f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onFixArt() }) }
                .semantics { contentDescription = "Cover. Long-press to fix the picture" },
        ) {
            if (art != null) Image(art.asImageBitmap(), album?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF101226)))), contentAlignment = Alignment.Center) {
                Ico(I.Note, C.TextLow, side * 0.25f)
            }
            // Darker strip under the floating top controls, so they read on bright covers.
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
        }
        // Floating top: close, signal path, queue.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircle(I.Down, "Close player", onClose)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SignalPill(st, settings, onClick = onSignal) }
            GlassCircle(I.Queue, "Queue", onQueue)
        }
        // The song's name on frosted glass, overlapping the cover's lower edge.
        Caption(art, t, st, settings, rating, glow, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp))
    }
}

@Composable
fun GlassCircle(icon: String, desc: String, onClick: () -> Unit) {
    if (Look.soft) return RoundButton(icon, desc, onClick, 44.dp)
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Ico(icon, Color.White, 20.dp) }
}

/** Minimal signal path: a dot for the state, then file format → where the sound goes. */
@Composable
fun SignalPill(st: PlayState, s: AppSettings, compact: Boolean = false, onClick: () -> Unit) {
    if (compact) return CompactSignal(st, s, onClick)
    val t = st.current ?: return
    val effects = Eq.anyEffect(s.sound, st.usb)
    val (dot, label) = when {
        st.usb && st.untouched -> C.Ok to "Bit-perfect"
        st.usb -> C.Amber to "32-bit DSP"
        else -> Color.White.copy(alpha = 0.5f) to if (effects) "Android · DSP" else "Android"
    }
    val dest = if (st.usb) "${st.dacName.ifBlank { "DAC" }.split(' ').last()} ${khz(st.dacRate)}k" else "Phone"
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.height(34.dp).widthIn(max = 260.dp)
            .then(if (Look.soft) Modifier.recessed(shape) else Modifier.clip(shape).background(Color.Black.copy(alpha = 0.32f)).border(1.dp, Color.White.copy(alpha = 0.14f), shape))
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "Signal path: $label, ${t.formatLabel} to $dest" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(label, style = body(11.sp, FontWeight.Bold, C.TextHi), maxLines = 1)
        Text("  ·  ${t.formatLabel.substringAfter(' ', t.formatLabel)} → $dest", style = mono(10.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The signal path as a small tag beside the artist: "● Bit-perfect → D07 384k", "● Android · DSP → Phone". */
@Composable
private fun CompactSignal(st: PlayState, s: AppSettings, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val path by AudioPathInfo.path.collectAsState()
    val effects = Eq.anyEffect(s.sound, st.usb) && !(s.output == "offload" && !st.usb)
    val label = when {
        st.usb -> if (st.untouched) "Bit-perfect" else "USB DAC + effects"
        st.phoneDirect -> if (st.untouched) "Direct · untouched" else if (effects) "Direct + effects" else "Direct"
        s.output == "usb_android" && AndroidBitPerfect.active.isNotEmpty() -> "Bit-perfect (Android)"
        s.output == "offload" && path.offload -> "Battery saver"
        s.output == "hires" && !effects -> "Hi-Res"
        else -> if (effects) "Phone + effects" else "Phone"
    }
    val dot = when { (st.usb || st.phoneDirect) && st.untouched -> C.Ok; s.output == "usb_android" && AndroidBitPerfect.active.isNotEmpty() -> C.Ok; effects -> C.Amber; else -> Color.White.copy(alpha = 0.55f) }
    val dest = if (st.usb) "${st.dacName.ifBlank { "DAC" }.split(' ').last()} ${khz(st.dacRate)}k" else remember(path.updatedAt, st.current?.id) { AudioPathInfo.deviceShort(context) }
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.height(24.dp)
            .then(if (Look.soft) Modifier.recessed(shape) else Modifier.clip(shape).background(Color.Black.copy(alpha = 0.26f)).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f))), shape))
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp)
            .semantics { contentDescription = "Output: $label to $dest. Tap to change" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).shadow(4.dp, CircleShape, ambientColor = dot, spotColor = dot).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = body(10.sp, FontWeight.Bold, C.TextHi), maxLines = 1)
        Text(" → $dest", style = mono(10.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Caption(art: Bitmap?, t: Track, st: PlayState, settings: AppSettings, rating: Int, glow: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.fillMaxWidth().clip(shape)) {
        // Frosted: the cover blurred behind a dark tint that is solid enough for clear text.
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(40.dp))
        }
        Box(
            Modifier.matchParentSize()
                .background(Color(0xFF0B0C14).copy(alpha = 0.78f))
                .background(Brush.linearGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent)))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))), shape),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            val from = t.album.ifBlank { t.folderName }
            Text("FROM ${from.uppercase()}", style = eyebrow(Color.White.copy(alpha = 0.6f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(t.title, style = display(21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.artist.ifBlank { "Unknown artist" }, style = body(15.sp, FontWeight.Medium, Color.White.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                (1..5).forEach { n ->
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { AppState.rate(t, if (rating == n) 0 else n) }.semantics { contentDescription = "Rate $n stars" }, contentAlignment = Alignment.Center) {
                        Ico(I.Star, if (n <= rating) C.Amber else Color.White.copy(alpha = 0.4f), 17.dp, filled = n <= rating)
                    }
                }
            }
            if (Eq.autoEqActive(settings.sound, st.usb)) {
                Spacer(Modifier.height(6.dp))
                Text("Tuned for ${settings.sound.autoEq!!.name}", style = body(12.sp, FontWeight.SemiBold, C.Teal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The cover as a card in the free space between the top bar and the controls: as big as that space
 * allows, centred, with its colour glowing around it. The song's name on frosted glass overlaps the
 * cover's lower edge and hangs a little below it.
 */
@Composable
fun CoverCard(album: Album?, art: Bitmap?, glow: Color, t: Track, st: PlayState, settings: AppSettings, rating: Int, modifier: Modifier, onFixArt: () -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        val hang = 44.dp
        val side = minOf(maxWidth, maxHeight - hang)
        val shape = RoundedCornerShape(28.dp)
        Box(Modifier.width(side).height(side + hang)) {
            Box(
                Modifier.size(side)
                    .shadow(42.dp, shape, ambientColor = glow, spotColor = glow)
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF101226))))
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onFixArt() }) }
                    .semantics { contentDescription = "Cover. Long-press to fix the picture" },
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) Image(art.asImageBitmap(), album?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Ico(I.Note, C.TextLow, side * 0.25f)
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), end = Offset(400f, 400f))))
                Box(Modifier.fillMaxSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape))
            }
            Caption(art, t, st, settings, rating, glow, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp))
        }
    }
}
