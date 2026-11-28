package com.hassasin.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Where an album's picture comes from. */
@Serializable
data class ArtChoice(
    /** "Online" (found automatically), "Chosen" (picked in Fix art), "Gallery", "Keep" (current art accepted). */
    val source: String,
    val mbid: String = "",
    val at: Long = System.currentTimeMillis(),
)

data class ArtInfo(val source: String, val width: Int, val height: Int, val hash: Long)

data class ArtProblem(val album: Album, val reason: String, val info: ArtInfo?)

data class ArtCandidate(
    val releaseId: String,
    val title: String,
    val artist: String,
    val date: String,
    val trackCount: Int,
    val score: Double,
    val thumbUrl: String,
    val fullUrl: String,
    /** False when the song's length was known and did not match. */
    val lengthOk: Boolean = true,
)

object ArtRepo {
    private const val TAG = "Art"
    private lateinit var appContext: Context
    private lateinit var chosenDir: File
    private lateinit var cacheDir: File
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val info = HashMap<String, ArtInfo?>()
    private val missing = HashSet<String>()
    private var choices: MutableMap<String, ArtChoice> = mutableMapOf()
    private val lock = Mutex()
    /** Bumped whenever a picture changes, so screens showing it redraw. */
    var version by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        chosenDir = File(context.filesDir, "art").apply { mkdirs() }
        cacheDir = File(context.cacheDir, "art").apply { mkdirs() }
        choices = Store.load<Map<String, ArtChoice>>("art_choices.json", emptyMap()).toMutableMap()
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun chosenFile(key: String) = File(chosenDir, md5(key) + ".jpg")
    private fun cachedFile(key: String) = File(cacheDir, md5(key) + ".jpg")

    fun choice(key: String): ArtChoice? = choices[key]

    /** Untagged songs (no album name) each get their own picture, looked up by the song itself. */
    fun artAlbumFor(t: Track): Album? =
        if (t.albumKey.startsWith("folder:")) Album("track:${t.id}", cleanSongTitle(t.title), t.artist, t.year, listOf(t)) else null

    /** "02 Taal Se Taal Mila" -> "Taal Se Taal Mila"; uploader tags removed too. */
    fun cleanSongTitle(title: String): String =
        LyricsRepo.titleCandidates(title).last().replace(Regex("^\\s*\\d{1,3}\\s*[-._)]?\\s+"), "").trim()

    fun cachedBitmap(album: Album): Bitmap? = memory.get(album.key)

    /** The album's picture: a chosen/downloaded one, else from the tags, else from the album's own folder. */
    suspend fun bitmap(album: Album, size: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(album.key)?.let { return@withContext it }
        if (album.key in missing) return@withContext null
        val (bmp, inf) = load(album)
        synchronized(info) { info[album.key] = inf }
        if (bmp == null) { missing += album.key; return@withContext null }
        val scaled = scale(bmp, size)
        memory.put(album.key, scaled)
        scaled
    }

    fun infoOf(album: Album): ArtInfo? = synchronized(info) { info[album.key] }

    private fun load(album: Album): Pair<Bitmap?, ArtInfo?> {
        val chosen = chosenFile(album.key)
        if (chosen.isFile && choices[album.key]?.source != "Keep") {
            decode(chosen.readBytes())?.let { return it to ArtInfo(choices[album.key]?.source ?: "Chosen", it.width, it.height, dHash(it)) }
        }
        // Tags: the first tracks that carry a picture.
        val cached = cachedFile(album.key)
        if (cached.isFile) {
            decode(cached.readBytes())?.let { b ->
                val meta = Store.load<Map<String, List<Int>>>("art_meta.json", emptyMap())[album.key]
                return b to ArtInfo(if (meta?.getOrNull(2) == 1) "Folder" else "Tags", meta?.getOrNull(0) ?: b.width, meta?.getOrNull(1) ?: b.height, dHash(b))
            }
        }
        for (t in album.tracks.take(3)) {
            val bytes = (if (t.bits == 1) DsdTagReader.read(t.path, withPicture = true)?.picture else null) ?: runCatching {
                MediaMetadataRetriever().run { try { setDataSource(t.path); embeddedPicture } finally { release() } }
            }.getOrNull() ?: continue
            val full = decode(bytes) ?: continue
            saveCache(album.key, full, folder = false)
            return full to ArtInfo("Tags", full.width, full.height, dHash(full))
        }
        // The album's folder, only when every track in that folder belongs to this album.
        folderImage(album)?.let { f ->
            decode(f.readBytes())?.let { full ->
                saveCache(album.key, full, folder = true)
                return full to ArtInfo("Folder", full.width, full.height, dHash(full))
            }
        }
        return null to null
    }

    private var folderAlbums: Map<String, Set<String>> = emptyMap()

    /** Called after each library scan: which albums share each folder. */
    fun setLibrary(tracks: List<Track>) {
        folderAlbums = tracks.groupBy { it.folder }.mapValues { (_, l) -> l.map { it.albumKey }.toSet() }
    }

    private fun folderImage(album: Album): File? {
        val folder = album.first.folder
        if ((folderAlbums[folder]?.size ?: 0) > 1) return null
        val names = listOf("cover", "folder", "front", "album", "albumart", "artwork")
        val files = File(folder).listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp") } ?: return null
        return files.firstOrNull { it.nameWithoutExtension.lowercase(Locale.ROOT) in names } ?: files.singleOrNull()
    }

    private fun saveCache(key: String, full: Bitmap, folder: Boolean) {
        runCatching {
            cachedFile(key).outputStream().use { scale(full, 1000).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val meta = Store.load<Map<String, List<Int>>>("art_meta.json", emptyMap()).toMutableMap()
            meta[key] = listOf(full.width, full.height, if (folder) 1 else 0)
            Store.save("art_meta.json", meta)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    private fun scale(b: Bitmap, size: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= size) return b
        val f = size.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** 64-bit difference hash: near-identical pictures give near-identical hashes. */
    fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            fun lum(p: Int) = ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114
            if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) h = h or (1L shl (y * 8 + x))
        }
        return h
    }

    private fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    // ---- problems ----

    /** Albums whose art is missing, tiny, or the same picture as a different album's. */
    suspend fun problems(albums: List<Album>): List<ArtProblem> {
        val out = mutableListOf<ArtProblem>()
        val withHash = mutableListOf<Pair<Album, ArtInfo>>()
        for (a in albums) {
            bitmap(a)
            val inf = infoOf(a)
            if (choices[a.key]?.source == "Keep") continue
            when {
                inf == null -> out += ArtProblem(a, "No picture", null)
                min(inf.width, inf.height) < 300 -> out += ArtProblem(a, "Low resolution (${inf.width}×${inf.height})", inf)
                else -> withHash += a to inf
            }
        }
        for ((a, inf) in withHash) {
            if (inf.source == "Online" || inf.source == "Chosen" || inf.source == "Gallery") continue
            val twins = withHash.filter { (b, i) -> b.key != a.key && b.title.lowercase(Locale.ROOT) != a.title.lowercase(Locale.ROOT) && hamming(i.hash, inf.hash) <= 4 }
            if (twins.isNotEmpty()) out += ArtProblem(a, "Same picture as “${twins.first().first.title}”" + if (twins.size > 1) " and ${twins.size - 1} more" else "", inf)
        }
        return out
    }

    // ---- online ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun lucene(s: String) = s.replace(Regex("[\\\\+\\-!(){}\\[\\]^\"~*?:/]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val d = Array(x.length + 1) { IntArray(y.length + 1) }
        for (i in 0..x.length) d[i][0] = i
        for (j in 0..y.length) d[0][j] = j
        for (i in 1..x.length) for (j in 1..y.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
        }
        val lev = 1.0 - d[x.length][y.length].toDouble() / max(x.length, y.length)
        // Same words in another order still match; repeated words are not counted as a match
        // ("Music" is not "Music, Music, Music").
        val sx = x.split(' ').sorted().joinToString(" "); val sy = y.split(' ').sorted().joinToString(" ")
        return if (sx == sy) max(lev, 0.98) else lev
    }

    /** Releases on MusicBrainz that look like this album, best first. */
    /** Matches from every source, best first. */
    suspend fun candidates(album: Album, query: String? = null): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val apple = if (AppState.settings.value.artFromApple) runCatching { appleCandidates(album, query) }.getOrDefault(emptyList()) else emptyList()
        // A sure match from Apple is enough; otherwise ask MusicBrainz as well.
        val mb = if (apple.any { it.score >= 0.85 && it.lengthOk }) emptyList() else runCatching { musicBrainzCandidates(album, query) }.getOrDefault(emptyList())
        (apple + mb).sortedByDescending { it.score }
    }

    private fun albumNoise(s: String) = Regex("(?i)best of|greatest|hits|vol\\.|volume|collection|classic|workout|lofi|lo-fi|instrumental|karaoke|cover|mashup|remix").containsMatchIn(s)

    /**
     * Apple's iTunes catalogue (song search). Scored on the song name, its length against the file,
     * the album name when the file has one, and the original soundtrack/album over compilations.
     */
    private suspend fun appleCandidates(album: Album, query: String?): List<ArtCandidate> {
        val t = album.first
        val single = album.key.startsWith("track:")
        val title = cleanSongTitle(t.title)
        val albumName = if (single) "" else LyricsRepo.cleanAlbum(album.title)
        val artist = LyricsRepo.mainArtist(t.artist)
        val term = query ?: listOf(title, if (albumName.isNotBlank() && !similarityHigh(albumName, title)) albumName else artist).filter { it.isNotBlank() }.joinToString(" ")
        val (code, body) = Net.itunes("https://itunes.apple.com/search?term=${enc(term)}&entity=song&country=IN&limit=15")
        if (code != 200 || body == null) { Log.w(TAG, "iTunes $code for $term"); return emptyList() }
        val results = runCatching { json.parseToJsonElement(body).jsonObject["results"]!!.jsonArray }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, ArtCandidate>()
        for (el in results) {
            val r = el.jsonObject
            val art = r["artworkUrl100"]?.jsonPrimitive?.contentOrNull ?: continue
            val coll = r["collectionName"]?.jsonPrimitive?.contentOrNull ?: ""
            val collId = r["collectionId"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = r["trackName"]?.jsonPrimitive?.contentOrNull ?: ""
            val rArtist = r["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
            val ms = r["trackTimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val diff = ms?.let { kotlin.math.abs(it - t.durationMs) / 1000.0 }
            val titleFit = similarity(LyricsRepo.titleCandidates(name).last(), title)
            val original = !albumNoise(coll)
            // A different edit of the same song (5:00 file vs 5:33 soundtrack) still counts as a fair length match.
            val sameSongOtherEdit = diff != null && titleFit >= 0.95 && original && diff <= 0.12 * t.durationMs / 1000.0
            val lengthFit = when { diff == null -> 0.4; diff <= 3 -> 1.0; diff <= 8 -> 0.7; sameSongOtherEdit -> 0.7; diff <= 25 -> 0.3; else -> 0.0 }
            // Untagged files say nothing about the album: neutral, not a penalty.
            val albumFit = if (albumName.isBlank()) 0.8 else similarity(LyricsRepo.cleanAlbum(coll), albumName)
            val score = 0.35 * titleFit + 0.3 * lengthFit + 0.2 * albumFit + (if (original) 0.15 else 0.0)
            val c = ArtCandidate(
                "itunes:$collId", coll, rArtist, (r["releaseDate"]?.jsonPrimitive?.contentOrNull ?: "").take(4),
                r["trackCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0, score,
                art.replace("100x100bb", "250x250bb"), art.replace("100x100bb", "1200x1200bb"),
                // Same song, original soundtrack, a different edit (5:00 vs 5:33): the cover is the same.
                lengthOk = diff != null && (diff <= 8 || sameSongOtherEdit),
            )
            if ((out[collId]?.score ?: -1.0) < score) out[collId] = c
        }
        return out.values.sortedByDescending { it.score }
    }

    private fun similarityHigh(a: String, b: String) = similarity(a, b) > 0.8

    private suspend fun musicBrainzCandidates(album: Album, query: String?): List<ArtCandidate> {
        if (album.key.startsWith("track:")) return songCandidates(album.first, query)
        return releaseCandidates(album, query)
    }

    /** Releases on MusicBrainz that look like this album. */
    private suspend fun releaseCandidates(album: Album, query: String?): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val title = LyricsRepo.cleanAlbum(if (album.key.startsWith("folder:")) album.first.album.ifBlank { album.title } else album.title)
        val artist = LyricsRepo.mainArtist(album.artist.takeIf { it != "Various artists" } ?: album.first.artist)
        val q = query?.let { lucene(it) } ?: buildString {
            append("release:\"${lucene(title)}\"")
            if (artist.isNotBlank()) append(" AND artist:\"${lucene(artist)}\"")
        }
        val (code, body) = Net.musicBrainz("https://musicbrainz.org/ws/2/release/?query=${enc(q)}&fmt=json&limit=10")
        if (code != 200 || body == null) { Log.w(TAG, "MusicBrainz $code for $q"); return@withContext emptyList() }
        val releases = runCatching { json.parseToJsonElement(body).jsonObject["releases"]!!.jsonArray }.getOrNull() ?: return@withContext emptyList()
        releases.mapNotNull { el ->
            val r = el.jsonObject
            val id = r["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rTitle = r["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val rArtist = r["artist-credit"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            val count = r["track-count"]?.jsonPrimitive?.intOrNull ?: 0
            val mbScore = (r["score"]?.jsonPrimitive?.intOrNull ?: 0) / 100.0
            val countFit = when { count == 0 -> 0.5; count == album.tracks.size -> 1.0; count >= album.tracks.size -> 0.7; else -> 0.3 }
            val score = 0.3 * mbScore + 0.35 * similarity(rTitle, title) + 0.25 * (if (artist.isBlank()) 0.5 else similarity(rArtist, artist)) + 0.1 * countFit
            ArtCandidate(
                id, rTitle, rArtist, r["date"]?.jsonPrimitive?.contentOrNull ?: "", count, score,
                "https://coverartarchive.org/release/$id/front-250", "https://coverartarchive.org/release/$id/front-1200",
            )
        }.sortedByDescending { it.score }
    }

    /**
     * For a single song without album tags: find the recording on MusicBrainz by title (and artist
     * when known), check its length against the file, and offer the albums it appears on.
     */
    private suspend fun songCandidates(t: Track, query: String?): List<ArtCandidate> {
        val title = cleanSongTitle(t.title)
        val artist = LyricsRepo.mainArtist(t.artist)
        val q = query?.let { lucene(it) } ?: buildString {
            append("recording:\"${lucene(title)}\"")
            if (artist.isNotBlank()) append(" AND artist:\"${lucene(artist)}\"")
        }
        val (code, body) = Net.musicBrainz("https://musicbrainz.org/ws/2/recording/?query=${enc(q)}&fmt=json&limit=15")
        if (code != 200 || body == null) { Log.w(TAG, "MusicBrainz $code for $q"); return emptyList() }
        val recs = runCatching { json.parseToJsonElement(body).jsonObject["recordings"]!!.jsonArray }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, ArtCandidate>()
        for (el in recs) {
            val r = el.jsonObject
            val rTitle = r["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val rArtist = r["artist-credit"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            val lengthMs = r["length"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val diff = lengthMs?.let { kotlin.math.abs(it - t.durationMs) / 1000.0 }
            val lengthFit = when { diff == null -> 0.4; diff <= 4 -> 1.0; diff <= 10 -> 0.7; diff <= 30 -> 0.3; else -> 0.0 }
            val mbScore = (r["score"]?.jsonPrimitive?.intOrNull ?: 0) / 100.0
            val titleFit = similarity(rTitle, title)
            val artistFit = if (artist.isBlank()) 0.5 else similarity(rArtist, artist)
            r["releases"]?.jsonArray?.forEach { relEl ->
                val rel = relEl.jsonObject
                val rg = rel["release-group"]?.jsonObject
                val rgId = rg?.get("id")?.jsonPrimitive?.contentOrNull ?: return@forEach
                // The film soundtrack or studio album beats a compilation.
                val compilation = rg["secondary-types"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "Compilation" } == true
                val score = 0.4 * titleFit + 0.3 * lengthFit + 0.1 * mbScore + 0.1 * artistFit + if (compilation) 0.0 else 0.1
                val c = ArtCandidate(
                    rgId, rel["title"]?.jsonPrimitive?.contentOrNull ?: "", rArtist,
                    rel["date"]?.jsonPrimitive?.contentOrNull ?: "", 0, score,
                    "https://coverartarchive.org/release-group/$rgId/front-250", "https://coverartarchive.org/release-group/$rgId/front-1200",
                    lengthOk = diff != null && diff <= 10,
                )
                if ((out[rgId]?.score ?: -1.0) < score) out[rgId] = c
            }
        }
        return out.values.sortedByDescending { it.score }.take(12)
    }

    /** At least four letters (in any script): enough to identify a song by its name. */
    fun meaningfulTitle(t: String): Boolean = t.count { it.isLetter() } >= 4

    suspend fun thumb(url: String): Bitmap? = withContext(Dispatchers.IO) { Net.bytes(url)?.let(::decode) }

    /** Saves a picture as this album's art. */
    suspend fun choose(album: Album, bytes: ByteArray, source: String, mbid: String = ""): Boolean = withContext(Dispatchers.IO) {
        val bmp = decode(bytes) ?: return@withContext false
        lock.withLock {
            chosenFile(album.key).outputStream().use { scale(bmp, 1200).compress(Bitmap.CompressFormat.JPEG, 92, it) }
            choices[album.key] = ArtChoice(source, mbid)
            Store.save("art_choices.json", choices)
        }
        forget(album.key)
        true
    }

    suspend fun chooseCandidate(album: Album, c: ArtCandidate, source: String = "Chosen"): Boolean {
        val bytes = Net.bytes(c.fullUrl) ?: Net.bytes(c.fullUrl.replace("front-1200", "front-500")) ?: return false
        return choose(album, bytes, source, c.releaseId)
    }

    suspend fun chooseUri(context: Context, album: Album, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
        choose(album, bytes, "Gallery")
    }

    /** Accepts the current picture: the album is no longer listed as a problem. */
    suspend fun keep(album: Album) = lock.withLock {
        choices[album.key] = ArtChoice("Keep")
        Store.save("art_choices.json", choices)
        forget(album.key)
    }

    /** Goes back to the picture in the file (or the folder). */
    suspend fun reset(album: Album) = lock.withLock {
        choices.remove(album.key)
        chosenFile(album.key).delete()
        Store.save("art_choices.json", choices)
        forget(album.key)
    }

    private fun forget(key: String) {
        memory.remove(key)
        missing.remove(key)
        synchronized(info) { info.remove(key) }
        version++
    }

    /**
     * Finds art online for albums (and untagged songs) without a picture. Only a sure match
     * (score >= 0.85, with a cover on the Cover Art Archive) is applied; up to three sure matches are
     * tried in turn. With [replace], albums listed as problems get a new picture too.
     */
    suspend fun autoFill(albums: List<Album>, replace: Set<String> = emptySet(), onProgress: (String) -> Unit = {}): Int {
        var fixed = 0
        for (a in albums) {
            if (choices.containsKey(a.key)) continue
            if (a.key !in replace && bitmap(a) != null) continue
            // A file called "03" or "0719-02_1752867212747" says nothing about the song: never guess.
            if (a.key.startsWith("track:") && !meaningfulTitle(a.title)) continue
            onProgress(a.title)
            val sure = candidates(a).filter { it.score >= 0.85 && (it.lengthOk || !it.releaseId.startsWith("itunes:") && !a.key.startsWith("track:")) }.take(3)
            Log.i(TAG, "'${a.title}': ${sure.size} sure match(es)" + (sure.firstOrNull()?.let { " best '${it.title}' %.2f".format(it.score) } ?: ""))
            for (c in sure) if (chooseCandidate(a, c, "Online")) { fixed++; break }
        }
        return fixed
    }
}
