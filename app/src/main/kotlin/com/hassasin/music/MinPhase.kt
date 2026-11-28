package com.hassasin.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Turns a linear-phase filter into a minimum-phase one with the same loudness at every frequency
 * (real-cepstrum method): the energy moves to the start, so there is no ringing before a sound.
 */
object MinPhase {
    fun convert(h: DoubleArray): DoubleArray {
        var n = 1
        while (n < h.size * 4) n = n shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        h.copyInto(re)
        fft(re, im, false)
        var peak = 0.0
        for (i in 0 until n) peak = maxOf(peak, hypot(re[i], im[i]))
        val floor = peak * 1e-10
        for (i in 0 until n) { re[i] = ln(maxOf(hypot(re[i], im[i]), floor)); im[i] = 0.0 }
        fft(re, im, true) // real cepstrum
        // Fold: keep c[0] and c[n/2], double the positive times, drop the negative ones.
        for (i in 1 until n / 2) { re[i] *= 2.0; im[i] = 0.0 }
        for (i in n / 2 + 1 until n) { re[i] = 0.0; im[i] = 0.0 }
        im[0] = 0.0; im[n / 2] = 0.0
        fft(re, im, false)
        for (i in 0 until n) { val m = exp(re[i]); val a = im[i]; re[i] = m * cos(a); im[i] = m * sin(a) }
        fft(re, im, true)
        return re.copyOf(h.size)
    }

    /** In-place radix-2 FFT; the inverse is scaled by 1/n. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
