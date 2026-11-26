package com.hassasin.music

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
    val glowTarget = remember(art) { art?.let { glowColor(it) } ?: Color(0xFF6D5BFF) }
    val glow by animateColorAsState(glowTarget, tween(700), label = "glow")
    androidx.compose.runtime.SideEffect { Look.tint = glow }
    if (Look.flow && t != null) return FlowPlayer(nav, t, st, settings, stats[t.id]?.rating ?: 0, glow)
    Box(Modifier.fillMaxSize()) {
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
internal fun GlowCover(bmp: android.graphics.Bitmap?, glow: Color, side: androidx.compose.ui.unit.Dp, onLongPress: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.size(side)
            .shadow(40.dp, shape, ambientColor = glow, spotColor = glow)
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
        Box(Modifier.padding(top = 8.dp)) { SignalPill(st, settings, compact = true) { nav.go(Route.Sound(5)) } }
        QualityLine(t)
    }
}

/** Less-used actions for the song, kept out of the way. */
@Composable
private fun MoreMenu(nav: Nav, t: Track?, album: Album?, s: AppSettings, rating: Int = 0) {
    var open by remember { mutableStateOf(false) }
    Box {
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
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(17.dp)).clickable(onClick = go).semantics { contentDescription = "$label: $value" },
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
    ScreenShell(sheet, Color(0xFF2A1F5C)) {
        Column(Modifier.fillMaxSize().then(if (sheet) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sheet) { RoundButton(I.Down, "Close lyrics", { nav.back() }); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: "Lyrics", style = body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(lyrics?.let { "${if (it.synced != null) "Synced" else "Plain"} · from ${it.source}" } ?: t?.artist ?: "", style = body(12.sp, color = C.TextMid), maxLines = 1)
                }
                if (lyrics?.let { l -> (l.plain ?: "").any { it in '\u0900'..'\u097F' } } == true) {
                    val hing = settings.lyricsHinglish
                    Box(
                        Modifier.height(40.dp).then(if (hing) Modifier.amberSelected(RoundedCornerShape(20.dp)) else Modifier.raised(RoundedCornerShape(20.dp), 4.dp))
                            .clip(RoundedCornerShape(20.dp)).clickable { AppState.update { it.copy(lyricsHinglish = !hing) } }
                            .padding(horizontal = 14.dp).semantics { contentDescription = if (hing) "Showing Hinglish. Tap for the original script" else "Showing the original script. Tap for Hinglish" },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (hing) "Hinglish" else "\u0905 \u2192 Aa", style = body(13.sp, FontWeight.Bold, if (hing) C.AmberText else C.TextHi)) }
                    Spacer(Modifier.width(8.dp))
                }
                RoundButton(I.Search, "Search lyrics", { searching = !searching })
            }
            if (searching && t != null) {
                LyricsSearch(t) { searching = false; reload++ }
                return@Column
            }
            val l = lyrics
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
                    l.synced != null -> SyncedLines(if (settings.lyricsHinglish) remember(l) { l.synced.map { it.copy(text = Hinglish.convert(it.text)) } } else l.synced, st.positionMs + offset) { Playback.seekTo(it - offset) }
                    else -> LazyColumn(contentPadding = PaddingValues(24.dp)) { item { Text((l.plain ?: "").let { if (settings.lyricsHinglish) Hinglish.convert(it) else it }, style = body(20.sp, FontWeight.SemiBold, C.TextHi).copy(lineHeight = 32.sp)) } }
                }
            }
            if (t != null && lyrics?.synced != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Timing", style = body(13.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                RoundButton(I.Minus, "Lyrics earlier", { offset -= 250; LyricsRepo.setOffset(t, offset) }, 40.dp)
                Text("%+.2f s".format(offset / 1000.0), style = mono(13.sp, FontWeight.Bold, C.AmberText), modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
                RoundButton(I.Plus, "Lyrics later", { offset += 250; LyricsRepo.setOffset(t, offset) }, 40.dp)
            }
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
                        val script = when {
                            sample.any { it in '\u0900'..'\u097F' || it in '\u0A00'..'\u0A7F' } -> "Hindi script"
                            sample.any { it.isLetter() } -> "Roman"
                            else -> ""
                        }
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
            DetailRow("Mode", if (st.usb) "USB Direct (bit-perfect)" else if (s.output == "usb") "Rich tuned (no DAC with access)" else mode.title)
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
 * starts; when the song changes by itself the pages glide along.
 */
@Composable
fun CoverPager(st: PlayState, page: @Composable (Track, Boolean) -> Unit) {
    val queue = st.queue
    if (queue.isEmpty() || st.index !in queue.indices) return
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = st.index) { queue.size }
    LaunchedEffect(st.index) { if (pager.currentPage != st.index && st.index in queue.indices) pager.animateScrollToPage(st.index) }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { p ->
            if (p != Playback.state.value.index && p in Playback.state.value.queue.indices) Playback.jumpTo(p)
        }
    }
    androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp) { i ->
        val song = queue.getOrNull(i) ?: return@HorizontalPager
        Box(Modifier.fillMaxSize().graphicsLayer {
            val off = ((pager.currentPage - i) + pager.currentPageOffsetFraction).let { kotlin.math.abs(it) }.coerceIn(0f, 1f)
            val sc = 1f - off * 0.12f
            scaleX = sc; scaleY = sc; alpha = 1f - off * 0.5f
        }) { page(song, i == st.index) }
    }
}

/**
 * The Flow look: the cover fills the top of the screen and melts into its own colour; a big bold
 * title sits on that colour; an outlined play button and a quiet seek line below; the visualizer
 * rises from the bottom. Swipe the cover to change song.
 */
@Composable
private fun FlowPlayer(nav: Nav, t: Track, st: PlayState, settings: AppSettings, rating: Int, glow: Color) {
    val context = LocalContext.current
    val deep = lerpColor(glow, Color.Black, 0.55f)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerpColor(glow, Color.Black, 0.35f), deep, lerpColor(glow, Color.Black, 0.78f))))) {
        Visualizer(settings.vizStyle, lerpColor(glow, Color.White, 0.25f).copy(alpha = 0.6f), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp))
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
