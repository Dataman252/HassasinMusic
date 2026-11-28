package com.hassasin.music

import org.junit.Test
import java.io.File

/** Runs the converter over a piece of a real DSD256 file, if one is on this computer, and prints its level. */
class DsdRealTest {
    @Test fun level() {
        val f = File(System.getenv("DSD_SAMPLE") ?: return)
        if (!f.isFile) return
        val b = f.readBytes()
        val n = b.size / 2
        val l = ByteArray(n) { b[2 * it] }; val r = ByteArray(n) { b[2 * it + 1] }
        val conv = DsdToPcm(11_289_600, 2, 176_400)
        var sum = 0.0; var peak = 0f; var count = 0
        var p = 0
        while (p < n) {
            val k = minOf(8192, n - p)
            val (out, frames) = conv.process(arrayOf(l.copyOfRange(p, p + k), r.copyOfRange(p, p + k)), k)
            for (i in 0 until frames * 2) { sum += out[i] * out[i]; peak = maxOf(peak, kotlin.math.abs(out[i])); count++ }
            p += k
        }
        println("DSDREAL frames=${count / 2} rms=${20 * kotlin.math.log10(kotlin.math.sqrt(sum / count))} dBFS peak=${20 * kotlin.math.log10(peak.toDouble())} dBFS")
    }
}
