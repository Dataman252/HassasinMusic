package com.hassasin.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** The track's real loudness shape for the seek bar: decoded once, then kept. */
object Waveform {
    private const val BARS = 112
    private val memory = HashMap<Long, FloatArray>()

    suspend fun peaks(context: Context, t: Track): FloatArray? = withContext(Dispatchers.IO) {
        memory[t.id]?.let { return@withContext it }
        val f = File(File(context.cacheDir, "waves").apply { mkdirs() }, "${t.id}-${t.sizeBytes}.bin")
        if (f.isFile) {
            val b = f.readBytes()
            val arr = FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f }
            memory[t.id] = arr
            return@withContext arr
        }
        val arr = runCatching { decode(t) }.onFailure { Log.w("Waveform", "failed for ${t.path}", it) }.getOrNull() ?: return@withContext null
        f.writeBytes(ByteArray(arr.size) { (arr[it] * 255).toInt().coerceIn(0, 255).toByte() })
        memory[t.id] = arr
        arr
    }

    private val fineMemory = HashMap<Long, FloatArray>()

    /** Loudness 20 times a second, for the lyric-sync timeline (decoded once per song, kept in memory). */
    suspend fun fine(t: Track): FloatArray? = withContext(Dispatchers.IO) {
        fineMemory[t.id]?.let { return@withContext it }
        // 100 points a second, each the loudest sample in its 10 ms: a real waveform, as editors draw it.
        val bars = (t.durationMs / 10).toInt().coerceIn(BARS, 100 * 60 * 30)
        runCatching { decode(t, bars, peakMode = true) }.getOrNull()?.also { synchronized(fineMemory) { fineMemory.clear(); fineMemory[t.id] = it } }
    }

    private suspend fun decode(t: Track, BARS: Int = Waveform.BARS, peakMode: Boolean = false): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(t.path)
            val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durationMs * 1000
            val sums = DoubleArray(BARS); val counts = IntArray(BARS)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outFloat = false
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(5000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        outFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val bar = ((info.presentationTimeUs.toDouble() / durUs.coerceAtLeast(1)) * BARS).toInt().coerceIn(0, BARS - 1)
                        // Sample every 8th frame: plenty for a picture, much faster.
                        val frameBytes = (if (outFloat) 4 else 2) * channels
                        var p = info.offset
                        val end = info.offset + info.size
                        var frame = 0L
                        while (p + frameBytes <= end) {
                            val v = if (outFloat) buf.getFloat(p).toDouble() else buf.getShort(p) / 32768.0
                            if (peakMode) {
                                // Each sample goes to the 10 ms slot it really plays in (a decoded chunk spans many slots).
                                val us = info.presentationTimeUs + frame * 1_000_000L / rate
                                val b2 = ((us.toDouble() / durUs.coerceAtLeast(1)) * BARS).toInt().coerceIn(0, BARS - 1)
                                sums[b2] += v * v; counts[b2]++
                                frame += 2
                            } else { sums[bar] += v * v; counts[bar]++ }
                            p += frameBytes * (if (peakMode) 2 else 8)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally { codec.stop(); codec.release() }
            val rms = FloatArray(BARS) { if (counts[it] == 0) 0f else sqrt(sums[it] / counts[it]).toFloat() }
            if (peakMode) {
                // Loudness in dB, stretched between the song's quiet and loud parts: on loud masters the
                // shape of each sung syllable and beat stays visible instead of a solid block.
                val db = FloatArray(BARS) { (20 * kotlin.math.log10(rms[it].coerceAtLeast(1e-5f))) }
                val sorted = db.sortedArray()
                val lo = sorted[(BARS * 0.08).toInt()]; val hi = sorted[(BARS * 0.995).toInt().coerceAtMost(BARS - 1)]
                val span = (hi - lo).coerceAtLeast(6f)
                return FloatArray(BARS) { ((db[it] - lo) / span).coerceIn(0f, 1f).let { v -> v * v } }
            }
            val peak = rms.fold(0f) { a, b -> max(a, abs(b)) }.coerceAtLeast(1e-4f)
            return FloatArray(BARS) { (rms[it] / peak).coerceIn(0f, 1f) }
        } finally { ex.release() }
    }
}
