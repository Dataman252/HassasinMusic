package com.hassasin.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A direct output the sink can stream 32-bit stereo frames to: the USB DAC engine or the phone's own. */
interface DirectOut {
    /** "usb" (the app's own USB driver) or "phone" (the phone's direct hardware output). */
    val kind: String
    /** The rate the sink feeds (the song's rate). */
    val outRate: Int
    val outBits: Int
    val outName: String
    fun startAt(hz: Int): Boolean
    fun write(data: ByteArray, offset: Int, length: Int): Int
    fun ringFrames(): Long
    fun framesPlayed(): Long
    fun underruns(): Long
    fun maxGapUs(): Long
    fun isRunning(): Boolean
    fun setPaused(p: Boolean)
    fun flush()
    fun close()
    fun setVolumeDb(db: Double): Double?
}

object PhoneDirectNative {
    init { System.loadLibrary("usbaudio") }
    @JvmStatic external fun open(bufferMs: Int, allowShared: Boolean): Long
    @JvmStatic external fun setGain(h: Long, linear: Double)
    @JvmStatic external fun hwRate(h: Long): Int
    @JvmStatic external fun hwBits(h: Long): Int
    @JvmStatic external fun exclusive(h: Long): Boolean
    @JvmStatic external fun setInputRate(h: Long, rate: Int)
    @JvmStatic external fun write(h: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun queuedFrames(h: Long): Long
    @JvmStatic external fun framesPlayed(h: Long): Long
    @JvmStatic external fun underruns(h: Long): Long
    @JvmStatic external fun isRunning(h: Long): Boolean
    @JvmStatic external fun setPaused(h: Long, p: Boolean)
    @JvmStatic external fun flush(h: Long)
    @JvmStatic external fun close(h: Long)
}

/**
 * The phone's own direct output: an exclusive (MMAP) AAudio stream that writes straight into the
 * hardware buffer, skipping Android's mixer, its effects and the phone maker's sound processing.
 * The hardware runs at one rate (48 kHz here); songs at that rate arrive bit-exact, others are
 * converted by the app's own high-quality resampler. Android's volume still applies (in hardware).
 */
class PhoneDirectSession private constructor(private val handle: Long) : DirectOut {
    override val kind = "phone"
    override var outRate = 0
        private set
    val hwRate: Int = PhoneDirectNative.hwRate(handle)
    override val outBits: Int = PhoneDirectNative.hwBits(handle)
    override val outName = "Phone"
    private var closed = false

    companion object {
        private const val TAG = "PhoneDirect"

        /**
         * Whether this phone lets apps open its direct (exclusive) output at all: null until checked.
         * Many phones (the vivo tested included) block it for every app, so the option is hidden there.
         */
        var available: Boolean? by mutableStateOf(null)
            private set

        /** Checks once per app start, off the main thread; moves a saved "direct" choice back to Phone if blocked. */
        fun checkAvailable() {
            if (available != null) return
            Thread {
                val ok = if (UsbLive.active && UsbLive.kind == "phone") true else open()?.let { it.close(); true } ?: false
                available = ok
                Log.i(TAG, "phone direct output ${if (ok) "available" else "blocked by this phone"}")
                if (!ok && AppState.settings.value.output == "direct") AppState.update { it.copy(output = "tuned") }
            }.start()
        }

        /** Opens the exclusive stream, or null when the phone only offers a shared (mixed) one right now. */
        fun open(): PhoneDirectSession? {
            val h = runCatching { PhoneDirectNative.open(300, false) }.getOrElse { Log.w(TAG, "open failed", it); 0L }
            if (h == 0L) { Log.i(TAG, "no exclusive stream: the phone mixes this output"); return null }
            return PhoneDirectSession(h)
        }
    }

    override fun startAt(hz: Int): Boolean {
        if (closed) return false
        PhoneDirectNative.setInputRate(handle, hz)
        outRate = hz
        return PhoneDirectNative.isRunning(handle)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) = if (closed) -1 else PhoneDirectNative.write(handle, data, offset, length)
    override fun ringFrames() = if (closed) 0 else PhoneDirectNative.queuedFrames(handle)
    override fun framesPlayed() = if (closed) 0 else PhoneDirectNative.framesPlayed(handle)
    override fun underruns() = if (closed) 0 else PhoneDirectNative.underruns(handle)
    override fun maxGapUs() = 0L
    override fun isRunning() = !closed && PhoneDirectNative.isRunning(handle)
    override fun setPaused(p: Boolean) { if (!closed) PhoneDirectNative.setPaused(handle, p) }
    override fun flush() { if (!closed) PhoneDirectNative.flush(handle) }
    /** App volume (DVC) on the phone's direct output: applied in 64-bit inside the engine. */
    override fun setVolumeDb(db: Double): Double? {
        if (closed) return null
        PhoneDirectNative.setGain(handle, Math.pow(10.0, db / 20.0))
        return db
    }
    override fun close() {
        if (closed) return
        closed = true
        PhoneDirectNative.close(handle)
    }
}

/**
 * Android's own bit-perfect USB mode (Android 14+): asks Android to send this app's music to the
 * USB DAC unmixed, at the song's own rate and format. Android keeps the DAC (calls and other apps
 * still work); system sounds are muted while it plays.
 */
object AndroidBitPerfect {
    private const val TAG = "BitPerfect"
    private val attrs: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    /** What was last asked for, for the details panel. */
    @Volatile var active: String = ""

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 34

    fun usbDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
    }

    /** The formats the DAC offers in bit-perfect mode, e.g. "16-bit 44.1/48/96 kHz". */
    fun describe(context: Context): String? {
        if (!supported()) return null
        val dev = usbDevice(context) ?: return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val list = runCatching { am.getSupportedMixerAttributes(dev) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (list.isEmpty()) return ""
        return list.groupBy { AudioPathInfo.encodingName(it.format.encoding) }.entries.joinToString("; ") { (enc, l) ->
            "$enc ${l.map { it.format.sampleRate }.distinct().sorted().joinToString("/") { khz(it) }} kHz"
        }
    }

    /**
     * Prepares bit-perfect output for a song at [rate] Hz sent as [encoding] (16-bit or float).
     * Returns true when Android accepted it.
     */
    fun prepare(context: Context, rate: Int, encoding: Int): Boolean {
        if (!supported()) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dev = usbDevice(context) ?: run { clear(context); return false }
        val options = runCatching { am.getSupportedMixerAttributes(dev) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && it.format.sampleRate == rate }
        val pick = options.firstOrNull { it.format.encoding == encoding }
        if (pick == null) {
            Log.i(TAG, "DAC offers no bit-perfect ${AudioPathInfo.encodingName(encoding)} at $rate Hz (has ${options.map { AudioPathInfo.encodingName(it.format.encoding) }})")
            clear(context)
            return false
        }
        val ok = runCatching { am.setPreferredMixerAttributes(attrs, dev, pick) }.getOrDefault(false)
        active = if (ok) "${AudioPathInfo.encodingName(encoding)} · ${khz(rate)} kHz" else ""
        Log.i(TAG, "bit-perfect ${active.ifBlank { "refused" }} on ${dev.productName}")
        return ok
    }

    fun clear(context: Context) {
        if (!supported() || active.isEmpty()) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        usbDevice(context)?.let { dev -> runCatching { am.clearPreferredMixerAttributes(attrs, dev) } }
        active = ""
    }
}
