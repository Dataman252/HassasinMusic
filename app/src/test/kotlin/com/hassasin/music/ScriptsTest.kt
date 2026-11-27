package com.hassasin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines from songs in the test library, in each script, turned into Roman letters. */
class ScriptsTest {
    private val samples = listOf(
        "கிளுகிளுக்குது, மனம் கிழிக்குது",
        "அரங்கம் அதிரட்டுமே",
        "கரங்கள் ஒசரட்டுமே",
        "ಕೋಳ ಲಕ ವೆಲ್ಲಾರಿ",
        "કિ રંગ જો લાગ્યો રે",
        "ਤੇਰੇ ਨਾਲ ਪਿਆਰ ਹੋ ਗਿਆ",
        "আমার সোনার বাংলা আমি তোমায় ভালোবাসি",
        "నీ ప్రేమ కోసం",
        "എന്റെ ഖൽബിലെ വെണ്ണിലാവ് നീ",
        "ହେ ପ୍ରଭୁ",
        "کالیاں باریاں وے گڈیاں نوں میں لاواں",
        "سوہݨا تے میتھوں دور گیا اے",
        "ہو کے سجݨ مجبور گیا اے",
        "آهای سیاه زنگی دلمو نکن خون",
        "دل دیاں گلاں",
    )

    @Test fun printsEveryScript() {
        samples.forEach { println("$it  →  ${Hinglish.convert(it)}") }
        samples.forEach { s ->
            val out = Hinglish.convert(s)
            assertFalse("left untouched: $s → $out", Hinglish.canConvert(out))
        }
    }

    @Test fun tamil() {
        assertEquals("Arangam athirattume", Hinglish.convert("அரங்கம் அதிரட்டுமே"))
    }

    @Test fun kannadaKeepsFinalVowel() {
        assertEquals("Kola laka vellaari", Hinglish.convert("ಕೋಳ ಲಕ ವೆಲ್ಲಾರಿ"))
    }

    @Test fun gujarati() {
        assertEquals("Ki rang jo laagyo re", Hinglish.convert("કિ રંગ જો લાગ્યો રે"))
    }

    @Test fun teluguKeepsFinalA() {
        assertEquals("Nee prema kosam", Hinglish.convert("నీ ప్రేమ కోసం"))
    }

    @Test fun urduWordList() {
        assertEquals("Ho ke sajan majboor gaya ae", Hinglish.convert("ہو کے سجݨ مجبور گیا اے"))
        assertEquals("Dil main", Hinglish.convert("دل میں"))
    }

    @Test fun hindiUnchanged() {
        assertEquals("Dil ye bechain ve, raste pe nain ve", Hinglish.convert("दिल ये बेचैन वे, रसते पे नैन वे"))
    }

    @Test fun togglesForAllScripts() {
        assertTrue(Hinglish.canConvert("அரங்கம்"))
        assertTrue(Hinglish.canConvert("دل"))
        assertFalse(Hinglish.canConvert("Take over now"))
        assertEquals("Tamil", Hinglish.scriptName("அரங்கம் அதிரட்டுமே now"))
        assertEquals("Urdu", Hinglish.scriptName("دل میں"))
    }
}
