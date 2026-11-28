package com.hassasin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DsdTest {
    /** A 1 kHz sine at 50 % modulation (DSD's 0 dB), as 1-bit DSD from a 2nd-order sigma-delta modulator. */
    private fun dsdSine(rate: Int, seconds: Double): ByteArray {
        val n = (rate * seconds).toInt() / 8 * 8
        val out = ByteArray(n / 8)
        var i1 = 0.0; var i2 = 0.0; var y = 1.0
        for (k in 0 until n) {
            val x = 0.5 * sin(2 * PI * 1000.0 * k / rate)
            i1 += x - y; i2 += i1 - y
            y = if (i2 >= 0) 1.0 else -1.0
            if (y > 0) out[k / 8] = (out[k / 8].toInt() or (0x80 ushr (k % 8))).toByte()
        }
        return out
    }

    /** [minSnr]: the test's own 2nd-order modulator limits it (~72 dB in-band at DSD64); real DSD is 5th-7th order. */
    private fun check(rate: Int, minSnr: Double) {
        val bytes = dsdSine(rate, 0.25)
        val conv = DsdToPcm(rate, 2, DsdPlayback.bestPcmRate(rate))
        val pcm = ArrayList<Float>()
        var p = 0
        while (p < bytes.size) {
            val n = minOf(4096, bytes.size - p)
            val chunk = bytes.copyOfRange(p, p + n)
            val (f, frames) = conv.process(arrayOf(chunk, chunk), n)
            for (i in 0 until frames) pcm += f[i * 2]
            p += n
        }
        val fs = conv.outRate.toDouble()
        // Skip the start (filter warm-up), fit the 1 kHz sine, measure what is left.
        val x = pcm.drop(pcm.size / 4).map { it.toDouble() }
        var a = 0.0; var b = 0.0
        x.forEachIndexed { i, v -> a += v * sin(2 * PI * 1000 * i / fs); b += v * cos(2 * PI * 1000 * i / fs) }
        a *= 2.0 / x.size; b *= 2.0 / x.size
        val amp = sqrt(a * a + b * b)
        // In-band noise (20 Hz – 20 kHz), Hann-windowed DFT, everything but the 1 kHz tone.
        // A whole number of 1 kHz cycles (10 ms steps), so the tone sits exactly on one bin: no leakage, no window.
        val step = (fs / 100).toInt()
        val n = x.size / step * step
        val w = DoubleArray(n) { 1.0 }
        fun power(hz: Double): Double {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) { val v = x[i] * w[i]; re += v * cos(2 * PI * hz * i / fs); im += v * sin(2 * PI * hz * i / fs) }
            return re * re + im * im
        }
        val df = fs / n
        var sig = 0.0; var noiseP = 0.0
        var k = 1
        while (k * df <= 20000) { val hz = k * df; val pw = power(hz); if (kotlin.math.abs(hz - 1000) <= 1.5 * df) sig += pw else if (hz >= 20) noiseP += pw; k++ }
        val snr = 10 * log10(sig / noiseP)
        // 50 % modulation → 0.5 × √2 ≈ 0.707 (−3 dBFS).
        assertEquals("amplitude at $rate", 0.707, amp, 0.02)
        assertTrue("in-band SNR at $rate is $snr dB", snr > minSnr)
    }

    @Test fun dsd64() = check(2_822_400, 68.0)
    @Test fun dsd256() = check(11_289_600, 75.0)
    @Test fun dsd256to44k() { val c = DsdToPcm(11_289_600, 2, 44_100); val (_, f) = c.process(arrayOf(ByteArray(65536), ByteArray(65536)), 65536); assertTrue(f > 0) }

    @Test fun reverseTable() {
        assertEquals(0x80.toByte(), DsdFile.REVERSE[1])
        assertEquals(0x01.toByte(), DsdFile.REVERSE[0x80])
        assertEquals(0xF0.toByte(), DsdFile.REVERSE[0x0F])
    }
}
