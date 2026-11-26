package com.hassasin.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class AutoEqEntry(val name: String, val path: String, val source: String, val rig: String)

/**
 * Headphone corrections from the AutoEq project (github.com/jaakkopasanen/AutoEq): thousands of
 * models measured by several reviewers. The index is downloaded once (about 850 KB) and refreshed
 * monthly; a profile is downloaded when chosen.
 */
object AutoEq {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    private const val MAX_AGE = 30L * 24 * 3600 * 1000
    private val line = Regex("^- \\[(.+?)]\\(\\./(.+?)\\) by (.+?)(?: on (.+))?$")
    private var entries: List<AutoEqEntry>? = null

    private fun indexFile(context: Context) = File(context.filesDir, "autoeq_index.md")

    suspend fun index(context: Context, refresh: Boolean = false): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        entries?.takeIf { !refresh }?.let { return@withContext it }
        val f = indexFile(context)
        if (refresh || !f.isFile || System.currentTimeMillis() - f.lastModified() > MAX_AGE) {
            val (code, body) = Net.get("$BASE/INDEX.md", retries = 1)
            if (code == 200 && body != null && body.length > 1000) f.writeText(body)
        }
        if (!f.isFile) return@withContext emptyList()
        val list = f.readLines().mapNotNull { l ->
            line.find(l.trim())?.let { m -> AutoEqEntry(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) }
        }
        entries = list
        list
    }

    fun search(all: List<AutoEqEntry>, q: String): List<AutoEqEntry> {
        val words = q.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.filter { e -> val n = e.name.lowercase(Locale.ROOT); words.all { it in n } }
            .sortedWith(compareBy({ it.name.length }, { it.name }))
            .take(60)
    }

    /** Downloads and reads one profile ("Preamp: -3.1 dB", "Filter 1: ON LSC Fc 105 Hz Gain -5.3 dB Q 0.70"). */
    suspend fun fetch(e: AutoEqEntry): AutoEqProfile? = withContext(Dispatchers.IO) {
        val folder = e.path.substringAfterLast('/')
        val (code, body) = Net.get("$BASE/${e.path}/$folder%20ParametricEQ.txt", retries = 1)
        if (code != 200 || body == null) return@withContext null
        parse(body, e.name, e.source)?.copy(path = e.path)
    }

    /** Measured, target and corrected curves (frequency, dB) for the chart; cached on the phone. */
    data class Curves(val freq: DoubleArray, val raw: DoubleArray, val target: DoubleArray, val corrected: DoubleArray)

    suspend fun curves(context: Context, p: AutoEqProfile): Curves? = withContext(Dispatchers.IO) {
        if (p.path.isBlank()) return@withContext null
        val folder = p.path.substringAfterLast('/')
        val cache = File(File(context.filesDir, "autoeq").apply { mkdirs() }, p.path.hashCode().toString() + ".csv")
        if (!cache.isFile) {
            val (code, body) = Net.get("$BASE/${p.path}/$folder.csv", retries = 1)
            if (code != 200 || body == null) return@withContext null
            cache.writeText(body)
        }
        val lines = cache.readLines()
        val head = lines.firstOrNull()?.split(',') ?: return@withContext null
        fun col(n: String) = head.indexOf(n)
        val fi = col("frequency"); val ri = col("raw"); val ti = col("target"); val ei = col("equalized_smoothed").takeIf { it >= 0 } ?: col("equalized_raw")
        if (fi < 0 || ri < 0 || ti < 0 || ei < 0) return@withContext null
        val rows = lines.drop(1).mapNotNull { l -> l.split(',').takeIf { it.size == head.size } }
        Curves(
            DoubleArray(rows.size) { rows[it][fi].toDouble() }, DoubleArray(rows.size) { rows[it][ri].toDoubleOrNull() ?: 0.0 },
            DoubleArray(rows.size) { rows[it][ti].toDoubleOrNull() ?: 0.0 }, DoubleArray(rows.size) { rows[it][ei].toDoubleOrNull() ?: 0.0 },
        )
    }

    fun parse(text: String, name: String, source: String): AutoEqProfile? {
        var pre = 0.0
        val filters = mutableListOf<EqFilter>()
        val f = Regex("Filter\\s*\\d+:\\s*(ON|OFF)\\s+(PK|LSC|HSC|LS|HS)\\s+Fc\\s+([\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB\\s+Q\\s+([\\d.]+)", RegexOption.IGNORE_CASE)
        text.lineSequence().forEach { l ->
            Regex("Preamp:\\s*(-?[\\d.]+)").find(l)?.let { pre = it.groupValues[1].toDouble() }
            f.find(l)?.let { m ->
                val type = when (m.groupValues[2].uppercase(Locale.ROOT)) { "LS", "LSC" -> "LSC"; "HS", "HSC" -> "HSC"; else -> "PK" }
                filters += EqFilter(type, m.groupValues[3].toDouble(), m.groupValues[4].toDouble(), m.groupValues[5].toDouble(), m.groupValues[1].uppercase(Locale.ROOT) == "ON")
            }
        }
        return if (filters.isEmpty()) null else AutoEqProfile(name, source, pre, filters)
    }
}
