package com.hassasin.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The player's ⋯ menu: one tidy sheet for the song (rate, go to, playlist, tags, cover) and playback (speed, sleep timer). */
object SongSheet {
    var track by mutableStateOf<Track?>(null)
}

@Composable
fun SongSheetHost(nav: Nav) {
    val open = SongSheet.track != null
    // Last song shown, so the sheet keeps its content while it slides away.
    var shownTrack by remember { mutableStateOf<Track?>(null) }
    SongSheet.track?.let { shownTrack = it }
    fun close() { SongSheet.track = null }
    androidx.activity.compose.BackHandler(open) { close() }
    AnimatedVisibility(open, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
    }
    AnimatedVisibility(
        open, modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(tween(260)) { it }, exit = slideOutVertically(tween(200)) { it },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            shownTrack?.let { t -> SheetBody(nav, t) { close() } }
        }
    }
}

@Composable
private fun SheetBody(nav: Nav, t: Track, close: () -> Unit) {
    val s by AppState.settings.collectAsState()
    val stats by AppState.stats.collectAsState()
    val rating = stats[t.id]?.rating ?: 0
    val album = albumOf(t)
    fun go(r: Route) { close(); nav.go(r) }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(if (Look.soft) Color(0xFF26282D) else Color(0xFF141622))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f)))

        // The song, and its rating.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = rememberArtBitmap(album, 200)
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Ico(I.Note, C.TextLow, 24.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ArtRepo.cleanSongTitle(t.title).ifBlank { t.title }, style = body(16.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist.ifBlank { "Unknown artist" }, style = body(13.sp, color = C.TextMid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    (1..5).forEach { n ->
                        Box(Modifier.size(30.dp).clip(CircleShape).clickable { AppState.rate(t, if (rating == n) 0 else n) }.semantics { contentDescription = "$n stars" }, contentAlignment = Alignment.CenterStart) {
                            Ico(I.Star, if (n <= rating) C.Amber else C.TextLow, 20.dp, filled = n <= rating)
                        }
                    }
                }
            }
        }

        // Song actions, then app places: two rows of four.
        val actions = listOf(
            Triple(I.Album, "Album") { go(Route.AlbumDetail(t.albumKey)) },
            Triple(I.Artist, "Artist") { if (t.artist.isNotBlank()) LyricsRepo.mainArtist(t.artist).let { a -> go(Route.Tracks(a, "artist", a)) } },
            Triple(I.Playlist, "Add to playlist") { close(); AddToPlaylist.songs = listOf(t) },
            Triple(I.Note, "Edit tags") { go(Route.EditTags(t.id)) },
            Triple(I.Image, "Change cover") { go(Route.FixArt(album?.key ?: t.albumKey)) },
            Triple(I.Library, "Playlists") { go(Route.Playlists) },
            Triple(I.HiRes, "Audiophile") { go(Route.SettingsPage("audio")) },
            Triple(I.Settings, "Settings") { go(Route.Settings) },
        )
        actions.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, label, act) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = act).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) { Ico(icon, C.TextHi, 22.dp) }
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = body(11.sp, FontWeight.SemiBold, C.TextMid), textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }

        // Speed (with pitch) on one line of chips.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SPEED", style = eyebrow(), modifier = Modifier.weight(1f))
                Text(if (s.keepPitch) "Pitch stays" else "Pitch follows", style = body(12.sp, FontWeight.SemiBold, if (s.keepPitch) C.AmberText else C.TextMid),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AppState.update { it.copy(keepPitch = !it.keepPitch) }; Speed.apply() }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Speed.CHOICES.forEach { sp -> Chip(if (sp == 1f) "1×" else "${sp}×", kotlin.math.abs(s.speed - sp) < 0.01f) { AppState.update { it.copy(speed = sp) }; Speed.apply() } }
            }
        }

        // Sleep timer on one line of chips.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SLEEP TIMER · ${SleepTimer.label().uppercase()}", style = eyebrow())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Off", !SleepTimer.active()) { SleepTimer.cancel() }
                listOf(15, 30, 45, 60, 90).forEach { m -> Chip("$m min", false) { SleepTimer.set(m); close() } }
                Chip("End of song", SleepTimer.endOfSong) { SleepTimer.setEndOfSong(); close() }
            }
        }
    }
}
