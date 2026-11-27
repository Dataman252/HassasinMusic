package com.hassasin.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The lock-screen player: when the screen turns off while music plays, the player (in the chosen
 * look) is placed over the lock screen. Swipe up to reach Android's own lock screen.
 *
 * Android only lets a background app open a screen like this through a "full-screen" notification,
 * so one is posted silently and removed again as soon as the player is up.
 */
object LockScreen {
    private const val TAG = "LockScreen"
    private const val CHANNEL = "lockscreen"
    private const val NOTE_ID = 7
    private var registered = false
    var showing: WeakReference<LockActivity>? = null

    fun canShow(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true

    fun askPermission(context: Context) {
        val i = if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Listens for the screen turning off (only possible while the app runs, which it does while playing). */
    fun register(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) { Power.screenOn = true; return }
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                Power.screenOn = false
                val playing = runCatching { Playback.player.isPlaying }.getOrDefault(false)
                if (!playing) { showing?.get()?.finish(); return }
                if (AppState.settings.value.lockScreen && showing?.get() == null) show(app)
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(receiver, filter)
    }

    private fun show(context: Context) {
        val intent = Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pi = PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lock screen player", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the player over the lock screen. Silent; removed at once."
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        }
        if (!canShow(context)) { Log.i(TAG, "full-screen permission missing: lock-screen player not shown"); return }
        val note = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hassasin)
            .setContentTitle(Playback.state.value.current?.title ?: "Hassasin Music")
            .setCategory(android.app.Notification.CATEGORY_TRANSPORT)
            .setFullScreenIntent(pi, true)
            .setOngoing(false)
            .setTimeoutAfter(4000)
            .build()
        runCatching { nm.notify(NOTE_ID, note) }.onFailure { Log.w(TAG, "notify failed", it) }
        Handler(Looper.getMainLooper()).postDelayed({ nm.cancel(NOTE_ID) }, 3000)
    }

    fun shown(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
    }
}

class LockActivity : ComponentActivity() {
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)
        enableEdgeToEdge()
        LockScreen.showing = WeakReference(this)
        LockScreen.shown(this)
        // Unlocked with a fingerprint or face: step aside.
        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(unlocked, f)
        setContent {
            val settings by AppState.settings.collectAsState()
            Look.soft = settings.theme == "soft"
            Look.flow = settings.theme == "flow"
            Viz.enabled = settings.vizStyle != "off"
            LockPlayer { finish() }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        if (LockScreen.showing?.get() === this) LockScreen.showing = null
        super.onDestroy()
    }
}

/** The player over the lock screen: clock, cover, song, quality, seek bar with controls. */
@Composable
fun LockPlayer(onClose: () -> Unit) {
    val st by Playback.state.collectAsState()
    val settings by AppState.settings.collectAsState()
    val context = LocalContext.current
    val t = st.current
    val album = albumOf(t)
    val art = rememberArtBitmap(album)
    val glowTarget = remember(art) { art?.let { glowColor(it) } ?: Look.tint }
    val glowAnim = animateColorAsState(glowTarget, tween(700), label = "lock glow")
    val glow = glowAnim.value
    SideEffect { Look.tint = glow }
    var drag by remember { mutableFloatStateOf(0f) }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    Box(
        Modifier.fillMaxSize().background(C.Ground)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag < -size.height * 0.18f) onClose() else drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag = (drag + d).coerceAtMost(0f) }
            }
            .graphicsLayer { translationY = drag * 0.6f; alpha = (1f + drag / (size.height * 0.6f)).coerceIn(0.2f, 1f) },
    ) {
        // Background in the chosen look.
        when {
            Look.flow -> {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(lerpColor(glow, Color.Black, 0.35f), lerpColor(glow, Color.Black, 0.55f), lerpColor(glow, Color.Black, 0.8f)))))
                if (art != null) Image(
                    art.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(520.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0.4f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) },
                )
            }
            else -> ImmersiveBackground(art) { glowAnim.value }
        }
        Visualizer(settings.vizStyle, (if (Look.flow) lerpColor(glow, Color.White, 0.25f) else C.Amber).copy(alpha = 0.5f), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Text(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)), style = display(64.sp, FontWeight.Medium, Color.White).copy(letterSpacing = (-1).sp))
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)), style = body(14.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.8f)))
            if (t == null) { Spacer(Modifier.weight(1f)); return@Column }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                when {
                    Look.flow -> Unit // the cover is the background
                    Look.soft -> SoftDisc(art, side * 0.92f, st.playing) {}
                    else -> GlowCover(art, { glowAnim.value }, side * 0.92f) {}
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val title = ArtRepo.cleanSongTitle(t.title).ifBlank { t.title }
                Text(if (Look.flow) title.uppercase() else title, style = display(if (Look.flow) 28.sp else 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(t.artist.ifBlank { "Unknown artist" }, style = body(15.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.88f)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                QualityLine(t)
            }
            Spacer(Modifier.height(10.dp))
            val peaks by produceState<FloatArray?>(null, t.id) { value = Waveform.peaks(context, t) }
            SeekZone(
                settings.seekStyle, peaks, if (st.durationMs > 0) st.positionMs.toFloat() / st.durationMs else 0f, st.playing,
                st.positionMs, st.durationMs, { Playback.seekTo((it * st.durationMs).toLong()) },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (Look.flow) {
                        OutlineRound(I.Prev, "Previous", 52.dp, Color.White) { Playback.previous() }
                        Box(
                            Modifier.size(76.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape).background(Color.White.copy(alpha = 0.06f))
                                .clickable { Playback.toggle() }.semantics { contentDescription = if (st.playing) "Pause" else "Play" },
                            contentAlignment = Alignment.Center,
                        ) { Ico(if (st.playing) I.Pause else I.Play, Color.White, 30.dp, filled = true) }
                        OutlineRound(I.Next, "Next", 52.dp, Color.White) { Playback.next() }
                    } else {
                        RoundButton(I.Prev, "Previous", { Playback.previous() }, 56.dp)
                        Box(
                            Modifier.size(76.dp).amberOrb().clip(CircleShape).clickable { Playback.toggle() }.semantics { contentDescription = if (st.playing) "Pause" else "Play" },
                            contentAlignment = Alignment.Center,
                        ) { Ico(if (st.playing) I.Pause else I.Play, C.OnAccent, 30.dp, filled = true) }
                        RoundButton(I.Next, "Next", { Playback.next() }, 56.dp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.clip(CircleShape).clickable { onClose() }.padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = "Close the player (or swipe up)" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ico(I.Up, Color.White.copy(alpha = 0.7f), 18.dp)
                Text("Swipe up to unlock", style = body(12.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.7f)), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
