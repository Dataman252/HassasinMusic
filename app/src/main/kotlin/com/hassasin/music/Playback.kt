package com.hassasin.music

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayState(
    val current: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val usb: Boolean = false,
    /** Playing through the phone's own direct (exclusive) output. */
    val phoneDirect: Boolean = false,
    val usbPath: String = "",
    val untouched: Boolean = true,
    val dacPresent: Boolean = false,
    val dacAllowed: Boolean = false,
    val dacName: String = "",
    val dacRate: Int = 0,
    val dacBits: Int = 0,
    val gaps: Long = 0,
)

@OptIn(UnstableApi::class)
private class MusicRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        // Phone decoders first; bundled FFmpeg decoders for formats the phone cannot play (ALAC, APE, DSD...).
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        // Formats the phone's own decoder gets wrong go to the bundled FFmpeg decoder instead.
        setMediaCodecSelector { mime, secure, tunneling ->
            if (mime in Playback.softwareOnly) emptyList() else MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        }
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        // Phone / Battery saver: 16-bit with the app's effects. Hi-Res: 32-bit float for hi-res files.
        val tuned = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(SoundEngine(forUsb = false)))
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        val hires = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(SoundEngine(forUsb = false)))
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        val android = DualSink.create(tuned, hires)
        return UsbAudioSink(context, android, SoundEngine(forUsb = true)) {
            val s = AppState.settings.value
            // During a call the DAC belongs to Android, so the call uses your earphones and the DAC's microphone.
            UsbOutputSettings(enabled = s.output == "usb" && !Playback.callActive, phoneDirect = s.output == "direct" && !Playback.callActive, volumeDb = s.dacVolumeDb, maxDb = s.dacMaxDb)
        }
    }
}

/** The one player for the whole app; the media service and the screens share it. */
@OptIn(UnstableApi::class)
object Playback {
    private const val TAG = "Playback"
    lateinit var player: ExoPlayer
        private set
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Formats decoded by FFmpeg instead of the phone. ALAC from the start: the vivo's own ALAC decoder
     * fails on 24-bit files ("Baapu", Error 0x80000000). Others are added when a decoder fails.
     */
    val softwareOnly: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf("audio/alac"))

    /** True while the phone rings or a call (or voice/video call app) is active. */
    @Volatile var callActive = false
        private set
    private var normalSince = 0L

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state

    private var queue: List<Track> = emptyList()
    private var effectsConfigured: Boolean? = null
    private var rebuildsForTrack = 0
    private var lastTrackId = -1L
    private var lastSave = 0L

    fun init(context: Context) {
        if (::player.isInitialized) return
        appContext = context.applicationContext
        player = ExoPlayer.Builder(appContext, MusicRenderersFactory(appContext))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                rebuildsForTrack = 0
                current()?.let { AppState.countPlay(it); AppState.findArtNow(it) }
                AppState.fetchLyricsNow(listOfNotNull(current(), queue.getOrNull(player.nextMediaItemIndex)))
                publish()
                saveResume()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { Viz.setPlaying(isPlaying); publish(); if (!isPlaying) saveResume() }
            override fun onPlaybackStateChanged(playbackState: Int) { publish() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { publish() }
            override fun onRepeatModeChanged(repeatMode: Int) { publish() }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error", error)
                // The phone's decoder failed: decode this format with FFmpeg and try the same song again.
                val mime = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
                val decoderError = error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
                if (decoderError && mime != null && softwareOnly.add(mime)) {
                    Log.i(TAG, "decoder failed for $mime: switching it to FFmpeg and retrying")
                    val pos = player.currentPosition
                    val idx = player.currentMediaItemIndex
                    player.seekTo(idx, pos)
                    player.prepare()
                    player.play()
                    return
                }
                // Still failing: skip the file rather than stopping.
                if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
            }
        })
        // Which decoder runs and what Android is sent, for the quality details.
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                AudioPathInfo.path.value = AudioPathInfo.path.value.copy(inputMime = format.sampleMimeType ?: "", decoder = if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW) "" else AudioPathInfo.path.value.decoder, updatedAt = System.currentTimeMillis())
            }
            override fun onAudioDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                AudioPathInfo.path.value = AudioPathInfo.path.value.copy(decoder = decoderName, offload = false, updatedAt = System.currentTimeMillis())
            }
            override fun onAudioTrackInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                AudioPathInfo.path.value = AudioPathInfo.path.value.copy(
                    trackEncoding = audioTrackConfig.encoding, trackRate = audioTrackConfig.sampleRate,
                    trackChannels = Integer.bitCount(audioTrackConfig.channelConfig), offload = audioTrackConfig.offload, updatedAt = System.currentTimeMillis(),
                )
            }
        })
        val s = AppState.settings.value
        applyOffload(s.output)
        player.shuffleModeEnabled = s.shuffle
        player.repeatMode = when (s.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        scope.launch { while (true) { tick(); delay(250) } }
    }

    fun current(): Track? = queue.getOrNull(player.currentMediaItemIndex)

    private fun item(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(t.uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                .setAlbumArtist(t.albumArtist).setTrackNumber(t.trackNo).setIsPlayable(true).setIsBrowsable(false)
                .setArtworkUri(ArtProvider.uriFor(t.id)).build(),
        )
        .build()

    fun playList(tracks: List<Track>, start: Int, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        queue = tracks
        shuffle?.let { setShuffle(it) }
        player.setMediaItems(tracks.map(::item), start.coerceIn(0, tracks.lastIndex), 0)
        player.prepare()
        player.play()
        publish()
    }

    fun playNext(t: Track) {
        if (queue.isEmpty()) return playList(listOf(t), 0)
        val at = (player.currentMediaItemIndex + 1).coerceAtMost(queue.size)
        queue = queue.toMutableList().apply { add(at, t) }
        player.addMediaItem(at, item(t))
        publish()
    }

    fun addToQueue(t: Track) {
        if (queue.isEmpty()) return playList(listOf(t), 0)
        queue = queue + t
        player.addMediaItem(item(t))
        publish()
    }

    fun removeAt(i: Int) {
        if (i !in queue.indices || i == player.currentMediaItemIndex) return
        queue = queue.toMutableList().apply { removeAt(i) }
        player.removeMediaItem(i)
        publish()
    }

    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        player.moveMediaItem(from, to)
        publish()
    }

    fun jumpTo(i: Int) { if (i in queue.indices) { player.seekTo(i, 0); player.play() } }

    fun toggle() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }

    /** Back to the start of the track, or to the previous track in the first 3 seconds. */
    fun previous() { if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem() }

    fun seekTo(ms: Long) = player.seekTo(ms)

    /** The exact playing position right now (the state's position is updated a few times a second). */
    fun positionNow(): Long = player.currentPosition

    fun setShuffle(on: Boolean) {
        player.shuffleModeEnabled = on
        AppState.update { it.copy(shuffle = on) }
    }

    fun cycleRepeat() {
        val next = (AppState.settings.value.repeat + 1) % 3
        player.repeatMode = when (next) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        AppState.update { it.copy(repeat = next) }
    }

    /** DAC volume in dB (USB Direct). Capped by the maximum set for the earphones. */
    fun setDacVolume(db: Double) {
        val s = AppState.settings.value
        val v = db.coerceIn(-74.0, s.dacMaxDb).let { Math.round(it * 2) / 2.0 }
        AppState.update { it.copy(dacVolumeDb = v) }
        UsbLive.session?.setVolumeDb(v)
    }

    /**
     * Sets the audio up again at the same spot: after the DAC is plugged in or out, the output
     * setting changes, or an effect is switched on/off (which adds or removes the sound engine).
     */
    /** Turns the phone's offload (hardware decoding) on or off for the chosen output. */
    private fun applyOffload(mode: String) {
        val prefs = androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (mode == "offload") androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                else androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            )
            .setIsGaplessSupportRequired(false)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(prefs).build()
    }

    /** The user picked another output: set it up and restart the sound at the same spot. */
    fun setOutput(mode: String) {
        AppState.update { it.copy(output = mode) }
        if (!::player.isInitialized) return
        applyOffload(mode)
        if (mode == "usb") onDacMaybeChanged() else rebuildAudio("output: $mode")
    }

    fun rebuildAudio(why: String) {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        Log.i(TAG, "rebuilding audio: $why")
        val pos = player.currentPosition
        val idx = player.currentMediaItemIndex
        val play = player.playWhenReady
        player.stop()
        player.seekTo(idx, pos)
        player.prepare()
        player.playWhenReady = play
    }

    /** Called when a USB device may have appeared or gained access. */
    fun onDacMaybeChanged() { main.postDelayed({ checkDac(force = true) }, 600) }

    private fun checkDac(force: Boolean = false) {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val s = AppState.settings.value
        val usbm = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usbm)
        val allowed = dac != null && usbm.hasPermission(dac)
        val wantUsb = s.output == "usb" && allowed && !callActive
        val ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING
        if (!ready) return
        when {
            UsbLive.lost && UsbLive.active -> { UsbLive.lost = false; rebuildAudio("direct output stopped (unplugged or rerouted)") }
            wantUsb && !(UsbLive.active && UsbLive.kind == "usb") && (force || rebuildsForTrack < 2) -> { rebuildsForTrack++; rebuildAudio("DAC available") }
            !wantUsb && UsbLive.active && UsbLive.kind == "usb" -> rebuildAudio("direct output off")
            UsbLive.active && UsbLive.kind == "phone" && (s.output != "direct" || callActive || wantUsb) -> rebuildAudio("phone direct off")
            !wantUsb && s.output == "direct" && !callActive && !UsbLive.active && (force || rebuildsForTrack < 2) -> { rebuildsForTrack++; rebuildAudio("phone direct") }
        }
    }

    private var tickCount = 0

    /**
     * A ringing phone or a call: hand the DAC back to Android at once (the app's hold on the DAC would
     * keep the call off your earphones and its microphone). Take it back a little after the call ends.
     */
    private fun watchCalls() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val inCall = am.mode == android.media.AudioManager.MODE_RINGTONE || am.mode == android.media.AudioManager.MODE_IN_CALL ||
            am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || (android.os.Build.VERSION.SDK_INT >= 30 && am.mode == android.media.AudioManager.MODE_CALL_SCREENING)
        val now = System.currentTimeMillis()
        if (inCall && !callActive) {
            callActive = true
            Log.i(TAG, "call: handing the DAC back to Android")
            if (UsbLive.session != null || UsbLive.active) {
                if (player.mediaItemCount > 0) rebuildAudio("call started")
            }
        } else if (!inCall && callActive) {
            if (normalSince == 0L) normalSince = now
            if (now - normalSince > 1500) {
                callActive = false; normalSince = 0L
                Log.i(TAG, "call ended: taking the DAC back")
                onDacMaybeChanged()
            }
        } else if (inCall) normalSince = 0L
    }

    private fun tick() {
        if (!::player.isInitialized) return
        tickCount++
        watchCalls()
        if (tickCount % 4 == 0) checkDac()
        // An effect switched on or off: the engine has to be added to or taken out of the chain.
        val effects = Eq.anyEffect(SoundState.settings, UsbLive.active)
        if (effectsConfigured != null && effectsConfigured != effects && player.mediaItemCount > 0) {
            effectsConfigured = effects
            rebuildAudio("effects ${if (effects) "on" else "off"}")
        } else effectsConfigured = effects
        publish()
        if (player.isPlaying && System.currentTimeMillis() - lastSave > 10_000) saveResume()
    }

    private fun publish() {
        val t = current()
        val usbm = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val dac = UsbDacProbe.findDac(usbm)
        val sess = UsbLive.session
        _state.value = PlayState(
            current = t,
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: (t?.durationMs ?: 0),
            queue = queue,
            index = player.currentMediaItemIndex,
            shuffle = player.shuffleModeEnabled,
            repeat = AppState.settings.value.repeat,
            usb = UsbLive.active && UsbLive.kind == "usb",
            phoneDirect = UsbLive.active && UsbLive.kind == "phone",
            usbPath = UsbLive.path,
            untouched = UsbLive.untouched,
            dacPresent = dac != null,
            dacAllowed = dac != null && usbm.hasPermission(dac),
            dacName = dac?.productName ?: "",
            dacRate = (sess as? PhoneDirectSession)?.hwRate ?: sess?.outRate ?: 0,
            dacBits = UsbLive.fileBits,
            gaps = sess?.underruns() ?: 0,
        )
        if (t != null && t.id != lastTrackId) { lastTrackId = t.id }
    }

    private fun saveResume() {
        lastSave = System.currentTimeMillis()
        if (queue.isEmpty()) return
        val r = Resume(queue.map { it.id }, player.currentMediaItemIndex, player.currentPosition)
        scope.launch(Dispatchers.IO) { runCatching { Store.save("resume.json", r) } }
    }

    /** Puts the last queue back (paused) after the app restarts. */
    fun restore(tracks: List<Track>) {
        if (queue.isNotEmpty()) return
        val r = Store.load("resume.json", Resume())
        if (r.queue.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        val list = r.queue.mapNotNull { byId[it] }
        if (list.isEmpty()) return
        queue = list
        player.setMediaItems(list.map(::item), r.index.coerceIn(0, list.lastIndex), r.positionMs)
        player.prepare()
        publish()
    }
}
