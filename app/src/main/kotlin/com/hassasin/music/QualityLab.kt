package com.hassasin.music

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// ============================================================ What a device can do, and how the app plays to it

/** The settings that decide the sound on one device. */
@Serializable
data class Tuning(val output: String, val dvc: Boolean, val resampler: Boolean, val dsdMode: String = "dop", val usbResample: String = "auto")

/** One tested output device: what it can do, what was found to be its limits, and (if changed by hand) its own settings. */
@Serializable
data class DeviceProfile(
    val key: String, val kind: String, val name: String, val testedAt: Long = 0,
    /** Rates the device takes (USB: listed by the DAC and confirmed by switching to each; Android: what it reports). */
    val rates: List<Int> = emptyList(),
    val bits: List<Int> = emptyList(),
    val channels: Int = 2,
    /** DSD multiples (64, 128, 256…) the DAC can play natively as DoP. */
    val dopDsd: List<Int> = emptyList(),
    /** The DAC offers a raw DSD format (not used by the app yet). */
    val rawDsd: Boolean = false,
    val volume: String = "",
    /** The stability test: highest rate, and gaps heard during it (-1 = not run). */
    val stableAt: Int = 0, val gaps: Long = -1,
    val mixerRate: Int = 0,
    /** Direct (phone) output is possible: the app can skip Android's mixer. */
    val direct: Boolean = false,
    /** Formats Android plays on this output without mixing them. */
    val unmixed: List<String> = emptyList(),
    val offload: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val limits: List<String> = emptyList(),
    /** Settings chosen by hand for this device (null = the recommended ones). */
    val tuning: Tuning? = null,
) {
    val complete: Boolean get() = testedAt > 0 && (kind != "usb" || rates.isNotEmpty())
}

object DeviceLab {
    private const val TAG = "DeviceLab"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val profiles = MutableStateFlow<Map<String, DeviceProfile>>(emptyMap())
    /** What the test is doing right now ("" = no test running). */
    val testing = MutableStateFlow("")
    /** A short note after the app tunes itself for a device ("" = none). */
    val banner = MutableStateFlow("")

    /** Rates worth testing and showing (below 44.1 kHz is for voice, not music). */
    val STANDARD = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000)

    @Volatile private var loaded = false
    @Volatile private var watching = false
    @Volatile private var lastApplied: String? = null
    @Volatile private var appliedAt = 0L

    fun load() {
        if (loaded) return
        loaded = true
        profiles.value = Store.load("devices.json", emptyMap())
    }

    private fun put(p: DeviceProfile) {
        profiles.value = profiles.value + (p.key to p)
        val m = profiles.value
        scope.launch(Dispatchers.IO) { runCatching { Store.save("devices.json", m) } }
    }

    fun forget(key: String) {
        profiles.value = profiles.value - key
        if (lastApplied == key) lastApplied = null
        val m = profiles.value
        scope.launch(Dispatchers.IO) { runCatching { Store.save("devices.json", m) } }
    }

    data class Current(val key: String, val kind: String, val name: String)

    /** What the sound goes to now: a USB DAC first (the app drives it itself), then Bluetooth, wired, the speaker. */
    fun current(context: Context): Current {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbDacProbe.findDac(usb)?.let { d -> return Current("usb:${d.vendorId}:${d.productId}", "usb", d.productName?.trim()?.ifBlank { null } ?: "USB DAC") }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }?.let { u ->
            val n = u.productName?.toString()?.trim().orEmpty()
            profiles.value.values.firstOrNull { it.kind == "usb" && it.name == n }?.let { return Current(it.key, "usb", it.name) }
        }
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }?.let {
            val n = it.productName?.toString()?.trim().orEmpty().ifBlank { "Bluetooth" }
            return Current("bt:$n", "bt", n)
        }
        if (outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }) return Current("wired", "wired", "Wired earphones")
        return Current("speaker", "speaker", "Phone speaker")
    }

    fun kindName(kind: String) = when (kind) { "usb" -> "USB DAC"; "bt" -> "Bluetooth"; "wired" -> "3.5 mm jack"; else -> "Phone speaker" }

    // ------------------------------------------------------------ Tests

    /** Tests what is connected now, keeps the result, and returns it. */
    suspend fun test(context: Context): DeviceProfile = withContext(Dispatchers.IO) {
        load()
        val cur = current(context)
        val old = profiles.value[cur.key]
        testing.value = "Starting…"
        val p = try {
            if (cur.kind == "usb") testUsb(context, cur) else testAndroid(context, cur)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "test failed", e)
            DeviceProfile(cur.key, cur.kind, cur.name, System.currentTimeMillis(), notes = listOf("The test stopped: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            testing.value = ""
        }
        val done = p.copy(limits = limitsOf(p), tuning = old?.tuning)
        put(done)
        done
    }

    /** Tests, then sets the app up for the device. */
    suspend fun testAndApply(context: Context) {
        val p = test(context)
        withContext(Dispatchers.Main) { apply(context, p) }
    }

    private fun progress(s: String) { testing.value = s }

    /** The player lets go of the DAC (paused) for the test, and takes it back after. */
    private fun hold(on: Boolean, resume: Boolean) {
        Playback.labHold = on
        val latch = CountDownLatch(1)
        Playback.main {
            if (Playback.isReady()) {
                if (on) Playback.player.pause()
                Playback.rebuildAudio(if (on) "device test" else "device test done")
                if (!on && resume) Playback.player.play()
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun testUsb(context: Context, cur: Current): DeviceProfile {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usb) ?: return DeviceProfile(cur.key, cur.kind, cur.name, notes = listOf("The DAC was unplugged"))
        if (!usb.hasPermission(dac)) return DeviceProfile(cur.key, cur.kind, cur.name, notes = listOf("Allow the app to use the DAC first: Android asks when it is plugged in (or choose Untouched USB under Output)"))
        val raw = runCatching { usb.openDevice(dac)?.let { c -> try { rawDsdOffered(c.rawDescriptors) } finally { c.close() } } }.getOrNull() ?: false
        val wasPlaying = Playback.state.value.playing
        progress("Taking the DAC from the player…")
        hold(true, false)
        try {
            val until = SystemClock.elapsedRealtime() + 4000
            while ((UsbLive.session != null || UsbLive.active) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            SystemClock.sleep(300)
            val s = UsbDacSession.open(usb, dac) ?: return DeviceProfile(cur.key, cur.kind, cur.name, System.currentTimeMillis(), notes = listOf("Could not open the DAC"))
            try {
                progress("Reading what the DAC offers…")
                val ranges = s.supportedRates()
                if (ranges.isNotEmpty()) DacRates.known = ranges
                val listed = STANDARD.filter { hz -> ranges.any { hz in it } }
                val bits = s.layout.alts.map { it.bits }.distinct().sorted()
                val vol = s.volumeRange?.let { "%.0f to %.0f dB".format(it.first, it.second) } ?: "None in the DAC (the app sets it)"
                // Each rate the DAC lists (or, if it lists none, each common one) is confirmed by switching to it.
                val ok = mutableListOf<Int>()
                for (hz in listed.ifEmpty { STANDARD }) {
                    progress("Switching to ${khz(hz)} kHz…")
                    if (s.start(hz)) { silence(s, hz, 250); ok += hz }
                }
                val max = ok.maxOrNull() ?: 0
                var gaps = -1L
                if (max > 0 && s.start(max)) {
                    progress("Stability at ${khz(max)} kHz…")
                    silence(s, max, 400)
                    val before = s.underruns()
                    silence(s, max, 3000)
                    gaps = s.underruns() - before
                }
                // DoP frames run at 1/16 of the DSD rate: DSD64 needs 176.4 kHz, DSD128 352.8 kHz, DSD256 705.6 kHz.
                val dop = listOf(64, 128, 256, 512).filter { m -> 44_100 * m / 16 in ok }
                return DeviceProfile(
                    cur.key, "usb", cur.name, System.currentTimeMillis(), rates = ok, bits = bits,
                    channels = s.layout.alts.maxOfOrNull { it.channels } ?: 2, dopDsd = dop, rawDsd = raw, volume = vol, stableAt = max, gaps = gaps,
                    notes = listOfNotNull(if (listed.isEmpty()) "This DAC does not list its rates, so the common ones were tried one by one" else null),
                )
            } finally {
                s.close()
            }
        } finally {
            progress("Giving the DAC back to the player…")
            SystemClock.sleep(400)
            hold(false, wasPlaying)
        }
    }

    /** Silence at [hz] for [ms], paced by the DAC itself (so its clock and the USB stream really run). */
    private fun silence(s: UsbDacSession, hz: Int, ms: Int) {
        val alt = s.current ?: return
        val chunk = hz / 100
        val buf = ByteArray(chunk * alt.frameBytes)
        val frames = hz.toLong() * ms / 1000
        var n = 0L
        val t0 = SystemClock.elapsedRealtime()
        while (n < frames && s.isRunning()) {
            var off = 0
            var waited = 0
            while (off < buf.size && s.isRunning() && waited < 1000) {
                val w = s.write(buf, off, buf.size - off)
                if (w <= 0) { SystemClock.sleep(3); waited += 3 } else { off += w; waited = 0 }
            }
            if (waited >= 1000) break
            n += chunk
        }
        while (s.framesPlayed() < frames && s.isRunning() && SystemClock.elapsedRealtime() - t0 < ms + 1500) SystemClock.sleep(10)
    }

    /** UAC2 streaming interfaces that offer RAW_DATA (bit 31 of bmFormats): native DSD by the DAC maker's rules. */
    private fun rawDsdOffered(d: ByteArray?): Boolean {
        if (d == null) return false
        var i = 0
        while (i + 2 <= d.size) {
            val len = d[i].toInt() and 0xFF
            if (len < 2 || i + len > d.size) break
            // CS_INTERFACE (0x24), AS_GENERAL (0x01), UAC2 layout: bmFormats at offset 6.
            if (len >= 16 && (d[i + 1].toInt() and 0xFF) == 0x24 && (d[i + 2].toInt() and 0xFF) == 0x01) {
                val fmt = (d[i + 6].toLong() and 0xFF) or ((d[i + 7].toLong() and 0xFF) shl 8) or ((d[i + 8].toLong() and 0xFF) shl 16) or ((d[i + 9].toLong() and 0xFF) shl 24)
                if (fmt and 0x80000000L != 0L) return true
            }
            i += len
        }
        return false
    }

    private fun testAndroid(context: Context, cur: Current): DeviceProfile {
        progress("Asking Android about ${cur.name}…")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            when (cur.kind) {
                "bt" -> it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                "wired" -> it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                else -> it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        }
        val rates = dev?.sampleRates?.toList().orEmpty().filter { it >= 44_100 }.distinct().sorted()
        val bits = dev?.encodings?.toList().orEmpty().mapNotNull {
            when (it) { AudioFormat.ENCODING_PCM_16BIT -> 16; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24; AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32; else -> null }
        }.distinct().sorted()
        val mixer = AudioPathInfo.mixerRate(context)
        var direct = false
        if (cur.kind != "bt") {
            progress("Checking Direct (phone) output…")
            PhoneDirectSession.checkAvailable()
            val until = SystemClock.elapsedRealtime() + 2500
            while (PhoneDirectSession.available == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            direct = PhoneDirectSession.available == true
        }
        progress("Checking what plays without mixing…")
        val unmixed = unmixedFormats(am)
        val offload = if (cur.kind == "bt") emptyList() else Outputs.offloadCodecs(context)
        val notes = when (cur.kind) {
            "bt" -> listOf("Android does not let apps read the Bluetooth codec (LDAC, aptX, AAC or SBC). Choose it in Developer options → Bluetooth audio codec.")
            "speaker" -> listOf("The phone's own speaker tuning (Phone output) makes small speakers sound fuller.")
            else -> emptyList()
        }
        return DeviceProfile(cur.key, cur.kind, cur.name, System.currentTimeMillis(), rates = rates, bits = bits, mixerRate = mixer, direct = direct, unmixed = unmixed, offload = offload, notes = notes)
    }

    /** Formats Android says it can play on the current route without mixing (Android 13+). */
    private fun unmixedFormats(am: AudioManager): List<String> {
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val out = mutableListOf<String>()
        for ((enc, name) in listOf(AudioFormat.ENCODING_PCM_FLOAT to "32-bit float", AudioFormat.ENCODING_PCM_24BIT_PACKED to "24-bit", AudioFormat.ENCODING_PCM_16BIT to "16-bit")) {
            for (hz in listOf(44_100, 48_000, 96_000, 192_000)) {
                val ok = runCatching {
                    val f = AudioFormat.Builder().setEncoding(enc).setSampleRate(hz).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                    AudioManager.getDirectPlaybackSupport(f, attrs) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                }.getOrDefault(false)
                if (ok) out += "$name ${khz(hz)} kHz"
            }
        }
        return out
    }

    /** What the test found the device cannot do, in plain words. */
    private fun limitsOf(p: DeviceProfile): List<String> = buildList {
        when (p.kind) {
            "usb" -> {
                if (p.rates.isEmpty()) return@buildList
                val missing = listOf(88_200, 176_400, 352_800, 96_000, 192_000, 384_000).filter { it !in p.rates }.sorted()
                if (missing.isNotEmpty()) add("No ${missing.joinToString(", ") { khz(it) }} kHz: songs at those rates are converted by the app to the DAC's nearest rate")
                if (p.dopDsd.isEmpty()) add("No native DSD (DoP): DSD is converted to PCM by the app" + if (p.rawDsd) " (the DAC offers a raw DSD format the app does not use yet)" else "")
                else if (256 !in p.dopDsd) add("Native DSD up to DSD${p.dopDsd.max()} only; higher DSD is converted to PCM")
                if (p.gaps > 0) add("${p.gaps} gap${if (p.gaps == 1L) "" else "s"} at ${khz(p.stableAt)} kHz: this phone's USB struggles at that rate")
                if (32 !in p.bits && 24 !in p.bits) add("16-bit only")
            }
            "bt" -> add("Bluetooth re-encodes all sound; how much survives depends on the codec")
            else -> {
                if (!p.direct) add("Android mixes everything at ${khz(p.mixerRate.coerceAtLeast(1))} kHz: no bit-perfect sound through the ${if (p.kind == "wired") "jack" else "speaker"}")
            }
        }
    }

    // ------------------------------------------------------------ Tuning

    /** The recommended settings for a device (its own, if chosen by hand). */
    fun tuningFor(p: DeviceProfile): Tuning = p.tuning ?: when (p.kind) {
        "usb" -> Tuning("usb", dvc = false, resampler = AppState.settings.value.appResampler, dsdMode = "dop", usbResample = "auto")
        "wired" -> if (p.direct) Tuning("direct", dvc = true, resampler = false) else Tuning("hires", dvc = true, resampler = true)
        "bt" -> Tuning("tuned", dvc = false, resampler = false)
        else -> Tuning("tuned", dvc = false, resampler = true)
    }

    fun currentTuning(s: AppSettings = AppState.settings.value) = Tuning(s.output, s.dvc, s.appResampler, s.dsdMode, s.usbResample)

    /** Sets the app up for [p]. Turning DVC on keeps the loudness: the app volume starts where Android's was. */
    fun apply(context: Context, p: DeviceProfile) {
        val t = tuningFor(p)
        val s = AppState.settings.value
        appliedAt = SystemClock.elapsedRealtime()
        lastApplied = p.key
        AppState.update { it.copy(appResampler = t.resampler, dsdMode = t.dsdMode, usbResample = t.usbResample) }
        if (t.dvc && !s.dvc) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val db = runCatching { am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, am.getStreamVolume(AudioManager.STREAM_MUSIC), AudioDeviceInfo.TYPE_WIRED_HEADPHONES) }.getOrDefault(-40f)
            val step = (Dvc.STEPS + db / Dvc.DB_PER_STEP).roundToInt().coerceIn(0, 20)
            AppState.update { it.copy(dvcStep = step) }
            Dvc.enable(context, true)
        } else if (!t.dvc && s.dvc) Dvc.enable(context, false)
        android.util.Log.i(TAG, "tuned for ${p.name}: $t")
        if (s.output != t.output) Playback.setOutput(t.output) else Playback.rebuildAudio("tuned for ${p.name}")
        banner.value = "Tuned for ${p.name}: ${Outputs.of(t.output).title}"
    }

    // ------------------------------------------------------------ Watching for devices

    /** From the app's start: tunes for each device as it is connected, and remembers settings changed by hand. */
    @OptIn(FlowPreview::class)
    fun watch(context: Context) {
        if (watching) return
        watching = true
        load()
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { changed(app) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { changed(app) }
        }, Handler(Looper.getMainLooper()))
        scope.launch { Playback.state.map { it.dacPresent to it.dacAllowed }.distinctUntilChanged().collect { changed(app) } }
        // Settings changed by hand while a device is in use become that device's own.
        scope.launch {
            AppState.settings.map { currentTuning(it) }.distinctUntilChanged().debounce(2000).collect { t ->
                val key = lastApplied ?: return@collect
                if (SystemClock.elapsedRealtime() - appliedAt < 4000 || testing.value.isNotEmpty()) return@collect
                val p = profiles.value[key] ?: return@collect
                val recommended = tuningFor(p.copy(tuning = null))
                put(p.copy(tuning = if (t == recommended) null else t))
            }
        }
    }

    private var pending: Job? = null

    private fun changed(app: Context) {
        pending?.cancel()
        pending = scope.launch {
            delay(1500)
            if (!AppState.settings.value.autoTune || testing.value.isNotEmpty()) return@launch
            val cur = current(app)
            android.util.Log.i(TAG, "output now ${cur.kind} \"${cur.name}\" (last tuned for $lastApplied)")
            val known = profiles.value[cur.key]
            if (cur.key == lastApplied && known?.complete == true) return@launch
            val p = when {
                known?.complete == true -> known
                // A DAC is only tested while nothing plays (the test borrows it); meanwhile the rates it lists are used.
                cur.kind == "usb" && Playback.state.value.playing -> known ?: DeviceProfile(cur.key, "usb", cur.name, rates = DacRates.listed())
                else -> test(app)
            }
            withContext(Dispatchers.Main) { apply(app, p) }
        }
    }

    // ------------------------------------------------------------ Rates for the USB output

    /**
     * The rate a song at [inRate] is sent to the DAC at: its own rate when the DAC takes it; otherwise the DAC's
     * nearest higher rate (nothing above the song is lost), or its highest; "max" sends everything at the highest;
     * "off" leaves rates the DAC lacks to Android (0).
     */
    /** The saved rates of the DAC plugged in now (so the first song is set up right before the DAC is opened). */
    fun ratesForAttachedDac(context: Context): List<IntRange> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val d = UsbDacProbe.findDac(usb) ?: return emptyList()
        return profiles.value["usb:${d.vendorId}:${d.productId}"]?.rates.orEmpty().map { it..it }
    }

    fun usbTargetFor(inRate: Int, rates: List<Int>, mode: String): Int {
        if (rates.isEmpty() || inRate <= 0) return inRate
        if (mode == "max") return rates.max()
        if (inRate in rates) return inRate
        if (mode == "off") return 0
        return rates.filter { it >= inRate }.minOrNull() ?: rates.max()
    }
}

// ============================================================ The library, file by file

@Serializable
data class QEntry(val codec: String, val rate: Int, val bits: Int, val kbps: Int, val lossless: Boolean, val dsd: Boolean)

/** One kind of file in the library ("FLAC 16/44.1", "MP3 320k", "DSD256") and how many. */
data class QGroup(val label: String, val entry: QEntry, val count: Int, val durationMs: Long)

/** What happens to a group on a device: 0 bit-perfect, 1 converted, 2 limited by the device. */
data class Outcome(val level: Int, val text: String)

object LibraryQuality {
    val groups = MutableStateFlow<List<QGroup>>(emptyList())
    /** -1 when idle, else how far the scan is (0..1). */
    val progress = MutableStateFlow(-1f)
    @Volatile private var scannedFor = -1

    suspend fun scan(force: Boolean = false) = withContext(Dispatchers.IO) {
        val tracks = AppState.library.value.tracks
        if (tracks.isEmpty() || (!force && scannedFor == tracks.size && groups.value.isNotEmpty()) || progress.value >= 0f) return@withContext
        progress.value = 0f
        val cache = Store.load<Map<String, QEntry>>("quality.json", emptyMap()).toMutableMap()
        val entries = ArrayList<Pair<Track, QEntry>>(tracks.size)
        tracks.forEachIndexed { i, t ->
            val key = "${t.path}|${t.sizeBytes}"
            val e = cache[key] ?: run {
                val q = Quality.of(t)
                QEntry(q.codec, q.sampleRate, q.bits, q.kbps, q.lossless, t.bits == 1).also { cache[key] = it }
            }
            entries += t to e
            if (i % 8 == 0) progress.value = i.toFloat() / tracks.size
        }
        val live = tracks.map { "${it.path}|${it.sizeBytes}" }.toHashSet()
        runCatching { Store.save("quality.json", cache.filterKeys { it in live }) }
        groups.value = entries.groupBy { (_, e) -> labelOf(e) }.map { (label, list) ->
            QGroup(label, list.first().second, list.size, list.sumOf { it.first.durationMs })
        }.sortedWith(compareByDescending<QGroup> { it.entry.dsd }.thenByDescending { it.entry.lossless }.thenByDescending { it.entry.rate }.thenByDescending { it.entry.bits }.thenByDescending { it.count })
        scannedFor = tracks.size
        progress.value = -1f
    }

    fun labelOf(e: QEntry): String = when {
        e.dsd -> "DSD${(e.rate / 44_100).coerceAtLeast(1)}"
        e.lossless -> "${e.codec} " + (if (e.bits > 0) "${e.bits}/" else "") + (if (e.rate > 0) khz(e.rate) else "?")
        else -> "${e.codec} " + when {
            e.kbps >= 300 -> "320k"; e.kbps >= 230 -> "256k"; e.kbps >= 170 -> "192k"; e.kbps >= 112 -> "128k"; e.kbps > 0 -> "under 128k"; else -> ""
        }.trim()
    }

    /** What a kind of file gets on a device, with the settings now in use. */
    fun outcome(e: QEntry, p: DeviceProfile?, s: AppSettings): Outcome {
        val kind = p?.kind ?: "speaker"
        val rate = if (e.rate > 0) e.rate else 44_100
        return when (kind) {
            "usb" -> {
                val rates = p?.rates.orEmpty()
                if (e.dsd) {
                    val m = e.rate / 44_100
                    if (s.dsdMode == "dop" && m in p?.dopDsd.orEmpty()) Outcome(0, "Native DSD (DoP)")
                    else {
                        val pcm = DsdPlayback.bestPcmRate(e.rate)
                        val to = DeviceLab.usbTargetFor(pcm, rates, s.usbResample)
                        Outcome(1, "DSD → PCM ${khz(pcm)} kHz" + if (to > 0 && to != pcm) " → ${khz(to)} kHz" else "")
                    }
                } else {
                    val to = DeviceLab.usbTargetFor(rate, rates, s.usbResample)
                    when {
                        to == rate -> Outcome(0, if (e.lossless) "Bit-perfect at ${khz(rate)} kHz" else "Decoded, sent unchanged at ${khz(rate)} kHz")
                        to == 0 -> Outcome(1, "${khz(rate)} kHz is not on the DAC: Android converts it")
                        else -> Outcome(1, "Converted by the app: ${khz(rate)} → ${khz(to)} kHz")
                    }
                }
            }
            "bt" -> Outcome(2, "Re-encoded for Bluetooth")
            else -> {
                val mixer = p?.mixerRate?.takeIf { it > 0 } ?: 48_000
                when {
                    e.dsd -> Outcome(1, "DSD → PCM ${khz(DsdPlayback.bestPcmRate(e.rate))} kHz, then ${khz(mixer)} kHz")
                    s.output == "direct" && p?.direct == true ->
                        if (rate == mixer) Outcome(0, "Bit-perfect (Direct)") else Outcome(1, "App converts ${khz(rate)} → ${khz(mixer)} kHz (Direct)")
                    rate == mixer -> Outcome(1, if (s.output == "hires") "32-bit float into Android's mixer" else "Into Android's mixer at its own rate")
                    s.appResampler -> Outcome(1, "App converts ${khz(rate)} → ${khz(mixer)} kHz")
                    else -> Outcome(1, "Android converts ${khz(rate)} → ${khz(mixer)} kHz")
                }
            }
        }
    }
}

// ============================================================ The screen

private fun ago(t: Long): String {
    if (t <= 0) return "not tested"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "tested just now"; m < 60 -> "tested $m min ago"; m < 1440 -> "tested ${m / 60} h ago"; else -> "tested ${m / 1440} days ago" }
}

@Composable
private fun LevelDot(level: Int) {
    // Status colours: green = untouched, amber = converted, grey = limited by the device.
    val c = when (level) { 0 -> C.Ok; 1 -> C.Amber; else -> Color.White.copy(alpha = 0.45f) }
    Box(Modifier.size(9.dp).clip(CircleShape).background(c))
}

@Composable
private fun ResultRow(title: String, value: String, level: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(title, style = body(12.sp, FontWeight.SemiBold, C.TextMid), modifier = Modifier.width(118.dp))
        if (level != null) { Box(Modifier.padding(top = 5.dp)) { LevelDot(level) }; Spacer(Modifier.width(6.dp)) }
        Text(value, style = body(13.sp), modifier = Modifier.weight(1f))
    }
}

/** Settings → Test my setup: tests the connected output, tunes the app for it, and shows the library on it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QualityLabScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by DeviceLab.profiles.collectAsState()
    val testing by DeviceLab.testing.collectAsState()
    val s by AppState.settings.collectAsState()
    val st by Playback.state.collectAsState()
    val groups by LibraryQuality.groups.collectAsState()
    val scanning by LibraryQuality.progress.collectAsState()
    val lib by AppState.library.collectAsState()
    var cur by remember { mutableStateOf(DeviceLab.current(ctx)) }
    LaunchedEffect(Unit) {
        Tour.busyThisSession = true
        DeviceLab.load()
        while (true) { cur = DeviceLab.current(ctx); delay(1500) }
    }
    LaunchedEffect(lib.tracks.size) { LibraryQuality.scan() }
    // First visit with this device: test it straight away.
    LaunchedEffect(cur.key) {
        delay(800)
        val now = DeviceLab.current(ctx)
        if (now.key == cur.key && DeviceLab.profiles.value[now.key]?.complete != true && DeviceLab.testing.value.isEmpty()) DeviceLab.testAndApply(ctx)
    }
    val p = profiles[cur.key]
    val t = p?.let { DeviceLab.tuningFor(it) }
    val applied = t != null && DeviceLab.currentTuning(s) == t
    @Suppress("UNUSED_EXPRESSION") st

    Backdrop(Color(0xFF1B1F4A)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header("Test my setup", { nav.back() }) }

            // ---- The device
            item {
                Column(Modifier.fillMaxWidth().amberSelected(RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(DeviceLab.kindName(cur.kind).uppercase(), style = mono(10.sp, FontWeight.Bold, C.AmberText))
                    Text(cur.name, style = display(22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (testing.isNotEmpty()) testing else ago(p?.testedAt ?: 0), style = body(13.sp, color = C.TextMid))
                    if (testing.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(if (p == null) "Test now" else "Test again", true) { scope.launch { DeviceLab.testAndApply(ctx) } }
                    }
                    if (cur.kind == "usb" && testing.isEmpty()) Text("The test borrows the DAC for about 10 seconds; music pauses and carries on after.", style = body(11.sp, color = C.TextLow))
                }
            }

            if (p != null && p.testedAt > 0) {
                // ---- What it can do
                item {
                    Column(Modifier.fillMaxWidth().raised().padding(16.dp)) {
                        Text("WHAT IT CAN DO", style = eyebrow())
                        Spacer(Modifier.height(8.dp))
                        when (p.kind) {
                            "usb" -> {
                                ResultRow("Rates", if (p.rates.isEmpty()) "Not known" else p.rates.joinToString(" · ") { khz(it) } + " kHz")
                                ResultRow("Bit depth", if (p.bits.isEmpty()) "Not known" else p.bits.joinToString(" / ") + "-bit")
                                ResultRow("Native DSD", if (p.dopDsd.isEmpty()) "No (DoP needs 176.4 kHz or more)" else p.dopDsd.joinToString(", ") { "DSD$it" } + " as DoP", if (p.dopDsd.isEmpty()) 2 else 0)
                                if (p.rawDsd) ResultRow("Raw DSD", "Offered by the DAC (not used by the app yet)")
                                ResultRow("Volume", p.volume)
                                if (p.gaps >= 0) ResultRow("Stability", if (p.gaps == 0L) "No gaps in 3 s at ${khz(p.stableAt)} kHz" else "${p.gaps} gap${if (p.gaps == 1L) "" else "s"} in 3 s at ${khz(p.stableAt)} kHz", if (p.gaps == 0L) 0 else 1)
                            }
                            else -> {
                                ResultRow("Android mixes at", "${khz(p.mixerRate.coerceAtLeast(1))} kHz")
                                ResultRow("Device rates", if (p.rates.isEmpty()) "Any (Android converts)" else p.rates.joinToString(" · ") { khz(it) } + " kHz")
                                if (p.bits.isNotEmpty()) ResultRow("Bit depth", p.bits.joinToString(" / ") + "-bit")
                                if (p.kind != "bt") ResultRow("Direct (phone)", if (p.direct) "Possible: the app can skip Android's mixer" else "Not on this phone", if (p.direct) 0 else 2)
                                ResultRow("Direct path", if (p.unmixed.isEmpty()) "Android reports none: everything goes through its mixer" else "Android reports one for " + p.unmixed.joinToString(", "))
                                if (p.offload.isNotEmpty()) ResultRow("Battery saver", p.offload.joinToString(", ") + " decoded by the audio chip")
                            }
                        }
                        p.notes.forEach { Text(it, style = body(12.sp, color = C.TextMid), modifier = Modifier.padding(top = 6.dp)) }
                        if (p.kind == "bt") Box(Modifier.padding(top = 8.dp)) {
                            Chip("Open Developer options", false) { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        }
                    }
                }
                if (p.limits.isNotEmpty()) item {
                    Column(Modifier.fillMaxWidth().raised().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("LIMITS FOUND", style = eyebrow())
                        p.limits.forEach { l ->
                            Row { Box(Modifier.padding(top = 5.dp)) { LevelDot(1) }; Spacer(Modifier.width(8.dp)); Text(l, style = body(13.sp)) }
                        }
                    }
                }

                // ---- How the app plays to it
                if (t != null) item {
                    Column(Modifier.fillMaxWidth().raised().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SET UP FOR ${cur.name.uppercase()}", style = eyebrow())
                        Spacer(Modifier.height(4.dp))
                        ResultRow("Output", Outputs.of(t.output).title)
                        ResultRow("Volume", if (t.dvc) "Direct Volume Control (64-bit, in the app)" else if (t.output == "usb") "In the DAC" else "Android's")
                        if (t.output != "usb") ResultRow("App resampler", if (t.resampler) "On" else "Off")
                        if (t.output == "usb") ResultRow("Rates it lacks", when (t.usbResample) { "max" -> "Everything upsampled to ${khz(p.rates.maxOrNull() ?: 0)} kHz"; "off" -> "Left to Android"; else -> "Converted by the app to its nearest rate" })
                        ResultRow("DSD", if (t.dsdMode == "dop" && p.dopDsd.isNotEmpty()) "Native (DoP)" else "Converted to PCM")
                        Text(if (p.tuning != null) "Your own settings for this device (changed by hand)." else "Recommended for what the test found.", style = body(11.sp, color = C.TextLow), modifier = Modifier.padding(top = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            if (!applied) Chip("Apply now", true) { DeviceLab.apply(ctx, p) }
                            if (p.tuning != null) Chip("Back to recommended", false) { DeviceLab.forget(p.key); scope.launch { DeviceLab.testAndApply(ctx) } }
                        }
                    }
                }
                item {
                    SettingRow("Tune for each device automatically", if (s.autoTune) "When a DAC, earphones or Bluetooth connect, the app tests them once and sets itself up" else "Off: settings stay as you set them") {
                        Toggle(s.autoTune, "Tune automatically") { v -> AppState.update { it.copy(autoTune = v) } }
                    }
                }
            }

            // ---- The library on this device
            item {
                Column(Modifier.fillMaxWidth().raised().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("YOUR LIBRARY ON THIS ${DeviceLab.kindName(cur.kind).uppercase()}", style = eyebrow())
                    if (scanning >= 0f) {
                        Text("Reading the files… ${(scanning * 100).toInt()}%", style = body(13.sp, color = C.TextMid))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f))) {
                            Box(Modifier.fillMaxWidth(scanning.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Amber))
                        }
                    } else if (groups.isNotEmpty()) {
                        val total = groups.sumOf { it.count }
                        val perfect = groups.filter { LibraryQuality.outcome(it.entry, p, s).level == 0 }.sumOf { it.count }
                        Text("$perfect of $total songs reach it untouched", style = display(18.sp))
                        Spacer(Modifier.height(4.dp))
                        groups.forEach { g ->
                            val o = LibraryQuality.outcome(g.entry, p, s)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.label, style = mono(13.sp, FontWeight.Bold))
                                    Text("${g.count} song${if (g.count == 1) "" else "s"} · ${fmtHours(g.durationMs)}", style = body(11.sp, color = C.TextLow))
                                }
                                LevelDot(o.level)
                                Spacer(Modifier.width(6.dp))
                                Text(o.text, style = body(12.sp, color = C.TextMid), modifier = Modifier.width(170.dp))
                            }
                        }
                        Text("Green: untouched · amber: converted on the way · grey: limited by the device", style = body(11.sp, color = C.TextLow), modifier = Modifier.padding(top = 4.dp))
                    } else Text("No songs yet.", style = body(13.sp, color = C.TextMid))
                }
            }

            // ---- Other devices tested before
            val others = profiles.values.filter { it.key != cur.key && it.testedAt > 0 }
            if (others.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().raised().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ALSO TESTED", style = eyebrow())
                    others.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = body(14.sp, FontWeight.SemiBold))
                                Text("${DeviceLab.kindName(o.kind)} · ${ago(o.testedAt)} · ${Outputs.of(DeviceLab.tuningFor(o).output).title}", style = body(11.sp, color = C.TextLow))
                            }
                            Chip("Forget", false) { DeviceLab.forget(o.key) }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtHours(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** "Tuned for Audiocular D07: Untouched (USB DAC)", for a few seconds after the app sets itself up for a device. */
@Composable
fun TunedBanner(nav: Nav) {
    val msg by DeviceLab.banner.collectAsState()
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { delay(4500); DeviceLab.banner.value = "" } }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(msg.isNotEmpty(), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Row(
                Modifier.raised(RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).clickable { DeviceLab.banner.value = ""; nav.go(Route.QualitySetup) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LevelDot(0)
                Spacer(Modifier.width(10.dp))
                Text(msg, style = body(13.sp, FontWeight.SemiBold), maxLines = 2)
            }
        }
    }
}
