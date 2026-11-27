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

    /** Writes lines as LRC text, for keeping tapped timings. */
    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { l ->
        val ms = l.timeMs.coerceAtLeast(0)
        "[%02d:%02d.%02d]%s".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10, l.text)
    }
}
