package com.hassasin.music

import androidx.compose.ui.graphics.drawscope.clipPath

import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.zIndex

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.Image
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerScreen(nav: Nav) {
    val st by Playback.state.collectAsState()
    val settings by AppState.settings.collectAsState()
    val stats by AppState.stats.collectAsState()
    val context = LocalContext.current
    val t = st.current
    val album = albumOf(t)
    val art = rememberArtBitmap(album)
    val glowTarget = remember(art) { art?.let { glowColor(it) } ?: Look.tint }
    val glowAnim = animateColorAsState(glowTarget, tween(700), label = "glow")
    // While a cover is swiped (or glides to the next song) the colour moves with it, frame by frame.
    val glow = remember(glowAnim) { { CoverSwipe.colorAt() ?: glowAnim.value } }
    LaunchedEffect(glow) { androidx.compose.runtime.snapshotFlow { glow() }.collect { Look.tint = it } }
    LaunchedEffect(t != null) { if (t != null && !AppState.settings.value.tourDone && !Tour.active) { kotlinx.coroutines.delay(900); if (!Changelog.showing) Tour.start() } }
    if (Look.flow && t != null) return FlowPlayer(nav, t, st, settings, stats[t.id]?.rating ?: 0, glow, glowTarget)
    Box(Modifier.fillMaxSize().playerSwipes(nav)) {
        ImmersiveBackground(art, glow)
        Visualizer(settings.vizStyle, C.Amber.copy(alpha = 0.55f), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
            if (t == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Pick a song in your library to start.", style = body(15.sp, color = C.TextMid))
                }
                return@Column
            }
            // 2. The cover, as large as the free space allows; it cross-fades between songs.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                // Swipe the cover sideways to move to the next or previous song.
                CoverPager(st) { song, isCurrent ->
                    val pageAlbum = albumOf(song)
                    val bmp = if (isCurrent) art else rememberArtBitmap(pageAlbum, 700)
                    CoverSwipe.Register(song, bmp)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (Look.soft) SoftDisc(bmp, side, st.playing && isCurrent) { nav.go(Route.FixArt(pageAlbum?.key ?: song.albumKey)) }
                        else GlowCover(bmp, glow, side) { nav.go(Route.FixArt(pageAlbum?.key ?: song.albumKey)) }
                    }
                }
            }
            // 3. What is playing, centred, with Library and More within thumb reach on either side.
            SongName(nav, t, album, stats[t.id]?.rating ?: 0, st, settings, flow = false)
            Spacer(Modifier.height(14.dp))
            // 4 + 5. The seek bar behind the play controls (style chosen in Settings).
            val peaks by produceState<FloatArray?>(null, t.id) { value = Waveform.peaks(context, t) }
            SeekZone(
                settings.seekStyle, peaks, if (st.durationMs > 0) st.positionMs.toFloat() / st.durationMs else 0f, st.playing,
                st.positionMs, st.durationMs, { Playback.seekTo((it * st.durationMs).toLong()) },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PlainIconButton(I.Shuffle, "Shuffle ${if (st.shuffle) "on" else "off"}", { Playback.setShuffle(!st.shuffle) }, if (st.shuffle) C.Amber else C.TextMid)
                    RoundButton(I.Prev, "Previous", { Playback.previous() }, 56.dp)
                    Box(
                        Modifier.size(76.dp).amberOrb().clip(CircleShape).clickable { Playback.toggle() }.semantics { contentDescription = if (st.playing) "Pause" else "Play" },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.Crossfade(st.playing, animationSpec = tween(180), label = "play") { playing ->
                            Ico(if (playing) I.Pause else I.Play, C.OnAccent, 30.dp, filled = true)
                        }
                    }
                    RoundButton(I.Next, "Next", { Playback.next() }, 56.dp)
                    Box(contentAlignment = Alignment.Center) {
                        PlainIconButton(I.Repeat, when (st.repeat) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" }, { Playback.cycleRepeat() }, if (st.repeat > 0) C.Amber else C.TextMid)
                        if (st.repeat == 2) Text("1", style = mono(9.sp, FontWeight.Bold, C.Amber), modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // 6. The three tools, each opening the same kind of sheet.
            ToolBar(nav, settings, st)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
internal fun GlowCover(bmp: android.graphics.Bitmap?, glow: () -> Color, side: androidx.compose.ui.unit.Dp, onLongPress: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.size(side)
            .graphicsLayer {
                // The coloured glow, read at draw time: its fade between songs costs no rebuild.
                shadowElevation = 40.dp.toPx(); this.shape = shape; clip = false
                ambientShadowColor = glow(); spotShadowColor = glow()
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF101226))))
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .semantics { contentDescription = "Cover. Long-press to change the picture" },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Ico(I.Note, C.TextLow, side * 0.25f)
        Box(Modifier.fillMaxSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape))
    }
}

/** Soft theme: the cover as a disc set in a raised ring; it turns slowly while music plays. */
@Composable
internal fun SoftDisc(bmp: android.graphics.Bitmap?, side: androidx.compose.ui.unit.Dp, playing: Boolean, onLongPress: () -> Unit) {
    val d = side * 0.98f
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "disc")
    val angle by spin.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(24000, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    var frozen by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (playing) frozen = angle
    Box(Modifier.size(d).softRaised(CircleShape, 22.dp, 10.dp).background(C.Ground, CircleShape), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(d * 0.88f).clip(CircleShape)
                .background(Color(0xFF1C1D21))
                .border(6.dp, Color(0xFF1A1B1F), CircleShape)
                .graphicsLayer { rotationZ = frozen }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
                .semantics { contentDescription = "Cover. Long-press to change the picture" },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Ico(I.Note, C.TextLow, d * 0.25f)
        }
        // The centre hole of the record.
        Box(Modifier.size(d * 0.07f).background(C.Ground, CircleShape).border(2.dp, Color.Black.copy(alpha = 0.4f), CircleShape))
    }
}

/**
 * The song, centred: Library (left) and More (right) sit beside the title where the thumb reaches;
 * under it the sound path and the quality strip.
 */
@Composable
private fun SongName(nav: Nav, t: Track, album: Album?, rating: Int, st: PlayState, settings: AppSettings, flow: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassCircle(I.Down, "Library") { nav.tab(Route.Home) }
            androidx.compose.animation.AnimatedContent(
                targetState = t,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 4 }) togetherWith fadeOut(tween(150)) },
                contentAlignment = Alignment.Center,
                label = "song name",
            ) { song ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val title = ArtRepo.cleanSongTitle(song.title).ifBlank { song.title }
                    Text(
                        if (flow) title.uppercase() else title,
                        style = if (flow) display(26.sp).copy(lineHeight = 30.sp, letterSpacing = 0.sp) else display(22.sp),
                        maxLines = if (flow) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        song.artist.ifBlank { "Unknown artist" },
                        style = body(if (flow) 16.sp else 15.sp, FontWeight.SemiBold, if (song.artist.isBlank()) C.TextLow else Color.White.copy(alpha = 0.88f)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    val meta = listOf(song.album.takeIf { it.isNotBlank() }, song.year.takeIf { it > 0 }?.toString()).filterNotNull()
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            MoreMenu(nav, t, album, settings, rating)
        }
        // The sound path, then the quality strip, centred under the name.
        Box(Modifier.padding(top = 8.dp).tourTarget("path")) { SignalPill(st, settings, compact = true) { nav.go(Route.Sound(5)) } }
        Box(Modifier.tourTarget("quality")) { QualityLine(t) }
    }
}

/** Less-used actions for the song, kept out of the way. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MoreMenu(nav: Nav, t: Track?, album: Album?, s: AppSettings, rating: Int = 0) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.tourTarget("more")) {
        GlassCircle(I.More, "More") { open = true }
        DropdownMenu(open, { open = false }, containerColor = if (Look.soft) Color(0xFF2B2D32) else Color(0xFF171925)) {
            fun item(label: String, go: () -> Unit) = @Composable { DropdownMenuItem(text = { Text(label, style = body(14.sp)) }, onClick = { open = false; go() }) }
            if (t != null) {
                Text("RATE", style = eyebrow(), modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                Row(Modifier.padding(horizontal = 8.dp)) {
                    (1..5).forEach { n ->
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { AppState.rate(t, if (rating == n) 0 else n) }.semantics { contentDescription = "$n stars" }, contentAlignment = Alignment.Center) {
                            Ico(I.Star, if (n <= rating) C.Amber else C.TextMid, 22.dp, filled = n <= rating)
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                item("Go to album") { nav.go(Route.AlbumDetail(t.albumKey)) }()
                if (t.artist.isNotBlank()) item("Go to artist") { LyricsRepo.mainArtist(t.artist).let { a -> nav.go(Route.Tracks(a, "artist", a)) } }()
                item("Change the picture") { nav.go(Route.FixArt(album?.key ?: t.albumKey)) }()
            }
            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            // Speed (Android output; the direct outputs always play at 1×) and the sleep timer.
            Text("SPEED", style = eyebrow(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 12.dp).width(280.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Speed.CHOICES.forEach { sp ->
                    Chip(if (sp == 1f) "1×" else "${sp}×", kotlin.math.abs(s.speed - sp) < 0.01f) { AppState.update { it.copy(speed = sp) } }
                }
            }
            DropdownMenuItem(
                text = { Text(if (s.keepPitch) "Pitch stays the same" else "Pitch follows speed", style = body(14.sp)) },
                trailingIcon = { if (s.keepPitch) Ico(I.Check, C.Amber, 18.dp) },
                onClick = { AppState.update { it.copy(keepPitch = !it.keepPitch) } },
            )
            Text("SLEEP TIMER · ${SleepTimer.label()}", style = eyebrow(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 12.dp).width(280.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { m -> Chip("$m min", false) { SleepTimer.set(m); open = false } }
                Chip("End of song", SleepTimer.endOfSong) { SleepTimer.setEndOfSong(); open = false }
                if (SleepTimer.active()) Chip("Cancel", false) { SleepTimer.cancel(); open = false }
            }
            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            if (t != null) item("Edit tags") { nav.go(Route.EditTags(t.id)) }()
            item("Playlists") { nav.go(Route.Playlists) }()
            Text("LOOK", style = eyebrow(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
            listOf("glow" to "Glow", "soft" to "Soft", "flow" to "Flow").forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = body(14.sp, if (s.theme == key) FontWeight.Bold else FontWeight.Normal, if (s.theme == key) C.AmberText else C.TextHi)) },
                    trailingIcon = { if (s.theme == key) Ico(I.Check, C.Amber, 18.dp) },
                    onClick = { open = false; AppState.update { it.copy(theme = key) } },
                )
            }
            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            item("Settings") { nav.go(Route.Settings) }()
            item("Credits") { nav.go(Route.Credits) }()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            item("Share the app") { ShareApp.share(ctx) }()
        }
    }
}

/** Lyrics · Sound · Queue: one bar, each part showing its current state, each opening a sheet. */
@Composable
private fun ToolBar(nav: Nav, s: AppSettings, st: PlayState) {
    val sound = s.sound
    val soundState = when {
        !sound.eqOn && !(sound.autoEqOn && sound.autoEq != null) -> "Off"
        sound.eqOn && sound.bassStyle != "Off" -> sound.bassStyle
        sound.eqOn -> sound.preset
        else -> sound.autoEq!!.name.substringBefore(" (")
    }
    val next = (st.queue.size - st.index - 1).coerceAtLeast(0)
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(60.dp)
            .then(if (Look.soft) Modifier.raised(shape, 8.dp) else Modifier.clip(shape).background(Color.Black.copy(alpha = 0.30f)).border(1.dp, Color.White.copy(alpha = 0.10f), shape))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        listOf(
            Triple("Lyrics", I.Lyrics, if (s.lyricsHinglish) "Hinglish" else "Synced") to { nav.go(Route.Lyrics) },
            Triple("Sound", I.Eq, soundState) to { nav.go(Route.Sound(0)) },
            Triple("Queue", I.Queue, if (next == 0) "Last song" else "$next next") to { nav.go(Route.Queue) },
        ).forEach { (info, go) ->
            val (label, icon, value) = info
            Row(
                Modifier.weight(1f).fillMaxSize().tourTarget(label.lowercase()).clip(RoundedCornerShape(17.dp)).clickable(onClick = go).semantics { contentDescription = "$label: $value" },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(icon, C.TextHi, 18.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label, style = body(12.sp, FontWeight.Bold, C.TextHi))
                    Text(value, style = body(10.sp, FontWeight.SemiBold, if (value == "Off") C.TextLow else C.AmberText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun LyricsScreen(nav: Nav, sheet: Boolean = false) {
    val st by Playback.state.collectAsState()
    val settings by AppState.settings.collectAsState()
    val context = LocalContext.current
    val t = st.current
    var reload by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val lyrics by produceState<Lyrics?>(null, t?.id, reload) {
        value = null
        value = t?.let { LyricsRepo.find(context, it, settings.autoLyrics && AppState.online(), force = false) }
        loadedFor = t?.id ?: -1
    }
    var offset by remember(t?.id) { mutableLongStateOf(t?.let { LyricsRepo.offsetMs(it) } ?: 0L) }
    val shown = lyrics
    // The player state moves 4 times a second; lyrics read the exact position 20 times a second.
    var livePos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { livePos = Playback.positionNow(); kotlinx.coroutines.delay(50) } }
    var tapping by remember(t?.id) { mutableStateOf(false) }
    ScreenShell(sheet, Color(0xFF2A1F5C)) {
        Column(Modifier.fillMaxSize().then(if (sheet) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sheet) { RoundButton(I.Down, "Close lyrics", { nav.back() }); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: "Lyrics", style = body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        shown?.let {
                            when {
                                it.synced != null -> "Synced · from ${it.source}"
                                !it.plain.isNullOrBlank() -> "Not timed yet · from ${it.source}"
                                else -> "From ${it.source}"
                            }
                        } ?: t?.artist ?: "",
                        style = body(12.sp, color = C.TextMid), maxLines = 1,
                    )
                }
                if (Hinglish.canConvert(lyrics?.plain)) {
                    val hing = settings.lyricsHinglish
                    val letter = when (Hinglish.scriptName(lyrics?.plain)) {
                        "Bengali" -> "অ"; "Punjabi" -> "ਅ"; "Gujarati" -> "અ"; "Odia" -> "ଅ"; "Tamil" -> "அ"
                        "Telugu" -> "అ"; "Kannada" -> "ಅ"; "Malayalam" -> "അ"; "Urdu" -> "ا"; else -> "अ"
                    }
                    Box(
                        Modifier.height(40.dp).then(if (hing) Modifier.amberSelected(RoundedCornerShape(20.dp)) else Modifier.raised(RoundedCornerShape(20.dp), 4.dp))
                            .clip(RoundedCornerShape(20.dp)).clickable { AppState.update { it.copy(lyricsHinglish = !hing) } }
                            .padding(horizontal = 14.dp).semantics { contentDescription = if (hing) "Showing Hinglish. Tap for the original script" else "Showing the original script. Tap for Hinglish" },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (hing) "Hinglish" else "$letter \u2192 Aa", style = body(13.sp, FontWeight.Bold, if (hing) C.AmberText else C.TextHi)) }
                    Spacer(Modifier.width(8.dp))
                }
                RoundButton(I.Search, "Search lyrics", { searching = !searching })
            }
            if (searching && t != null) {
                LyricsSearch(t) { searching = false; reload++ }
                return@Column
            }
            val plainText = lyrics?.plain
            if (tapping && t != null && !plainText.isNullOrBlank()) {
                TapSync(t, plainText, st.playing, shown?.synced) { saved -> tapping = false; if (saved) reload++ }
                return@Column
            }
            val l = shown
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    t == null -> Text("Nothing playing.", style = body(15.sp, color = C.TextMid), modifier = Modifier.align(Alignment.Center))
                    loadedFor != t.id -> Text("Looking for lyrics…", style = body(15.sp, color = C.TextMid), modifier = Modifier.align(Alignment.Center))
                    l == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No lyrics found", style = display(16.sp, FontWeight.Medium))
                        Spacer(Modifier.height(8.dp))
                        Text(if (AppState.online()) "Search by hand with the button above." else "You're offline (or on mobile data with Wi-Fi only on).", style = body(14.sp, color = C.TextMid), textAlign = TextAlign.Center)
                    }
                    l.instrumental && l.synced.isNullOrEmpty() && l.plain.isNullOrBlank() -> Text("Instrumental", style = display(18.sp, FontWeight.Medium, C.TextMid), modifier = Modifier.align(Alignment.Center))
                    l.synced != null -> SyncedLines(if (settings.lyricsHinglish) remember(l) { l.synced.map { it.copy(text = Hinglish.convert(it.text)) } } else l.synced, livePos + offset) { Playback.seekTo(it - offset) }
                    else -> LazyColumn(contentPadding = PaddingValues(24.dp)) { item { Text((l.plain ?: "").let { if (settings.lyricsHinglish) Hinglish.convert(it) else it }, style = body(20.sp, FontWeight.SemiBold, C.TextHi).copy(lineHeight = 32.sp)) } }
                }
            }
            // Lyrics without timing: one pass of tapping makes them scroll in sync from then on.
            if (t != null && shown != null && shown.synced == null && !shown.plain.isNullOrBlank() && !shown.instrumental) Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("No timed lyrics online", style = body(13.sp, FontWeight.Bold))
                    Text("Tap once as each line starts; they stay in sync after that.", style = body(11.sp, color = C.TextMid))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Tap to sync", style = body(13.sp, FontWeight.Bold, C.OnAccent),
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(C.Amber).clickable { tapping = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { contentDescription = "Time the lines yourself by tapping as each one starts" },
                )
            }
            // Timed lyrics that start at the wrong moment (another cut of the song, an intro of a different
            // length): the spacing is right, so one tap when the first line is sung lines up every line.
            if (t != null && shown?.synced != null) {
                val first = shown.synced.firstOrNull { it.text.isNotBlank() }
                val otherCut = shown.source == LyricsRepo.OTHER_VERSION
                if (first != null) Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp)
                        .then(if (otherCut) Modifier.clip(RoundedCornerShape(14.dp)).background(C.Amber.copy(alpha = 0.12f)).padding(10.dp) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (otherCut) "Timed for another version of this song" else "Lines early or late?", style = body(13.sp, FontWeight.Bold))
                        Text("Tap when you hear: \u201C${first.text.take(40)}\u201D", style = body(11.sp, color = C.TextMid), maxLines = 2)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "First line now", style = body(13.sp, FontWeight.Bold, C.OnAccent),
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(C.Amber)
                            .clickable {
                                // The first line belongs where the tap was (less the usual reaction time); every line moves with it.
                                offset = first.timeMs - (Playback.positionNow() - LyricSync.REACTION_MS)
                                LyricsRepo.setOffset(t, offset)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .semantics { contentDescription = "Tap when the first line is sung; all lines move to match" },
                    )
                }
            }
            if (t != null && shown?.synced != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Timing", style = body(13.sp, FontWeight.Bold))
                Spacer(Modifier.width(10.dp))
                if (!shown.plain.isNullOrBlank()) Text(
                    "Re-tap", style = body(13.sp, FontWeight.Bold, C.TextHi),
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).raised(RoundedCornerShape(18.dp), 4.dp)
                        .clickable { tapping = true }.padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Time the lines again by tapping as each one starts" },
                )
                Spacer(Modifier.weight(1f))
                RoundButton(I.Minus, "Lyrics earlier", { offset -= 250; LyricsRepo.setOffset(t, offset) }, 40.dp)
                Text("%+.2f s".format(offset / 1000.0), style = mono(13.sp, FontWeight.Bold, C.AmberText), modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
                RoundButton(I.Plus, "Lyrics later", { offset += 250; LyricsRepo.setOffset(t, offset) }, 40.dp)
            }
        }
    }
}

/**
 * Timing lyrics by hand. Play the song and tap a lyric line the moment it is sung: that line is pinned
 * to that moment (any line, in any order). Pins sit on a real waveform of the song and can be dragged;
 * the timeline scrubs; play/pause, ±5 s and the exact time are always at hand. Lines not pinned yet are
 * placed evenly between their pinned neighbours when saving. Timed lyrics open with their pins in place.
 */
@Composable
private fun TapSync(t: Track, plain: String, playing: Boolean, existing: List<LyricLine>?, done: (saved: Boolean) -> Unit) {
    val lines = remember(plain) { LyricSync.lines(plain) }
    var times by remember(lines) {
        val have = existing?.filter { it.text.isNotBlank() }?.takeIf { it.size == lines.size }?.map { it.timeMs }
        mutableStateOf<List<Long?>>(have ?: List(lines.size) { null })
    }
    var selected by remember { mutableIntStateOf(-1) }
    val list = rememberLazyListState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val peaks by produceState<FloatArray?>(null, t.id) { value = Waveform.fine(t) }
    var pos by remember { mutableLongStateOf(Playback.positionNow()) }
    LaunchedEffect(Unit) { while (true) { pos = Playback.positionNow(); kotlinx.coroutines.delay(33) } }
    val nextUnpinned = times.indexOfFirst { it == null }
    val pinnedCount = times.count { it != null }
    // The line being sung now (by the pins), so the list can follow along.
    val singing = times.indices.lastOrNull { i -> times[i]?.let { it <= pos } == true } ?: -1
    LaunchedEffect(singing, nextUnpinned) {
        val focus = if (singing >= 0) singing else nextUnpinned
        if (focus >= 0) list.animateScrollToItem((focus - 2).coerceAtLeast(0))
    }
    fun pin(i: Int, at: Long = Playback.positionNow() - LyricSync.REACTION_MS) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        times = times.toMutableList().also { it[i] = at.coerceIn(0, t.durationMs) }
        selected = i
    }
    fun move(i: Int, ms: Long) { times = times.toMutableList().also { it[i] = ms.coerceIn(0, t.durationMs) } }
    fun save() {
        val filled = LyricSync.fillGaps(times, t.durationMs)
        LyricsRepo.choose(t, LyricSync.toLrc(lines.mapIndexed { i, s -> LyricLine(filled[i], s) }), plain, false, "Tapped")
        done(true)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Play the song and tap a line when it is sung. Drag pins on the waveform to fine-tune.",
            style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            itemsIndexed(lines) { i, line ->
                val at = times[i]
                val sel = i == selected
                val now = i == singing
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                sel -> C.Amber.copy(alpha = 0.18f)
                                now -> Color.White.copy(alpha = 0.06f)
                                else -> Color.Transparent
                            },
                        )
                        .clickable { pin(i) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Line ${i + 1}: $line. Tap to pin it at the current moment" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(width = 62.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (at != null) C.Amber.copy(alpha = if (sel) 1f else 0.25f) else Color.White.copy(alpha = 0.06f))
                            // Tap the time to hear the line from just before its pin.
                            .clickable(enabled = at != null) { selected = i; Playback.seekTo(((at ?: 0) - 1500).coerceAtLeast(0)); if (!playing) Playback.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(at?.let { fmtTimeMs(it) } ?: "${i + 1}", style = mono(11.sp, FontWeight.Bold, if (at != null && sel) C.OnAccent else if (at != null) C.AmberText else C.TextLow))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Hinglish.takeIf { AppState.settings.value.lyricsHinglish }?.convert(line) ?: line,
                        style = body(if (now) 19.sp else 17.sp, if (now || sel) FontWeight.Bold else FontWeight.SemiBold, if (now) C.AmberLight else if (at != null) C.TextHi else C.TextMid),
                    )
                }
            }
        }
        SyncTimeline(
            peaks, t.durationMs, pos, times, selected,
            onSeek = { Playback.seekTo(it) }, onSelect = { selected = it }, onMove = { i, ms -> move(i, ms) },
        )
        // Transport: back 5 s, play/pause, forward 5 s, and the exact time.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${fmtTimeMs(pos)} / ${fmtTime(t.durationMs)}", style = mono(13.sp, FontWeight.Bold, C.AmberText), modifier = Modifier.width(118.dp))
            Spacer(Modifier.weight(1f))
            Text("−5 s", style = body(13.sp, FontWeight.Bold), modifier = Modifier.clip(RoundedCornerShape(16.dp)).raised(RoundedCornerShape(16.dp), 3.dp).clickable { Playback.seekTo((Playback.positionNow() - 5000).coerceAtLeast(0)) }.padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(52.dp).amberOrb().clip(CircleShape).clickable { Playback.toggle() }.semantics { contentDescription = if (playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) { Ico(if (playing) I.Pause else I.Play, C.OnAccent, 24.dp, filled = true) }
            Spacer(Modifier.width(10.dp))
            Text("+5 s", style = body(13.sp, FontWeight.Bold), modifier = Modifier.clip(RoundedCornerShape(16.dp)).raised(RoundedCornerShape(16.dp), 3.dp).clickable { Playback.seekTo(Playback.positionNow() + 5000) }.padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(118.dp))
        }
        // Fine-tune the selected line.
        if (selected in times.indices && times[selected] != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("Line ${selected + 1}", style = body(12.sp, FontWeight.Bold, C.AmberText))
            Spacer(Modifier.width(8.dp))
            listOf(-100L to "−0.1 s", 100L to "+0.1 s").forEach { (d, label) ->
                Text(label, style = body(12.sp, FontWeight.Bold), modifier = Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(14.dp)).raised(RoundedCornerShape(14.dp), 3.dp).clickable { times[selected]?.let { move(selected, it + d) } }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Text("Remove pin", style = body(12.sp, FontWeight.Bold, C.TextMid), modifier = Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(14.dp)).raised(RoundedCornerShape(14.dp), 3.dp).clickable { times = times.toMutableList().also { it[selected] = null } }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(I.Close, "Cancel", { done(false) }, 44.dp)
            // The next line without a pin, for tapping along in order.
            if (nextUnpinned >= 0) Box(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).raised(RoundedCornerShape(25.dp), 4.dp)
                    .clickable { if (!playing && pinnedCount == 0) { Playback.seekTo(0); Playback.toggle() } else pin(nextUnpinned) },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (!playing && pinnedCount == 0) "Start the song" else "Pin line ${nextUnpinned + 1} now", style = body(14.sp, FontWeight.Bold))
            }
            Box(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(if (pinnedCount > 0) C.Amber else C.Amber.copy(alpha = 0.3f))
                    .clickable(enabled = pinnedCount > 0) { save() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (nextUnpinned < 0) "Save timing" else "Save ($pinnedCount/${lines.size})", style = body(14.sp, FontWeight.Bold, C.OnAccent))
            }
        }
    }
}

private fun fmtTimeMs(ms: Long) = "%d:%02d.%01d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

/**
 * The sync timeline: 12 seconds of the song's waveform around the playhead (mirrored peaks, the played
 * part lit), with a numbered pin for each timed line. Drag a pin to move its line; drag elsewhere to
 * scrub; tap to jump there.
 */
@Composable
private fun SyncTimeline(
    peaks: FloatArray?, durationMs: Long, posMs: Long, times: List<Long?>, selected: Int,
    onSeek: (Long) -> Unit, onSelect: (Int) -> Unit, onMove: (Int, Long) -> Unit,
) {
    val windowMs = 12_000L
    val amber = C.Amber; val amberLight = C.AmberLight; val waveLit = C.AmberLight; val wave = Color.White.copy(alpha = 0.35f)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val pinStyle = mono(10.sp, FontWeight.Bold, C.OnAccent)
    var scrubAt by remember { mutableStateOf<Long?>(null) }
    val center = scrubAt ?: posMs
    val left = center - windowMs / 3
    val current by androidx.compose.runtime.rememberUpdatedState(Triple(left, times, durationMs))
    Box(
        Modifier.fillMaxWidth().height(120.dp).recessed(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                var dragging = -1
                var startLeft = 0L
                var startX = 0f
                fun xOf(ms: Long, l: Long) = ((ms - l).toFloat() / windowMs) * size.width
                detectDragGestures(
                    onDragStart = { o ->
                        val (l, ts, _) = current
                        val hit = ts.indices.filter { ts[it] != null }.minByOrNull { kotlin.math.abs(xOf(ts[it]!!, l) - o.x) }
                        dragging = if (hit != null && kotlin.math.abs(xOf(ts[hit]!!, l) - o.x) < 28.dp.toPx()) hit else -1
                        if (dragging >= 0) onSelect(dragging)
                        startLeft = l; startX = o.x
                        if (dragging < 0) scrubAt = l + windowMs / 3
                    },
                    onDragEnd = { scrubAt?.let { onSeek(it.coerceIn(0, current.third)) }; scrubAt = null; dragging = -1 },
                    onDragCancel = { scrubAt = null; dragging = -1 },
                ) { change, _ ->
                    change.consume()
                    if (dragging >= 0) onMove(dragging, current.first + (change.position.x / size.width * windowMs).toLong())
                    else scrubAt = (startLeft + windowMs / 3 - ((change.position.x - startX) / size.width * windowMs).toLong()).coerceIn(0, current.third)
                }
            }
            .pointerInput(Unit) { detectTapGestures { o -> onSeek((current.first + (o.x / size.width * windowMs).toLong()).coerceIn(0, current.third)) } },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val mid = h * 0.58f
            val amp = h * 0.36f
            val px = ((posMs - left).toFloat() / windowMs) * w
            // Mirrored peak waveform, one thin column per pixel pair.
            val step = 2f
            var x = 0f
            while (x < w) {
                val ms = left + (x / w * windowMs).toLong()
                if (ms in 0..durationMs && peaks != null) {
                    val i = ((ms.toDouble() / durationMs.coerceAtLeast(1)) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
                    val v = peaks[i]
                    val half = (amp * v).coerceAtLeast(1f)
                    drawLine(if (x <= px) waveLit else wave, androidx.compose.ui.geometry.Offset(x, mid - half), androidx.compose.ui.geometry.Offset(x, mid + half), strokeWidth = 1.6f)
                }
                x += step
            }
            if (peaks == null) drawLine(wave, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(w, mid), strokeWidth = 2f)
            // Second marks along the bottom.
            var s = (left / 1000) * 1000
            while (s < left + windowMs) {
                val sx = ((s - left).toFloat() / windowMs) * w
                if (s >= 0) drawLine(Color.White.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(sx, h - 8f), androidx.compose.ui.geometry.Offset(sx, h), strokeWidth = 2f)
                s += 1000
            }
            // Pins: a line through the waveform with the line number in a tag on top.
            times.forEachIndexed { i, ms ->
                if (ms == null) return@forEachIndexed
                val x2 = ((ms - left).toFloat() / windowMs) * w
                if (x2 < -40f || x2 > w + 40f) return@forEachIndexed
                val col = if (i == selected) amberLight else amber
                drawLine(col, androidx.compose.ui.geometry.Offset(x2, 24f), androidx.compose.ui.geometry.Offset(x2, h), strokeWidth = if (i == selected) 5f else 3f)
                val label = measurer.measure("${i + 1}", pinStyle)
                val tw = label.size.width + 18f; val th = label.size.height + 8f
                drawRoundRect(col, androidx.compose.ui.geometry.Offset(x2 - tw / 2, 2f), androidx.compose.ui.geometry.Size(tw, th), androidx.compose.ui.geometry.CornerRadius(th / 2))
                drawText(label, topLeft = androidx.compose.ui.geometry.Offset(x2 - label.size.width / 2, 6f))
            }
            // The playhead.
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(px, 0f), androidx.compose.ui.geometry.Offset(px, h), strokeWidth = 3f)
        }
    }
}

/** Which track the lyrics on screen belong to (−1 while loading). */
private var loadedFor by mutableLongStateOf(-1L)

@Composable
private fun SyncedLines(lines: List<LyricLine>, posMs: Long, onSeek: (Long) -> Unit) {
    val list = rememberLazyListState()
    val current = lines.indexOfLast { it.timeMs <= posMs }.coerceAtLeast(0)
    LaunchedEffect(current) { list.animateScrollToItem((current - 3).coerceAtLeast(0)) }
    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(lines) { i, line ->
            val on = i == current
            val near = abs(i - current)
            Text(
                line.text.ifBlank { "♪" },
                style = body(if (on) 24.sp else 20.sp, if (on) FontWeight.Bold else FontWeight.SemiBold, if (on) C.AmberLight else if (i < current) C.TextLow else C.TextHi.copy(alpha = if (near < 4) 0.85f else 0.6f)).copy(lineHeight = 32.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSeek(line.timeMs) },
            )
        }
    }
}

@Composable
private fun LyricsSearch(t: Track, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("${LyricsRepo.titleCandidates(t.title).last()} ${LyricsRepo.mainArtist(t.artist)}".trim()) }
    var results by remember { mutableStateOf<List<LrcCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run() { busy = true; scope.launch { results = LyricsRepo.searchText(q); busy = false } }
    LaunchedEffect(Unit) { run() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(q, { q = it }, singleLine = true, textStyle = body(15.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.weight(1f))
            Text("Search", style = body(14.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { run() }.padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Song length: ${fmtTime(t.durationMs)}. Pick the one with the closest length.", style = body(12.sp, color = C.TextMid))
        Spacer(Modifier.height(8.dp))
        if (busy) Text("Searching…", style = body(14.sp, color = C.TextMid))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            itemsIndexed(results ?: emptyList()) { _, c ->
                val diff = abs(c.durationSec - t.durationMs / 1000.0)
                Row(
                    Modifier.fillMaxWidth().raised().clip(RoundedCornerShape(16.dp)).clickable {
                        LyricsRepo.choose(t, c.synced, c.plain, c.instrumental, "LRCLIB"); done()
                    }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = body(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.artist} · ${c.album}", style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val sample = c.synced ?: c.plain ?: ""
                        val script = Hinglish.scriptName(sample)?.let { "$it script" } ?: if (sample.any { it.isLetter() }) "Roman" else ""
                        if (script.isNotBlank()) Text(script, style = body(11.sp, color = C.TextMid))
                        Text(if (c.synced != null) "Synced" else if (c.instrumental) "Instrumental" else "Plain", style = body(12.sp, FontWeight.Bold, if (c.synced != null) C.AmberText else C.TextMid))
                        Text("${fmtTime((c.durationSec * 1000).toLong())}${if (diff <= 3) " ✓" else ""}", style = mono(11.sp, color = if (diff <= 3) C.OkText else C.TextMid))
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(nav: Nav, sheet: Boolean = false) {
    val st by Playback.state.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (st.index > 2) list.scrollToItem(st.index - 2) }
    ScreenShell(sheet, Color(0xFF1B1F4A)) {
        Column(Modifier.fillMaxSize().then(if (sheet) Modifier else Modifier.statusBarsPadding())) {
            if (sheet) Text("Queue", style = display(22.sp), modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp))
            else Header("Queue", { nav.back() })
            Text("${st.queue.size} songs · ${fmtTime(st.queue.sumOf { it.durationMs })}", style = body(13.sp, color = C.TextMid), modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            LazyColumn(state = list) {
                itemsIndexed(st.queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                    TrackRow(
                        t, i == st.index, { Playback.jumpTo(i) },
                        listOfNotNull(
                            MenuAction("Move up") { Playback.move(i, i - 1) }.takeIf { i > 0 },
                            MenuAction("Move down") { Playback.move(i, i + 1) }.takeIf { i < st.queue.lastIndex },
                            MenuAction("Remove from queue") { Playback.removeAt(i) }.takeIf { i != st.index },
                            MenuAction("Go to album") { nav.go(Route.AlbumDetail(t.albumKey)) },
                        ),
                    )
                }
            }
        }
    }
}


@Composable
private fun DetailRow(k: String, v: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(310.dp)) {
        Text(k, style = body(12.sp, color = C.TextMid), modifier = Modifier.width(100.dp))
        Text(v, style = body(13.sp, FontWeight.SemiBold).copy(lineHeight = 18.sp))
    }
}

/** The artist, with the sound path as a small tag beside it. */
@Composable
private fun ArtistWithPath(t: Track, st: PlayState, settings: AppSettings, style: androidx.compose.ui.text.TextStyle, onPath: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t.artist.ifBlank { "Unknown artist" }, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        SignalPill(st, settings, compact = true, onClick = onPath)
    }
}

/** "HI-RES | FLAC  24-bit  48 kHz  1,545 kbps" on one frosted strip; tap for every detail of the file. */
@Composable
fun QualityLine(t: Track) {
    val q by produceState<QualityInfo?>(null, t.id) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Quality.of(t) } }
    var open by remember { mutableStateOf(false) }
    val info = q ?: return
    Box {
        val shape = RoundedCornerShape(999.dp)
        Row(
            Modifier.padding(top = 8.dp).height(26.dp)
                .then(if (Look.soft) Modifier.recessed(shape) else Modifier.clip(shape).background(Color.Black.copy(alpha = 0.24f)).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.05f))), shape))
                .clip(shape).clickable { open = true }
                .padding(start = 3.dp, end = 10.dp)
                .semantics { contentDescription = "Quality: ${info.short}. Tap for details" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val badge = if (info.lossless) (if (t.hiRes) "HI-RES" else "LOSSLESS") else "LOSSY"
            Box(
                Modifier.height(20.dp).clip(shape)
                    .background(if (info.lossless) Brush.horizontalGradient(listOf(C.AmberLight, C.Amber)) else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.10f))))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(badge, style = mono(9.sp, FontWeight.Bold, if (info.lossless) C.OnAccent else C.TextHi).copy(letterSpacing = 0.8.sp)) }
            val parts = listOfNotNull(
                info.codec,
                info.bits.takeIf { it > 0 }?.let { "$it-bit" },
                info.sampleRate.takeIf { it > 0 }?.let { "${khz(it)} kHz" },
                info.kbps.takeIf { it > 0 }?.let { "%,d kbps".format(java.util.Locale.US, it) },
            )
            parts.forEachIndexed { i, part ->
                if (i > 0) Box(Modifier.padding(horizontal = 7.dp).size(width = 1.dp, height = 10.dp).background(Color.White.copy(alpha = 0.20f)))
                else Spacer(Modifier.width(8.dp))
                Text(part, style = mono(11.sp, if (i == 0) FontWeight.Bold else FontWeight.Medium, if (i == 0) C.TextHi else C.TextHi.copy(alpha = 0.78f)), maxLines = 1)
            }
        }
        DropdownMenu(
            open, { open = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = if (Look.soft) Color(0xE62B2D32) else Color(0xEB101119),
            tonalElevation = 0.dp, shadowElevation = 18.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))),
        ) {
            val st = Playback.state.value
            Text("QUALITY", style = eyebrow(), modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
            listOf(
                "Format" to "${info.codec} — ${info.codecLong}",
                "Type" to if (info.lossless) "Lossless" else "Lossy (compressed)",
                "Sample rate" to if (info.sampleRate > 0) "${khz(info.sampleRate)} kHz" else "Unknown",
                "Bit depth" to if (info.bits > 0) "${info.bits}-bit" else if (info.lossless) "Not stored in the header" else "Not applicable",
                "Channels" to when (info.channels) { 1 -> "Mono"; 2 -> "Stereo"; else -> "${info.channels} channels" },
                "Bitrate" to if (info.kbps > 0) "%,d kbps".format(java.util.Locale.US, info.kbps) else "Unknown",
                "File" to "%.1f MB · .%s".format(java.util.Locale.US, info.sizeMb, info.container.lowercase()),
            ).forEach { (k, v) -> DetailRow(k, v) }
            // How it is decoded and where it goes.
            val ctx = LocalContext.current
            val path by AudioPathInfo.path.collectAsState()
            val s = AppState.settings.value
            val mode = Outputs.of(s.output)
            val (decKind, decLine) = AudioPathInfo.decoderLine(path, st.usb)
            Text("DECODING & OUTPUT", style = eyebrow(), modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
            DetailRow("Mode", when {
                st.usb -> if (st.untouched) "USB DAC, bit-perfect" else "USB DAC, with effects"
                st.phoneDirect -> "Direct (phone): ${if (st.untouched) "untouched" else "converted to ${khz(st.dacRate)} kHz by the app"}, no phone processing"
                s.output == "direct" -> "Phone (direct output not available right now)"
                s.output == "usb_android" && AndroidBitPerfect.active.isNotEmpty() -> "Untouched (Android USB): ${AndroidBitPerfect.active}"
                s.output == "usb_android" -> "Phone (Android bit-perfect not available for this song)"
                s.output == "usb" -> "Phone (no DAC plugged in)"
                s.output == "offload" && !path.offload -> "Phone (Battery saver doesn't take this file type)"
                else -> mode.title
            })
            DetailRow("Decoder", "$decKind\n$decLine")
            if (st.usb) {
                DetailRow("Sent as", "${khz(st.dacRate)} kHz ${if (st.untouched) "untouched samples" else "32-bit, after the app's effects"}")
                DetailRow("Plays on", "${st.dacName.ifBlank { "USB DAC" }} directly, skipping Android's mixer and the phone's tuning")
            } else {
                val mixer = AudioPathInfo.mixerRate(ctx)
                DetailRow("Sent as", if (path.offload) "Compressed file to the audio chip" else "${AudioPathInfo.encodingName(path.trackEncoding)}${if (path.trackRate > 0) " · ${khz(path.trackRate)} kHz" else ""}")
                if (!path.offload && mixer > 0) DetailRow("Android mixer", if (path.trackRate > 0 && path.trackRate != mixer) "Resampled to ${khz(mixer)} kHz, then the phone's tuning" else "${khz(mixer)} kHz, then the phone's tuning")
                DetailRow("Plays on", AudioPathInfo.device(ctx))
                val effects = Eq.anyEffect(s.sound, false)
                DetailRow("App effects", when { path.offload -> "Off (offload)"; effects -> "On"; else -> "Off" })
            }
        }
    }
}


/**
 * The cover as pages of the queue: swipe sideways and the next (or previous) song slides in and
 * starts; when the song changes by itself the pages glide along. Swipe up opens the library,
 * swipe down opens Settings.
 */
@Composable
fun CoverPager(st: PlayState, page: @Composable (Track, Boolean) -> Unit) {
    val queue = st.queue
    if (queue.isEmpty() || st.index !in queue.indices) return
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = st.index) { queue.size }
    val style = AppState.settings.collectAsState().value.coverAnim
    // Every change of song, by swipe or by itself, moves the pager; the chosen style is drawn from the
    // pager position, so the animation follows the finger while swiping.
    LaunchedEffect(st.index) {
        if (pager.currentPage != st.index && st.index in queue.indices) {
            if (kotlin.math.abs(pager.currentPage - st.index) > 1) pager.scrollToPage(st.index)
            else pager.animateScrollToPage(st.index, animationSpec = androidx.compose.animation.core.tween(if (style == "slide") 450 else 650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    androidx.compose.runtime.SideEffect { CoverSwipe.ids = queue.map { it.id } }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { CoverSwipe.pos = it }
    }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { p ->
            if (p != Playback.state.value.index && p in Playback.state.value.queue.indices) Playback.jumpTo(p)
        }
    }
    val gap = 16.dp
    androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize().tourTarget("cover"), beyondViewportPageCount = 1, pageSpacing = gap) { i ->
        val song = queue.getOrNull(i) ?: return@HorizontalPager
        // o: 0 = in place, +1 = one page to the left (earlier song), -1 = one page to the right (next song).
        fun off() = ((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
        Box(
            Modifier.fillMaxSize()
                // Notebook: the earlier song is the page on top, so it can turn over the next one.
                .then(if (style == "flip") Modifier.zIndex(-i.toFloat()) else Modifier)
                .graphicsLayer {
                    val o = off()
                    val a = kotlin.math.abs(o)
                    // Fade, Zoom, Flip and Reveal keep both covers in the same place; only Slide moves.
                    val pin = o * (size.width + gap.toPx())
                    when (style) {
                        "fade" -> { translationX = pin; alpha = 1f - a }
                        "zoom" -> {
                            translationX = pin
                            // The cover to the right comes from 125 % and settles; the one to the left shrinks away.
                            val z = if (o < 0) 1f + 0.25f * a else 1f - 0.2f * a
                            scaleX = z; scaleY = z; alpha = 1f - a
                        }
                        "flip" -> {
                            translationX = pin
                            if (o > 0f) {
                                // Turned like a notebook page, hinged on the left edge: swipe left and it turns
                                // over to the left; swipe right and the page turns back over the cover.
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                cameraDistance = 9f * density
                                rotationY = -o * 90f
                            }
                        }
                        "mask" -> {
                            translationX = pin
                            if (o < 0f) {
                                // The next cover is revealed through a circle growing from the side you swipe from.
                                CoverSwipe.coverH = size.height
                                val r = (1f - a) * CoverSwipe.radius(size.width, size.height)
                                shape = androidx.compose.foundation.shape.GenericShape { sz, _ ->
                                    addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(sz.width, sz.height / 2f), r))
                                }
                                clip = true
                            } else { val z = 1f - 0.08f * a; scaleX = z; scaleY = z }
                        }
                        "wipe" -> {
                            translationX = pin
                            if (o < 0f) {
                                // The next cover is revealed behind a straight edge sweeping across from the side you swipe from.
                                shape = androidx.compose.foundation.shape.GenericShape { sz, _ ->
                                    addRect(androidx.compose.ui.geometry.Rect(sz.width * a, 0f, sz.width, sz.height))
                                }
                                clip = true
                            } else { val z = 1f - 0.05f * a; scaleX = z; scaleY = z }
                        }
                        else -> { val sc = 1f - a * 0.12f; scaleX = sc; scaleY = sc; alpha = 1f - a * 0.5f }
                    }
                }
                .drawWithContent {
                    drawContent()
                    // Shade: the turning page darkens as it turns away; the covered cover darkens under the reveal.
                    val o = off()
                    val shade = when (style) { "flip" -> if (o > 0f) 0.55f * o else 0.35f * -o; "mask", "wipe" -> if (o > 0f) 0.5f * o else 0f; else -> 0f }
                    if (shade > 0.01f) drawRect(Color.Black.copy(alpha = shade))
                },
        ) { page(song, i == st.index) }
    }
}

/**
 * Up and down swipes anywhere on the player: up opens the library, down opens Settings. A short,
 * quick flick counts as much as a long drag; sideways swipes stay with the cover (change song).
 */
private fun Modifier.playerSwipes(nav: Nav): Modifier = pointerInput(nav) {
    var total = 0f
    var sideways = 0f
    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { total = 0f; sideways = 0f; tracker.resetTracking() },
        onDragEnd = {
            val v = tracker.calculateVelocity().y
            val th = 56.dp.toPx(); val fling = 900.dp.toPx()
            when {
                total < -th || (v < -fling && total < 0) -> nav.tab(Route.Home)
                total > th || (v > fling && total > 0) -> nav.go(Route.Settings)
            }
        },
    ) { change, dy -> total += dy; tracker.addPosition(change.uptimeMillis, change.position); change.consume() }
}

/**
 * The Flow look: the cover fills the top of the screen and melts into its own colour; a big bold
 * title sits on that colour; an outlined play button and a quiet seek line below; the visualizer
 * rises from the bottom. Swipe the cover to change song.
 */
@Composable
private fun FlowPlayer(nav: Nav, t: Track, st: PlayState, settings: AppSettings, rating: Int, glow: () -> Color, glowTarget: Color) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().playerSwipes(nav).drawBehind {
            fun bg(g: Color) = Brush.verticalGradient(listOf(lerpColor(g, Color.Black, 0.35f), lerpColor(g, Color.Black, 0.55f), lerpColor(g, Color.Black, 0.78f)))
            CoverSwipe.fullH = size.height
            val sp = CoverSwipe.split()
            val anim = settings.coverAnim
            if (sp != null && (anim == "wipe" || anim == "mask")) {
                // The reveal edge carries on below the cover: the next song's colour sweeps the whole player.
                val (c0, c1, f) = sp
                drawRect(bg(c0))
                if (anim == "wipe") clipRect(left = size.width * (1f - f)) { drawRect(bg(c1)) }
                else {
                    val ch = CoverSwipe.coverH.takeIf { it > 0f } ?: (size.height * 0.6f)
                    val circle = androidx.compose.ui.graphics.Path().apply {
                        addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(size.width, ch / 2f), f * CoverSwipe.radius(size.width, ch)))
                    }
                    clipPath(circle) { drawRect(bg(c1)) }
                }
            } else drawRect(bg(glow()))
        },
    ) {
        Visualizer(settings.vizStyle, lerpColor(glowTarget, Color.White, 0.25f).copy(alpha = 0.6f), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp))
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val h = maxHeight
                Box(
                    Modifier.fillMaxWidth().height(h)
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        },
                ) {
                    CoverPager(st) { song, isCurrent ->
                        val b = rememberArtBitmap(albumOf(song), 1000)
                        CoverSwipe.Register(song, b)
                        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Ico(I.Note, C.TextLow, 96.dp) }
                        @Suppress("UNUSED_EXPRESSION") isCurrent
                    }
                }
                // A soft shade under the status bar so the clock and icons stay readable on any cover.
                Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
            }
            Column(Modifier.padding(horizontal = 14.dp)) {
                SongName(nav, t, albumOf(t), rating, st, settings, flow = true)
            }
            Column(Modifier.padding(horizontal = 18.dp)) {
                val peaks by produceState<FloatArray?>(null, t.id) { value = Waveform.peaks(context, t) }
                SeekZone(
                    settings.seekStyle, peaks, if (st.durationMs > 0) st.positionMs.toFloat() / st.durationMs else 0f, st.playing,
                    st.positionMs, st.durationMs, { Playback.seekTo((it * st.durationMs).toLong()) },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        OutlineRound(I.Shuffle, "Shuffle", 44.dp, if (st.shuffle) C.Amber else Color.White.copy(alpha = 0.85f)) { Playback.setShuffle(!st.shuffle) }
                        OutlineRound(I.Prev, "Previous", 48.dp, Color.White) { Playback.previous() }
                        Box(
                            Modifier.size(78.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape).background(Color.White.copy(alpha = 0.06f))
                                .clickable { Playback.toggle() }.semantics { contentDescription = if (st.playing) "Pause" else "Play" },
                            contentAlignment = Alignment.Center,
                        ) { androidx.compose.animation.Crossfade(st.playing, label = "flow play") { p -> Ico(if (p) I.Pause else I.Play, Color.White, 30.dp, filled = true) } }
                        OutlineRound(I.Next, "Next", 48.dp, Color.White) { Playback.next() }
                        OutlineRound(I.Repeat, "Repeat", 44.dp, if (st.repeat > 0) C.Amber else Color.White.copy(alpha = 0.85f)) { Playback.cycleRepeat() }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ToolBar(nav, settings, st)
                Spacer(Modifier.height(10.dp))
            }
        }
        @Suppress("UNUSED_EXPRESSION") rating
    }
}

@Composable
internal fun OutlineRound(icon: String, desc: String, size: androidx.compose.ui.unit.Dp, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).border(1.5.dp, tint.copy(alpha = 0.7f), CircleShape).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, size * 0.42f, filled = icon == I.Prev || icon == I.Next) }
}

/**
 * Where the cover pager is between songs, and each queued song's colour, so the player's colour
 * (and the reveal edge) moves with the swipe instead of changing after it.
 */
object CoverSwipe {
    /** Pager position: 3.0 = on the 4th song, 3.4 = 40 % of the way to the 5th. */
    var pos by androidx.compose.runtime.mutableFloatStateOf(-1f)
    var ids: List<Long> = emptyList()
    val colors = androidx.compose.runtime.mutableStateMapOf<Long, Color>()
    /** Cover and whole-player heights (px), so the circle reveal lines up on both. */
    var coverH = 0f
    var fullH = 0f

    /** Remembers a song's colour once its cover is loaded. */
    @Composable
    fun Register(song: Track, bmp: android.graphics.Bitmap?) {
        LaunchedEffect(song.id, bmp) { if (bmp != null) colors[song.id] = glowColor(bmp) }
    }

    /** The two songs either side of the swipe, and how far it has gone; null when not between songs. */
    fun split(): Triple<Color, Color, Float>? {
        val p = pos
        if (p < 0f) return null
        val i = kotlin.math.floor(p).toInt()
        val f = p - i
        if (f < 0.001f || f > 0.999f) return null
        val c0 = colors[ids.getOrNull(i) ?: return null] ?: return null
        val c1 = colors[ids.getOrNull(i + 1) ?: return null] ?: return null
        return Triple(c0, c1, f)
    }

    /** The colour at the pager position (blended mid-swipe); null when a colour is not known yet. */
    fun colorAt(): Color? {
        split()?.let { (c0, c1, f) -> return androidx.compose.ui.graphics.lerp(c0, c1, f) }
        val p = pos
        if (p < 0f) return null
        return colors[ids.getOrNull(kotlin.math.round(p).toInt()) ?: return null]
    }

    /** Circle reveal radius at full size: on Flow it must reach the bottom of the player too. */
    fun radius(w: Float, coverHeight: Float): Float =
        if (Look.flow && fullH > 0f) kotlin.math.hypot(w, maxOf(coverHeight / 2f, fullH - coverHeight / 2f)) else kotlin.math.hypot(w, coverHeight)
}
