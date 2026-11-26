package com.hassasin.music

/**
 * Lyrics typed in two Indian scripts at once (common on lyric sites: a Punjabi verse in Gurmukhi
 * inside a Hindi song written in Devanagari). When most of the text is Devanagari, the Gurmukhi
 * lines are converted letter by letter to Devanagari: both scripts share one layout in Unicode, so
 * the words stay the same and only the letters change.
 */
object Script {
    private fun isGurmukhi(c: Char) = c in '਀'..'੿'
    private fun isDevanagari(c: Char) = c in 'ऀ'..'ॿ'

    /** How many letters of the "other" script sit inside the main one (0 = one script only). */
    fun mixedCount(s: String?): Int {
        if (s == null) return 0
        val g = s.count(::isGurmukhi); val d = s.count(::isDevanagari)
        return if (g > 0 && d > 0) minOf(g, d) else 0
    }

    fun unify(s: String?): String? {
        if (s == null) return null
        val g = s.count(::isGurmukhi); val d = s.count(::isDevanagari)
        if (g == 0 || d == 0 || d < g) return s
        return gurmukhiToDevanagari(s)
    }

    private val CONSONANT = 'क'..'ह'
    private val NUKTA_CONSONANT = 'क़'..'य़'

    fun gurmukhiToDevanagari(s: String): String {
        val out = StringBuilder(s.length + 8)
        var double = false // addak: doubles the next consonant
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (!isGurmukhi(c)) { out.append(c); i++; continue }
            val next = s.getOrNull(i + 1)
            when (c) {
                'ੰ' -> out.append('ं') // tippi -> anusvara
                'ੱ' -> double = true        // addak
                'ੲ' -> { // iri: vowel carrier
                    when (next) {
                        'ਿ' -> { out.append('इ'); i++ }
                        'ੀ' -> { out.append('ई'); i++ }
                        'ੇ' -> { out.append('ए'); i++ }
                        'ੈ' -> { out.append('ऐ'); i++ }
                        else -> out.append('इ')
                    }
                }
                'ੳ' -> { // ura: vowel carrier
                    when (next) {
                        'ੁ' -> { out.append('उ'); i++ }
                        'ੂ' -> { out.append('ऊ'); i++ }
                        'ੋ' -> { out.append('ओ'); i++ }
                        else -> out.append('उ')
                    }
                }
                'ੴ', 'ੵ' -> {}
                else -> {
                    val m = (c.code - 0x100).toChar()
                    if (double && (m in CONSONANT || m in NUKTA_CONSONANT)) { out.append(m).append('्'); double = false }
                    out.append(m)
                }
            }
            i++
        }
        return out.toString()
    }
}
