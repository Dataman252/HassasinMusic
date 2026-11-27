package com.hassasin.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a generated playlist should hold. */
data class GenRules(
    val artists: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    /** "any", "lossless", "hires" */
    val quality: String = "any",
    val minRating: Int = 0,
    /** "any", "never" (never played), "often" (3+ plays), "forgotten" (not played for 60 days) */
    val plays: String = "any",
    /** Added in the last N days (0 = any time). */
    val addedDays: Int = 0,
    /** How many songs (0 = all that match). */
    val count: Int = 50,
    /** "shuffle", "mix" (no artist twice in a row), "most", "newest", "oldest" */
    val order: String = "mix",
)

/** Builds playlists from rules: artists, genres, years, quality, rating, play history, when added. */
object Generator {
    private val lossless = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "AIF", "DSF", "DFF", "WV")

    fun matches(lib: List<Track>, stats: Map<Long, TrackStat>, r: GenRules): List<Track> {
        val now = System.currentTimeMillis()
        return lib.filter { t ->
            val st = stats[t.id] ?: TrackStat()
            (r.artists.isEmpty() || Library.splitArtists(t.artist).any { it in r.artists } || t.albumArtist in r.artists) &&
                (r.genres.isEmpty() || t.genre in r.genres) &&
                (r.yearFrom == 0 || t.year >= r.yearFrom) && (r.yearTo == 0 || (t.year in 1..r.yearTo)) &&
                when (r.quality) { "hires" -> t.hiRes; "lossless" -> t.ext in lossless || t.hiRes; else -> true } &&
                st.rating >= r.minRating &&
                when (r.plays) {
                    "never" -> st.plays == 0
                    "often" -> st.plays >= 3
                    "forgotten" -> st.plays > 0 && now - st.lastPlayed > 60L * 24 * 3600 * 1000
                    else -> true
                } &&
                (r.addedDays == 0 || now / 1000 - t.dateAdded <= r.addedDays * 86400L)
        }
    }

    fun build(lib: List<Track>, stats: Map<Long, TrackStat>, r: GenRules): List<Track> {
        val m = matches(lib, stats, r)
        val ordered = when (r.order) {
            "most" -> m.sortedByDescending { stats[it.id]?.plays ?: 0 }
            "newest" -> m.sortedByDescending { it.dateAdded }
            "oldest" -> m.sortedBy { if (it.year > 0) it.year else 9999 }
            "mix" -> artistMix(m.shuffled())
            else -> m.shuffled()
        }
        return if (r.count > 0) ordered.take(r.count) else ordered
    }

    /** Spreads artists out: no artist plays twice in a row when it can be avoided. */
    fun artistMix(list: List<Track>): List<Track> {
        val left = list.toMutableList()
        val out = ArrayList<Track>(list.size)
        while (left.isNotEmpty()) {
            val last = out.lastOrNull()?.let { LyricsRepo.mainArtist(it.artist) }
            val i = left.indexOfFirst { LyricsRepo.mainArtist(it.artist) != last }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(i)
        }
        return out
    }

    /** Ready-made rule sets. */
    fun presets(current: Track?): List<Pair<String, GenRules>> = listOfNotNull(
        "Discover (never played)" to GenRules(plays = "never", order = "mix"),
        "Favourites (4★+)" to GenRules(minRating = 4, count = 0, order = "mix"),
        "Hi-Res mix" to GenRules(quality = "hires", order = "mix"),
        "Fresh (added this month)" to GenRules(addedDays = 30, count = 0, order = "newest"),
        "Most played" to GenRules(plays = "often", order = "most"),
        "Forgotten gems" to GenRules(plays = "forgotten", order = "mix"),
        "Throwback (before 2005)" to GenRules(yearFrom = 1950, yearTo = 2004, order = "mix"),
        current?.let { "More like this artist" to GenRules(artists = setOf(LyricsRepo.mainArtist(it.artist)), count = 0, order = "shuffle") },
        current?.genre?.takeIf { it.isNotBlank() }?.let { g -> "More $g" to GenRules(genres = setOf(g), order = "mix") },
    )

    fun describe(r: GenRules): String = buildList {
        if (r.artists.isNotEmpty()) add(r.artists.take(2).joinToString(" & ") + if (r.artists.size > 2) " +${r.artists.size - 2}" else "")
        if (r.genres.isNotEmpty()) add(r.genres.joinToString(", "))
        if (r.yearFrom > 0 || r.yearTo > 0) add("${if (r.yearFrom > 0) r.yearFrom else "…"}–${if (r.yearTo > 0) r.yearTo else "…"}")
        if (r.quality == "hires") add("Hi-Res") else if (r.quality == "lossless") add("Lossless")
        if (r.minRating > 0) add("${r.minRating}★+")
        when (r.plays) { "never" -> add("Unplayed"); "often" -> add("Most played"); "forgotten" -> add("Forgotten") }
        if (r.addedDays > 0) add("Added ${r.addedDays} days")
    }.joinToString(" · ").ifBlank { "Mix" }
}

/** Make a playlist from rules (or a preset), see how many songs match, then play it or keep it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneratorScreen(nav: Nav) {
    val lib by AppState.library.collectAsState()
    val stats by AppState.stats.collectAsState()
    val st by Playback.state.collectAsState()
    var r by remember { mutableStateOf(GenRules()) }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val matching = remember(r, lib, stats) { Generator.matches(lib.tracks, stats, r) }
    val topArtists = remember(lib) { lib.tracks.flatMap { Library.splitArtists(it.artist) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(24).map { it.key } }
    val genres = remember(lib) { lib.tracks.map { it.genre }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(16).map { it.key } }
    var artistQuery by remember { mutableStateOf("") }
    fun result() = Generator.build(lib.tracks, stats, r)
    Backdrop(Color(0xFF1B1F4A), Color(0xFF3D5BFF)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header("Generate a playlist", { nav.back() }) }
            item {
                Section("Quick picks") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Generator.presets(st.current).forEach { (label, rules) -> Chip(label, r == rules) { r = rules; name = label.substringBefore(" (") } }
                    }
                }
            }
            item {
                Section("Artists") {
                    Row(Modifier.fillMaxWidth().height(44.dp).recessed().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (artistQuery.isEmpty()) Text("Find an artist", style = body(13.sp, color = C.TextMid))
                            BasicTextField(artistQuery, { artistQuery = it }, singleLine = true, textStyle = body(13.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    val shown = if (artistQuery.isBlank()) topArtists else lib.tracks.flatMap { Library.splitArtists(it.artist) }.distinct().filter { it.contains(artistQuery, true) }.take(24)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (r.artists.filter { it !in shown } + shown).forEach { a -> Chip(a, a in r.artists) { r = r.copy(artists = if (a in r.artists) r.artists - a else r.artists + a) } }
                    }
                }
            }
            if (genres.isNotEmpty()) item {
                Section("Genres") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.forEach { g -> Chip(g, g in r.genres) { r = r.copy(genres = if (g in r.genres) r.genres - g else r.genres + g) } }
                    }
                }
            }
            item {
                Section("Years") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Triple("Any", 0, 0), Triple("Before 1990", 1900, 1989), Triple("90s", 1990, 1999), Triple("2000s", 2000, 2009), Triple("2010s", 2010, 2019), Triple("2020s", 2020, 2099)).forEach { (label, a, b) ->
                            Chip(label, r.yearFrom == a && r.yearTo == b) { r = r.copy(yearFrom = a, yearTo = b) }
                        }
                    }
                }
            }
            item {
                Section("Sound and history") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("any" to "Any quality", "lossless" to "Lossless", "hires" to "Hi-Res").forEach { (k, l) -> Chip(l, r.quality == k) { r = r.copy(quality = k) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..5).forEach { n -> Chip(if (n == 0) "Any rating" else "$n★+", r.minRating == n) { r = r.copy(minRating = n) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("any" to "Any plays", "never" to "Never played", "often" to "Played often", "forgotten" to "Not for 60 days").forEach { (k, l) -> Chip(l, r.plays == k) { r = r.copy(plays = k) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Added any time", 7 to "This week", 30 to "This month", 365 to "This year").forEach { (d, l) -> Chip(l, r.addedDays == d) { r = r.copy(addedDays = d) } }
                    }
                }
            }
            item {
                Section("Size and order") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(25, 50, 100, 0).forEach { n -> Chip(if (n == 0) "All" else "$n songs", r.count == n) { r = r.copy(count = n) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("mix" to "Artist mix", "shuffle" to "Shuffle", "most" to "Most played", "newest" to "Newest added", "oldest" to "By year").forEach { (k, l) -> Chip(l, r.order == k) { r = r.copy(order = k) } }
                    }
                }
            }
            item {
                val total = if (r.count > 0) minOf(r.count, matching.size) else matching.size
                val mins = Generator.build(matching, stats, r.copy(order = "shuffle")).sumOf { it.durationMs } / 60000
                Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$total songs · ${mins / 60}h ${mins % 60}m", style = body(16.sp, FontWeight.Bold, C.AmberText))
                    Text("${matching.size} match: ${Generator.describe(r)}", style = body(12.sp, color = C.TextMid))
                    Row(Modifier.fillMaxWidth().height(44.dp).recessed().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) Text("Playlist name", style = body(13.sp, color = C.TextMid))
                            BasicTextField(name, { name = it }, singleLine = true, textStyle = body(13.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.Amber).clickable(enabled = matching.isNotEmpty()) {
                                val songs = result(); Playback.playList(songs, 0, shuffle = false); nav.go(Route.Player)
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text("Play now", style = body(14.sp, FontWeight.Bold, C.OnAccent)) }
                        Box(
                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).raised(RoundedCornerShape(23.dp), 4.dp).clickable(enabled = matching.isNotEmpty()) {
                                val songs = result()
                                val p = Playlists.create(name.ifBlank { Generator.describe(r) }, songs)
                                status = "Saved “${p.name}” with ${songs.size} songs."
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text("Save as playlist", style = body(14.sp, FontWeight.Bold)) }
                    }
                    if (status.isNotBlank()) Text(status, style = body(12.sp, color = C.TextMid))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().raised().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = body(14.sp, FontWeight.Bold))
        content()
    }
}

