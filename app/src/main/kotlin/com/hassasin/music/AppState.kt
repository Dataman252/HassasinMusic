package com.hassasin.music

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        LyricsRepo.init(this)
        ArtRepo.init(this)
        AppState.init(this)
        Playback.init(this)
    }
}

/** Lock screen, notification and headset controls. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private val SHUFFLE = androidx.media3.session.SessionCommand("hassasin.SHUFFLE", android.os.Bundle.EMPTY)
        private val REPEAT = androidx.media3.session.SessionCommand("hassasin.REPEAT", android.os.Bundle.EMPTY)
    }

    override fun onCreate() {
        super.onCreate()
        Playback.init(this)
        LockScreen.register(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_hassasin) },
        )
        val s = MediaSession.Builder(this, Playback.player)
            .setSessionActivity(open)
            .setBitmapLoader(ArtBitmapLoader(this))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SHUFFLE).add(REPEAT).build())
                        .build()

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand, args: android.os.Bundle,
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    when (customCommand.customAction) {
                        SHUFFLE.customAction -> Playback.setShuffle(!Playback.state.value.shuffle)
                        REPEAT.customAction -> Playback.cycleRepeat()
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        session = s
        // Shuffle and repeat buttons beside the play controls, showing their current state.
        scope.launch {
            Playback.state.collect { st -> s.setCustomLayout(buttons(st.shuffle, st.repeat)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun buttons(shuffle: Boolean, repeat: Int) = com.google.common.collect.ImmutableList.of(
        androidx.media3.session.CommandButton.Builder(if (shuffle) androidx.media3.session.CommandButton.ICON_SHUFFLE_ON else androidx.media3.session.CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (shuffle) "Shuffle on" else "Shuffle off").setSessionCommand(SHUFFLE).build(),
        androidx.media3.session.CommandButton.Builder(
            when (repeat) { 1 -> androidx.media3.session.CommandButton.ICON_REPEAT_ALL; 2 -> androidx.media3.session.CommandButton.ICON_REPEAT_ONE; else -> androidx.media3.session.CommandButton.ICON_REPEAT_OFF },
        ).setDisplayName(when (repeat) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" }).setSessionCommand(REPEAT).build(),
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends it; while playing, music keeps going.
        if (!Playback.player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        session?.run { release() }
        session = null
        super.onDestroy()
    }
}

/**
 * Gives the notification, lock screen and car/watch controls the same cover the app shows
 * (songs carry "hassasin-art://<track id>" as their artwork address).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArtBitmapLoader(context: Context) : androidx.media3.common.util.BitmapLoader {
    private val fallback = androidx.media3.datasource.DataSourceBitmapLoader(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray) = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: android.net.Uri): com.google.common.util.concurrent.ListenableFuture<android.graphics.Bitmap> {
        if (uri.scheme != "hassasin-art") return fallback.loadBitmap(uri)
        val future = com.google.common.util.concurrent.SettableFuture.create<android.graphics.Bitmap>()
        scope.launch {
            val id = uri.host?.toLongOrNull()
            val track = AppState.library.value.tracks.firstOrNull { it.id == id }
            val bmp = albumOf(track)?.let { ArtRepo.bitmap(it, 720) }
            if (bmp != null) future.set(bmp) else future.setException(IllegalStateException("no cover"))
        }
        return future
    }
}

data class LibraryData(
    val tracks: List<Track> = emptyList(),
    /** Every folder with music, chosen or not (for the folder picker). */
    val allFolders: List<String> = emptyList(),
    val albums: List<Album> = emptyList(),
    val scanned: Boolean = false,
)

/** Settings, library and statistics shared by every screen. */
object AppState {
    private const val TAG = "AppState"
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    private val _library = MutableStateFlow(LibraryData())
    val library: StateFlow<LibraryData> = _library

    private val _stats = MutableStateFlow<Map<Long, TrackStat>>(emptyMap())
    val stats: StateFlow<Map<Long, TrackStat>> = _stats

    /** What the background art finder is doing, for the Fix art screen. */
    val artStatus = MutableStateFlow("")

    fun init(context: Context) {
        appContext = context.applicationContext
        _settings.value = Store.load("settings.json", AppSettings())
        SoundState.set(_settings.value.sound)
        _stats.value = Store.load<Map<Long, TrackStat>>("stats.json", emptyMap())
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        _settings.value = s
        if (s.sound != SoundState.settings) SoundState.set(s.sound)
        scope.launch(Dispatchers.IO) { runCatching { Store.save("settings.json", s) } }
    }

    fun updateSound(change: (SoundSettings) -> SoundSettings) = update { it.copy(sound = change(it.sound)) }

    fun countPlay(t: Track) {
        val m = _stats.value.toMutableMap()
        val old = m[t.id] ?: TrackStat()
        m[t.id] = old.copy(plays = old.plays + 1, lastPlayed = System.currentTimeMillis())
        _stats.value = m
        scope.launch(Dispatchers.IO) { runCatching { Store.save("stats.json", m) } }
    }

    fun rate(t: Track, stars: Int) {
        val m = _stats.value.toMutableMap()
        m[t.id] = (m[t.id] ?: TrackStat()).copy(rating = stars)
        _stats.value = m
        scope.launch(Dispatchers.IO) { runCatching { Store.save("stats.json", m) } }
    }

    private var artJob: Job? = null

    suspend fun scan() {
        val all = withContext(Dispatchers.IO) { Library.scan(appContext, _settings.value) }
        val chosen = _settings.value.musicFolders
        val tracks = if (chosen.isEmpty()) all else all.filter { t -> chosen.any { f -> t.folder == f || t.folder.startsWith("$f/") } }
        ArtRepo.setLibrary(tracks)
        _library.value = LibraryData(tracks, all.map { it.folder }.distinct().sorted(), Library.albums(tracks), true)
        withContext(Dispatchers.Main) { Playback.restore(tracks) }
        if (_settings.value.autoArt) findMissingArt(includeProblems = true)
        fetchAllLyrics()
    }

    /** Albums, plus each untagged song as its own picture "album". */
    fun artTargets(): List<Album> {
        val lib = _library.value
        return lib.albums.filterNot { it.key.startsWith("folder:") } + lib.tracks.mapNotNull { ArtRepo.artAlbumFor(it) }
    }

    fun albumByKey(key: String): Album? =
        if (key.startsWith("track:")) _library.value.tracks.firstOrNull { "track:${it.id}" == key }?.let { ArtRepo.artAlbumFor(it) }
        else _library.value.albums.firstOrNull { it.key == key }

    /**
     * Looks for art online (sure matches only): albums and songs with no picture, and with
     * [replace] also the albums listed as problems.
     */
    fun findMissingArt(replace: Set<String> = emptySet(), includeProblems: Boolean = false) {
        if (artJob?.isActive == true) return
        artJob = scope.launch(Dispatchers.IO) {
            if (!Net.allowed(appContext, _settings.value.onlineOnWifiOnly)) { artStatus.value = "Offline: can't look for pictures"; return@launch }
            val targets = artTargets()
            val fix = if (includeProblems) replace + ArtRepo.problems(targets).filter { it.info != null }.map { it.album.key } else replace
            val n = ArtRepo.autoFill(targets, fix) { artStatus.value = "Looking for “$it”" }
            artStatus.value = if (n > 0) "Found $n picture${if (n == 1) "" else "s"} online" else "No new sure matches found"
            Log.i(TAG, "art auto-fill done: $n")
        }
    }

    private var lyricsJob: Job? = null
    private var lyricsNowJob: Job? = null

    /**
     * Fetches lyrics for the whole library in the background (LRCLIB), so the lyrics screen opens
     * with them ready. Songs with lyrics in the file or beside it are skipped.
     */
    fun fetchAllLyrics() {
        if (!_settings.value.autoLyrics || lyricsJob?.isActive == true) return
        lyricsJob = scope.launch(Dispatchers.IO) {
            var found = 0
            for (t in _library.value.tracks) {
                if (!Net.allowed(appContext, _settings.value.onlineOnWifiOnly)) break
                if (!LyricsRepo.needsLookup(t)) continue
                if (LyricsRepo.sidecar(t) != null || Tags.embeddedLyrics(t.path) != null) continue
                if (LyricsRepo.find(appContext, t, online = true) != null) found++
                kotlinx.coroutines.delay(400)
            }
            Log.i(TAG, "background lyrics done: $found found")
        }
    }

    /** The song that just started (and the next one): lyrics ready before they are asked for. */
    fun fetchLyricsNow(tracks: List<Track>) {
        if (!_settings.value.autoLyrics) return
        lyricsNowJob?.cancel()
        lyricsNowJob = scope.launch(Dispatchers.IO) {
            for (t in tracks) if (LyricsRepo.needsLookup(t) && Net.allowed(appContext, _settings.value.onlineOnWifiOnly)) LyricsRepo.find(appContext, t, online = true)
        }
    }

    private var nowJob: Job? = null

    /** The song that just started has no picture: look for one right away (sure matches only). */
    fun findArtNow(t: Track) {
        if (!_settings.value.autoArt) return
        nowJob?.cancel()
        nowJob = scope.launch(Dispatchers.IO) {
            val a = ArtRepo.artAlbumFor(t) ?: _library.value.albums.firstOrNull { it.key == t.albumKey } ?: return@launch
            if (!Net.allowed(appContext, _settings.value.onlineOnWifiOnly)) return@launch
            val n = ArtRepo.autoFill(listOf(a))
            if (n > 0) artStatus.value = "Found the picture for “${a.title}”"
        }
    }

    fun online(): Boolean = Net.allowed(appContext, _settings.value.onlineOnWifiOnly)
}
