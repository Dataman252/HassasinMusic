package com.hassasin.music

/**
 * Devanagari → Hinglish (Hindi in Roman letters, the way people type it): "दिल ये बेचैन वे" →
 * "Dil ye bechain ve". Uses the usual Hinglish spellings (aa, ee, oo, chh, sh) and drops the silent
 * "a" the way Hindi is spoken ("रसते" → "raste", "मिलना" → "milna", "ताल" → "taal").
 * Anything that is not Devanagari is left as it is.
 */
object Hinglish {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y",
    )
    private val nukta = mapOf("k" to "q", "kh" to "kh", "g" to "g", "j" to "z", "d" to "d", "dh" to "dh", "ph" to "f")
    private val independent = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )

    private class Syl(var cons: String, var vowel: String, var inherent: Boolean, var coda: String = "", var deleted: Boolean = false)

    private fun isDevanagari(c: Char) = c in 'ऀ'..'ॿ'

    /** Converts every Devanagari word in [text]; each line starts with a capital letter. */
    fun convert(text: String): String = text.lines().joinToString("\n") { line ->
        if (line.none(::isDevanagari)) line
        else {
            val sb = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (isDevanagari(line[i]) && line[i] != '।' && line[i] != '॥') {
                    val start = i
                    while (i < line.length && isDevanagari(line[i]) && line[i] != '।' && line[i] != '॥') i++
                    sb.append(word(line.substring(start, i)))
                } else {
                    sb.append(when (line[i]) { '।', '॥' -> "."; else -> line[i].toString() })
                    i++
                }
            }
            sb.toString().replaceFirstChar { it.uppercase() }
        }
    }

    fun word(w: String): String {
        val syls = mutableListOf<Syl>()
        var joinNext = false
        for (c in w) {
            when {
                c in consonants -> {
                    val r = consonants.getValue(c)
                    if (joinNext && syls.isNotEmpty()) { syls.last().cons += r; syls.last().vowel = "a"; syls.last().inherent = true }
                    else syls += Syl(r, "a", true)
                    joinNext = false
                }
                c == '़' -> syls.lastOrNull()?.let { s -> // nukta on the last consonant
                    val tail = nukta.keys.sortedByDescending { it.length }.firstOrNull { s.cons.endsWith(it) }
                    if (tail != null) s.cons = s.cons.dropLast(tail.length) + nukta.getValue(tail)
                }
                c == '्' -> syls.lastOrNull()?.let { it.vowel = ""; it.inherent = false; joinNext = true }
                c in matras -> syls.lastOrNull()?.let { it.vowel = matras.getValue(c); it.inherent = false }
                c in independent -> { syls += Syl("", independent.getValue(c), false); joinNext = false }
                c == 'ं' || c == 'ँ' -> syls.lastOrNull()?.let { it.coda += "n" }
                c == 'ः' -> syls.lastOrNull()?.let { it.coda += "h" }
                else -> {}
            }
        }
        if (syls.isEmpty()) return w
        // A word ending in a virama keeps its consonant cluster with no vowel.
        // Silent "a": at the end of a word of two or more syllables...
        val last = syls.last()
        if (syls.size > 1 && last.inherent && last.coda.isEmpty()) last.deleted = true
        // ...and inside a word, between a vowel and a following consonant that keeps its vowel (V C a C V).
        for (k in syls.size - 2 downTo 1) {
            val s = syls[k]
            val prev = syls[k - 1]
            val next = syls[k + 1]
            if (s.inherent && s.coda.isEmpty() && s.cons.isNotEmpty() && next.cons.isNotEmpty() && !next.deleted &&
                prev.vowel.isNotEmpty() && !prev.deleted && prev.coda.isEmpty() && !syls[k - 1].let { it.inherent && it.deleted }
            ) {
                s.deleted = true
            }
        }
        val sb = StringBuilder()
        syls.forEachIndexed { k, s ->
            sb.append(s.cons)
            if (!s.deleted) sb.append(s.vowel)
            if (s.coda.isNotEmpty()) {
                // Nasal before p/b/m sounds like "m"; "में" is spelled "mein".
                val nextCons = syls.getOrNull(k + 1)?.cons ?: ""
                val isEnd = k == syls.lastIndex
                sb.append(
                    when {
                        s.coda == "n" && isEnd && s.vowel == "e" -> { sb.setLength(sb.length - 1); "ein" }
                        s.coda == "n" && (nextCons.startsWith("p") || nextCons.startsWith("b") || nextCons.startsWith("m")) -> "m"
                        else -> s.coda
                    },
                )
            }
        }
        var out = sb.toString()
        // Hinglish spells long final vowels short in words of two or more syllables: "mila", "teri".
        if (syls.size > 1 && syls.last().coda.isEmpty()) {
            out = when {
                out.endsWith("aa") -> out.dropLast(1)
                out.endsWith("ee") -> out.dropLast(2) + "i"
                out.endsWith("oo") -> out.dropLast(2) + "u"
                else -> out
            }
        }
        return out
    }
}
