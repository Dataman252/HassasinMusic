package com.hassasin.music

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    /** Time-stamped lines; null when only plain text exists. */
    val synced: List<LyricLine>?,
    val plain: String?,
    val instrumental: Boolean,
    /** Where they came from: "File", "Tags", "LRCLIB", "Pasted", "Tapped". */
    val source: String,
)

@Serializable
data class CachedLyrics(
    val found: Boolean,
    val raw: String? = null,
    val plain: String? = null,
    val instrumental: Boolean = false,
    val source: String = "",
    val checkedAt: Long = System.currentTimeMillis(),
    /** Which version of the search logic produced this; "not found" from older logic is retried. */
    val logic: Int = 0,
    /** Picked or timed by the user: wins over lyrics in the file or its tags. */
    val userChosen: Boolean = false,
)

@Serializable
data class LrcCandidate(val id: Long, val title: String, val artist: String, val album: String, val durationSec: Double, val synced: String?, val plain: String?, val instrumental: Boolean)

object LyricsRepo {
    private const val TAG = "Lyrics"
    private const val RETRY_MS = 24L * 3600 * 1000
    /** Bump when the search improves, so earlier "not found" answers are tried again. */
    private const val LOGIC = 7
    private lateinit var dir: File

    fun init(context: Context) { dir = File(context.filesDir, "lyrics").apply { mkdirs() } }

    private fun cacheFile(t: Track) = File(dir, "${t.id}-${t.sizeBytes}.json")

    // ---- parsing ----

    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(rawIn: String?, plainIn: String?, instrumental: Boolean, source: String): Lyrics {
        val raw = Script.unify(rawIn)
        val plainAlt = Script.unify(plainIn)
        if (raw != null && stamp.containsMatchIn(raw)) {
            val offset = offsetTag.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val lines = mutableListOf<LyricLine>()
            raw.lineSequence().forEach { line ->
                val stamps = stamp.findAll(line).toList()
                if (stamps.isEmpty()) return@forEach
                // Enhanced LRC: per-word times "<00:01.57>" and singer marks "v1:" / "M:" / "F:" / "D:" are not words.
                val text = line.substring(stamps.last().range.last + 1)
                    .replace(Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>"), "")
                    .replace(Regex("^\\s*(?:v\\d+|[MFD]):\\s*"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                stamps.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    lines += LyricLine((min * 60 + sec) * 1000 + ms - offset, text)
                }
            }
            lines.sortBy { it.timeMs }
            return Lyrics(lines, plainAlt ?: lines.joinToString("\n") { it.text }, instrumental, source)
        }
        return Lyrics(null, raw ?: plainAlt, instrumental, source)
    }

    // ---- names used for searching ----

    /** Versions of a title to try, from most to least exact: tags like " - EnVy", "(Remastered)" and "feat." removed. */
    fun titleCandidates(title: String): List<String> {
        val out = linkedSetOf(title.trim())
        var t = title
        // Untagged files: the title is the file name, often with the track number in front
        // ("02 Taal Se Taal Mila", "03 - Aap Ki Kashish"). Removed first, so " - " after it is not taken for a tag.
        t = t.replace(Regex("^\\s*\\d{1,3}\\s*[-._)]?\\s+"), "")
        out += t.trim()
        t = t.replace(Regex("\\s+-\\s+@\\S+.*$"), "")             // " - @uploader"
        out += t.trim()
        t = t.replace(Regex("\\s*[(\\[][^)\\]]*(remaster|version|edit|mix|live|explicit|bonus|from |feat|ft\\.)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE), "")
        out += t.trim()
        // " - EnVy", " - Remastered 2011", unless that would leave almost nothing of the title.
        val noSuffix = t.replace(Regex("\\s+-\\s+[^-]+$"), "")
        if (noSuffix.count { it.isLetter() } >= 3) { t = noSuffix; out += t.trim() }
        // The most cleaned-up version goes last.
        out.remove(t.trim()); out += t.trim()
        return out.filter { it.isNotBlank() }
    }

    fun mainArtist(artist: String): String = Library.splitArtists(artist).firstOrNull() ?: artist

    fun cleanAlbum(album: String): String =
        album.replace(Regex("\\s*[(\\[][^)\\]]*(soundtrack|deluxe|edition|remaster|ep)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+-\\s+(EP|Single)$", RegexOption.IGNORE_CASE), "").trim()

    // ---- finding ----

    /**
     * Lyrics for a track, in this order: the user's own pick or tapped timing → .lrc beside the file →
     * lyrics in the file's tags → cache → LRCLIB by exact match → LRCLIB search. Lyrics in the file
     * without timing still get an online look-up, and timed lyrics found there are used instead.
     * Returns null when nothing is found.
     */
    suspend fun find(context: Context, t: Track, online: Boolean, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        val cached = Store.load<CachedLyrics?>("lyrics/${cacheFile(t).name}", null)
        if (cached != null && cached.found && cached.userChosen && !force) return@withContext parse(cached.raw, cached.plain, cached.instrumental, cached.source)
        val local = sidecar(t)?.let { parse(it, null, false, "File") } ?: Tags.embeddedLyrics(t.path)?.let { parse(it, null, false, "Tags") }
        if (local?.synced != null) return@withContext local
        fun better(found: Lyrics?): Lyrics? = if (found != null && (local == null || found.synced != null)) found else local
        if (cached != null && !force) {
            if (cached.found) return@withContext better(parse(cached.raw, cached.plain, cached.instrumental, cached.source))
            if (cached.logic >= LOGIC && System.currentTimeMillis() - cached.checkedAt < RETRY_MS) return@withContext local
        }
        if (!online) return@withContext local
        val hit = lookup(t)
        Store.save("lyrics/${cacheFile(t).name}", hit?.let { CachedLyrics(true, it.synced ?: it.plain, it.plain, it.instrumental, "LRCLIB", logic = LOGIC) } ?: CachedLyrics(false, logic = LOGIC))
        better(hit?.let { parse(it.synced, it.plain, it.instrumental, "LRCLIB") })
    }

    /** True when this track still needs an online look-up (nothing local, nothing cached that counts). */
    fun needsLookup(t: Track): Boolean {
        val cached = Store.load<CachedLyrics?>("lyrics/${cacheFile(t).name}", null) ?: return true
        if (cached.found) return false
        return cached.logic < LOGIC || System.currentTimeMillis() - cached.checkedAt >= RETRY_MS
    }

    fun sidecar(t: Track): String? {
        val base = t.path.substringBeforeLast('.')
        return listOf("$base.lrc", "$base.LRC").map(::File).firstOrNull { it.isFile }?.readText()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun candidate(o: JsonObject) = LrcCandidate(
        id = o["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
        title = o["trackName"]?.jsonPrimitive?.contentOrNull ?: "",
        artist = o["artistName"]?.jsonPrimitive?.contentOrNull ?: "",
        album = o["albumName"]?.jsonPrimitive?.contentOrNull ?: "",
        durationSec = o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        synced = o["syncedLyrics"]?.jsonPrimitive?.contentOrNull,
        plain = o["plainLyrics"]?.jsonPrimitive?.contentOrNull,
        instrumental = o["instrumental"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private suspend fun lookup(t: Track): LrcCandidate? {
        val secs = t.durationMs / 1000
        val artist = mainArtist(t.artist.ifBlank { t.albumArtist })
        val album = cleanAlbum(t.album)
        // 1. Exact look-ups (title, artist, album and length must all fit).
        for (title in titleCandidates(t.title).take(3)) {
            val (code, body) = Net.get(
                "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}&album_name=${enc(album)}&duration=$secs", retries = 1,
            )
            if (code == 200 && body != null) {
                val c = runCatching { candidate(json.parseToJsonElement(body).jsonObject) }.getOrNull()
                if (c != null && (c.synced != null || c.plain != null || c.instrumental)) {
                    Log.i(TAG, "exact match for '${t.title}' as '$title'")
                    return c
                }
            }
        }
        // 2. Search by title + artist, then by title alone (artist tags are often wrong: the film's name),
        //    keeping only results whose title really matches.
        // Search words without bracketed extras ("(Jhankar)", "[Explicit]"): LRCLIB matches the plain name.
        val clean = titleCandidates(t.title).last().replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifBlank { titleCandidates(t.title).last() }
        val seen = LinkedHashMap<Long, LrcCandidate>()
        for (results in listOf(search(clean, artist), search(clean, ""), if (artist.isNotBlank()) searchText("$clean $artist") else emptyList())) {
            results.forEach { seen.putIfAbsent(it.id, it) }
            pick(seen.values.toList(), secs, clean)?.let { Log.i(TAG, "search match for '${t.title}' as '$clean'"); return it }
        }
        // 3. Only another version of the song (Jhankar, remix, a longer cut): its words, without timing.
        pickOtherVersion(seen.values.toList(), secs, clean)?.let { c ->
            Log.i(TAG, "other version for '${t.title}': ${c.durationSec}s vs ${secs}s, shown without timing")
            return c.copy(synced = null, plain = c.plain ?: c.synced?.let { stripTimes(it) })
        }
        Log.i(TAG, "no lyrics for '${t.title}' by '$artist'")
        return null
    }

    /** A title reduced to its words: brackets ("(Jhankar)", "[Explicit]") and punctuation removed. */
    private fun titleKey(s: String) = titleCandidates(s).last().replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Same song name: equal, one contained in the other ("Man Ko Ati Bhavey" / "… London Dreams"), or very close. */
    fun titleFits(found: String, title: String): Boolean {
        // Compare both the full and the cleaned-up forms ("Ibn - E - Batuta" must not lose "Batuta" as a tag).
        fun keys(s: String) = setOf(titleKey(s), s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()).filter { it.isNotEmpty() }
        for (a in keys(found)) for (b in keys(title)) {
            if (a == b) return true
            if (a.length >= 6 && b.length >= 6 && (a.startsWith(b) || b.startsWith(a))) return true
            if (ArtRepo.similarity(a, b) >= 0.8) return true
        }
        return false
    }

    private fun titleFits(c: LrcCandidate, title: String) = titleFits(c.title, title)

    /** Same song, same length (within 4 s); synced and single-script versions first. */
    fun pick(results: List<LrcCandidate>, secs: Long, title: String? = null): LrcCandidate? =
        results.filter { abs(it.durationSec - secs) <= 4 && (it.synced != null || it.plain != null || it.instrumental) && (title == null || titleFits(it, title)) }
            .sortedWith(compareBy({ if (it.synced != null) 0 else 1 }, { Script.mixedCount(it.synced ?: it.plain) > 0 }, { abs(it.durationSec - secs) }, { Script.mixedCount(it.synced ?: it.plain) }))
            .firstOrNull()

    /** Same title, different length (up to 20 %): the words still help, the timing would not. */
    private fun pickOtherVersion(results: List<LrcCandidate>, secs: Long, title: String): LrcCandidate? =
        results.filter { (it.plain != null || it.synced != null) && abs(it.durationSec - secs) <= 0.2 * secs && titleFits(it, title) }
            .minByOrNull { abs(it.durationSec - secs) }

    private fun stripTimes(lrc: String) = lrc.lines().map { it.replace(Regex("\\[[^]]*]"), "").trim() }.filter { it.isNotBlank() }.joinToString("\n")

    suspend fun search(title: String, artist: String): List<LrcCandidate> {
        val url = if (artist.isNotBlank()) "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        else "https://lrclib.net/api/search?q=${enc(title)}"
        val (code, body) = Net.get(url, retries = 1)
        if (code != 200 || body == null) return emptyList()
        return runCatching { (json.parseToJsonElement(body) as JsonArray).map { candidate(it.jsonObject) } }.getOrDefault(emptyList())
    }

    /** Free-text search for the manual picker. */
    suspend fun searchText(q: String): List<LrcCandidate> = withContext(Dispatchers.IO) {
        val (code, body) = Net.get("https://lrclib.net/api/search?q=${enc(q)}", retries = 1)
        if (code != 200 || body == null) emptyList()
        else runCatching { json.parseToJsonElement(body).jsonArray.map { candidate(it.jsonObject) } }.getOrDefault(emptyList())
    }

    /** Keeps the user's choice (from the manual search, or pasted text) for this track. */
    fun choose(t: Track, raw: String?, plain: String?, instrumental: Boolean, source: String) {
        Store.save("lyrics/${cacheFile(t).name}", CachedLyrics(true, raw ?: plain, plain, instrumental, source, userChosen = true))
    }

    // ---- per-track timing offset ----

    private var offsets: MutableMap<Long, Long>? = null

    fun offsetMs(t: Track): Long = (offsets ?: Store.load<Map<Long, Long>>("lyrics_offsets.json", emptyMap()).toMutableMap().also { offsets = it })[t.id] ?: 0L

    fun setOffset(t: Track, ms: Long) {
        val m = offsets ?: Store.load<Map<Long, Long>>("lyrics_offsets.json", emptyMap()).toMutableMap()
        if (ms == 0L) m.remove(t.id) else m[t.id] = ms
        offsets = m
        Store.save("lyrics_offsets.json", m)
    }

    @Suppress("unused") private fun lower(s: String) = s.lowercase(Locale.ROOT)
}
