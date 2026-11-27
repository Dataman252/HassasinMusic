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
        // Letters only the South Indian scripts use (mapped here from Tamil, Malayalam, Telugu...).
        'ऴ' to "zh", 'ऱ' to "r", 'ऩ' to "n",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y",
    )
    private val nukta = mapOf("k" to "q", "kh" to "kh", "g" to "g", "j" to "z", "d" to "d", "dh" to "dh", "ph" to "f")
    private val independent = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
        'ऎ' to "e", 'ऒ' to "o", 'ॠ' to "ri",
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
        'ॆ' to "e", 'ॊ' to "o",
    )

    private class Syl(var cons: String, var vowel: String, var inherent: Boolean, var coda: String = "", var deleted: Boolean = false)

    /** Any Indian script (Devanagari to Malayalam). */
    private fun isIndic(c: Char) = c in '\u0900'..'\u0D7F'
    private fun isJoiner(c: Char) = c == '\u200C' || c == '\u200D'
    private fun isConvertible(c: Char) = (isIndic(c) || Urdu.isArabic(c)) && c != '।' && c != '॥' && !Urdu.isPunct(c)

    /** True when [text] has words this can turn into Roman letters (the lyrics toggle shows then). */
    fun canConvert(text: String?): Boolean = text != null && text.any { it.isLetter() && (isIndic(it) || Urdu.isArabic(it)) }

    /** The main non-Roman script in [text], for labels: e.g. "Tamil", "Urdu". */
    fun scriptName(text: String?): String? {
        if (text == null) return null
        val counts = HashMap<String, Int>()
        for (c in text) {
            if (!c.isLetter()) continue
            val n = when (c) {
                in '\u0900'..'\u097F' -> "Hindi"; in '\u0980'..'\u09FF' -> "Bengali"; in '\u0A00'..'\u0A7F' -> "Punjabi"
                in '\u0A80'..'\u0AFF' -> "Gujarati"; in '\u0B00'..'\u0B7F' -> "Odia"; in '\u0B80'..'\u0BFF' -> "Tamil"
                in '\u0C00'..'\u0C7F' -> "Telugu"; in '\u0C80'..'\u0CFF' -> "Kannada"; in '\u0D00'..'\u0D7F' -> "Malayalam"
                else -> if (Urdu.isArabic(c)) "Urdu" else null
            } ?: continue
            counts[n] = (counts[n] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** Converts every Indian-script or Urdu word in [text]; each line starts with a capital letter. */
    fun convert(text: String): String = text.lines().joinToString("\n") { line ->
        if (line.none(::isConvertible)) line
        else {
            val sb = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (isConvertible(line[i])) {
                    val start = i
                    while (i < line.length && (isConvertible(line[i]) || isJoiner(line[i]))) i++
                    sb.append(anyWord(line.substring(start, i).filterNot(::isJoiner)))
                } else {
                    sb.append(when (line[i]) { '।', '॥', '۔' -> "."; '،' -> ","; '؟' -> "?"; '؛' -> ";"; '\u200C', '\u200D' -> ""; else -> line[i].toString() })
                    i++
                }
            }
            sb.toString().replaceFirstChar { it.uppercase() }
        }
    }

    /** One word in any supported script. */
    private fun anyWord(w: String): String {
        val c = w.firstOrNull { isIndic(it) || Urdu.isArabic(it) } ?: return w
        return when {
            Urdu.isArabic(c) -> Urdu.word(w)
            c in '\u0900'..'\u097F' -> word(w)
            c in '\u0A00'..'\u0A7F' -> word(Script.gurmukhiToDevanagari(w))
            c in '\u0980'..'\u09FF' -> word(Brahmic.toDevanagari(w, 0x0980), lang = "bn")
            c in '\u0A80'..'\u0AFF' -> word(Brahmic.toDevanagari(w, 0x0A80))
            // Odia and the South Indian languages say the final "a" (prema, not prem): no Hindi-style dropping.
            c in '\u0B00'..'\u0B7F' -> word(Brahmic.toDevanagari(w, 0x0B00), hindi = false)
            c in '\u0B80'..'\u0BFF' -> word(Brahmic.toDevanagari(w, 0x0B80), hindi = false, lang = "ta")
            c in '\u0C00'..'\u0C7F' -> word(Brahmic.toDevanagari(w, 0x0C00), hindi = false, lang = "te")
            c in '\u0C80'..'\u0CFF' -> word(Brahmic.toDevanagari(w, 0x0C80), hindi = false, lang = "kn")
            // Malayalam: a word ending in a bare virama is said with a short "u" (nilaavu).
            else -> word(Brahmic.toDevanagari(w, 0x0D00), hindi = false, lang = "ml") + if (w.endsWith('\u0D4D')) "u" else ""
        }
    }

    /**
     * One Devanagari word. [hindi]: drop the unspoken "a" and shorten long final vowels the Hinglish
     * way. [lang]: the spoken language, for its own spelling habits (Tanglish "th", "ng", "s"...).
     */
    fun word(w: String, hindi: Boolean = true, lang: String = "hi"): String {
        val tamil = lang == "ta"
        val south = lang in setOf("ta", "te", "kn", "ml")
        val syls = mutableListOf<Syl>()
        var joinNext = false
        for (c in w) {
            when {
                c in consonants -> {
                    val r = when {
                        tamil && c == 'त' -> "th"
                        tamil && c == 'च' -> "s"
                        else -> consonants.getValue(c)
                    }
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
        if (hindi && syls.size > 1 && last.inherent && last.coda.isEmpty()) last.deleted = true
        // ...and inside a word, between a vowel and a following consonant that keeps its vowel (V C a C V).
        if (hindi) for (k in syls.size - 2 downTo 1) {
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
                        hindi && s.coda == "n" && isEnd && s.vowel == "e" -> { sb.setLength(sb.length - 1); "ein" }
                        s.coda == "n" && (nextCons.startsWith("p") || nextCons.startsWith("b") || nextCons.startsWith("m")) -> "m"
                        // South Indian languages end words with "m" (kosam, manam); Bengali says "ng" (bangla).
                        south && s.coda == "n" && isEnd -> "m"
                        lang == "bn" && s.coda == "n" -> "ng"
                        else -> s.coda
                    },
                )
            }
        }
        var out = sb.toString()
        if (tamil) {
            // Tamil has one letter for k/g, t/d, p/b, ch/s: the sound changes after a nasal or when doubled.
            out = out.replace("nth", "ndh").replace("nk", "ng").replace("nt", "nd").replace("mp", "mb")
                .replace("ss", "chch").replace("ns", "nj").replace("rr", "tr").replace("nr", "ndr")
        }
        if (lang == "ml") out = out.replace("nr", "nt")
        // Hinglish spells long final vowels short in words of two or more syllables: "mila", "teri".
        if (hindi && syls.size > 1 && syls.last().coda.isEmpty()) {
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

/**
 * The Indian scripts share one letter layout in Unicode (each script sits in its own block at the same
 * position), so a letter moves to Devanagari by its offset; a few script-only letters are fixed by hand.
 */
object Brahmic {
    private val fixes: Map<Char, String> = mapOf(
        // Bengali: য is "j" in speech; য় is "y"; ৎ is a final "t".
        '\u09AF' to "ज", '\u09DF' to "य", '\u09CE' to "त्", '\u09F0' to "र", '\u09F1' to "व",
        // Odia
        '\u0B5F' to "य", '\u0B71' to "व",
        // Tamil: aytham is left out; the au length mark is part of the vowel sign.
        '\u0B83' to "", '\u0BD7' to "",
        // Telugu / Kannada length marks
        '\u0C55' to "", '\u0C56' to "", '\u0CD5' to "", '\u0CD6' to "",
        // Malayalam chillu letters (a consonant with no vowel) and the au length mark
        '\u0D7A' to "ण्", '\u0D7B' to "न्", '\u0D7C' to "र्", '\u0D7D' to "ल्", '\u0D7E' to "ळ्", '\u0D7F' to "क्",
        '\u0D54' to "म्", '\u0D55' to "य्", '\u0D56' to "ऴ्", '\u0D57' to "",
    )

    fun toDevanagari(w: String, base: Int): String {
        val sb = StringBuilder(w.length)
        for ((i, c) in w.withIndex()) {
            val fix = fixes[c]
            when {
                // Bengali য with a nukta dot is য় ("y"); the dot itself is then skipped.
                c == '\u09AF' && w.getOrNull(i + 1) == '\u09BC' -> sb.append('य')
                c == '\u09BC' && w.getOrNull(i - 1) == '\u09AF' -> {}
                fix != null -> sb.append(fix)
                c.code in base until base + 0x80 -> sb.append((c.code - base + 0x0900).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
