package com.hassasin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class MinPhaseTest {
    /** A 129-tap windowed low-pass (linear phase, energy in the middle). */
    private fun lowPass(): DoubleArray = DoubleArray(129) { n ->
        val x = n - 64.0
        val sinc = if (x == 0.0) 0.5 else sin(PI * 0.5 * x) / (PI * x)
        sinc * (0.5 - 0.5 * cos(2 * PI * n / 128.0))
    }

    private fun magDb(h: DoubleArray, f: Double): Double {
        var re = 0.0; var im = 0.0
        for (n in h.indices) { re += h[n] * cos(2 * PI * f * n); im -= h[n] * sin(2 * PI * f * n) }
        return 20 * log10(hypot(re, im).coerceAtLeast(1e-12))
    }

    @Test fun sameResponseEnergyUpFront() {
        val lin = lowPass()
        val min = MinPhase.convert(lin)
        // Same loudness at every frequency in the passband and transition (within 0.1 dB).
        for (f in listOf(0.0, 0.05, 0.1, 0.15, 0.2, 0.23)) assertEquals("at $f", magDb(lin, f), magDb(min, f), 0.1)
        // Stopband stays deep.
        assertTrue(magDb(min, 0.4) < -40)
        // Most of the energy is in the first quarter (linear phase has it in the middle).
        fun early(h: DoubleArray) = h.take(h.size / 4).sumOf { it * it } / h.sumOf { it * it }
        assertTrue("min-phase early energy ${early(min)}", early(min) > 0.9)
        assertTrue(early(lin) < 0.1)
    }

    @Test fun resamplerTablesSumToOne() {
        for (f in listOf("sharp", "steep", "soft", "minphase")) {
            val t = AppResampler.table(f, 44100, 48000)
            val taps = 2 * AppResampler.design(f).first
            for (p in listOf(0, 128, 256, 511)) {
                val sum = (0 until taps).sumOf { t[p * taps + it] }
                assertEquals("$f phase $p", 1.0, sum, 1e-9)
            }
            assertTrue(t.none { it.isNaN() || abs(it) > 2 })
        }
    }
}
