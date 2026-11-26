package com.hassasin.music

import androidx.compose.animation.togetherWith
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Menu entries for a song. */
fun songActions(nav: Nav, t: Track): List<MenuAction> = listOf(
    MenuAction("Play next") { Playback.playNext(t) },
    MenuAction("Add to queue") { Playback.addToQueue(t) },
    MenuAction("Go to album") { nav.go(Route.AlbumDetail(t.albumKey)) },
    MenuAction("Go to artist") { nav.go(Route.Tracks(LyricsRepo.mainArtist(t.artist), "artist", LyricsRepo.mainArtist(t.artist))) },
    MenuAction("Fix album art") { nav.go(Route.FixArt(albumOf(t)?.key ?: t.albumKey)) },
)

private data class Tile(val label: String, val count: String, val icon: String, val route: Route, val hot: Boolean = false)

@Composable
fun HomeScreen(nav: Nav) {
    val lib by AppState.library.collectAsState()
    val stats by AppState.stats.collectAsState()
    val artStatus by AppState.artStatus.collectAsState()
    Backdrop(Color(0xFF1B1F4A), Color(0xFF3D5BFF)) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Library", style = display(26.sp), modifier = Modifier.weight(1f))
                        RoundButton(I.Settings, "Settings", { nav.go(Route.Settings) })
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).recessed().clip(RoundedCornerShape(16.dp)).clickable { nav.go(Route.Search) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(I.Search, C.TextMid, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Search songs, albums, artists", style = body(15.sp, color = C.TextMid))
                    }
                    Spacer(Modifier.height(16.dp))
                    if (!lib.scanned) Text("Reading your library…", style = body(14.sp, color = C.TextMid), modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            val t = lib.tracks
            val tiles = listOf(
                Tile("Folders", t.map { it.folder }.distinct().size.toString(), I.Folder, Route.Category("folders")),
                Tile("Albums", lib.albums.size.toString(), I.Album, Route.Category("albums")),
                Tile("Artists", t.flatMap { Library.splitArtists(it.artist) }.distinct().size.toString(), I.Artist, Route.Category("artists")),
                Tile("Genres", t.map { it.genre }.filter { it.isNotBlank() }.distinct().size.toString(), I.Genre, Route.Category("genres")),
                Tile("Hi-Res", t.count { it.hiRes }.toString(), I.HiRes, Route.Tracks("Hi-Res", "hires", ""), hot = true),
                Tile("All songs", t.size.toString(), I.Note, Route.Tracks("All songs", "all", "")),
                Tile("Top rated", stats.count { it.value.rating >= 4 }.toString(), I.Star, Route.Tracks("Top rated", "top", "")),
                Tile("Most played", stats.count { it.value.plays > 0 }.toString(), I.Chart, Route.Tracks("Most played", "most", "")),
                Tile("Recent", "30 days", I.Clock, Route.Tracks("Recently added", "recent", "")),
            )
            items(tiles) { GlowTile(it) { nav.go(it.route) } }
            item(span = { GridItemSpan(3) }) {
                Column(Modifier.padding(top = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recently added", style = display(15.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
                        Text("Fix art", style = body(13.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.go(Route.FixArt()) }.padding(8.dp))
                    }
                    if (artStatus.isNotBlank()) Text(artStatus, style = body(12.sp, color = C.TextMid))
                    Spacer(Modifier.height(12.dp))
                    val recent = remember(lib.albums) { lib.albums.sortedByDescending { a -> a.tracks.maxOf { it.dateAdded } }.take(12) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(recent, key = { it.key }) { a -> AlbumCard(a, 116.dp) { nav.go(Route.AlbumDetail(a.key)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlowTile(tile: Tile, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (tile.hot) Brush.radialGradient(listOf(Color(0xFF3550FF), Color(0xFF1A2380), Color(0xFF0C1029)))
    else Brush.linearGradient(listOf(Color(0xFF18204A), Color(0xFF0C1029)))
    Column(
        Modifier.height(92.dp)
            .shadow(if (tile.hot) 18.dp else 6.dp, shape, ambientColor = if (tile.hot) Color(0xFF4664FF) else Color.Black, spotColor = if (tile.hot) Color(0xFF4664FF) else Color.Black)
            .background(bg, shape)
            .border(1.dp, if (tile.hot) Color(0xB38CAAFF) else Color(0x2E788CFF), shape)
            .clip(shape).clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Ico(tile.icon, if (tile.hot) Color(0xFFDCE4FF) else Color(0xFF8FA2FF), 20.dp)
        Column {
            Text(tile.label, style = body(13.sp, FontWeight.Bold), maxLines = 1)
            Text(tile.count, style = mono(10.sp, color = Color(0xFFAEB6E0)))
        }
    }
}

@Composable
fun AlbumCard(a: Album, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(Modifier.width(size).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box {
            ArtBox(a, size, 14.dp)
            if (a.hiRes) Text(
                "HI-RES", style = mono(9.sp, FontWeight.Bold, C.AmberText),
                modifier = Modifier.padding(8.dp).background(Color(0xBF05060C), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp).align(Alignment.BottomStart),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(a.title, style = body(13.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.artist, style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CategoryScreen(nav: Nav, kind: String) {
    val lib by AppState.library.collectAsState()
    val title = when (kind) { "albums" -> "Albums"; "artists" -> "Artists"; "folders" -> "Folders"; else -> "Genres" }
    Backdrop(Color(0xFF1B1F4A)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(title, { nav.back() })
            when (kind) {
                "albums" -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) { items(lib.albums, key = { it.key }) { a -> AlbumCard(a, 158.dp) { nav.go(Route.AlbumDetail(a.key)) } } }
                else -> {
                    val groups: List<Pair<String, Int>> = remember(lib.tracks, kind) {
                        when (kind) {
                            "artists" -> lib.tracks.flatMap { t -> Library.splitArtists(t.artist).map { it to t } }.groupBy({ it.first }, { it.second }).map { it.key to it.value.size }
                            "folders" -> lib.tracks.groupBy { it.folder }.map { it.key to it.value.size }
                            else -> lib.tracks.filter { it.genre.isNotBlank() }.groupBy { it.genre }.map { it.key to it.value.size }
                        }.sortedBy { it.first.lowercase(Locale.ROOT) }
                    }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(groups, key = { it.first }) { (name, n) ->
                            val label = if (kind == "folders") name.substringAfterLast('/') else name
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.go(Route.Tracks(label, kind.removeSuffix("s"), name)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(44.dp).raised(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Ico(when (kind) { "artists" -> I.Artist; "folders" -> I.Folder; else -> I.Genre }, C.AmberText, 20.dp)
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = body(15.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (kind == "folders") name.removePrefix("/storage/emulated/0/") else "$n song${if (n == 1) "" else "s"}", style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("$n", style = mono(12.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun tracksFor(kind: String, arg: String, lib: LibraryData, stats: Map<Long, TrackStat>): List<Track> {
    val t = lib.tracks
    return when (kind) {
        "artist" -> t.filter { arg in Library.splitArtists(it.artist) || it.albumArtist == arg }.sortedWith(compareBy({ it.album.lowercase(Locale.ROOT) }, { it.discNo }, { it.trackNo }))
        "folder" -> t.filter { it.folder == arg }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.path.lowercase(Locale.ROOT) }))
        "genre" -> t.filter { it.genre == arg }.sortedBy { it.title.lowercase(Locale.ROOT) }
        "hires" -> t.filter { it.hiRes }.sortedBy { it.title.lowercase(Locale.ROOT) }
        "top" -> t.filter { (stats[it.id]?.rating ?: 0) >= 4 }.sortedByDescending { stats[it.id]?.rating ?: 0 }
        "most" -> t.filter { (stats[it.id]?.plays ?: 0) > 0 }.sortedByDescending { stats[it.id]?.plays ?: 0 }.take(100)
        "played" -> t.filter { (stats[it.id]?.lastPlayed ?: 0) > 0 }.sortedByDescending { stats[it.id]?.lastPlayed ?: 0 }.take(100)
        "recent" -> t.filter { System.currentTimeMillis() / 1000 - it.dateAdded < 30L * 24 * 3600 }.sortedByDescending { it.dateAdded }
        else -> t.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
}

@Composable
private fun PlayShuffleRow(tracks: List<Track>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(24.dp)
        Row(
            Modifier.weight(1f).height(48.dp).amberOrb(shape).clip(shape).clickable { Playback.playList(tracks, 0, shuffle = false) },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Ico(I.Play, C.OnAccent, 18.dp, filled = true); Spacer(Modifier.width(8.dp)); Text("Play", style = body(15.sp, FontWeight.Bold, C.OnAccent)) }
        Row(
            Modifier.weight(1f).height(48.dp).raised(shape).clip(shape).clickable { Playback.playList(tracks, (tracks.indices).random(), shuffle = true) },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Ico(I.Shuffle, C.TextHi, 18.dp); Spacer(Modifier.width(8.dp)); Text("Shuffle", style = body(15.sp, FontWeight.Bold)) }
    }
}

@Composable
fun TracksScreen(nav: Nav, r: Route.Tracks) {
    val lib by AppState.library.collectAsState()
    val stats by AppState.stats.collectAsState()
    val st by Playback.state.collectAsState()
    val tracks = remember(lib, stats, r) { tracksFor(r.kind, r.arg, lib, stats) }
    Backdrop(Color(0xFF1B1F4A)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Header(r.title, { nav.back() }) }
            item {
                Text("${tracks.size} song${if (tracks.size == 1) "" else "s"} · ${fmtTime(tracks.sumOf { it.durationMs })}", style = body(13.sp, color = C.TextMid), modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
                if (tracks.isNotEmpty()) PlayShuffleRow(tracks)
                Spacer(Modifier.height(8.dp))
                if (tracks.isEmpty()) Text(
                    when (r.kind) { "top" -> "Rate songs 4 or 5 stars on the player to see them here."; "most" -> "Songs you play show up here."; else -> "Nothing here yet." },
                    style = body(14.sp, color = C.TextMid), modifier = Modifier.padding(20.dp),
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, st.current?.id == t.id, { Playback.playList(tracks, i, shuffle = false) }, songActions(nav, t))
            }
        }
    }
}

@Composable
fun AlbumScreen(nav: Nav, key: String) {
    val lib by AppState.library.collectAsState()
    val st by Playback.state.collectAsState()
    val a = lib.albums.firstOrNull { it.key == key } ?: run { Header("Album", { nav.back() }); return }
    Backdrop(Color(0xFF2A1F5C)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Header("", { nav.back() }) { RoundButton(I.Image, "Fix album art", { nav.go(Route.FixArt(a.key)) }) } }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtBox(a, 240.dp, 22.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(a.title, style = display(20.sp), modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(listOf(a.artist, if (a.year > 0) a.year.toString() else "").filter { it.isNotBlank() }.joinToString(" · "), style = body(14.sp, color = C.TextMid))
                    Text("${a.tracks.size} songs · ${fmtTime(a.tracks.sumOf { it.durationMs })} · ${a.first.formatLabel}", style = mono(11.sp))
                    Spacer(Modifier.height(16.dp))
                    PlayShuffleRow(a.tracks)
                    Spacer(Modifier.height(10.dp))
                }
            }
            itemsIndexed(a.tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, st.current?.id == t.id, { Playback.playList(a.tracks, i, shuffle = false) }, songActions(nav, t), showArt = false, number = t.trackNo.takeIf { it > 0 } ?: (i + 1))
            }
        }
    }
}

@Composable
fun SearchScreen(nav: Nav) {
    val lib by AppState.library.collectAsState()
    val st by Playback.state.collectAsState()
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val words = q.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
    val songs = remember(q, lib) { if (words.isEmpty()) emptyList() else lib.tracks.filter { t -> val s = "${t.title} ${t.artist} ${t.album}".lowercase(Locale.ROOT); words.all { it in s } }.take(80) }
    val albums = remember(q, lib) { if (words.isEmpty()) emptyList() else lib.albums.filter { a -> val s = "${a.title} ${a.artist}".lowercase(Locale.ROOT); words.all { it in s } }.take(20) }
    Backdrop(Color(0xFF1B1F4A)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
            item {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(I.Back, "Back", { nav.back() })
                    Spacer(Modifier.width(12.dp))
                    Row(Modifier.weight(1f).height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ico(I.Search, C.TextMid, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text("Songs, albums, artists", style = body(15.sp, color = C.TextMid))
                            BasicTextField(q, { q = it }, singleLine = true, textStyle = body(15.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (albums.isNotEmpty()) item {
                Text("Albums", style = display(14.sp, FontWeight.Medium), modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums, key = { it.key }) { a -> AlbumCard(a, 116.dp) { nav.go(Route.AlbumDetail(a.key)) } }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (songs.isNotEmpty()) item { Text("Songs", style = display(14.sp, FontWeight.Medium), modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)) }
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t -> TrackRow(t, st.current?.id == t.id, { Playback.playList(songs, i, shuffle = false) }, songActions(nav, t)) }
        }
    }
}


/** Library tabs; remembered while the app runs. */
private var libraryTab by androidx.compose.runtime.mutableIntStateOf(0)

private data class LibTab(val label: String, val icon: String, val kind: String)

/** In the order people reach for them: everything, what you just played, your favourites, then the rest. */
private val LIB_TABS = listOf(
    LibTab("All songs", I.Note, "all"), LibTab("Recently played", I.Clock, "played"), LibTab("Most played", I.Chart, "most"),
    LibTab("Hi-Res", I.HiRes, "hires"), LibTab("Albums", I.Album, "albums"), LibTab("Artists", I.Artist, "artists"),
    LibTab("Folders", I.Folder, "folders"), LibTab("Recently added", I.Plus, "recent"), LibTab("Top rated", I.Star, "top"),
)

/**
 * The library on one screen: a title bar (search, settings), one row of tabs, and the list for
 * the chosen tab. Switching tabs cross-fades the list; albums and artists open their own pages.
 */
@Composable
fun LibraryHub(nav: Nav) {
    val lib by AppState.library.collectAsState()
    val stats by AppState.stats.collectAsState()
    val st by Playback.state.collectAsState()
    Backdrop(Color(0xFF1B1F4A), Color(0xFF3D5BFF)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Library", style = display(26.sp))
                    Text(if (lib.scanned) "${lib.tracks.size} songs · ${lib.albums.size} albums" else "Reading your library…", style = body(12.sp, color = C.TextMid))
                }
                RoundButton(I.Search, "Search", { nav.go(Route.Search) })
                Spacer(Modifier.width(10.dp))
                RoundButton(I.Settings, "Settings", { nav.go(Route.Settings) })
            }
            UpdateBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(LIB_TABS) { i, t ->
                    val on = i == libraryTab
                    val shape = RoundedCornerShape(20.dp)
                    Row(
                        Modifier.height(40.dp)
                            .then(if (on) Modifier.amberSelected(shape) else Modifier.raised(shape, 4.dp))
                            .clip(shape).clickable { libraryTab = i }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(t.icon, if (on) C.AmberText else C.TextMid, 15.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(t.label, style = body(13.sp, FontWeight.Bold, if (on) C.AmberText else C.TextHi))
                    }
                }
            }
            androidx.compose.animation.AnimatedContent(
                targetState = libraryTab,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (targetState > initialState) it / 10 else -it / 10 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
                },
                modifier = Modifier.weight(1f),
                label = "library tab",
            ) { tab ->
                when (LIB_TABS[tab].kind) {
                    "albums" -> LazyVerticalGrid(
                        GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) { items(lib.albums, key = { it.key }) { a -> AlbumCard(a, 158.dp) { nav.go(Route.AlbumDetail(a.key)) } } }
                    "artists" -> GroupList(nav, "artists", lib)
                    "folders" -> GroupList(nav, "folders", lib)
                    else -> {
                        val kind = LIB_TABS[tab].kind
                        val tracks = remember(lib, stats, kind) { tracksFor(kind, "", lib, stats) }
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            item {
                                if (tracks.isNotEmpty()) {
                                    Text("${tracks.size} song${if (tracks.size == 1) "" else "s"} · ${fmtTime(tracks.sumOf { it.durationMs })}", style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
                                    PlayShuffleRow(tracks)
                                    Spacer(Modifier.height(8.dp))
                                } else Text(
                                    when (kind) { "top" -> "Rate songs 4 or 5 stars on the player to see them here."; "most", "played" -> "Songs you play show up here."; else -> "Nothing here yet." },
                                    style = body(14.sp, color = C.TextMid), modifier = Modifier.padding(20.dp),
                                )
                            }
                            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                                Box(Modifier.animateItem()) {
                                    TrackRow(t, st.current?.id == t.id, { Playback.playList(tracks, i, shuffle = false); nav.go(Route.Player) }, songActions(nav, t))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Artists or folders as a list; each opens its songs. */
@Composable
private fun GroupList(nav: Nav, kind: String, lib: LibraryData) {
    val groups: List<Pair<String, Int>> = remember(lib.tracks, kind) {
        when (kind) {
            "artists" -> lib.tracks.flatMap { t -> Library.splitArtists(t.artist).map { it to t } }.groupBy({ it.first }, { it.second }).map { it.key to it.value.size }
            else -> lib.tracks.groupBy { it.folder }.map { it.key to it.value.size }
        }.sortedBy { it.first.lowercase(Locale.ROOT) }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(groups, key = { it.first }) { (name, n) ->
            val label = if (kind == "folders") name.substringAfterLast('/') else name
            Row(
                Modifier.fillMaxWidth().clickable { nav.go(Route.Tracks(label, kind.removeSuffix("s"), name)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).raised(RoundedCornerShape(14.dp), 5.dp), contentAlignment = Alignment.Center) {
                    Ico(if (kind == "artists") I.Artist else I.Folder, C.AmberText, 20.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = body(15.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (kind == "folders") name.removePrefix("/storage/emulated/0/") else "$n song${if (n == 1) "" else "s"}", style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("$n", style = mono(12.sp))
            }
        }
    }
}
