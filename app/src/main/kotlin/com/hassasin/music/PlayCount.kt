package com.hassasin.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Counts a play only after 30 seconds of real listening (pauses and seeks don't add time), and adds
 * up how long each song has been listened to, so playlists can be built from what you really play.
 */
object PlayCounter {
    const val COUNT_AFTER_MS = 30_000L
    private var trackId = -1L
    private var heardMs = 0L
    private var counted = false
    private var lastAt = 0L
    private var unsavedMs = 0L

    /** Called by the player loop. */
    fun tick(t: Track?, playing: Boolean) {
        val now = System.currentTimeMillis()
        val dt = if (lastAt == 0L) 0L else (now - lastAt).coerceIn(0, 2000)
        lastAt = now
        if (t == null) return
        if (t.id != trackId) { flush(); trackId = t.id; heardMs = 0; counted = false; return }
        if (!playing) return
        heardMs += dt; unsavedMs += dt
        if (!counted && heardMs >= COUNT_AFTER_MS) { counted = true; AppState.countPlay(t) }
        if (unsavedMs >= 15_000) flush()
    }

    /** A new play of the same song (repeat one, or picked again) starts counting afresh. */
    fun restart() { flush(); heardMs = 0; counted = false }

    private fun flush() {
        if (trackId >= 0 && unsavedMs > 0) AppState.addListening(trackId, unsavedMs / 1000)
        unsavedMs %= 1000
    }
}

/** "Add to playlist…": the song(s) waiting for a playlist to be picked or named. */
object AddToPlaylist {
    var songs by mutableStateOf<List<Track>>(emptyList())
}

/** Pick a playlist for the waiting songs, or name a new one. */
@Composable
fun AddToPlaylistDialog() {
    val songs = AddToPlaylist.songs
    if (songs.isEmpty()) return
    val all by Playlists.all.collectAsState()
    remember { Playlists.list() }
    var name by remember(songs) { mutableStateOf("") }
    var done by remember(songs) { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { AddToPlaylist.songs = emptyList() },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(24.dp).fillMaxWidth().raised(RoundedCornerShape(24.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add to playlist", style = display(20.sp))
            Text(if (songs.size == 1) songs[0].title else "${songs.size} songs", style = body(13.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().height(46.dp).recessed().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text("New playlist name", style = body(14.sp, color = C.TextMid))
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = body(14.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                }
                Text("Create", style = body(14.sp, FontWeight.Bold, if (name.isBlank()) C.TextLow else C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = name.isNotBlank()) {
                    val p = Playlists.create(name, songs); done = "Made “${p.name}”"; AddToPlaylist.songs = emptyList()
                }.padding(8.dp))
            }
            if (all.isNotEmpty()) Text("Or add to", style = body(12.sp, FontWeight.Bold, C.TextMid))
            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(all, key = { it.id }) { p ->
                    val already = songs.all { it.path in p.paths }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f))
                            .clickable(enabled = !already) { Playlists.add(p.id, songs); AddToPlaylist.songs = emptyList() }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(I.Playlist, C.AmberText, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(p.name, style = body(14.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (already) "Already in" else "${p.paths.size}", style = body(12.sp, color = C.TextMid))
                    }
                }
            }
            Text("Cancel", style = body(14.sp, FontWeight.Bold, C.TextMid), modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(8.dp)).clickable { AddToPlaylist.songs = emptyList() }.padding(8.dp))
        }
    }
}

/** Add songs to a playlist: search, tick songs, add them all at once. */
@Composable
fun AddSongsScreen(nav: Nav, playlistId: Long) {
    val lib by AppState.library.collectAsState()
    val stats by AppState.stats.collectAsState()
    val p = Playlists.get(playlistId) ?: run { nav.back(); return }
    var q by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val list = remember(q, lib) {
        val s = q.trim().lowercase()
        lib.tracks.filter { s.isEmpty() || it.title.lowercase().contains(s) || it.artist.lowercase().contains(s) || it.album.lowercase().contains(s) }
    }
    Backdrop(Color(0xFF1B1F4A), Color(0xFF3D5BFF)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header("Add to ${p.name}", { nav.back() })
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(46.dp).recessed().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Ico(I.Search, C.TextMid, 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search songs, artists, albums", style = body(14.sp, color = C.TextMid))
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = body(14.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(list, key = { it.id }) { t ->
                    val inList = t.path in p.paths
                    val on = t.id in picked
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !inList) { picked = if (on) picked - t.id else picked + t.id }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (on || inList) C.Amber else Color.White.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) { if (on || inList) Ico(I.Check, C.OnAccent, 16.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = body(14.sp, FontWeight.SemiBold, if (inList) C.TextLow else C.TextHi), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(t.artist, playsLabel(stats[t.id])).filter { it.isNotBlank() }.joinToString(" · "), style = body(12.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Box(
                Modifier.padding(16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(if (picked.isEmpty()) C.Amber.copy(alpha = 0.3f) else C.Amber)
                    .clickable(enabled = picked.isNotEmpty()) { Playlists.add(p.id, lib.tracks.filter { it.id in picked }); nav.back() },
                contentAlignment = Alignment.Center,
            ) { Text(if (picked.isEmpty()) "Tick songs to add" else "Add ${picked.size} song${if (picked.size == 1) "" else "s"}", style = body(15.sp, FontWeight.Bold, C.OnAccent)) }
        }
    }
}

/** "12 plays · 41 min" for a song, or "" when never played. */
fun playsLabel(st: TrackStat?): String {
    if (st == null || st.plays == 0) return ""
    val mins = st.listenedSec / 60
    return "${st.plays} play${if (st.plays == 1) "" else "s"}" + if (mins > 0) " · ${if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "$mins min"}" else ""
}
