package com.hassasin.music

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.PlaybackParameters
import kotlin.math.pow

/**
 * ReplayGain: songs (or whole albums) play at the same loudness, from the gain written in their tags.
 * Only turns songs down (the player's volume cannot go above full), and never past the song's peak.
 */
object ReplayGain {
    private val cache = HashMap<Long, Tags.Gain?>()
    @Volatile private var current = 1f

    fun gain(): Float = current

    /** Works out the gain for the song that just started (tags read off the main thread). */
    fun onTrack(t: Track?) {
        val s = AppState.settings.value
        if (t == null || s.replayGain == "off") { current = 1f; return }
        Thread {
            val g = synchronized(cache) { cache.getOrPut(t.id) { Tags.replayGain(t.path) } }
            val db = when (s.replayGain) { "album" -> g?.album ?: g?.track; else -> g?.track ?: g?.album }
            val peak = when (s.replayGain) { "album" -> g?.albumPeak ?: g?.trackPeak; else -> g?.trackPeak ?: g?.albumPeak }
            current = if (db == null) 10.0.pow(s.replayGainMissingDb / 20.0).toFloat().coerceAtMost(1f)
            else {
                var lin = 10.0.pow((db + s.replayGainPreampDb) / 20.0)
                if (peak != null && peak > 0) lin = minOf(lin, 1.0 / peak)
                lin.toFloat().coerceIn(0f, 1f)
            }
            Log.i("ReplayGain", "${t.title}: ${db ?: "none"} dB → ×${"%.2f".format(current)}")
            Dvc.applyNow()
        }.start()
    }

    fun describe(t: Track?): String = if (AppState.settings.value.replayGain == "off" || t == null) "" else "%.1f dB".format(20 * kotlin.math.log10(current.coerceAtLeast(1e-4f)))
}

/** Speed and pitch (Android output only: the direct outputs always play at 1×). */
object Speed {
    val CHOICES = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f)

    fun apply() {
        if (!Playback.isReady()) return
        val s = AppState.settings.value
        val sp = s.speed.coerceIn(0.25f, 3f)
        Playback.main { Playback.player.playbackParameters = PlaybackParameters(sp, if (s.keepPitch) 1f else sp) }
    }
}

/** Sleep timer: stops the music after a set time or at the end of the current song, fading out over the last 10 s. */
object SleepTimer {
    @Volatile var endsAt = 0L
        private set
    @Volatile var endOfSong = false
        private set
    private const val FADE_MS = 10_000L

    fun set(minutes: Int) { endOfSong = false; endsAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L }
    fun setEndOfSong() { endsAt = 0L; endOfSong = true }
    fun cancel() { endsAt = 0L; endOfSong = false; Dvc.applyNow() }
    fun active() = endsAt > 0 || endOfSong

    fun label(): String = when {
        endOfSong -> "End of song"
        endsAt > 0 -> "${((endsAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)} min left"
        else -> "Off"
    }

    /** Volume factor for the fade-out, 1 when not ending. */
    fun fade(): Float {
        if (endsAt <= 0) return 1f
        val left = endsAt - System.currentTimeMillis()
        return if (left >= FADE_MS) 1f else (left.coerceAtLeast(0).toFloat() / FADE_MS).let { it * it }
    }

    /** Called by the player loop. */
    fun tick() {
        if (endsAt > 0 && System.currentTimeMillis() >= endsAt) { endsAt = 0L; Playback.pauseForTimer() }
    }

    /** The song changed: with "end of song", stop now. */
    fun onSongEnded(): Boolean {
        if (!endOfSong) return false
        endOfSong = false
        Playback.pauseForTimer()
        return true
    }
}

/**
 * Resume when headphones come back: if music stopped because wired, USB or Bluetooth headphones were
 * unplugged or disconnected, it starts again when headphones are connected within 30 minutes.
 */
object AutoResume {
    private val outputs = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )
    private var pausedByUnplugAt = 0L

    fun register(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (removed.any { it.isSink && it.type in outputs } && Playback.isReady() && Playback.player.isPlaying) pausedByUnplugAt = System.currentTimeMillis()
            }

            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (!AppState.settings.value.autoResume) return
                if (added.none { it.isSink && it.type in outputs }) return
                if (pausedByUnplugAt > 0 && System.currentTimeMillis() - pausedByUnplugAt < 30 * 60_000L && Playback.isReady() && !Playback.player.isPlaying) {
                    pausedByUnplugAt = 0
                    // Give Bluetooth a moment to take the audio route.
                    Handler(Looper.getMainLooper()).postDelayed({ Playback.player.play() }, 1500)
                }
            }
        }, Handler(Looper.getMainLooper()))
    }
}

/** What changed in each version, shown once after an update and in Credits. */
object Changelog {
    val NOTES: List<Pair<String, List<String>>> = listOf(
        "0.9.1" to listOf(
            "Plays count only after 30 seconds of listening; every song shows its play count, playlists their total",
            "Listening time per song; generator: 'Played 5+' rules, 'Most listened' order and preset",
            "Add to playlist: pick a playlist or name a new one; '+ Add songs' inside a playlist",
            "Cover changes: real crossfade (Fade), settle-in (Zoom) and two-sided Flip",
            "Notification 'Artist only' shows the artist",
        ),
        "0.9" to listOf(
            "Settings in sections (Look, Playback, Sound & output, Lyrics & covers, Library, Lock screen & notification, About), with search",
            "Help improve: optional anonymous reports so problems on your phone can be fixed without asking you",
            "Generate playlists: quick picks (Discover, Favourites, Hi-Res mix, Fresh, Throwback, More like this artist) or your own rules",
        ),
        "0.8" to listOf(
            "What's new screen after every update",
            "ReplayGain: songs or albums at the same loudness (Settings)",
            "Playback speed and pitch, and a sleep timer with fade-out (⋯ menu)",
            "Playlists: create, add songs, import and export M3U",
            "Resume when headphones reconnect",
            "Home-screen widget",
            "App resampler: high-quality conversion to the phone's rate before Android",
            "Edit song tags (title, artist, album, year, track) for FLAC and MP3",
        ),
        "0.7" to listOf(
            "Output chain: every step from file to speaker, live CPU and battery draw",
            "Copy or share a diagnostics report",
            "Direct Volume Control (DVC): the app sets the volume in 64-bit",
            "Battery saver sends FLAC to the phone's audio chip where supported",
            "Less battery use with the screen off",
        ),
        "0.6" to listOf("Crossfade between songs", "Cover animation on song change: Slide, Fade, Zoom, Flip"),
        "0.5.2" to listOf("Easier lyric syncing: tap any line, real waveform, play/pause and time on the sync screen"),
        "0.5.1" to listOf("No jump at the end of screen changes"),
        "0.5" to listOf("Smoother screens", "Notification player upgrades", "Lyric sync pins and 'First line now'", "Library refresh and Recently added"),
        "0.4" to listOf("Clipping warning", "Hinglish for all Indian scripts and Urdu", "Tap to sync lyrics", "Share the app"),
    )

    /** The notes to show now, once, after an update (null when already seen or on a fresh install). */
    fun pending(context: Context): Pair<String, List<String>>? {
        val prefs = context.getSharedPreferences("changelog", Context.MODE_PRIVATE)
        val now = BuildConfig.VERSION_NAME
        val seen = prefs.getString("seen", null)
        if (seen == now) return null
        prefs.edit().putString("seen", now).apply()
        if (seen == null && prefs.getBoolean("fresh_done", false).not()) {
            prefs.edit().putBoolean("fresh_done", true).apply()
            // First run of a version with this screen: show it too (it was an update for existing users).
        }
        return NOTES.firstOrNull { it.first == now }
    }
}
