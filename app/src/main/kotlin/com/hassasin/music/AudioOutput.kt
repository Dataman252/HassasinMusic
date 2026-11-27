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
data class OutputMode(val key: String, val title: String, val line: String, val effects: Boolean, val tags: List<String>, val more: Boolean = false)

object Outputs {
    val ALL = listOf(
        OutputMode(
            "tuned", "Phone",
            "Android's normal output, with your phone's own sound processing. Works on speaker, wired, Bluetooth and USB.",
            effects = true, tags = listOf("Default", "App effects", "Battery: low"),
        ),
        OutputMode(
            "direct", "Direct (phone)",
            "Skips Android's mixer and the phone's sound processing: the app writes straight into the phone's audio hardware (speaker or wired).",
            effects = true, tags = listOf("No phone processing", "App effects", "Battery: medium"),
        ),
        OutputMode(
            "usb", "Untouched (USB DAC)",
            "The exact file goes to your USB DAC through the app's own driver, at the song's own rate and bit depth.",
            effects = true, tags = listOf("Bit-perfect", "DAC volume", "Battery: higher (powers the DAC)"),
        ),
        OutputMode(
            "usb_android", "Untouched (Android USB)",
            "Android's own bit-perfect USB mode: the file goes unmixed to your DAC, and Android keeps the DAC for calls and other apps.",
            effects = false, tags = listOf("Bit-perfect", "Android 14+", "Battery: higher (powers the DAC)"),
        ),
        OutputMode(
            "hires", "Hi-Res",
            "Keeps 24-bit songs at full depth on their way to Android (when app effects are off).",
            effects = true, tags = listOf("24-bit", "Battery: low"), more = true,
        ),
        OutputMode(
            "offload", "Battery saver",
            "Your phone's audio chip plays the file itself (MP3, AAC and, where the phone supports it, FLAC), at its own rate. App effects pause.",
            effects = false, tags = listOf("Battery: lowest", "Native rate"), more = true,
        ),
    )

    fun of(key: String): OutputMode = ALL.firstOrNull { it.key == key } ?: ALL.first()

    /** The options this phone can use: Direct (phone) only where the phone allows it. */
    fun shown(): List<OutputMode> = ALL.filter { it.key != "direct" || PhoneDirectSession.available == true }

    /** Codecs the phone's audio chip can decode itself (offload), checked with Android. */
    fun offloadCodecs(context: Context): List<String> {
        if (android.os.Build.VERSION.SDK_INT < 31) return emptyList()
        val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val codecs = mutableListOf("MP3" to AudioFormat.ENCODING_MP3, "AAC" to AudioFormat.ENCODING_AAC_LC, "Opus" to AudioFormat.ENCODING_OPUS)
        if (android.os.Build.VERSION.SDK_INT >= 33) codecs += "FLAC" to 29 // AudioFormat.ENCODING_FLAC (hidden constant before API 35)
        return codecs.filter { (_, enc) ->
            runCatching {
                val f = AudioFormat.Builder().setEncoding(enc).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                AudioManager.getPlaybackOffloadSupport(f, attrs) != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED
            }.getOrDefault(false)
        }.map { it.first }
    }

    /** What each option can really do on this phone, shown under it. */
    fun onThisPhone(context: Context, key: String, st: PlayState): String {
        val mixer = AudioPathInfo.mixerRate(context).takeIf { it > 0 }?.let { "${khz(it)} kHz" } ?: "a fixed rate"
        return when (key) {
            "tuned" -> "On this phone: everything is mixed at $mixer."
            "hires" -> "On this phone: the mixer runs at $mixer, so you may hear little or no difference."
            "offload" -> offloadCodecs(context).let { c ->
                if (c.isEmpty()) "On this phone: not available; plays as Phone."
                else "On this phone: works for ${c.joinToString(" and ")} files.${if ("FLAC" !in c) " FLAC files play as Phone." else ""}"
            }
            "direct" -> "On this phone: the hardware runs at $mixer. Songs at that rate arrive untouched; others are converted by the app's own high-quality converter. Not for Bluetooth."
            "usb_android" -> when (val d = AndroidBitPerfect.describe(context)) {
                null -> if (android.os.Build.VERSION.SDK_INT < 34) "Needs Android 14 or newer." else "No USB DAC plugged in: plays as Phone until you plug one in."
                "" -> "This DAC offers no bit-perfect formats to Android: plays as Phone."
                else -> "This DAC offers: $d. Turn app effects off for bit-perfect."
            }
            "usb" -> when {
                st.usb -> "Now: playing on ${st.dacName.ifBlank { "your DAC" }}."
                st.dacPresent && !st.dacAllowed -> "DAC plugged in: allow access when Android asks."
                else -> "No DAC plugged in: plays as Phone until you plug one in."
            }
            else -> ""
        }
    }
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

    /** Short device name for the sound-path pill: Speaker, Bluetooth, Wired, USB. */
    fun deviceShort(context: Context): String = device(context).substringBefore(" · ").let {
        when (it) { "Phone speaker", "Phone" -> "Speaker"; "Wired headphones", "Wired headset" -> "Wired"; "Bluetooth LE" -> "Bluetooth"; else -> it }
    }

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
            return if ((mode == "hires" || mode == "usb_android") && !effects) floatSink else intSink
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
