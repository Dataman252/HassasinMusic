package com.hassasin.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** One stop of the tour: what it points at (a marked part of the player), what it says, and a gesture hint. */
data class TourStep(val target: String?, val title: String, val text: String, val gesture: String? = null)

/**
 * The first-run tour: walks over the real player, lighting up one part at a time, and ends at the
 * best-sound setup. Skippable; Settings → About → Take the tour runs it again.
 */
object Tour {
    var active by mutableStateOf(false)
        private set
    var step by mutableIntStateOf(0)
    /** Where each marked part of the player is on screen. */
    val targets = mutableStateMapOf<String, Rect>()

    val STEPS = listOf(
        TourStep(null, "Welcome to Hassasin Music", "A 30-second tour of the player. Skip it any time; it is in Settings → About if you want it again."),
        TourStep("cover", "Swipe the cover", "Swipe left or right for the next or previous song. Pick the animation in Settings → Look.", gesture = "sideways"),
        TourStep(null, "Up for Library, down for Settings", "Swipe up anywhere on the player to open your library. Swipe down for Settings.", gesture = "updown"),
        TourStep("path", "Where your sound goes", "The path from the app to your ears. Tap it to see every step, and to choose how the sound leaves the phone."),
        TourStep("quality", "What you are hearing", "The song's format, bit depth, sample rate and bitrate."),
        TourStep("lyrics", "Lyrics", "Synced lyrics, also in Hinglish. Tap a line to jump there, or sync a song yourself."),
        TourStep("sound", "Sound", "Equalizer, earphone correction and effects."),
        TourStep("more", "More", "Rate the song, speed, sleep timer, tags, playlists and Settings."),
        TourStep(null, "The best sound for your setup", "Answer one question and the app sets itself up for the cleanest sound this phone can give."),
    )

    /** The tour or the setup ran in this session: no coffee card on top of a first look around. */
    @Volatile var busyThisSession = false

    fun start() { step = 0; active = true; busyThisSession = true }

    fun finish() { active = false; AppState.update { it.copy(tourDone = true) } }
}

/** Marks a part of the screen so the tour can point at it. */
fun Modifier.tourTarget(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { Tour.targets.remove(key) } }
    onGloballyPositioned { c -> val r = c.boundsInRoot(); if (Tour.targets[key] != r) Tour.targets[key] = r }
}

@Composable
fun TourOverlay(nav: Nav) {
    if (!Tour.active) return
    val step = Tour.STEPS.getOrNull(Tour.step) ?: run { Tour.finish(); return }
    val last = Tour.step == Tour.STEPS.lastIndex
    val hole = step.target?.let { Tour.targets[it] }
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val pulse by rememberInfiniteTransition(label = "tour").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    fun next() { if (last) Tour.finish() else Tour.step++ }
    BoxWithConstraints(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { next() }) {
        val hPx = with(density) { maxHeight.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        // The dimmed screen with a lit window over the part being explained, and a pulsing ring around it.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.78f))
            if (hole != null) {
                val r = Rect(hole.left - pad, hole.top - pad, hole.right + pad, hole.bottom + pad)
                drawRoundRect(Color.Transparent, r.topLeft, r.size, CornerRadius(22.dp.toPx()), blendMode = BlendMode.Clear)
                val grow = pulse * 10.dp.toPx()
                drawRoundRect(
                    C.Amber.copy(alpha = 1f - pulse), Offset(r.left - grow, r.top - grow), Size(r.width + 2 * grow, r.height + 2 * grow),
                    CornerRadius(22.dp.toPx() + grow), style = Stroke(2.dp.toPx()),
                )
            }
            // Gesture hints: a dot gliding the way to swipe.
            when (step.gesture) {
                "sideways" -> {
                    val c = hole?.center ?: Offset(wPx / 2, hPx * 0.3f)
                    val x = c.x + (0.3f - pulse * 0.6f) * wPx
                    drawCircle(Color.White.copy(alpha = 0.9f * (1f - pulse * 0.6f)), 14.dp.toPx(), Offset(x, c.y))
                }
                "updown" -> {
                    val x = wPx / 2
                    drawCircle(Color.White.copy(alpha = 0.9f * (1f - pulse * 0.6f)), 14.dp.toPx(), Offset(x - 50.dp.toPx(), hPx * (0.62f - pulse * 0.25f)))
                    drawCircle(Color.White.copy(alpha = 0.9f * (1f - pulse * 0.6f)), 14.dp.toPx(), Offset(x + 50.dp.toPx(), hPx * (0.37f + pulse * 0.25f)))
                }
            }
        }
        // The card: below the lit part when it is in the top half of the screen, above it otherwise.
        val cardTop = when {
            hole == null -> null
            hole.center.y < hPx / 2 -> hole.bottom + pad + with(density) { 16.dp.toPx() }
            else -> null
        }
        val cardBottomGap = if (hole != null && hole.center.y >= hPx / 2) hPx - hole.top + pad + with(density) { 16.dp.toPx() } else null
        Box(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentAlignment = when { cardTop != null -> Alignment.TopCenter; cardBottomGap != null -> Alignment.BottomCenter; else -> Alignment.Center },
        ) {
            Column(
                Modifier
                    .then(if (cardTop != null) Modifier.offset { IntOffset(0, cardTop.roundToInt()) } else Modifier)
                    .then(if (cardBottomGap != null) Modifier.offset { IntOffset(0, -cardBottomGap.roundToInt()) } else Modifier)
                    .fillMaxWidth().raised(RoundedCornerShape(22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Tour.step + 1} / ${Tour.STEPS.size}", style = mono(11.sp, color = C.AmberText), modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tour.STEPS.indices.forEach { i -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (i <= Tour.step) C.Amber else Color.White.copy(alpha = 0.18f))) }
                    }
                }
                Text(step.title, style = display(20.sp))
                Text(step.text, style = body(14.sp, color = C.TextMid).copy(lineHeight = 20.sp))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!last) Text("Skip tour", style = body(14.sp, FontWeight.Bold, C.TextMid), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Tour.finish() }.padding(8.dp))
                    else Text("Later", style = body(14.sp, FontWeight.Bold, C.TextMid), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Tour.finish() }.padding(8.dp))
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.Amber)
                            .clickable { if (last) { Tour.finish(); nav.go(Route.QualitySetup) } else next() }.padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (last) "Set it up" else if (Tour.step == 0) "Start" else "Next", style = body(15.sp, FontWeight.Bold, C.OnAccent)) }
                }
            }
        }
    }
}
