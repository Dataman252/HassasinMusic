package com.hassasin.music

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

@Serializable
data class AppSettings(
    /**
     * Output: "tuned" (the phone's decoder and sound tuning, default), "hires" (32-bit float to
     * Android), "offload" (the phone's audio chip decodes), "usb" (USB Direct, bit-perfect to a DAC).
     */
    val output: String = "tuned",
    val dacVolumeDb: Double = -40.0,
    /** Highest DAC volume allowed, to protect sensitive earphones. */
    val dacMaxDb: Double = -10.0,
    val sound: SoundSettings = SoundSettings(),
    val userPresets: Map<String, List<Double>> = emptyMap(),
    val autoArt: Boolean = true,
    /** Covers from Apple's iTunes catalogue (best for Indian releases); MusicBrainz is always used too. */
    val artFromApple: Boolean = true,
    val autoLyrics: Boolean = true,
    /** Show Hindi lyrics in Roman letters (Hinglish). */
    val lyricsHinglish: Boolean = false,
    /** "glow" (cover-coloured light) or "soft" (matte neumorphic, indigo accent). */
    val theme: String = "glow",
    /** Seek bar style behind the play controls: wave, mirror, line, capsule, ring, dots, squiggle. */
    val seekStyle: String = "wave",
    /** Visualizer on the player: off, wave, bars, mirror. */
    val vizStyle: String = "wave",
    val onlineOnWifiOnly: Boolean = false,
    /** Show the player (in the chosen look) on the lock screen while music plays. */
    val lockScreen: Boolean = true,
    /** Seconds the end of a song fades out and the next fades in (0 = off). Songs of one album stay gapless. */
    val crossfadeSec: Int = 0,
    /** Direct Volume Control: the app applies the volume (64-bit); Android's media volume stays at maximum. */
    val dvc: Boolean = false,
    /** ReplayGain: "off", "track" or "album". */
    val replayGain: String = "off",
    val replayGainPreampDb: Double = 0.0,
    /** Gain for songs without ReplayGain tags (≤ 0 dB). */
    val replayGainMissingDb: Double = 0.0,
    /** Playback speed (Android output only) and whether the pitch stays the same. */
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    /** Start again when headphones reconnect after being unplugged. */
    val autoResume: Boolean = true,
    /** Anonymous reports: 0 = not asked yet, 1 = allowed, 2 = declined. */
    val helpImprove: Int = 0,
    /** Convert songs to the phone's mixer rate in the app (high-quality sinc) instead of Android's resampler. */
    val appResampler: Boolean = false,
    /** App volume step 0..30 (2 dB each, 30 = 0 dB). */
    val dvcStep: Int = 20,
    /** How the cover changes with the song: "slide", "fade", "zoom", "flip". */
    val coverAnim: String = "slide",
    /** Second line of the notification card: "quality" (artist · format · output), "lyrics" (the line sung now), "artist". */
    val notifLine: String = "quality",
    /** Buttons beside the play controls on the card: "shuffle_repeat", "seek", "love_lyrics". */
    val notifButtons: String = "shuffle_repeat",
    /** Cover sharpness on the player, −100 (softer) … 0 (as is) … 200 (sharper). */
    val artSharpness: Int = 0,
    /** Shorter files (ringtones, voice notes) are left out of the library. */
    val minDurationSec: Int = 45,
    /** Play music only from these folders (empty = every folder). */
    val musicFolders: List<String> = emptyList(),
    val hiddenFolders: List<String> = listOf("WhatsApp", "Notifications", "Ringtones", "Alarms", "Recordings", "Call"),
    val shuffle: Boolean = false,
    val repeat: Int = 0, // 0 off, 1 all, 2 one
    /** The first-run tour has been seen (or skipped). */
    val tourDone: Boolean = false,
    /** The best-quality setup has been run, and for which listening device. */
    val setupDone: Boolean = false,
    val setupDevice: String = "",
    /** App resampler filter: "sharp" (48-tap linear phase), "steep" (128-tap), "soft" (24-tap, least ringing), "minphase" (no pre-ringing). */
    val resamplerFilter: String = "sharp",
    /** Dither when the app makes 16-bit: "tpdf" (flat), "shaped" (noise moved to where ears are least sensitive), "off". */
    val dither: String = "tpdf",
    /** What the app resampler hands to Android: "auto" (32-bit float where possible) or "16" (16-bit, dithered). */
    val outDepth: String = "auto",
    /** Pure mode: what was on before it, so turning it off brings it all back. Null = pure mode off. */
    val pureBackup: PureBackup? = null,
    /** DSD files: "dop" (native DSD to a USB DAC that takes it, PCM otherwise) or "pcm" (always converted). */
    val dsdMode: String = "dop",
)

/** Settings put aside while Pure mode is on. */
@Serializable
data class PureBackup(val sound: SoundSettings, val crossfadeSec: Int, val replayGain: String, val speed: Float)

@Serializable
data class TrackStat(val plays: Int = 0, val lastPlayed: Long = 0, val rating: Int = 0, /** Seconds really listened, all plays together. */ val listenedSec: Long = 0)

@Serializable
data class Resume(val queue: List<Long> = emptyList(), val index: Int = 0, val positionMs: Long = 0)

/** Small JSON files in the app's private folder, written through a temp file so a crash never leaves half a file. */
object Store {
    private lateinit var dir: File

    fun init(context: Context) { dir = context.filesDir }

    fun file(name: String) = File(dir, name)

    inline fun <reified T> load(name: String, default: T): T = runCatching {
        val f = file(name)
        if (!f.exists()) default else json.decodeFromString<T>(f.readText())
    }.getOrDefault(default)

    inline fun <reified T> save(name: String, value: T) {
        val f = file(name)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(value))
        tmp.renameTo(f)
    }
}
