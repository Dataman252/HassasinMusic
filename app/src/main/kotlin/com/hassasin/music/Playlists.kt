package com.hassasin.music

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Playlist(val id: Long, val name: String, val paths: List<String> = emptyList())

/** The user's playlists, kept as file paths (so they survive library rescans) and saved as JSON. */
object Playlists {
    private val _all = MutableStateFlow<List<Playlist>>(emptyList())
    val all: StateFlow<List<Playlist>> = _all
    private var loaded = false

    private fun ensure() { if (!loaded) { loaded = true; _all.value = Store.load("playlists.json", emptyList()) } }
    private fun save(list: List<Playlist>) { _all.value = list; Store.save("playlists.json", list) }

    fun list(): List<Playlist> { ensure(); return _all.value }
    fun get(id: Long) = list().firstOrNull { it.id == id }

    fun create(name: String, first: List<Track> = emptyList()): Playlist {
        val p = Playlist(System.currentTimeMillis(), name.trim().ifBlank { "Playlist ${list().size + 1}" }, first.map { it.path })
        save(list() + p)
        return p
    }

    fun add(id: Long, tracks: List<Track>) = save(list().map { p -> if (p.id == id) p.copy(paths = p.paths + tracks.map { it.path }.filter { it !in p.paths }) else p })
    fun removeAt(id: Long, index: Int) = save(list().map { p -> if (p.id == id) p.copy(paths = p.paths.filterIndexed { i, _ -> i != index }) else p })
    fun move(id: Long, from: Int, to: Int) = save(list().map { p ->
        if (p.id != id || from !in p.paths.indices || to !in p.paths.indices) p
        else p.copy(paths = p.paths.toMutableList().also { val x = it.removeAt(from); it.add(to, x) })
    })
    fun rename(id: Long, name: String) = save(list().map { if (it.id == id) it.copy(name = name) else it })
    fun delete(id: Long) = save(list().filter { it.id != id })

    /** The playlist's songs that are in the library now, in order. */
    fun tracks(p: Playlist): List<Track> {
        val byPath = AppState.library.value.tracks.associateBy { it.path }
        return p.paths.mapNotNull { byPath[it] }
    }

    /** Reads an M3U/M3U8 file: each line that is not a comment is a song path or file name. */
    fun importM3u(context: Context, uri: Uri): Playlist? {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        val lib = AppState.library.value.tracks
        val byPath = lib.associateBy { it.path }
        val byName = lib.groupBy { File(it.path).name.lowercase() }
        val found = text.lines().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
            val clean = line.replace('\\', '/').removePrefix("file://")
            byPath[clean] ?: byName[clean.substringAfterLast('/').lowercase()]?.firstOrNull()
        }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported"
        return create(name, found.distinctBy { it.path })
    }

    /** Writes the playlist as an extended M3U with full paths (works in other players on the phone). */
    fun exportM3u(context: Context, p: Playlist, uri: Uri) {
        val lib = AppState.library.value.tracks.associateBy { it.path }
        val body = buildString {
            appendLine("#EXTM3U")
            appendLine("#PLAYLIST:${p.name}")
            p.paths.forEach { path ->
                lib[path]?.let { t -> appendLine("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}") }
                appendLine(path)
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }
}

/** The playlists: tap one to open it; make a new one; import an M3U file. */
@Composable
fun PlaylistsList(nav: Nav, header: Boolean = false) {
    val context = LocalContext.current
    val all by Playlists.all.collectAsState()
    remember { Playlists.list() }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val p = runCatching { Playlists.importM3u(context, uri) }.getOrNull()
        status = if (p == null) "Couldn't read that file." else "Imported “${p.name}”: ${p.paths.size} songs found in your library."
    }
    LazyColumn(Modifier.fillMaxSize().then(if (header) Modifier.statusBarsPadding() else Modifier), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (header) item { Header("Playlists", { nav.back() }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("✨ Generate", false) { nav.go(Route.GeneratePlaylist) }
                Chip("+ New", false) { naming = true }
                Chip("Import M3U", false) { import.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/plain", "*/*")) }
            }
        }
        if (naming) item {
            Row(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text("Playlist name", style = body(14.sp, color = C.TextMid))
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = body(14.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                }
                Text("Create", style = body(14.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Playlists.create(name); name = ""; naming = false }.padding(8.dp))
            }
        }
        if (status.isNotBlank()) item { Text(status, style = body(12.sp, color = C.TextMid)) }
        if (all.isEmpty()) item { Text("No playlists yet. Make one here, or use “Add to playlist” in any song's menu.", style = body(13.sp, color = C.TextMid)) }
        itemsIndexed(all, key = { _, p -> p.id }) { _, p ->
            Row(
                Modifier.fillMaxWidth().raised().clip(RoundedCornerShape(16.dp)).clickable { nav.go(Route.PlaylistDetail(p.id)) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(I.Playlist, C.AmberText, 22.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = body(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.paths.size} songs", style = body(12.sp, color = C.TextMid))
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(nav: Nav) {
    Backdrop(Color(0xFF1B1F4A), Color(0xFF3D5BFF)) { PlaylistsList(nav, header = true) }
}

/** One playlist: play or shuffle it, remove or move songs, rename, export as M3U, delete. */
@Composable
fun PlaylistScreen(nav: Nav, id: Long) {
    val context = LocalContext.current
    val all by Playlists.all.collectAsState()
    val lib by AppState.library.collectAsState()
    val st by Playback.state.collectAsState()
    val p = all.firstOrNull { it.id == id } ?: Playlists.get(id) ?: return
    val tracks = remember(p, lib) { Playlists.tracks(p) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(p.name) { mutableStateOf(p.name) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Playlists.exportM3u(context, p, uri) }
    }
    Backdrop(Color(0xFF1B1F4A), Color(0xFF3D5BFF)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Header(p.name, { nav.back() }) }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stats by AppState.stats.collectAsState()
                    val plays = tracks.sumOf { stats[it.id]?.plays ?: 0 }
                    Text("${tracks.size} songs${if (tracks.size < p.paths.size) " (${p.paths.size - tracks.size} not in the library now)" else ""} · $plays plays", style = body(12.sp, color = C.TextMid))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Play", false) { if (tracks.isNotEmpty()) { Playback.playList(tracks, 0, shuffle = false); nav.go(Route.Player) } }
                        Chip("Shuffle", false) { if (tracks.isNotEmpty()) { Playback.playList(tracks, tracks.indices.random(), shuffle = true); nav.go(Route.Player) } }
                        Chip("+ Add songs", false) { nav.go(Route.AddSongs(p.id)) }
                        Chip("Export M3U", false) { export.launch("${p.name}.m3u8") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Rename", renaming) { renaming = !renaming }
                        Chip("Delete playlist", false) { Playlists.delete(p.id); nav.back() }
                    }
                    if (renaming) Row(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(name, { name = it }, singleLine = true, textStyle = body(14.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.weight(1f))
                        Text("Save", style = body(14.sp, FontWeight.Bold, C.Amber), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Playlists.rename(p.id, name); renaming = false }.padding(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            itemsIndexed(tracks, key = { i, t -> "${t.id}-$i" }) { i, t ->
                TrackRow(
                    t, st.current?.id == t.id, { Playback.playList(tracks, i, shuffle = false); nav.go(Route.Player) },
                    listOf(
                        MenuAction("Remove from playlist") { Playlists.removeAt(p.id, p.paths.indexOf(t.path)) },
                        MenuAction("Move up") { val k = p.paths.indexOf(t.path); Playlists.move(p.id, k, k - 1) },
                        MenuAction("Move down") { val k = p.paths.indexOf(t.path); Playlists.move(p.id, k, k + 1) },
                    ) + songActions(nav, t),
                )
            }
        }
    }
}
