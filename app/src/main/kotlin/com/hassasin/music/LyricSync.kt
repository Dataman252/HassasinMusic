package com.hassasin.music

/**
 * Helpers for timing lyrics by hand ("Tap to sync"): which lines get a time, and writing the result
 * as LRC. Timing worked out from the sound alone was tried and measured against real timed lyrics:
 * lines landed 7-20 s off (instrumental breaks and repeated lines), so the app does not guess.
 */
object LyricSync {
    /** How late a tap lands after the sound, on average (human reaction to a line starting). */
    const val REACTION_MS = 200L
    private val label = Regex("^[\\[(]?(chorus|verse|hook|intro|outro|bridge|pre-chorus|refrain)[^)\\]]*[)\\]]?:?$", RegexOption.IGNORE_CASE)

    /** Lines worth timing: blank lines (stanza breaks) and labels like "[Chorus]" are skipped. */
    fun lines(plain: String): List<String> = plain.lines().map { it.trim() }.filter { it.isNotEmpty() && !label.matches(it) }

    /**
     * Times for every line from the pinned ones: lines between two pins share the gap evenly; lines
     * before the first pin lead up to it 3 s apart; lines after the last pin follow 3 s apart.
     */
    fun fillGaps(times: List<Long?>, durationMs: Long): List<Long> {
        val out = times.toMutableList()
        val pinned = out.indices.filter { out[it] != null }
        if (pinned.isEmpty()) return out.indices.map { it * 3000L }
        val first = pinned.first(); val last = pinned.last()
        for (i in 0 until first) out[i] = (out[first]!! - 3000L * (first - i)).coerceAtLeast(0)
        for (i in last + 1 until out.size) out[i] = (out[last]!! + 3000L * (i - last)).coerceAtMost(durationMs)
        for ((a, b) in pinned.zipWithNext()) for (i in a + 1 until b) out[i] = out[a]!! + (out[b]!! - out[a]!!) * (i - a) / (b - a)
        // Keep the order even if pins were dragged past each other.
        val sorted = out.map { it!! }
        return sorted.runningReduce { prev, cur -> maxOf(prev, cur) }
    }

    /** Writes lines as LRC text, for keeping tapped timings. */
    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { l ->
        val ms = l.timeMs.coerceAtLeast(0)
        "[%02d:%02d.%02d]%s".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, l.text)
    }
}
