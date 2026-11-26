package com.hassasin.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** One way of getting the sound out, as listed on the Output tab. */
data class OutputMode(val key: String, val title: String, val line: String, val effects: Boolean, val tags: List<String>)

object Outputs {
    val ALL = listOf(
        OutputMode(
            "tuned", "Rich tuned (phone)",
            "The phone's own decoder and sound tuning: Android's mixer plus the phone maker's enhancements (Hi-Fi, Dolby, etc.). Works everywhere: speaker, wired, Bluetooth, USB.",
            effects = true, tags = listOf("Default", "Phone tuning", "App effects"),
        ),
        OutputMode(
            "hires", "Hi-Res (32-bit float)",
            "24-bit and higher files go to Android in 32-bit float at their own rate, so nothing is cut to 16-bit. The phone's mixer and tuning still apply. While app effects are on, this works like Rich tuned.",
            effects = true, tags = listOf("Full bit depth", "Phone tuning"),
        ),
        OutputMode(
            "offload", "Phone DSP (offload)",
            "The compressed file goes straight to the phone's audio chip, which decodes and plays it. Lowest battery use. App effects and the visualizer are off; the phone's own tuning still applies.",
            effects = false, tags = listOf("Hardware decoder", "Battery saver"),
        ),
        OutputMode(
            "usb", "USB Direct (bit-perfect)",
            "Straight to your USB DAC at each song's own rate and bit depth, skipping Android's mixer and the phone's tuning. With app effects off, the DAC gets the file's exact samples. Without a DAC, plays as Rich tuned.",
            effects = true, tags = listOf("Bit-perfect", "DAC volume"),
        ),
    )

    fun of(key: String): OutputMode = ALL.firstOrNull { it.key == key } ?: ALL.first()
}

/** What the decoder and output are doing right now (for the quality details). */
data class AudioPath(
    val inputMime: String = "",
    val decoder: String = "",
    val trackEncoding: Int = 0,
    val trackRate: Int = 0,
    val trackChannels: Int = 0,
    val offload: Boolean = false,
    val updatedAt: Long = 0,
)

object AudioPathInfo {
    val path = MutableStateFlow(AudioPath())

    /** Who decodes the file, in plain words. */
    fun decoderLine(p: AudioPath, usb: Boolean): Pair<String, String> {
        val d = p.decoder
        return when {
            p.offload -> "Phone's audio chip" to "The compressed file is decoded by the phone's DSP (offload)"
            p.inputMime == "audio/raw" || p.inputMime.isBlank() && d.isBlank() -> "None (raw PCM)" to "Uncompressed file: its samples are read as they are"
            d.contains("ffmpeg", true) -> "Bundled FFmpeg" to "Decoded in the app by FFmpeg (software, exact)"
            d.startsWith("c2.android.") || d.startsWith("OMX.google.") -> "Android software" to "Android's own software decoder ($d)"
            d.startsWith("c2.") || d.startsWith("OMX.") -> "Phone hardware" to "The phone maker's decoder ($d)"
            d.isNotBlank() -> "Decoder" to d
            else -> "…" to (if (usb) "Starting" else "Starting")
        }
    }

    fun encodingName(e: Int): String = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit PCM"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit PCM"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit PCM"
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        AudioFormat.ENCODING_PCM_8BIT -> "8-bit PCM"
        AudioFormat.ENCODING_MP3 -> "MP3 (compressed)"
        AudioFormat.ENCODING_AAC_LC, AudioFormat.ENCODING_AAC_HE_V1, AudioFormat.ENCODING_AAC_HE_V2 -> "AAC (compressed)"
        AudioFormat.ENCODING_OPUS -> "Opus (compressed)"
        else -> if (e == 0) "…" else "format $e"
    }

    /** Android's mixer rate (what everything is resampled to on the normal path). */
    fun mixerRate(context: Context): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

    /** Where Android is sending media right now (best guess from what is connected). */
    fun device(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun name(d: AudioDeviceInfo, kind: String) = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }?.let { "$kind · $it" } ?: kind
        val order = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Bluetooth",
            AudioDeviceInfo.TYPE_BLE_HEADSET to "Bluetooth LE",
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB",
            AudioDeviceInfo.TYPE_USB_DEVICE to "USB",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "Wired headphones",
            AudioDeviceInfo.TYPE_WIRED_HEADSET to "Wired headset",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Phone speaker",
        )
        for ((type, kind) in order) outs.firstOrNull { it.type == type }?.let { return name(it, kind) }
        return "Phone"
    }
}

/**
 * Android's output with two sinks behind it: one that sends 16-bit (with the app's effects) and one
 * that keeps hi-res files in 32-bit float. The output mode picks one each time the sound is set up.
 */
@UnstableApi
object DualSink {
    fun create(intSink: AudioSink, floatSink: AudioSink): AudioSink {
        var active: AudioSink = intSink
        fun wanted(): AudioSink {
            val mode = AppState.settings.value.output
            val effects = Eq.anyEffect(SoundState.settings, false)
            return if (mode == "hires" && !effects) floatSink else intSink
        }
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            val a = args ?: emptyArray()
            fun call(target: AudioSink): Any? = try { method.invoke(target, *a) } catch (e: InvocationTargetException) { throw e.targetException }
            when (method.name) {
                "configure" -> {
                    val next = wanted()
                    if (next !== active) { runCatching { active.flush() }; runCatching { active.reset() }; active = next }
                    call(active)
                }
                "getFormatSupport", "supportsFormat", "getFormatOffloadSupport" -> call(wanted())
                // Settings go to both, so the other one is ready when it takes over.
                "setListener", "setPlayerId", "setClock", "setPlaybackParameters", "setSkipSilenceEnabled", "setAudioAttributes",
                "setAudioSessionId", "setAuxEffectInfo", "setPreferredDevice", "setVolume", "setOffloadMode", "setOffloadDelayPadding",
                "enableTunnelingV21", "disableTunneling", "setOutputStreamOffsetUs", "release" -> {
                    val other = if (active === intSink) floatSink else intSink
                    runCatching { call(other) }
                    call(active)
                }
                "equals" -> a.firstOrNull() === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DualSink(${if (active === intSink) "16-bit" else "float"})"
                else -> call(active)
            }
        }
        return Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java), handler) as AudioSink
    }
}
