package com.hassasin.music

import java.text.Normalizer

/**
 * Urdu (and Punjabi in Shahmukhi, Saraiki, Persian) in Roman letters, the Hinglish way:
 * "دل میں" → "dil main". Urdu leaves most short vowels unwritten, so common song words come from a
 * word list and the rest is spelled by rule: an "a" between two consonants, long vowels from the
 * letters (ا aa, و o/w, ی i/y, ے e). Vowel marks (zabar, zer, pesh), when a text has them, are used.
 */
object Urdu {
    fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    /** Punctuation and digits inside the Arabic block: not part of a word. */
    fun isPunct(c: Char) = c in "،؛؟۔٪٫٬٭" || c in '٠'..'٩' || c in '۰'..'۹' || c == 'ـ'

    /** Common song words, written without vowel marks. */
    private val WORDS: Map<String, String> = mapOf(
        "دل" to "dil", "میں" to "main", "مینوں" to "mainu", "ہے" to "hai", "ہیں" to "hain", "ہوں" to "hoon", "ہو" to "ho",
        "تو" to "tu", "توں" to "tu", "تم" to "tum", "ہم" to "hum", "آپ" to "aap", "میرا" to "mera", "میری" to "meri", "میرے" to "mere",
        "تیرا" to "tera", "تیری" to "teri", "تیرے" to "tere", "تینوں" to "tainu", "تینو" to "tainu", "مینو" to "mainu",
        "کی" to "ki", "کے" to "ke", "کا" to "ka", "کو" to "ko", "نے" to "ne", "سے" to "se", "پر" to "par", "اور" to "aur",
        "نہیں" to "nahin", "نئیں" to "nai", "نہ" to "na", "دے" to "de", "دا" to "da", "دی" to "di", "وے" to "ve", "وی" to "vi",
        "نوں" to "nu", "نو" to "nu", "اے" to "ae", "سی" to "si", "سن" to "sun", "کہ" to "ke", "یہ" to "yeh", "وہ" to "woh",
        "جو" to "jo", "کیا" to "kya", "کیوں" to "kyun", "کیسے" to "kaise", "کب" to "kab", "جب" to "jab", "تب" to "tab", "اب" to "ab",
        "سب" to "sab", "تھا" to "tha", "تھی" to "thi", "تھے" to "the", "گیا" to "gaya", "گئی" to "gayi", "گئے" to "gaye",
        "عشق" to "ishq", "محبت" to "mohabbat", "پیار" to "pyaar", "جان" to "jaan", "یار" to "yaar", "سجن" to "sajan", "سجݨ" to "sajan",
        "سجناں" to "sajnaan", "ماہی" to "maahi", "ماہیا" to "maahiya", "رب" to "rabb", "اللہ" to "Allah", "خدا" to "khuda", "مولا" to "maula",
        "دنیا" to "duniya", "زندگی" to "zindagi", "آنکھیں" to "aankhen", "اکھیاں" to "akhiyaan", "رات" to "raat", "دن" to "din",
        "صنم" to "sanam", "دلبر" to "dilbar", "مجھے" to "mujhe", "تجھے" to "tujhe", "مجھ" to "mujh", "تجھ" to "tujh",
        "اپنا" to "apna", "اپنی" to "apni", "اپنے" to "apne", "بن" to "bin", "بنا" to "bina", "دیکھ" to "dekh", "آ" to "aa", "جا" to "ja",
        "اک" to "ik", "ایک" to "ek", "دو" to "do", "دور" to "door", "پاس" to "paas", "کر" to "kar", "کرے" to "kare", "کرنا" to "karna",
        "ساتھ" to "saath", "بات" to "baat", "باتیں" to "baatein", "یاد" to "yaad", "درد" to "dard", "غم" to "gham", "خوشی" to "khushi",
        "چاند" to "chaand", "دیوانہ" to "deewana", "دیوانی" to "deewani", "مستی" to "masti", "نشہ" to "nasha", "ہوا" to "hawa",
        "وفا" to "wafa", "بے" to "be", "کوئی" to "koi", "کچھ" to "kuch", "بھی" to "bhi", "ہی" to "hi", "تک" to "tak", "میرے" to "mere",
        "آج" to "aaj", "کل" to "kal", "پھر" to "phir", "یوں" to "yun", "ایسے" to "aise", "جیسے" to "jaise", "جیون" to "jeevan",
        "مکھڑا" to "mukhda", "نال" to "naal", "تے" to "te", "وچ" to "vich", "کتھے" to "kithe", "ایتھے" to "ithe", "اوتھے" to "othe",
        "سوہنا" to "sohna", "سوہݨا" to "sohna", "سوہنی" to "sohni", "گڈی" to "gaddi", "گڈیاں" to "gaddiyaan", "منڈا" to "munda",
        "کڑی" to "kudi", "ہن" to "hun", "جدوں" to "jadon", "کدی" to "kadi", "کدے" to "kade", "پردیس" to "pardes", "مجبور" to "majboor",
        "گیا" to "gaya", "رہا" to "raha", "رہی" to "rahi", "رہے" to "rahe", "لگا" to "laga", "لگی" to "lagi", "لگے" to "lage",
        "من" to "man", "تن" to "tan", "منم" to "manam", "مجنون" to "majnoon", "مجنوں" to "majnoon", "لیلیٰ" to "laila", "لیلی" to "laila",
        "کجا" to "koja", "سیاہ" to "siyah", "خون" to "khoon", "چو" to "cho", "رفتی" to "rafti", "آہای" to "aahaay",
    )

    private val CONS: Map<Char, String> = mapOf(
        'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh",
        'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r", 'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh",
        'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ک' to "k", 'گ' to "g",
        'ل' to "l", 'م' to "m", 'ن' to "n", 'ݨ' to "n", 'ڻ' to "n", 'ٻ' to "b", 'ڄ' to "j", 'ڳ' to "g", 'ڏ' to "d", 'ۏ' to "w",
    )

    /** Same letter, different code points (Arabic vs Urdu forms). */
    private fun norm(c: Char): Char = when (c) {
        'ي', 'ى' -> 'ی'; 'ك' -> 'ک'; 'ه' -> 'ہ'; 'ۀ', 'ۂ' -> 'ہ'; 'ة' -> 'ہ'; 'أ', 'إ', 'ٱ' -> 'ا'; 'ؤ' -> 'و'; 'ۓ' -> 'ے'
        else -> c
    }

    private const val FATHA = 'َ'; private const val KASRA = 'ِ'; private const val DAMMA = 'ُ'
    private const val SHADDA = 'ّ'; private const val SUKUN = 'ْ'; private const val TANWEEN = 'ً'
    private const val SUPER_ALEF = 'ٰ'

    private fun isMark(c: Char) = c in 'ً'..'ٟ' || c == SUPER_ALEF || c == 'ٔ' || c == 'ٕ'

    private class Unit(var r: String, val vowel: Boolean, var noVowelAfter: Boolean = false)

    fun word(raw: String): String {
        val w = Normalizer.normalize(raw, Normalizer.Form.NFKC).map(::norm).joinToString("").filterNot { it == 'ء' || it == 'ٔ' }
        val bare = w.filterNot(::isMark)
        WORDS[bare]?.let { return it }
        val marked = w.any { it == FATHA || it == KASRA || it == DAMMA }
        val u = mutableListOf<Unit>()
        fun last() = u.lastOrNull()
        val letters = w.toCharArray()
        var i = 0
        while (i < letters.size) {
            val c = letters[i]
            val next = letters.getOrNull(i + 1)?.let { n -> if (isMark(n)) letters.drop(i + 1).firstOrNull { !isMark(it) } else n }
            val first = u.isEmpty()
            val end = letters.drop(i + 1).none { !isMark(it) }
            when {
                c == FATHA -> u += Unit("a", true)
                c == KASRA -> u += Unit("i", true)
                c == DAMMA -> u += Unit("u", true)
                c == TANWEEN -> u += Unit("an", true)
                c == SUPER_ALEF -> u += Unit("aa", true)
                c == SHADDA -> last()?.takeIf { !it.vowel }?.let { u += Unit(it.r.takeLast(1), false) }
                c == SUKUN -> last()?.noVowelAfter = true
                isMark(c) -> {}
                c == 'آ' -> u += Unit("aa", true)
                c == 'ا' -> u += if (first) Unit(if (next == 'و' || next == 'ی' || next == 'ے') "" else "a", true) else Unit("aa", true)
                c == 'ع' -> u += Unit(if (first) "a" else "", true)
                c == 'و' -> u += when {
                    first -> Unit("w", false)
                    last()?.vowel == true && last()?.r?.isNotEmpty() == true -> Unit("w", false)
                    next == 'ا' || next == 'ی' || next == 'ے' -> Unit("w", false)
                    else -> Unit(if (end) "o" else "o", true)
                }
                c == 'ی' -> u += when {
                    first -> Unit("y", false)
                    last()?.vowel == true && last()?.r?.isNotEmpty() == true -> Unit("y", false)
                    next == 'ا' || next == 'و' -> Unit("iy", true)
                    end -> Unit("i", true)
                    else -> Unit("ee", true)
                }
                c == 'ے' -> u += Unit("e", true)
                c == 'ئ' -> u += Unit(if (next == 'ے') "" else "i", true)
                c == 'ں' -> u += Unit("n", true)
                c == 'ھ' -> last()?.takeIf { !it.vowel }?.let { it.r += "h" } ?: run { u += Unit("h", false) }
                c == 'ہ' -> u += if (end && !first && last()?.vowel == false) Unit("a", true) else Unit("h", false)
                c in CONS -> u += Unit(CONS.getValue(c), false)
                else -> {}
            }
            i++
        }
        val sb = StringBuilder()
        u.forEachIndexed { k, x ->
            sb.append(x.r)
            val nx = u.getOrNull(k + 1)
            // Unwritten short vowel between two consonants.
            if (!marked && !x.vowel && !x.noVowelAfter && nx != null && !nx.vowel && !(k > 0 && x.r == "n" && (nx.r.startsWith("g") || nx.r.startsWith("k")))) sb.append('a')
        }
        return sb.toString().ifBlank { raw }
    }
}
