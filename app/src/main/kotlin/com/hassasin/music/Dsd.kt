package com.hassasin.music

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSD files (.dff DSDIFF and .dsf), read by the app itself:
 *  - converted to 24-bit PCM (DSD64 → 88.2 kHz, DSD128 and up → 176.4 kHz) with a two-stage filter, for any output;
 *  - or sent natively to a USB DAC as DoP (DSD over PCM: the DSD bits packed untouched into 24-bit frames with
 *    the 0x05/0xFA marker), when the Untouched USB output is on and the DAC accepts the DoP rate.
 */
data class DsdInfo(
    val dsf: Boolean, val rate: Int, val channels: Int, val dataStart: Long, val dataBytes: Long,
    /** Bytes of DSD per channel that are music (DSF pads its last block). */
    val bytesPerChannel: Long,
    /** DSF stores the earliest sample in the lowest bit. */
    val lsbFirst: Boolean,
) {
    val durationUs: Long get() = bytesPerChannel * 8 * 1_000_000L / rate
    val label: String get() = "DSD${rate / 44100}"
}

object DsdFile {
    /** Reads the header of a .dff or .dsf (null when it is not one, or is DST-compressed). */
    fun read(path: String): DsdInfo? = runCatching {
        RandomAccessFile(path, "r").use { f ->
            parse(object : Reader {
                override fun read(n: Int): ByteArray = ByteArray(n).also { f.readFully(it) }
                override fun skip(n: Long) { f.seek(f.filePointer + n) }
                override val pos: Long get() = f.filePointer
            })
        }
    }.getOrNull()

    interface Reader { fun read(n: Int): ByteArray; fun skip(n: Long); val pos: Long }

    private fun be(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
    private fun le(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in n - 1 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
    private fun id(b: ByteArray, o: Int = 0) = String(b, o, 4, Charsets.ISO_8859_1)

    fun parse(r: Reader): DsdInfo? {
        val head = r.read(16)
        return when (id(head)) {
            "FRM8" -> {
                if (id(head, 12) != "DSD ") return null
                var rate = 0; var ch = 2; var compressed = false
                while (true) {
                    val h = r.read(12)
                    val size = be(h, 4, 8)
                    when (id(h)) {
                        "PROP" -> {
                            r.read(4) // "SND "
                            var left = size - 4
                            while (left >= 12) {
                                val s = r.read(12)
                                val sz = be(s, 4, 8)
                                val padded = sz + (sz and 1)
                                when (id(s)) {
                                    "FS  " -> { rate = be(r.read(4), 0, 4).toInt(); r.skip(padded - 4) }
                                    "CHNL" -> { ch = be(r.read(2), 0, 2).toInt(); r.skip(padded - 2) }
                                    "CMPR" -> { compressed = id(r.read(4)) != "DSD "; r.skip(padded - 4) }
                                    else -> r.skip(padded)
                                }
                                left -= 12 + padded
                            }
                            if (left > 0) r.skip(left)
                        }
                        "DSD " -> {
                            if (compressed || rate <= 0 || ch <= 0) return null
                            return DsdInfo(false, rate, ch, r.pos, size, size / ch, false)
                        }
                        "DST " -> return null
                        else -> r.skip(size + (size and 1))
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            "DSD " -> {
                // DSD chunk is 28 bytes; 12 more to skip, then "fmt ".
                r.skip(28 - 16)
                val fh = r.read(12)
                if (id(fh) != "fmt ") return null
                val fmt = r.read((le(fh, 4, 8) - 12).toInt())
                val ch = le(fmt, 12, 4).toInt()
                val rate = le(fmt, 16, 4).toInt()
                val bps = le(fmt, 20, 4).toInt()
                val samples = le(fmt, 24, 8)
                val dh = r.read(12)
                if (id(dh) != "data") return null
                val data = le(dh, 4, 8) - 12
                DsdInfo(true, rate, ch, r.pos, data, (samples + 7) / 8, bps == 1)
            }
            else -> null
        }
    }

    /** Bit order reversed, for DSF's lowest-bit-first bytes. */
    val REVERSE = ByteArray(256) { b -> var v = 0; for (i in 0 until 8) if (b and (1 shl i) != 0) v = v or (0x80 ushr i); v.toByte() }
}

/** Tags of a DSD file (Android cannot read them): from the ID3v2 tag DFF and DSF files carry. */
data class DsdTags(
    val title: String = "", val artist: String = "", val albumArtist: String = "", val album: String = "",
    val year: Int = 0, val track: Int = 0, val disc: Int = 0, val genre: String = "", val picture: ByteArray? = null,
)

object DsdTagReader {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, DsdTags>()

    fun read(path: String, withPicture: Boolean = false): DsdTags? {
        if (!withPicture) cache[path]?.let { return it }
        val t = runCatching { RandomAccessFile(path, "r").use { f -> id3Bytes(f)?.let { parse(it, withPicture) } } }.getOrNull() ?: return null
        if (!withPicture) cache[path] = t
        return t
    }

    /** The raw ID3v2 tag: DFF keeps it in a top-level "ID3 " chunk after the music, DSF at its metadata offset. */
    private fun id3Bytes(f: RandomAccessFile): ByteArray? {
        val h = ByteArray(16); f.readFully(h)
        fun be(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
        fun le(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in n - 1 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
        val tag = String(h, 0, 4, Charsets.ISO_8859_1)
        if (tag == "DSD ") {
            val rest = ByteArray(12); f.readFully(rest)
            val meta = le(rest, 4, 8)
            if (meta <= 0 || meta + 10 > f.length()) return null
            f.seek(meta)
            return readId3At(f)
        }
        if (tag != "FRM8") return null
        var pos = 16L
        val end = minOf(f.length(), 12 + be(h, 4, 8))
        val ch = ByteArray(12)
        while (pos + 12 <= end) {
            f.seek(pos); f.readFully(ch)
            val id = String(ch, 0, 4, Charsets.ISO_8859_1)
            val size = be(ch, 4, 8)
            if (id == "ID3 ") { f.seek(pos + 12); return readId3At(f) }
            pos += 12 + size + (size and 1)
        }
        return null
    }

    private fun readId3At(f: RandomAccessFile): ByteArray? {
        val head = ByteArray(10); f.readFully(head)
        if (String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
        if (size <= 0 || size > 20 shl 20) return null
        val body = ByteArray(size); f.readFully(body)
        return head + body
    }

    private fun text(b: ByteArray, o: Int, len: Int): String {
        if (len <= 1) return ""
        val enc = b[o].toInt()
        val raw = b.copyOfRange(o + 1, o + len)
        val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(raw, cs).trim { it <= ' ' || it == '\uFEFF' }
    }

    fun parse(tag: ByteArray, withPicture: Boolean): DsdTags {
        val ver = tag[3].toInt()
        var p = 10
        if (tag[5].toInt() and 0x40 != 0) { // extended header
            val ext = ((tag[p].toInt() and 0xFF) shl 24) or ((tag[p + 1].toInt() and 0xFF) shl 16) or ((tag[p + 2].toInt() and 0xFF) shl 8) or (tag[p + 3].toInt() and 0xFF)
            p += if (ver >= 4) ext else ext + 4
        }
        val fields = HashMap<String, String>()
        var picture: ByteArray? = null
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (ver >= 4) ((tag[p + 4].toInt() and 0x7F) shl 21) or ((tag[p + 5].toInt() and 0x7F) shl 14) or ((tag[p + 6].toInt() and 0x7F) shl 7) or (tag[p + 7].toInt() and 0x7F)
            else ((tag[p + 4].toInt() and 0xFF) shl 24) or ((tag[p + 5].toInt() and 0xFF) shl 16) or ((tag[p + 6].toInt() and 0xFF) shl 8) or (tag[p + 7].toInt() and 0xFF)
            val o = p + 10
            if (size <= 0 || o + size > tag.size) break
            when {
                id.startsWith("T") && id != "TXXX" -> fields[id] = text(tag, o, size)
                id == "APIC" && withPicture && picture == null -> {
                    // encoding, MIME (Latin-1, ends in 0), picture type, description (ends in 0 or 00 00), then the image.
                    val enc = tag[o].toInt()
                    var q = o + 1
                    while (q < o + size && tag[q].toInt() != 0) q++
                    q += 2
                    if (enc == 1 || enc == 2) { while (q + 1 < o + size && !(tag[q].toInt() == 0 && tag[q + 1].toInt() == 0)) q += 2; q += 2 }
                    else { while (q < o + size && tag[q].toInt() != 0) q++; q++ }
                    if (q < o + size) picture = tag.copyOfRange(q, o + size)
                }
            }
            p = o + size
        }
        fun num(k: String) = fields[k]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        val year = fields["TDRC"]?.take(4)?.toIntOrNull() ?: fields["TYER"]?.take(4)?.toIntOrNull() ?: 0
        return DsdTags(
            title = fields["TIT2"].orEmpty(), artist = fields["TPE1"].orEmpty(), albumArtist = fields["TPE2"].orEmpty(),
            album = fields["TALB"].orEmpty(), year = year, track = num("TRCK"), disc = num("TPOS"),
            genre = fields["TCON"].orEmpty(), picture = picture,
        )
    }
}

/** Clock rates the plugged-in DAC lists (filled when the app opens it). */
object DacRates {
    @Volatile var known: List<IntRange> = emptyList()
    fun allows(hz: Int) = known.isEmpty() || known.any { hz in it }
}

/** What the DSD player is doing now, for the output chain and the USB output. */
object DsdPlayback {
    /** DoP frame rate being sent (0 = converting to PCM). */
    @Volatile var dopRate = 0
    @Volatile var pcmRate = 0
    @Volatile var label = ""
    /** DoP rates the DAC refused: those songs are converted instead. */
    val refused = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** PCM rate for a DSD rate: 88.2 kHz for DSD64, 176.4 kHz above, lowered to what a USB DAC in use lists. */
    fun pcmRateFor(dsdRate: Int): Int {
        val best = if (dsdRate >= 5_644_800) 176_400 else 88_200
        val usb = AppState.settings.value.output == "usb" && Playback.state.value.dacPresent
        return if (!usb) best else listOf(176_400, 88_200, 44_100).filter { it <= best }.firstOrNull { DacRates.allows(it) } ?: 44_100
    }

    /** Plain rate for tests (no DAC). */
    fun bestPcmRate(dsdRate: Int) = if (dsdRate >= 5_644_800) 176_400 else 88_200

    fun wantDop(dsdRate: Int): Boolean {
        val s = AppState.settings.value
        return s.dsdMode == "dop" && s.output == "usb" && Playback.state.value.dacPresent && (dsdRate / 16) !in refused && DacRates.allows(dsdRate / 16) &&
            !Eq.anyEffect(s.sound, true) && dsdRate % 44100 == 0
    }

    /** A DSD song playing as PCM (converted by the app): never "bit-perfect". */
    fun converted(st: PlayState): Boolean = st.current?.bits == 1 && dopRate == 0

    fun describe(): String = when {
        dopRate > 0 -> "Native $label to the DAC as DoP (${khz(dopRate)} kHz frames): the DSD bits arrive untouched"
        pcmRate > 0 -> "The app's DSD converter: $label → ${khz(pcmRate)} kHz 24-bit (two-stage 64-bit filter)"
        else -> "DSD"
    }
}

/** DSD (1-bit) to PCM: a byte-table FIR at the DSD rate down to 4 × the output rate, then a polyphase FIR down by 4. */
class DsdToPcm(dsdRate: Int, private val ch: Int, val outRate: Int) {
    private val fs1 = outRate * 4
    private val stepBytes = (dsdRate / fs1) / 8
    private val groups: Int
    private val lut: FloatArray
    private val h2: FloatArray

    init {
        require(stepBytes >= 1 && dsdRate % fs1 == 0) { "unsupported DSD rate $dsdRate" }
        // Stage 1: keep to ~0.45 × output rate, stop what would fold back below half the output rate.
        val d1 = (fs1 - 0.95 * outRate) / dsdRate
        var n1 = ceil((90 - 8) / (2.285 * 2 * PI * d1)).toInt().coerceAtLeast(32)
        n1 = (n1 + 7) / 8 * 8
        val fc1 = (0.45 * outRate + (fs1 - 0.5 * outRate)) / 2 / dsdRate
        val h1 = kaiserLowpass(n1, fc1, 8.96)
        groups = n1 / 8
        lut = FloatArray(groups * 256)
        for (g in 0 until groups) for (b in 0 until 256) {
            var s = 0.0
            for (k in 0 until 8) s += h1[8 * g + k] * (if (b and (0x80 ushr k) != 0) 1.0 else -1.0)
            lut[g * 256 + b] = s.toFloat()
        }
        // Stage 2: flat to ~0.42 × output rate, down 80 dB by 0.5 ×.
        var n2 = ceil((80 - 8) / (2.285 * 2 * PI * (0.08 * outRate / fs1))).toInt()
        n2 = (n2 + 3) / 4 * 4
        h2 = kaiserLowpass(n2, 0.46 * outRate / fs1, 7.86).map { it.toFloat() }.toFloatArray()
    }

    private fun kaiserLowpass(n: Int, fc: Double, beta: Double): DoubleArray {
        val norm = AppResampler.bessel0(beta)
        val m = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val x = i - m
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = x / m
            sinc * AppResampler.bessel0(beta * sqrt((1 - r * r).coerceAtLeast(0.0))) / norm
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        return h
    }

    private val inBuf = Array(ch) { ByteArray(1 shl 16) }
    private var inLen = 0
    private val mid = Array(ch) { FloatArray(1 shl 14) }
    private var midLen = 0

    fun reset() { inLen = 0; midLen = 0 }

    /** Feeds [n] DSD bytes per channel (earliest sample in the top bit); returns the new PCM frames, interleaved. */
    fun process(dsd: Array<ByteArray>, n: Int): Pair<FloatArray, Int> {
        if (inLen + n > inBuf[0].size) for (c in 0 until ch) inBuf[c] = inBuf[c].copyOf(maxOf(inLen + n, inBuf[c].size * 2))
        for (c in 0 until ch) System.arraycopy(dsd[c], 0, inBuf[c], inLen, n)
        inLen += n
        // Stage 1.
        val count1 = if (inLen >= groups) (inLen - groups) / stepBytes + 1 else 0
        if (midLen + count1 > mid[0].size) for (c in 0 until ch) mid[c] = mid[c].copyOf(maxOf(midLen + count1, mid[c].size * 2))
        for (c in 0 until ch) {
            if (DsdNative.ok) { DsdNative.stage1(lut, groups, stepBytes, inBuf[c], count1, mid[c], midLen); continue }
            val b = inBuf[c]; val m = mid[c]
            var p = 0
            for (i in 0 until count1) {
                var s = 0f
                var g = 0
                while (g < groups) { s += lut[(g shl 8) or (b[p + g].toInt() and 0xFF)]; g++ }
                m[midLen + i] = s
                p += stepBytes
            }
        }
        midLen += count1
        val used = count1 * stepBytes
        for (c in 0 until ch) System.arraycopy(inBuf[c], used, inBuf[c], 0, inLen - used)
        inLen -= used
        // Stage 2: one output for every 4 stage-1 samples.
        val n2 = h2.size
        val frames = if (midLen >= n2) (midLen - n2) / 4 + 1 else 0
        val out = FloatArray(frames * ch)
        val gain = 1.41421356f // DSD's 0 dB (50 % modulation) → −3 dBFS; its +3 dB maximum → full scale
        for (c in 0 until ch) {
            if (DsdNative.ok) { if (frames > 0) DsdNative.decimate(h2, n2, mid[c], 4, frames, out, ch, c, gain); continue }
            val m = mid[c]
            for (f in 0 until frames) {
                val base = f * 4
                var s = 0f
                for (k in 0 until n2) s += h2[k] * m[base + k]
                out[f * ch + c] = s * gain
            }
        }
        val used2 = frames * 4
        for (c in 0 until ch) System.arraycopy(mid[c], used2, mid[c], 0, midLen - used2)
        midLen -= used2
        return out to frames
    }
}

/** The converter's inner loops in C++ (vectorised); the Kotlin loops are the fallback (and what unit tests run). */
object DsdNative {
    val ok: Boolean = runCatching { System.loadLibrary("usbaudio"); true }.getOrDefault(false)
    @JvmStatic external fun stage1(lut: FloatArray, groups: Int, step: Int, input: ByteArray, count: Int, out: FloatArray, outOff: Int)
    @JvmStatic external fun decimate(h: FloatArray, n: Int, mid: FloatArray, dec: Int, frames: Int, out: FloatArray, stride: Int, offset: Int, gain: Float)
}

/** Plays .dff and .dsf through ExoPlayer as PCM (or DoP) produced here. */
@OptIn(UnstableApi::class)
class DsdExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private var track: TrackOutput? = null
    private var info: DsdInfo? = null
    private var conv: DsdToPcm? = null
    private var dop = false
    private var outRate = 0
    private var bytePos = 0L          // DSD bytes per channel already read
    private var framesOut = 0L
    private var dopMarker = false
    private var carry: ByteArray? = null // DoP: an odd byte per channel waiting for its pair
    private var raw = ByteArray(0)
    private var chan = Array(2) { ByteArray(0) }

    override fun sniff(input: ExtractorInput): Boolean {
        val b = ByteArray(16)
        input.peekFully(b, 0, 16)
        val a = String(b, 0, 4, Charsets.ISO_8859_1)
        return (a == "FRM8" && String(b, 12, 4, Charsets.ISO_8859_1) == "DSD ") || a == "DSD "
    }

    override fun init(output: ExtractorOutput) { this.output = output }

    private fun setUp(input: ExtractorInput): Boolean {
        val i = DsdFile.parse(object : DsdFile.Reader {
            override fun read(n: Int): ByteArray = ByteArray(n).also { input.readFully(it, 0, n) }
            override fun skip(n: Long) { var left = n; while (left > 0) { val k = minOf(left, 1L shl 20).toInt(); input.skipFully(k); left -= k } }
            override val pos: Long get() = input.position
        }) ?: return false
        info = i
        dop = DsdPlayback.wantDop(i.rate) && i.channels == 2
        outRate = if (dop) i.rate / 16 else DsdPlayback.pcmRateFor(i.rate)
        conv = if (dop) null else DsdToPcm(i.rate, i.channels, outRate)
        DsdPlayback.dopRate = if (dop) outRate else 0
        DsdPlayback.pcmRate = if (dop) 0 else outRate
        DsdPlayback.label = i.label
        val t = output.track(0, C.TRACK_TYPE_AUDIO)
        t.format(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(i.channels).setSampleRate(outRate)
                .setPcmEncoding(C.ENCODING_PCM_24BIT).setMaxInputSize(1 shl 18).setAverageBitrate(outRate * 24 * i.channels).build(),
        )
        track = t
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = i.durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val per = align(i)
                val bytes = (timeUs.coerceAtLeast(0) * i.rate / 8 / 1_000_000L / per * per).coerceIn(0, maxOf(0, i.bytesPerChannel - per))
                return SeekMap.SeekPoints(SeekPoint(bytes * 8 * 1_000_000L / i.rate, i.dataStart + bytes * i.channels))
            }
        })
        output.endTracks()
        bytePos = 0; framesOut = 0; carry = null
        return true
    }

    /** Seek granularity in bytes per channel: DSF blocks of 4096, or 64 bytes (whole filter and DoP steps). */
    private fun align(i: DsdInfo) = if (i.dsf) 4096L else 64L

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (info == null && !setUp(input)) return Extractor.RESULT_END_OF_INPUT
        val i = info!!
        if (skipHeader) { skipHeader = false; val k = i.dataStart - input.position; if (k > 0) input.skipFully(k.toInt()) }
        val left = i.bytesPerChannel - bytePos
        if (left <= 0) return Extractor.RESULT_END_OF_INPUT
        // One DSF block group, or 8 KB per channel of interleaved DFF bytes.
        val perCh = if (i.dsf) 4096 else 8192
        val readBytes = perCh * i.channels
        if (raw.size < readBytes) raw = ByteArray(readBytes)
        if (chan.size != i.channels || chan[0].size < perCh) chan = Array(i.channels) { ByteArray(perCh) }
        val want = if (i.dsf) readBytes else (minOf(left, perCh.toLong()) * i.channels).toInt()
        val got = readAvailable(input, raw, want)
        if (got <= 0) return Extractor.RESULT_END_OF_INPUT
        val n: Int
        if (i.dsf) {
            n = minOf(left, (got / i.channels).toLong(), 4096L).toInt()
            val rev = i.lsbFirst
            for (c in 0 until i.channels) {
                val o = c * 4096
                val dst = chan[c]
                if (rev) for (k in 0 until n) dst[k] = DsdFile.REVERSE[raw[o + k].toInt() and 0xFF] else System.arraycopy(raw, o, dst, 0, n)
            }
        } else {
            n = got / i.channels
            for (k in 0 until n) for (c in 0 until i.channels) chan[c][k] = raw[k * i.channels + c]
        }
        bytePos += n
        val pcm = if (dop) dopFrames(n) else pcmFrames(n)
        if (pcm.isNotEmpty()) {
            val t = track!!
            val timeUs = framesOut * 1_000_000L / outRate
            framesOut += pcm.size / (3 * i.channels)
            t.sampleData(ParsableByteArray(pcm), pcm.size)
            t.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pcm.size, 0, null)
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readAvailable(input: ExtractorInput, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r == C.RESULT_END_OF_INPUT) break
            off += r
        }
        return off
    }

    private fun pcmFrames(n: Int): ByteArray {
        val (f, frames) = conv!!.process(chan, n)
        val out = ByteArray(f.size * 3)
        var o = 0
        for (x in f) {
            val v = (x.coerceIn(-1f, 1f) * 8388607f).toInt()
            out[o++] = v.toByte(); out[o++] = (v shr 8).toByte(); out[o++] = (v shr 16).toByte()
        }
        @Suppress("UNUSED_EXPRESSION") frames
        return out
    }

    /** DoP: every 16 DSD bits per channel become one 24-bit frame, top byte the alternating 0x05 / 0xFA marker. */
    private fun dopFrames(n: Int): ByteArray {
        val ch = chan.size
        val c0 = carry
        val total = n + (if (c0 != null) 1 else 0)
        val frames = total / 2
        val out = ByteArray(frames * ch * 3)
        fun byteAt(c: Int, k: Int): Byte = if (c0 != null) (if (k == 0) c0[c] else chan[c][k - 1]) else chan[c][k]
        var o = 0
        for (f in 0 until frames) {
            val marker = if (dopMarker) 0xFA.toByte() else 0x05.toByte()
            dopMarker = !dopMarker
            for (c in 0 until ch) {
                out[o++] = byteAt(c, 2 * f + 1)   // later 8 samples in the low byte
                out[o++] = byteAt(c, 2 * f)       // earlier 8 samples above them
                out[o++] = marker
            }
        }
        carry = if (total % 2 == 1) ByteArray(ch) { c -> byteAt(c, total - 1) } else null
        return out
    }

    override fun seek(position: Long, timeUs: Long) {
        val i = info ?: return
        conv?.reset(); carry = null; dopMarker = false
        if (position < i.dataStart) { bytePos = 0; framesOut = 0; skipHeader = true; return }
        bytePos = ((position - i.dataStart) / i.channels).coerceAtLeast(0)
        framesOut = if (dop) bytePos / 2 else bytePos * 8L * outRate / i.rate
    }

    /** After a seek back to the very start the input is at the top of the file: skip to the music. */
    private var skipHeader = false

    override fun release() { DsdPlayback.dopRate = 0; DsdPlayback.pcmRate = 0 }
}
