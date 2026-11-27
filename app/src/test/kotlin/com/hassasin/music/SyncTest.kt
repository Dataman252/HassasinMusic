package com.hassasin.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTest {
    @Test fun gapsAreFilledBetweenPins() {
        // Lines 1 and 4 pinned; 2 and 3 share the gap; 5 follows 3 s after the last pin; 0 leads up 3 s before.
        val out = LyricSync.fillGaps(listOf(null, 10_000L, null, null, 16_000L, null), 200_000L)
        assertEquals(listOf(7_000L, 10_000L, 12_000L, 14_000L, 16_000L, 19_000L), out)
    }

    @Test fun orderIsKeptWhenPinsCross() {
        assertEquals(listOf(5_000L, 5_000L), LyricSync.fillGaps(listOf(5_000L, 4_000L), 60_000L))
    }

    @Test fun lrcFormat() {
        assertEquals("[01:02.34]Hello", LyricSync.toLrc(listOf(LyricLine(62_345L, "Hello"))))
    }
}
