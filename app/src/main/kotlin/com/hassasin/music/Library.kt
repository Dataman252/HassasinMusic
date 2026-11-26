package com.hassasin.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

data class Track(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val durationMs: Long,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val mime: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val bitrate: Int,
    /** From the file's own header where we can read it (FLAC, WAV); 0 = unknown. */
    val sampleRate: Int,
    val bits: Int,
) {
    val folder: String get() = File(path).parent ?: ""
    val folderName: String get() = File(folder).name
    val albumKey: String get() = albumKeyOf(albumArtist.ifBlank { artist }, album, folder)
    val ext: String get() = path.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val hiRes: Boolean get() = sampleRate > 48000 || bits > 16
    val formatLabel: String
        get() = buildString {
            append(ext.ifBlank { mime.substringAfter('/').uppercase(Locale.ROOT) })
            if (bits > 0 && sampleRate > 0) append(" ${bits}/${khz(sampleRate)}")
            else if (bitrate > 0) append(" ${bitrate / 1000} kbps")
        }
}

data class Album(val key: String, val title: String, val artist: String, val year: Int, val tracks: List<Track>) {
    val first get() = tracks.first()
    val hiRes get() = tracks.any { it.hiRes }
}

fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(Locale.ROOT, hz / 1000.0)

fun albumKeyOf(artist: String, album: String, folder: String): String {
    val a = album.trim().lowercase(Locale.ROOT)
    // Untagged files: each folder is its own "album".
    return if (a.isBlank() || a == "<unknown>") "folder:$folder" else "${artist.trim().lowercase(Locale.ROOT)}|$a"
}

object Library {
    private const val TAG = "Library"

    fun scan(context: Context, settings: AppSettings): List<Track> {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 30) cols += listOf(
            MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.DISC_NUMBER,
        )
        val out = mutableListOf<Track>()
        val hidden = settings.hiddenFolders.map { it.lowercase(Locale.ROOT) }
        val minMs = settings.minDurationSec * 1000L
        val info = Store.load<Map<String, List<Int>>>("formats.json", emptyMap()).toMutableMap()
        var infoChanged = false
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
            "${MediaStore.Audio.Media.DURATION} >= ?", arrayOf(minMs.toString()), null,
        )?.use { c ->
            fun str(name: String) = c.getColumnIndex(name).let { if (it >= 0) c.getString(it) ?: "" else "" }
            fun int(name: String) = c.getColumnIndex(name).let { if (it >= 0) c.getInt(it) else 0 }
            fun long(name: String) = c.getColumnIndex(name).let { if (it >= 0) c.getLong(it) else 0L }
            while (c.moveToNext()) {
                val path = str(MediaStore.Audio.Media.DATA)
                val lower = path.lowercase(Locale.ROOT)
                if (hidden.any { h -> lower.split('/').any { seg -> seg.contains(h) } }) continue
                val id = long(MediaStore.Audio.Media._ID)
                val size = long(MediaStore.Audio.Media.SIZE)
                val infoKey = "$path|$size"
                val fmt = info[infoKey] ?: readFormat(path).also { info[infoKey] = it; infoChanged = true }
                val rawTrack = int(MediaStore.Audio.Media.TRACK)
                val artist = str(MediaStore.Audio.Media.ARTIST).let { if (it == "<unknown>") "" else it }
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    path = path,
                    title = str(MediaStore.Audio.Media.TITLE).ifBlank { File(path).nameWithoutExtension },
                    artist = artist,
                    // Android fills in the folder name ("Telegram", "Download") when a file has no album tag.
                    album = str(MediaStore.Audio.Media.ALBUM).let { a -> if (a.equals(File(path).parentFile?.name ?: "", ignoreCase = true)) "" else a },
                    albumArtist = str(MediaStore.Audio.Media.ALBUM_ARTIST),
                    genre = str(MediaStore.Audio.Media.GENRE),
                    durationMs = long(MediaStore.Audio.Media.DURATION),
                    // Older Android packs the disc into the thousands of the track number.
                    trackNo = rawTrack % 1000,
                    discNo = int(MediaStore.Audio.Media.DISC_NUMBER).takeIf { it > 0 } ?: (rawTrack / 1000).coerceAtLeast(1),
                    year = int(MediaStore.Audio.Media.YEAR),
                    mime = str(MediaStore.Audio.Media.MIME_TYPE),
                    sizeBytes = size,
                    dateAdded = long(MediaStore.Audio.Media.DATE_ADDED),
                    bitrate = int(MediaStore.Audio.Media.BITRATE),
                    sampleRate = fmt.getOrElse(0) { 0 },
                    bits = fmt.getOrElse(1) { 0 },
                )
            }
        }
        if (infoChanged) runCatching { Store.save("formats.json", info.filterKeys { k -> out.any { "${it.path}|${it.sizeBytes}" == k } }) }
        Log.i(TAG, "scanned ${out.size} tracks")
        return out
    }

    /** Sample rate and bit depth from the file header: FLAC STREAMINFO or WAV fmt chunk. */
    fun readFormat(path: String): List<Int> = runCatching {
        RandomAccessFile(path, "r").use { f ->
            val head = ByteArray(4); f.readFully(head)
            val tag = String(head, Charsets.ISO_8859_1)
            when {
                tag == "fLaC" -> {
                    // Metadata block header (4 bytes), then STREAMINFO.
                    val b = ByteArray(4 + 18); f.readFully(b)
                    val s = 4 + 10
                    val rate = ((b[s].toInt() and 0xFF) shl 12) or ((b[s + 1].toInt() and 0xFF) shl 4) or ((b[s + 2].toInt() and 0xF0) shr 4)
                    val bps = (((b[s + 2].toInt() and 0x01) shl 4) or ((b[s + 3].toInt() and 0xF0) shr 4)) + 1
                    listOf(rate, bps)
                }
                tag == "RIFF" -> {
                    val b = ByteArray(32); f.seek(12); f.readFully(b)
                    if (String(b, 0, 4, Charsets.ISO_8859_1) != "fmt ") listOf(0, 0) else {
                        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                        val rate = u16(12) or (u16(14) shl 16)
                        listOf(rate, u16(22))
                    }
                }
                tag.startsWith("ID3") || (head[0].toInt() and 0xFF) == 0xFF -> listOf(0, 0)
                else -> listOf(0, 0)
            }
        }
    }.getOrDefault(listOf(0, 0))

    fun albums(tracks: List<Track>): List<Album> = tracks.groupBy { it.albumKey }.map { (key, list) ->
        val sorted = list.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title.lowercase(Locale.ROOT) }))
        val f = sorted.first()
        val title = if (key.startsWith("folder:")) f.folderName else f.album
        val artist = f.albumArtist.ifBlank {
            val artists = list.map { it.artist }.distinct()
            if (artists.size == 1) artists[0] else "Various artists"
        }
        Album(key, title, artist, list.maxOf { it.year }, sorted)
    }.sortedBy { it.title.lowercase(Locale.ROOT) }

    /** Each artist named in a track's artist tag ("A, B & C" counts for all three). */
    fun splitArtists(s: String): List<String> =
        s.split(Regex("\\s*(,|&|;| feat\\.? | ft\\.? | x )\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotBlank() }
}
