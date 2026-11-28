package com.hassasin.music

import androidx.compose.ui.layout.onSizeChanged
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Route {
    data object Home : Route()
    data class Category(val kind: String) : Route()
    data class AlbumDetail(val key: String) : Route()
    data class Tracks(val title: String, val kind: String, val arg: String) : Route()
    data object Search : Route()
    data object Player : Route()
    data object Lyrics : Route()
    data object Queue : Route()
    data object Eq : Route()
    data object Earphones : Route()
    data object Output : Route()
    /** The Sound sheet; tab 0 EQ, 1 Bass, 2 Earphones, 3 Output. */
    data class Sound(val tab: Int = 0) : Route()
    data class FixArt(val albumKey: String? = null) : Route()
    data object Settings : Route()
    data object Folders : Route()
    data object Credits : Route()
    data object Playlists : Route()
    data object GeneratePlaylist : Route()
    data class AddSongs(val playlistId: Long) : Route()
    data class SettingsPage(val key: String) : Route()
    data object QualitySetup : Route()
    data class PlaylistDetail(val id: Long) : Route()
    data class EditTags(val trackId: Long) : Route()
}

/** Routes shown as a sheet sliding up over the screen below. */
val Route.isSheet: Boolean get() = this is Route.Lyrics || this is Route.Queue || this is Route.Eq || this is Route.Earphones || this is Route.Output || this is Route.Sound

class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val top get() = stack.last()
    /** The full screen under any open sheet. */
    val base get() = stack.lastOrNull { !it.isSheet } ?: Route.Home
    fun go(r: Route) { if (stack.last() != r) stack.add(r) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun tab(r: Route) { stack.clear(); stack.add(Route.Home); if (r != Route.Home) stack.add(r) }
}

/** Status bar height in pixels, for the top fade. */
private var WindowInsetsTop = 0f

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null
    private val nav = Nav()
    private var granted by mutableStateOf(false)
    /** When the volume keys last changed the DAC volume (for the on-screen readout). */
    private var volumeShownAt by mutableLongStateOf(0L)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r[audioPermission] == true || hasAudioPermission()
        if (granted) lifecycleScope.launch { AppState.scan() }
    }

    private val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() = checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = hasAudioPermission()
        if (granted) lifecycleScope.launch {
            AppState.scan()
            // A song is ready to resume: start on the player, with the library one tap away.
            if (savedInstanceState == null && Playback.state.value.current != null && nav.stack.size == 1) nav.go(Route.Player)
        }
        else {
            val perms = mutableListOf(audioPermission)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            askPermissions.launch(perms.toTypedArray())
        }
        // Connecting to the media service is what lets it show the notification and lock-screen controls.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        setContent { Root() }
        // New versions come from the app's GitHub releases (checked at most once an hour).
        Reports.installCrashHandler(this)
        Reports.onStart(applicationContext)
        lifecycleScope.launch { Updater.check(applicationContext) }
        PhoneDirectSession.checkAvailable()
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Playback.onDacMaybeChanged()
    }

    /** In USB Direct the phone's volume keys move the DAC's own volume, 1 dB per press. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (UsbLive.active && UsbLive.kind == "usb" && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.0 else -1.0
                Playback.setDacVolume(AppState.settings.value.dacVolumeDb + step)
                volumeShownAt = System.currentTimeMillis()
            }
            return true
        }
        // DVC: the volume keys move the app's own volume (Android's stays at maximum).
        if (Dvc.on() && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Dvc.setStep(Dvc.step() + if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                volumeShownAt = System.currentTimeMillis()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Root() {
        val settings by AppState.settings.collectAsState()
        Look.soft = settings.theme == "soft"
        Look.flow = settings.theme == "flow"
        Viz.enabled = settings.vizStyle != "off"
        // Back: from the library it goes to Now playing (when a song is loaded); from Now playing over the
        // library it leaves the app, so the two never bounce back and forth.
        val hasSong = Playback.state.collectAsState().value.current != null
        val playerOnLibrary = nav.stack.size == 2 && nav.stack.last() == Route.Player
        BackHandler(enabled = (nav.stack.size > 1 && !playerOnLibrary) || (nav.stack.size == 1 && hasSong)) {
            if (nav.stack.size == 1) nav.go(Route.Player) else nav.back()
        }
        // Changing the look rebuilds every surface with the new colours.
        key(settings.theme) {
            Box(Modifier.fillMaxSize().background(C.Ground)) {
                WindowInsetsTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toFloat() }
                if (!granted) PermissionScreen { askPermissions.launch(arrayOf(audioPermission)) }
                else {
                    val base = nav.base
                    val depth = nav.stack.count { !it.isSheet }
                    var lastDepth by remember { mutableIntStateOf(depth) }
                    val forward = depth >= lastDepth
                    SideEffect { lastDepth = depth }
                    // The mini player floats over the pages instead of taking space from them: pages keep the
                    // same size while it slides in or out, so nothing jumps after a screen change.
                    val density = LocalDensity.current
                    var miniHeight by remember { mutableIntStateOf(0) }
                    val hasSong = Playback.state.collectAsState().value.current != null
                    val miniPad = with(density) { (if (hasSong) miniHeight else 0).toDp() }
                    Box(Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = base,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val spring = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 380f)
                                when {
                                    // The player rises from the bottom and sinks back down.
                                    targetState == Route.Player -> (slideInVertically(spring) { it / 3 } + fadeIn(tween(260))) togetherWith fadeOut(tween(180))
                                    initialState == Route.Player -> fadeIn(tween(260)) togetherWith (slideOutVertically(spring) { it / 3 } + fadeOut(tween(200)))
                                    forward -> (slideInHorizontally(spring) { it / 4 } + fadeIn(tween(240))) togetherWith (slideOutHorizontally(spring) { -it / 8 } + fadeOut(tween(160)))
                                    else -> (slideInHorizontally(spring) { -it / 4 } + fadeIn(tween(240))) togetherWith (slideOutHorizontally(spring) { it / 8 } + fadeOut(tween(160)))
                                }
                            },
                            label = "screens",
                        ) { r ->
                            Box(
                                Modifier.fillMaxSize().padding(bottom = if (r == Route.Player) 0.dp else miniPad).drawWithContent {
                                    drawContent()
                                    if (r != Route.Player) {
                                        // Scrolled content fades out under the status bar and above the mini player.
                                        val top = WindowInsetsTop
                                        drawRect(Brush.verticalGradient(listOf(C.Ground, C.Ground.copy(alpha = 0.85f), Color.Transparent), startY = 0f, endY = top + 14.dp.toPx()), size = Size(size.width, top + 14.dp.toPx()))
                                        val h = 28.dp.toPx()
                                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, C.Ground), startY = size.height - h, endY = size.height), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                                    }
                                },
                            ) {
                                when (r) {
                                    Route.Home -> LibraryHub(nav)
                                    is Route.Category -> CategoryScreen(nav, r.kind)
                                    is Route.AlbumDetail -> AlbumScreen(nav, r.key)
                                    is Route.Tracks -> TracksScreen(nav, r)
                                    Route.Search -> SearchScreen(nav)
                                    Route.Player -> PlayerScreen(nav)
                                    is Route.FixArt -> FixArtScreen(nav, r.albumKey)
                                    Route.Settings -> SettingsScreen(nav)
                                    Route.Folders -> FoldersScreen(nav)
                                    Route.Credits -> CreditsScreen(nav)
                                    Route.Playlists -> PlaylistsScreen(nav)
                                    Route.GeneratePlaylist -> GeneratorScreen(nav)
                                    is Route.AddSongs -> AddSongsScreen(nav, r.playlistId)
                                    is Route.SettingsPage -> SettingsPageScreen(nav, r.key)
                                    is Route.PlaylistDetail -> PlaylistScreen(nav, r.id)
                                    is Route.EditTags -> TagEditorScreen(nav, r.trackId)
                                    Route.QualitySetup -> QualitySetupScreen(nav)
                                    else -> LibraryHub(nav)
                                }
                            }
                        }
                        AnimatedVisibility(
                            base != Route.Player, modifier = Modifier.align(Alignment.BottomCenter),
                            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)), exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
                        ) {
                            Box(Modifier.navigationBarsPadding().onSizeChanged { if (it.height > 0) miniHeight = it.height }) { MiniPlayer(nav) }
                        }
                    }
                    SheetHost(nav)
                    SongSheetHost(nav)
                    WhatsNew(nav)
                    AddToPlaylistDialog()
                    SetupNudge(nav)
                    TourOverlay(nav)
                    VolumeHud(volumeShownAt)
                    DonatePrompt()
                }
            }
        }
    }
}

/**
 * Lyrics, Queue and Sound open as the same sheet: it slides up over the screen below (which dims),
 * has a handle and a title, and closes with Back or by tapping above it.
 */
@Composable
private fun SheetHost(nav: Nav) {
    val top = nav.top
    var shown by remember { mutableStateOf<Route?>(null) }
    if (top.isSheet) shown = top
    val visible = top.isSheet
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { nav.back() })
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it },
        exit = slideOutVertically(tween(240)) { it },
    ) {
        val r = shown ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 28.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxSize().clip(shape)
                    .background(if (Look.soft) Brush.verticalGradient(listOf(Color(0xFF2B2D32), Color(0xFF232529))) else Brush.verticalGradient(listOf(Color(0xFF181A26), Color(0xFF0B0C13))))
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp, 5.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
                }
                Box(Modifier.weight(1f).navigationBarsPadding()) {
                    when (r) {
                        Route.Lyrics -> LyricsScreen(nav, sheet = true)
                        Route.Queue -> QueueScreen(nav, sheet = true)
                        Route.Eq -> SoundSheet(nav, 0)
                        Route.Earphones -> SoundSheet(nav, 4)
                        Route.Output -> SoundSheet(nav, 5)
                        is Route.Sound -> SoundSheet(nav, r.tab)
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(ask: () -> Unit) {
    Backdrop(C.Indigo) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("Hassasin Music", style = display(28.sp))
            Spacer(Modifier.height(12.dp))
            Text("To show your songs, allow access to music and audio on this phone.", style = body(16.sp, color = C.TextMid))
            Spacer(Modifier.height(24.dp))
            Box(Modifier.height(52.dp).amberOrb(RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).clickable(onClick = ask).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Text("Allow access", style = body(15.sp, FontWeight.Bold, C.OnAccent))
            }
        }
    }
}

@Composable
private fun VolumeHud(shownAt: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shownAt) { if (shownAt > 0) { visible = true; delay(1500); visible = false } }
    val s by AppState.settings.collectAsState()
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.glass(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Dvc.on()) {
                    Text("Volume", style = body(13.sp, FontWeight.Bold, C.TextHi))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(120.dp).height(6.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(s.dvcStep / Dvc.STEPS.toFloat()).height(6.dp).background(C.Amber, RoundedCornerShape(3.dp)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(Dvc.label(), style = mono(13.sp, FontWeight.Bold, C.AmberLight))
                } else {
                    Ico(I.Plug, C.AmberText, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("DAC ${"%.1f".format(s.dacVolumeDb)} dB", style = mono(14.sp, FontWeight.Bold, C.AmberLight))
                    if (s.dacVolumeDb >= s.dacMaxDb) Text("  · at your limit", style = body(12.sp, color = C.Limit))
                }
            }
        }
    }
}

/** Once after each update: what changed in this version. */
@Composable
private fun WhatsNew(nav: Nav) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var notes by remember { mutableStateOf(Changelog.pending(context)) }
    androidx.compose.runtime.SideEffect { Changelog.showing = notes != null }
    val n = notes ?: return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { notes = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).fillMaxWidth().raised(RoundedCornerShape(24.dp)).padding(22.dp)) {
            Text("What's new", style = display(22.sp))
            Text("Hassasin Music ${n.first}", style = mono(12.sp, color = C.AmberText))
            Spacer(Modifier.height(12.dp))
            n.second.forEach { line ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).background(C.Amber, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = body(14.sp).copy(lineHeight = 20.sp))
                }
            }
            Spacer(Modifier.height(14.dp))
            // Asked once: may the app send anonymous reports, so problems can be fixed without asking?
            val s by AppState.settings.collectAsState()
            val reportScope = androidx.compose.runtime.rememberCoroutineScope()
            var sentNote by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().recessed(RoundedCornerShape(16.dp)).padding(12.dp)) {
                Text("Help improve Hassasin Music", style = body(14.sp, FontWeight.Bold))
                Text("Send anonymous reports (phone model, Android version, output formats, errors). Never song names, file paths or accounts.", style = body(12.sp, color = C.TextMid).copy(lineHeight = 17.sp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (s.helpImprove == 1) "Allowed ✓" else "Allow", s.helpImprove == 1) { AppState.update { it.copy(helpImprove = 1) } }
                    Chip("No thanks", s.helpImprove == 2) { AppState.update { it.copy(helpImprove = 2) } }
                    Chip(sentNote.ifBlank { "Send a report" }, false) {
                        if (!Reports.canSend()) OutputChain.share(context, OutputChain.report(context))
                        else { sentNote = "Sending…"; reportScope.launch { sentNote = if (Reports.send(context, "manual", null)) "Sent ✓" else "Not sent" } }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                Chip("Take the tour", false) { notes = null; nav.tab(Route.Player); Tour.start() }
                Chip("Best sound setup", false) { notes = null; nav.go(Route.QualitySetup) }
            }
            Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.Amber).clickable { notes = null }, contentAlignment = Alignment.Center) {
                Text("Got it", style = body(15.sp, FontWeight.Bold, C.OnAccent))
            }
        }
    }
}

@Composable
private fun MiniPlayer(nav: Nav) {
    val st by Playback.state.collectAsState()
    val t = st.current ?: return
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .glass(shape)
                .then(if (Look.soft) Modifier else Modifier.background(Brush.verticalGradient(listOf(Color(0x99282450), Color(0x99121224))), shape))
                .clip(shape)
                .clickable { nav.go(Route.Player) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtBox(albumOf(t), 48.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (st.usb) StatusDot("USB Direct · ${t.formatLabel.substringAfter(' ', t.formatLabel)}")
                else Text(t.artist, style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(48.dp).amberOrb().clip(CircleShape).clickable { Playback.toggle() }
                    .semantics { contentDescription = if (st.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) { Ico(if (st.playing) I.Pause else I.Play, C.OnAccent, 20.dp, filled = true) }
        }
    }
}
