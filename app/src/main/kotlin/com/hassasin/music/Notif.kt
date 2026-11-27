package com.hassasin.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the system media card (notification shade, lock screen, watch, car) shows under the title.
 * Android 13+ draws that card itself, so the app shapes what goes into it: the second line carries the
 * quality and output ("A.R. Rahman · FLAC 16/44.1 · Bit-perfect") or the lyric being sung now.
 */
object NotifLine {
    /** "Bit-perfect", "USB DAC + effects", "Phone + effects"... the same words as the player's pill. */
    fun output(st: PlayState, s: AppSettings): String {
        val effects = Eq.anyEffect(s.sound, st.usb) && !(s.output == "offload" && !st.usb)
        return when {
            st.usb -> if (st.untouched) "Bit-perfect" else "USB DAC + effects"
            st.phoneDirect -> if (st.untouched) "Direct" else "Direct + effects"
            s.output == "usb_android" && AndroidBitPerfect.active.isNotEmpty() -> "Bit-perfect"
            s.output == "offload" && AudioPathInfo.path.value.offload -> "Battery saver"
            effects -> "Effects on"
            else -> ""
        }
    }

    fun quality(t: Track, st: PlayState, s: AppSettings): String =
        listOf(t.artist.ifBlank { t.albumArtist }, t.formatLabel, output(st, s)).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The player as the media session sees it: the same player, but the artist line can be replaced
 * (quality or live lyric). [refresh] tells the session the line changed so the card redraws.
 */
@OptIn(UnstableApi::class)
class NotifPlayer(player: Player) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var line: String? = null
        private set

    override fun addListener(listener: Player.Listener) { super.addListener(listener); listeners += listener }
    override fun removeListener(listener: Player.Listener) { super.removeListener(listener); listeners -= listener }

    override fun getMediaMetadata(): MediaMetadata {
        val m = super.getMediaMetadata()
        // "03 - Aap Ki Kashish" shows as "Aap Ki Kashish".
        val title = m.title?.toString()?.replace(Regex("^\\s*\\d{1,3}\\s*[-._)]?\\s+"), "")?.ifBlank { null }
        val b = m.buildUpon()
        if (title != null) b.setTitle(title).setDisplayTitle(title)
        line?.let { b.setArtist(it).setSubtitle(it) }
        return b.build()
    }

    fun setLine(text: String?) {
        if (text == line) return
        line = text
        main.post {
            val m = mediaMetadata
            val events = Player.Events(androidx.media3.common.FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
            listeners.forEach { it.onMediaMetadataChanged(m); it.onEvents(this, events) }
        }
    }
}

/** Keeps the card's second line up to date: on song, output and setting changes, and line by line for lyrics. */
object NotifUpdater {
    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope, np: NotifPlayer) {
        job?.cancel()
        job = scope.launch {
            var lyricsFor = -1L
            var lines: List<LyricLine>? = null
            while (true) {
                val st = Playback.state.value
                val s = AppState.settings.value
                val t = st.current
                if (t == null) { np.setLine(null); delay(1000); continue }
                if (s.notifLine == "lyrics" && lyricsFor != t.id) {
                    lyricsFor = t.id
                    lines = runCatching { LyricsRepo.find(context, t, online = false)?.synced }.getOrNull()
                }
                val text = when (s.notifLine) {
                    "artist" -> null
                    "lyrics" -> lines?.let { ls ->
                        val pos = Playback.positionNow() + LyricsRepo.offsetMs(t)
                        ls.lastOrNull { it.timeMs <= pos }?.text?.takeIf { it.isNotBlank() }
                            ?.let { if (s.lyricsHinglish) Hinglish.convert(it) else it }
                    } ?: NotifLine.quality(t, st, s)
                    else -> NotifLine.quality(t, st, s)
                }
                np.setLine(text)
                delay(if (s.notifLine == "lyrics" && st.playing) 200 else 1000)
            }
        }
    }
}

/**
 * A cover for songs that have no picture, so the card never shows a grey note: the song's initials
 * in the app's gold on a deep gradient, with the colour picked from the artist (each artist keeps one).
 */
object GeneratedCover {
    private val cache = android.util.LruCache<String, Bitmap>(8)
    private val hues = floatArrayOf(250f, 275f, 220f, 300f, 200f, 330f, 235f, 285f)

    fun make(t: Track, size: Int = 720): Bitmap {
        val key = "${t.id}-$size"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = hues[Math.floorMod((t.artist.ifBlank { t.album }).lowercase().hashCode(), hues.size)]
        val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.42f))
        val bottom = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.70f, 0.10f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size * 0.4f, size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        // A warm glow behind the letters.
        p.shader = RadialGradient(size * 0.5f, size * 0.45f, size * 0.55f, 0x55FFB547, 0x00FFB547, Shader.TileMode.CLAMP)
        c.drawCircle(size * 0.5f, size * 0.45f, size * 0.55f, p)
        p.shader = null
        val words = ArtRepo.cleanSongTitle(t.title).split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
        val initials = words.take(2).joinToString("") { w -> w.first { it.isLetterOrDigit() }.uppercase() }.ifBlank { "♪" }
        p.color = 0xFFFFCF85.toInt()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = size * (if (initials.length > 1) 0.36f else 0.44f)
        p.setShadowLayer(size * 0.03f, 0f, size * 0.01f, 0x66000000)
        val fm = p.fontMetrics
        c.drawText(initials, size / 2f, size * 0.47f - (fm.ascent + fm.descent) / 2, p)
        p.clearShadowLayer()
        // The song name, small, near the bottom.
        p.color = 0xCCFFFFFF.toInt()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        p.textSize = size * 0.055f
        val name = ArtRepo.cleanSongTitle(t.title).let { if (it.length > 26) it.take(25) + "…" else it }
        c.drawText(name, size / 2f, size * 0.86f, p)
        p.color = 0x88FFCF85.toInt()
        p.textSize = size * 0.035f
        p.letterSpacing = 0.3f
        c.drawText("HASSASIN MUSIC", size / 2f, size * 0.93f, p)
        cache.put(key, bmp)
        return bmp
    }
}

/** Which two buttons sit beside the play controls on the card. */
object NotifButtons {
    val CHOICES = listOf("shuffle_repeat" to "Shuffle · Repeat", "seek" to "−10 s · +10 s", "love_lyrics" to "Love · Lyrics line")

    fun flowOf() = AppState.settings.map { it.notifButtons }.distinctUntilChanged()
}

/**
 * Covers for other apps on the phone. The media card, vivo's music widget, watches and cars read the
 * song's artwork address themselves; a plain content:// address (read-only, covers only) lets them
 * show the same cover the app shows, or the made-up one for songs without a picture.
 */
class ArtProvider : android.content.ContentProvider() {
    companion object {
        const val AUTHORITY = "com.hassasin.music.art"
        fun uriFor(trackId: Long): android.net.Uri = android.net.Uri.parse("content://$AUTHORITY/track/$trackId")
        fun idOf(uri: android.net.Uri): Long? = if (uri.authority == AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null

        /** The cover for a track: its own, else one made for it. */
        suspend fun bitmapFor(id: Long?): Bitmap? {
            val track = AppState.library.value.tracks.firstOrNull { it.id == id } ?: return null
            return albumOf(track)?.let { ArtRepo.bitmap(it, 720) } ?: GeneratedCover.make(track)
        }
    }

    override fun onCreate() = true
    override fun getType(uri: android.net.Uri) = "image/jpeg"

    override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val id = idOf(uri) ?: return null
        val dir = java.io.File(context!!.cacheDir, "card_art").apply { mkdirs() }
        // Named by the art version too, so a newly chosen cover is not served from an old file.
        val f = java.io.File(dir, "$id-${ArtRepo.version}.jpg")
        if (!f.isFile) {
            val bmp = kotlinx.coroutines.runBlocking { bitmapFor(id) } ?: return null
            dir.listFiles { x -> x.name.startsWith("$id-") }?.forEach { it.delete() }
            val tmp = java.io.File(dir, "$id.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(f)
        }
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
