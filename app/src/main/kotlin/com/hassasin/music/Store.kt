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
    /** Cover sharpness on the player, −100 (softer) … 0 (as is) … 200 (sharper). */
    val artSharpness: Int = 0,
    /** Shorter files (ringtones, voice notes) are left out of the library. */
    val minDurationSec: Int = 45,
    /** Play music only from these folders (empty = every folder). */
    val musicFolders: List<String> = emptyList(),
    val hiddenFolders: List<String> = listOf("WhatsApp", "Notifications", "Ringtones", "Alarms", "Recordings", "Call"),
    val shuffle: Boolean = false,
    val repeat: Int = 0, // 0 off, 1 all, 2 one
)

@Serializable
data class TrackStat(val plays: Int = 0, val lastPlayed: Long = 0, val rating: Int = 0)

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
