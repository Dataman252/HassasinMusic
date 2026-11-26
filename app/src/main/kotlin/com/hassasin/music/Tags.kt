package com.hassasin.music

import java.io.RandomAccessFile
import java.nio.charset.Charset

/** Reads lyrics stored inside the audio file. Read-only: files are never changed. */
object Tags {

    fun embeddedLyrics(path: String): String? = runCatching {
        RandomAccessFile(path, "r").use { f ->
            val head = ByteArray(8); f.readFully(head)
            val tag = String(head, 0, 4, Charsets.ISO_8859_1)
            when {
                tag == "fLaC" -> flac(f)
                tag.startsWith("ID3") -> id3(f)
                String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(f)
                else -> null
            }
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    // ---- FLAC: Vorbis comments ----

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    private fun flac(f: RandomAccessFile): String? {
        var pos = 4L
        while (pos < f.length()) {
            f.seek(pos)
            val h = ByteArray(4); f.readFully(h)
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4) {
                val b = ByteArray(len); f.readFully(b)
                var i = 0
                val vendor = le32(b, i).toInt(); i += 4 + vendor
                val count = le32(b, i).toInt(); i += 4
                val found = mutableMapOf<String, String>()
                repeat(count) {
                    if (i + 4 > b.size) return@repeat
                    val l = le32(b, i).toInt(); i += 4
                    if (l < 0 || i + l > b.size) return@repeat
                    val kv = String(b, i, l, Charsets.UTF_8); i += l
                    val eq = kv.indexOf('=')
                    if (eq > 0) found[kv.substring(0, eq).uppercase()] = kv.substring(eq + 1)
                }
                return found["SYNCEDLYRICS"] ?: found["LYRICS"] ?: found["UNSYNCEDLYRICS"] ?: found["UNSYNCED LYRICS"]
            }
            if (last) break
            pos += 4 + len
        }
        return null
    }

    // ---- MP3: ID3v2 USLT ----

    private fun syncsafe(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0x7F) shl 21) or ((b[i + 1].toInt() and 0x7F) shl 14) or ((b[i + 2].toInt() and 0x7F) shl 7) or (b[i + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun id3(f: RandomAccessFile): String? {
        f.seek(0)
        val h = ByteArray(10); f.readFully(h)
        val ver = h[3].toInt()
        val size = syncsafe(h, 6)
        if (size <= 0 || size > 64 * 1024 * 1024) return null
        val b = ByteArray(size); f.readFully(b)
        var i = 0
        if ((h[5].toInt() and 0x40) != 0) i += if (ver == 4) syncsafe(b, 0) else be32(b, 0) + 4
        while (i + 10 <= b.size) {
            val id = String(b, i, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val len = if (ver == 4) syncsafe(b, i + 4) else be32(b, i + 4)
            if (len <= 0 || i + 10 + len > b.size) break
            if (id == "USLT") return uslt(b, i + 10, len)
            i += 10 + len
        }
        return null
    }

    private fun uslt(b: ByteArray, start: Int, len: Int): String? {
        val enc = b[start].toInt()
        var i = start + 4 // encoding + language
        val end = start + len
        val wide = enc == 1 || enc == 2
        // Skip the content descriptor (ends with a null of the encoding's width).
        while (i < end) {
            if (!wide && b[i].toInt() == 0) { i += 1; break }
            if (wide && i + 1 < end && b[i].toInt() == 0 && b[i + 1].toInt() == 0) { i += 2; break }
            i += if (wide) 2 else 1
        }
        if (i >= end) return null
        val cs: Charset = when (enc) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        return String(b, i, end - i, cs).trimEnd('\u0000')
    }

    // ---- MP4 / M4A: ©lyr ----

    private fun mp4(f: RandomAccessFile): String? = findAtom(f, 0, f.length(), listOf("moov", "udta", "meta", "ilst", "©lyr", "data"))

    private fun findAtom(f: RandomAccessFile, from: Long, to: Long, path: List<String>): String? {
        var pos = from
        val want = path.first()
        while (pos + 8 <= to) {
            f.seek(pos)
            val h = ByteArray(8); f.readFully(h)
            var size = be32(h, 0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) { val ext = ByteArray(8); f.readFully(ext); size = (be32(ext, 0).toLong() shl 32) or (be32(ext, 4).toLong() and 0xFFFFFFFFL); header = 16 }
            if (size == 0L) size = to - pos
            if (size < header) return null
            if (type == want) {
                if (path.size == 1) {
                    val n = (size - header - 8).toInt()
                    if (n <= 0 || n > 4 * 1024 * 1024) return null
                    f.seek(pos + header + 8)
                    val b = ByteArray(n); f.readFully(b)
                    return String(b, Charsets.UTF_8)
                }
                // "meta" has 4 bytes of version and flags before its children.
                val childStart = pos + header + if (type == "meta") 4 else 0
                return findAtom(f, childStart, pos + size, path.drop(1))
            }
            pos += size
        }
        return null
    }
}
