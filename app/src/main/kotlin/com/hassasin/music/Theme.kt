package com.hassasin.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours. Amber = the signal (active, playing); green = bit-perfect only; red = limits. */
/** The chosen look. Read by colours and surfaces; the root recomposes everything when it changes. */
object Look {
    @Volatile var soft = false
    /** Flow look: everything tinted with the playing song's cover colour. */
    @Volatile var flow = false
    /** The playing song's cover colour (set by the player). */
    var tint by androidx.compose.runtime.mutableStateOf(Color(0xFF5B3A8A))
}

/**
 * Colours. The accent ("Amber" in Glow, indigo in Soft) marks what is active or playing;
 * green = bit-perfect only; red = limits.
 */
object C {
    val Ground: Color get() = if (Look.soft) Color(0xFF26282D) else Color(0xFF07080D)
    val TextHi: Color get() = if (Look.soft) Color(0xFFE6E7EB) else Color(0xFFF2F3F8)
    val TextMid: Color get() = if (Look.soft) Color(0xFFA3A7B0) else Color(0xFFB9BCD6)
    val TextLow: Color get() = if (Look.soft) Color(0xFF7E828C) else Color(0xFF8D92AC)
    val Amber: Color get() = if (Look.soft) Color(0xFF7C80FF) else Color(0xFFFFB547)
    val AmberLight: Color get() = if (Look.soft) Color(0xFFB4B7FF) else Color(0xFFFFD69A)
    val AmberDeep: Color get() = if (Look.soft) Color(0xFF4B4FD6) else Color(0xFFE07B12)
    val AmberText: Color get() = if (Look.soft) Color(0xFFB4B7FF) else Color(0xFFFFCF85)
    /** Text/icons drawn on the accent fill. */
    val OnAccent: Color get() = if (Look.soft) Color(0xFFF4F4FF) else Color(0xFF2A1600)
    val Indigo = Color(0xFF6D5BFF)
    val Violet = Color(0xFF8C7BFF)
    val Teal = Color(0xFF2FD4E0)
    val Ok = Color(0xFF4ADE80)
    val OkText = Color(0xFF6FF0A0)
    val Limit = Color(0xFFFF6B5A)
    val Track: Color get() = if (Look.soft) Color(0xFF3A3D44) else Color(0xFF2E3146)
    val PanelTop: Color get() = if (Look.soft) Color(0xFF2B2D33) else Color(0xFF1C1E2E)
    val PanelBottom: Color get() = if (Look.soft) Color(0xFF24262B) else Color(0xFF0F101A)
    val Well: Color get() = if (Look.soft) Color(0xFF1F2125) else Color(0xFF05060A)
    /** Soft theme: the light edge and the dark edge of a raised shape. */
    val SoftLight = Color(0xFF3C3F47)
    val SoftDark = Color(0xFF0E0F11)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

object F {
    val Display = FontFamily(variable(R.font.unbounded, 500), variable(R.font.unbounded, 700))
    val Body = FontFamily(variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700))
    val Mono = FontFamily(variable(R.font.jetbrains_mono, 500), variable(R.font.jetbrains_mono, 700))
}

fun body(size: TextUnit = 14.sp, weight: FontWeight = FontWeight.Normal, color: Color = C.TextHi) =
    TextStyle(fontFamily = F.Body, fontSize = size, fontWeight = weight, color = color)

fun display(size: TextUnit = 22.sp, weight: FontWeight = FontWeight.Bold, color: Color = C.TextHi) =
    TextStyle(fontFamily = F.Display, fontSize = size, fontWeight = weight, color = color, letterSpacing = (-0.2).sp)

fun mono(size: TextUnit = 11.sp, weight: FontWeight = FontWeight.Medium, color: Color = C.TextMid) =
    TextStyle(fontFamily = F.Mono, fontSize = size, fontWeight = weight, color = color)

fun eyebrow(color: Color = C.TextMid) = TextStyle(fontFamily = F.Body, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 1.5.sp)

// ---- depth ----

/** Soft theme: a shape lifted off the surface, lit from the top-left (light edge) and shaded bottom-right. */
fun Modifier.softRaised(shape: Shape, radius: Dp = 16.dp, distance: Dp = 6.dp) = this.drawBehind {
    val r = radius.toPx(); val d = distance.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    val bounds = outline.bounds
    drawIntoCanvas { canvas ->
        fun pass(color: Color, dx: Float) {
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = C.Ground.toArgb()
                setShadowLayer(r, dx, dx, color.toArgb())
            }
            val cr = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x else if (shape == CircleShape) size.minDimension / 2 else 0f
            canvas.nativeCanvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, cr, cr, paint)
        }
        pass(C.SoftDark, d)
        pass(C.SoftLight, -d)
    }
}

/** A raised control: lit from the top, with a soft shadow below (Glow) or a soft neumorphic lift (Soft). */
fun Modifier.raised(shape: Shape = RoundedCornerShape(16.dp), elevation: Dp = 8.dp): Modifier =
    if (Look.soft) this.softRaised(shape, radius = elevation * 2.2f, distance = elevation * 0.9f)
        .background(Brush.linearGradient(listOf(Color(0xFF2C2E34), Color(0xFF232529))), shape)
    else this
        .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .background(Brush.verticalGradient(listOf(C.PanelTop, C.PanelBottom)), shape)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f))), shape)

/** A glass panel over the glow behind it (Glow), or a softly raised card (Soft). */
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp)): Modifier =
    if (Look.soft) this.softRaised(shape, 18.dp, 7.dp).background(C.Ground, shape)
    else this
        // No elevation shadow: under a see-through panel Android draws it as a dark box inside the card.
        .drawBehind {
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.35f)), startY = size.height * 0.5f, endY = size.height + 18.dp.toPx()),
                topLeft = Offset(6.dp.toPx(), 10.dp.toPx()), size = Size(size.width - 12.dp.toPx(), size.height + 4.dp.toPx()),
                cornerRadius = CornerRadius(24.dp.toPx()),
            )
        }
        .background(Brush.verticalGradient(listOf(Color(0xFF1E2030).copy(alpha = 0.92f), Color(0xFF12131D).copy(alpha = 0.92f))), shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.01f))), shape)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))), shape)

/** A sunken well: darker at the top edge, a faint light line at the bottom. */
fun Modifier.recessed(shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    if (Look.soft) this
        .background(Color(0xFF212327), shape)
        .clip(shape)
        .drawWithContent {
            drawContent()
            // Pressed in: shade along the top-left inner edge, light along the bottom-right.
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), endY = 12.dp.toPx()))
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent), endX = 10.dp.toPx()))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.05f)), startY = size.height - 8.dp.toPx()))
        }
    else this
        .background(Brush.verticalGradient(listOf(Color(0xFF020306), Color(0xFF0A0B12))), shape)
        .border(1.dp, Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.9f), Color.White.copy(alpha = 0.07f))), shape)
        .clip(shape)
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endY = 14f))
        }

/** The main action: an amber "tube" (Glow) or a glowing indigo button (Soft). */
fun Modifier.amberOrb(shape: Shape = CircleShape): Modifier =
    if (Look.soft) this.softRaised(shape, 18.dp, 6.dp)
        .shadow(16.dp, shape, ambientColor = C.Amber, spotColor = C.Amber)
        .background(Brush.radialGradient(listOf(Color(0xFF8E92FF), Color(0xFF5458E0), Color(0xFF3A3DA8))), shape)
    else this
        .shadow(18.dp, shape, ambientColor = C.Amber, spotColor = C.AmberDeep)
        .background(Brush.radialGradient(listOf(Color(0xFFFFE0A8), C.Amber, C.AmberDeep), center = Offset.Unspecified), shape)

/** A selected chip or segment: lit and outlined in the accent. */
fun Modifier.amberSelected(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    if (Look.soft) this.recessed(shape).border(1.dp, C.Amber.copy(alpha = 0.55f), shape)
    else this
        .shadow(10.dp, shape, ambientColor = C.Amber, spotColor = C.Amber)
        .background(Brush.verticalGradient(listOf(Color(0xFF4A2F0E), Color(0xFF24160A))), shape)
        .border(1.dp, C.Amber.copy(alpha = 0.6f), shape)

/** Full-screen background: coloured light behind the content (Glow) or a calm matte surface (Soft). */
@Composable
fun Backdrop(tint: Color, accent: Color = C.Amber, content: @Composable BoxScope.() -> Unit) {
    val soft = Look.soft
    Box(
        Modifier
            .fillMaxSize()
            .background(C.Ground)
            .drawWithContent {
                if (Look.flow) {
                    val c = Look.tint
                    drawRect(Brush.verticalGradient(listOf(lerpColor(c, Color.Black, 0.35f), lerpColor(c, Color.Black, 0.62f), lerpColor(c, Color.Black, 0.82f))))
                } else if (!soft) {
                    drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.12f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.2f), radius = size.width * 1.0f))
                    drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.55f), radius = size.width * 0.6f))
                } else {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF2B2D32), Color(0xFF222428))))
                }
                drawContent()
            },
        content = content,
    )
}

/** A screen's frame: its own backdrop when full screen; nothing extra inside a sheet. */
@Composable
fun ScreenShell(sheet: Boolean, tint: Color, accent: Color = C.Amber, content: @Composable BoxScope.() -> Unit) {
    if (sheet) Box(Modifier.fillMaxSize(), content = content) else Backdrop(tint, accent, content)
}

// ---- icons (24×24 line icons drawn from path data) ----

object I {
    const val Back = "M15 18l-6-6 6-6"
    const val Down = "M6 9l6 6 6-6"
    const val More = "M5 12h.01M12 12h.01M19 12h.01"
    const val Play = "M8 5v14l11-7z"
    const val Pause = "M7 5h4v14H7zM13 5h4v14h-4z"
    const val Prev = "M7 6h2v12H7zM20 6v12l-9-6z"
    const val Next = "M15 6h2v12h-2zM4 6v12l9-6z"
    const val Shuffle = "M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"
    const val Repeat = "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"
    const val Eq = "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"
    const val Headphones = "M3 18v-6a9 9 0 0 1 18 0v6M21 19a2 2 0 0 1-2 2h-1v-6h3zM3 19a2 2 0 0 0 2 2h1v-6H3z"
    const val Queue = "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"
    const val Lyrics = "M4 6h16M4 10h10M4 14h16M4 18h8"
    const val Star = "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"
    const val Search = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-4.3-4.3"
    const val Folder = "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
    const val Album = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"
    const val Artist = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0"
    const val Genre = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
    const val HiRes = "M3 12h3l3-8 4 16 3-8h5"
    const val Playlist = "M3 6h12M3 12h12M3 18h8M17 18V8l4-2"
    const val Chart = "M4 20V10M10 20V4M16 20v-7M22 20H2"
    const val Clock = "M12 7v5l3 2M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z"
    const val Plug = "M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0zM12 18v4"
    const val Library = "M4 4h4v16H4zM10 4h4v16h-4zM16 5l4 1-3 14-4-1z"
    const val Disc = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM10 8l6 4-6 4z"
    const val Settings = "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"
    const val Image = "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01"
    const val Check = "M5 12l5 5 9-10"
    const val Close = "M6 6l12 12M18 6L6 18"
    const val Plus = "M12 5v14M5 12h14"
    const val Minus = "M5 12h14"
    const val Reset = "M3 12a9 9 0 1 0 3-6.7M3 4v5h5"
    const val Trash = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"
    const val Up = "M6 15l6-6 6 6"
    const val Note = "M9 18V5l12-2v13M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"
}

private val pathCache = HashMap<String, Path>()

private fun parsed(d: String): Path = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

@Composable
fun Ico(d: String, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier, filled: Boolean = false, stroke: Float = 2f) {
    val p = remember(d) { parsed(d) }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            if (filled) drawPath(p, color, style = Fill)
            else drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}


fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
