package com.hassasin.music

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** The song details that can be edited. */
data class TagValues(val title: String, val artist: String, val album: String, val albumArtist: String, val year: String, val track: String)

/**
 * Writes song details into FLAC (Vorbis comments) and MP3 (ID3v2) files. Everything else in the file
 * (pictures, lyrics, ReplayGain, the audio itself) is kept byte for byte. The file is rebuilt in the
 * app's cache first and written back only when that worked; Android asks the user for permission.
 */
object TagWriter {
    fun supported(path: String) = path.lowercase().let { it.endsWith(".flac") || it.endsWith(".mp3") }

    fun uriOf(t: Track): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id)

    /** Builds the new file contents from the old ones. */
    fun rebuild(old: ByteArray, path: String, v: TagValues): ByteArray =
        if (path.lowercase().endsWith(".flac")) flac(old, v) else mp3(old, v)

    // ---- FLAC ----

    private fun le32(x: Int) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())
    private fun rdLe32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun flac(old: ByteArray, v: TagValues): ByteArray {
        require(old.size > 8 && String(old, 0, 4, Charsets.ISO_8859_1) == "fLaC") { "Not a FLAC file" }
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        var pos = 4
        var vendor = "Hassasin Music"
        val comments = mutableListOf<String>()
        while (true) {
            val last = (old[pos].toInt() and 0x80) != 0
            val type = old[pos].toInt() and 0x7F
            val len = ((old[pos + 1].toInt() and 0xFF) shl 16) or ((old[pos + 2].toInt() and 0xFF) shl 8) or (old[pos + 3].toInt() and 0xFF)
            val body = old.copyOfRange(pos + 4, pos + 4 + len)
            when (type) {
                4 -> {
                    var i = 0
                    val vl = rdLe32(body, i); i += 4; vendor = String(body, i, vl, Charsets.UTF_8); i += vl
                    val n = rdLe32(body, i); i += 4
                    repeat(n) { val l = rdLe32(body, i); i += 4; comments += String(body, i, l, Charsets.UTF_8); i += l }
                }
                1 -> {} // padding: replaced below
                else -> blocks += type to body
            }
            pos += 4 + len
            if (last) break
        }
        val replace = mapOf("TITLE" to v.title, "ARTIST" to v.artist, "ALBUM" to v.album, "ALBUMARTIST" to v.albumArtist, "DATE" to v.year, "TRACKNUMBER" to v.track)
        val kept = comments.filter { c -> c.substringBefore('=').uppercase() !in replace.keys }
        val all = kept + replace.filterValues { it.isNotBlank() }.map { (k, x) -> "$k=${x.trim()}" }
        val vc = ByteArrayOutputStream().apply {
            val vb = vendor.toByteArray(Charsets.UTF_8); write(le32(vb.size)); write(vb)
            write(le32(all.size)); all.forEach { c -> val cb = c.toByteArray(Charsets.UTF_8); write(le32(cb.size)); write(cb) }
        }.toByteArray()
        // STREAMINFO first, then the comments, the other blocks, and fresh padding for later edits.
        val ordered = listOf(blocks.first { it.first == 0 }) + listOf(4 to vc) + blocks.filter { it.first != 0 } + listOf(1 to ByteArray(4096))
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        ordered.forEachIndexed { k, (type, body) ->
            val last = k == ordered.lastIndex
            out.write((type or if (last) 0x80 else 0).toInt())
            out.write(byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
            out.write(body)
        }
        out.write(old, pos, old.size - pos)
        return out.toByteArray()
    }

    // ---- MP3 (ID3v2.3 written; other frames from v2.3/v2.4 kept) ----

    private fun syncsafeRead(b: ByteArray, i: Int) = ((b[i].toInt() and 0x7F) shl 21) or ((b[i + 1].toInt() and 0x7F) shl 14) or ((b[i + 2].toInt() and 0x7F) shl 7) or (b[i + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun mp3(old: ByteArray, v: TagValues): ByteArray {
        val frames = mutableListOf<Pair<String, ByteArray>>() // id to frame body (with original flags dropped)
        var audioStart = 0
        var ver = 3
        if (old.size > 10 && String(old, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            ver = old[3].toInt()
            require((old[5].toInt() and 0x80) == 0) { "This MP3's tag uses unsynchronisation, which the editor does not change" }
            val size = syncsafeRead(old, 6)
            audioStart = 10 + size + if ((old[5].toInt() and 0x10) != 0) 10 else 0
            var i = 10
            if ((old[5].toInt() and 0x40) != 0) i += if (ver == 4) syncsafeRead(old, 10) else be32(old, 10) + 4
            while (i + 10 <= 10 + size) {
                val id = String(old, i, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val len = if (ver == 4) syncsafeRead(old, i + 4) else be32(old, i + 4)
                if (len <= 0 || i + 10 + len > 10 + size) break
                frames += id to old.copyOfRange(i + 10, i + 10 + len)
                i += 10 + len
            }
        }
        // Text frames in v2.3 form: UTF-16 with BOM. v2.4-only ids are converted (TDRC → TYER).
        fun text(s: String): ByteArray = byteArrayOf(1) + s.toByteArray(Charsets.UTF_16) // Java UTF_16 writes a BOM
        val mine = mapOf("TIT2" to v.title, "TPE1" to v.artist, "TALB" to v.album, "TPE2" to v.albumArtist, "TYER" to v.year, "TRCK" to v.track)
        val kept = frames.filter { (id, _) -> id !in mine.keys && id != "TDRC" && (ver != 4 || id !in setOf("TDOR", "TDRL", "TSOT", "TSOP", "TSOA", "TIPL", "TMCL")) }
        // v2.4 text frames may be UTF-8 (encoding 3), which v2.3 lacks: re-encode those as UTF-16.
        val fixed = kept.map { (id, b) -> if (id.startsWith("T") && b.isNotEmpty() && b[0].toInt() == 3) id to text(String(b, 1, b.size - 1, Charsets.UTF_8).trimEnd('\u0000')) else id to b }
        val all = fixed + mine.filterValues { it.isNotBlank() }.map { (id, s) -> id to text(s.trim()) }
        val body = ByteArrayOutputStream()
        all.forEach { (id, b) ->
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(byteArrayOf((b.size shr 24).toByte(), (b.size shr 16).toByte(), (b.size shr 8).toByte(), b.size.toByte(), 0, 0))
            body.write(b)
        }
        body.write(ByteArray(2048)) // padding
        val size = body.size()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1)); out.write(byteArrayOf(3, 0, 0))
        out.write(byteArrayOf(((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte()))
        body.writeTo(out)
        out.write(old, audioStart, old.size - audioStart)
        return out.toByteArray()
    }

    /** Rebuilds the file and writes it back (after the user allowed editing). */
    suspend fun write(context: Context, t: Track, v: TagValues): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = uriOf(t)
            val old = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
            val new = rebuild(old, t.path, v)
            // Keep a copy until the new file is safely written.
            val backup = File(context.cacheDir, "tag-backup.bin").apply { writeBytes(old) }
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(new) } ?: error("Couldn't write the file")
            } catch (e: Exception) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(backup.readBytes()) } }
                throw e
            } finally { backup.delete() }
            MediaScannerConnection.scanFile(context, arrayOf(t.path), null, null)
            Unit
        }
    }
}

/** Edit a song's title, artist, album, album artist, year and track number. */
@Composable
fun TagEditorScreen(nav: Nav, trackId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = AppState.library.value.tracks.firstOrNull { it.id == trackId } ?: run { nav.back(); return }
    var v by remember { mutableStateOf(TagValues(t.title, t.artist, t.album, t.albumArtist, if (t.year > 0) t.year.toString() else "", if (t.trackNo > 0) t.trackNo.toString() else "")) }
    var status by remember { mutableStateOf("") }
    fun save() {
        status = "Saving…"
        scope.launch {
            status = TagWriter.write(context, t, v).fold({
                kotlinx.coroutines.delay(1200); AppState.rescan(context); "Saved."
            }, { "Not saved: ${it.message}" })
        }
    }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) save() else status = "Not saved: editing was not allowed."
    }
    Backdrop(Color(0xFF2A1F5C)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Header("Edit tags", { nav.back() }) }
            if (!TagWriter.supported(t.path)) {
                item { Text("Editing works for FLAC and MP3 files. This one is ${t.ext}.", style = body(14.sp, color = C.TextMid)) }
                return@LazyColumn
            }
            item { Text(File(t.path).name, style = mono(11.sp, color = C.TextMid)) }
            val fields = listOf(
                "Title" to v.title, "Artist" to v.artist, "Album" to v.album, "Album artist" to v.albumArtist, "Year" to v.year, "Track number" to v.track,
            )
            fields.forEachIndexed { i, (label, value) ->
                item {
                    Column {
                        Text(label, style = body(12.sp, FontWeight.Bold, C.TextMid))
                        Box(Modifier.fillMaxWidth().height(48.dp).recessed().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            BasicTextField(value, { x ->
                                v = when (i) { 0 -> v.copy(title = x); 1 -> v.copy(artist = x); 2 -> v.copy(album = x); 3 -> v.copy(albumArtist = x); 4 -> v.copy(year = x.filter(Char::isDigit).take(4)); else -> v.copy(track = x.filter { it.isDigit() || it == '/' }.take(7)) }
                            }, singleLine = true, textStyle = body(15.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Save to file", false) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val pi = MediaStore.createWriteRequest(context.contentResolver, listOf(TagWriter.uriOf(t)))
                            allow.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        } else save()
                    }
                    Chip("Cancel", false) { nav.back() }
                }
            }
            if (status.isNotBlank()) item { Text(status, style = body(13.sp, color = if (status.startsWith("Not")) C.Limit else C.TextHi)) }
            item { Text("Pictures, lyrics, ReplayGain and the audio stay as they are. Android asks once per file before the app may change it.", style = body(12.sp, color = C.TextLow)) }
        }
    }
}
