package com.hassasin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LogicTest {

    @Test fun peakFilterHasItsGainAtItsFrequency() {
        val f = EqFilter("PK", 1000.0, 6.0, 1.41)
        assertEquals(6.0, Eq.responseDb(listOf(f), 1000.0), 0.05)
        assertTrue(abs(Eq.responseDb(listOf(f), 20.0)) < 0.2)
    }

    @Test fun shelvesReachTheirGainFarFromTheCorner() {
        assertEquals(-5.3, Eq.responseDb(listOf(EqFilter("LSC", 105.0, -5.3, 0.7)), 20.0), 0.3)
        assertEquals(4.0, Eq.responseDb(listOf(EqFilter("HSC", 10000.0, 4.0, 0.7)), 19000.0), 0.4)
    }

    @Test fun autoPreampCancelsTheLargestBoost() {
        val filters = listOf(EqFilter("PK", 100.0, 6.0, 1.0), EqFilter("PK", 3000.0, 3.0, 1.0))
        assertEquals(-6.0, Eq.autoPreampDb(filters), 0.2)
    }

    @Test fun noEffectsMeansBitPerfect() {
        assertTrue(!Eq.anyEffect(SoundSettings(), usb = true))
        assertTrue(!Eq.anyEffect(SoundSettings(eqOn = true), usb = true)) // on, but flat
        assertTrue(Eq.anyEffect(SoundSettings(eqOn = true, bassDb = 2.0), usb = true))
        val p = AutoEqProfile("X", "Y", -3.0, listOf(EqFilter("PK", 100.0, 3.0, 1.0)))
        assertTrue(Eq.anyEffect(SoundSettings(autoEq = p, autoEqOn = true, autoEqOnlyUsb = true), usb = true))
        assertTrue(!Eq.anyEffect(SoundSettings(autoEq = p, autoEqOn = true, autoEqOnlyUsb = true), usb = false))
    }

    @Test fun parsesAutoEqProfile() {
        val text = """
            Preamp: -3.1 dB
            Filter 1: ON LSC Fc 105 Hz Gain -5.3 dB Q 0.70
            Filter 2: ON PK Fc 146 Hz Gain -6.8 dB Q 0.51
            Filter 6: ON HSC Fc 10000 Hz Gain -2.5 dB Q 0.70
        """.trimIndent()
        val p = AutoEq.parse(text, "KZ Castor", "Harpo")!!
        assertEquals(-3.1, p.preampDb, 1e-9)
        assertEquals(3, p.filters.size)
        assertEquals("LSC", p.filters[0].type)
        assertEquals(146.0, p.filters[1].fc, 1e-9)
        assertEquals("HSC", p.filters[2].type)
    }

    @Test fun parsesSyncedLyricsWithOffsetAndRepeatedStamps() {
        val lrc = "[ar:Someone]\n[offset:+500]\n[00:12.34]First line\n[00:15.00][01:02.5]Chorus\n[00:20.123]Last"
        val l = LyricsRepo.parse(lrc, null, false, "File")
        val s = l.synced!!
        assertEquals(4, s.size)
        assertEquals(12340 - 500L, s[0].timeMs)
        assertEquals("Chorus", s[1].text)
        assertEquals(20123 - 500L, s[2].timeMs)
        assertEquals(62500 - 500L, s[3].timeMs)
    }

    @Test fun enhancedLrcShowsOnlyTheWords() {
        val lrc = "[ti:Boom Shaka]\n[00:00.105]v1:<00:00.105>(Boom <00:00.336><00:00.336>shakala<00:00.935><00:00.935>ka)<00:00.975>\n[00:13.896]v1:<00:13.896>अगर <00:14.132><00:14.132>करूँ<00:14.369>"
        val l = LyricsRepo.parse(lrc, null, false, "Tags").synced!!
        assertEquals(2, l.size)
        assertEquals("(Boom shakalaka)", l[0].text)
        assertEquals(105L, l[0].timeMs)
        assertEquals("अगर करूँ", l[1].text)
    }

    @Test fun everyEffectAtOnceStaysFiniteAndBounded() {
        val all = FxSettings(agcOn = true, bassOn = true, clarityOn = true, clarityMode = "ozone", spectrumOn = true, tubeOn = true, analogOn = true,
            dynOn = true, fieldOn = true, diffOn = true, vheOn = true, reverbOn = true, cureOn = true, compOn = true)
        val fx = FxChain(all, 48000)
        val out = DoubleArray(2)
        var peak = 0.0
        val rnd = java.util.Random(1)
        for (i in 0 until 48000 * 2) {
            val x = kotlin.math.sin(i * 2 * Math.PI * 220 / 48000) * 0.5 + rnd.nextGaussian() * 0.05
            fx.process(x, x * 0.8, out)
            assertTrue(out[0].isFinite() && out[1].isFinite())
            peak = maxOf(peak, abs(out[0]), abs(out[1]))
        }
        assertTrue("peak $peak", peak < 4.0)
    }

    @Test fun compressorTurnsLoudPartsDown() {
        val fx = FxChain(FxSettings(compOn = true, compThresholdDb = -20.0, compRatio = 8.0, compAutoMakeup = false), 48000)
        val out = DoubleArray(2)
        var last = 0.0
        for (i in 0 until 48000) { fx.process(0.9, 0.9, out); last = out[0] }
        assertTrue("compressed $last", last < 0.5)
    }

    @Test fun plainLyricsStayPlain() {
        val l = LyricsRepo.parse("just words\nno times", null, false, "Tags")
        assertNull(l.synced)
        assertEquals("just words\nno times", l.plain)
    }

    @Test fun cleansUploaderTagsFromTitles() {
        assertEquals("Tu Hi Mera", LyricsRepo.titleCandidates("Tu Hi Mera - EnVy").last())
        assertTrue("Tera Deedar Hua" in LyricsRepo.titleCandidates("Tera Deedar Hua (From the Heart) - EnVy"))
        assertTrue("Shape of You" in LyricsRepo.titleCandidates("Shape of You - @indianflacsongs"))
        assertTrue("Song" in LyricsRepo.titleCandidates("Song (feat. Someone)"))
        assertTrue("Taal Se Taal Mila" in LyricsRepo.titleCandidates("02 Taal Se Taal Mila"))
        assertEquals("Taal Se Taal Mila", ArtRepo.cleanSongTitle("02 Taal Se Taal Mila"))
        assertEquals("Aap Ki Kashish", ArtRepo.cleanSongTitle("03 - Aap Ki Kashish"))
    }

    @Test fun cleansAlbumsAndArtists() {
        assertEquals("Jannat 2", LyricsRepo.cleanAlbum("Jannat 2 (Original Motion Picture Soundtrack)"))
        assertEquals("Julie", LyricsRepo.cleanAlbum("Julie (Original Motion Picture Soundtrack) - EP"))
        assertEquals("KK", LyricsRepo.mainArtist("KK & Pritam"))
        assertEquals(listOf("Rahat Fateh Ali Khan", "Pritam", "Anupam Amod"), Library.splitArtists("Rahat Fateh Ali Khan, Pritam & Anupam Amod"))
    }

    @Test fun picksLyricsByLengthAndPrefersSynced() {
        val a = LrcCandidate(1, "A", "x", "", 200.0, null, "plain", false)
        val b = LrcCandidate(2, "A", "x", "", 203.0, "[00:01.00]x", null, false)
        val c = LrcCandidate(3, "A", "x", "", 260.0, "[00:01.00]x", null, false)
        assertEquals(2L, LyricsRepo.pick(listOf(a, b, c), 201)!!.id)
        assertNull(LyricsRepo.pick(listOf(c), 201))
    }

    @Test fun gurmukhiLinesBecomeDevanagari() {
        // "ਤਾਲ ਸੇ ਤਾਲ ਮਿਲਾ" -> "ताल से ताल मिला"
        assertEquals("\u0924\u093E\u0932 \u0938\u0947 \u0924\u093E\u0932 \u092E\u093F\u0932\u093E", Script.gurmukhiToDevanagari("\u0A24\u0A3E\u0A32 \u0A38\u0A47 \u0A24\u0A3E\u0A32 \u0A2E\u0A3F\u0A32\u0A3E"))
        // tippi -> anusvara; addak doubles: "ਦਿੱਲ" -> "दिल्ल"
        assertEquals("\u0926\u093F\u0932\u094D\u0932", Script.gurmukhiToDevanagari("\u0A26\u0A3F\u0A71\u0A32"))
        val mixed = "\u0A24\u0A3E\u0A32\n\u0924\u093E\u0932 \u0938\u0947 \u0924\u093E\u0932 \u092E\u093F\u0932\u093E"
        assertEquals(0, Script.mixedCount(Script.unify(mixed)))
        assertTrue(!ArtRepo.meaningfulTitle("03"))
        assertTrue(!ArtRepo.meaningfulTitle("0719-02_1752867212747"))
        assertTrue(ArtRepo.meaningfulTitle("Taal Se Taal Mila"))
    }

    @Test fun hinglishReadsTheWayPeopleTypeIt() {
        assertEquals("Dil ye bechain ve, raste pe nain ve", Hinglish.convert("दिल ये बेचैन वे, रसते पे नैन वे"))
        assertEquals("Taal se taal mila", Hinglish.convert("ताल से ताल मिला"))
        assertEquals("milna", Hinglish.word("मिलना"))
        assertEquals("samajhna", Hinglish.word("समझना"))
        assertEquals("kamal", Hinglish.word("कमल"))
        assertEquals("hain", Hinglish.word("हैं"))
        assertEquals("mein", Hinglish.word("में"))
        assertEquals("teri", Hinglish.word("तेरी"))
        assertEquals("aaja", Hinglish.word("आजा"))
        assertEquals("zindagi", Hinglish.word("ज़िंदगी"))
        assertEquals("Hello", Hinglish.convert("Hello"))
    }

    @Test fun bassStylesPutTheirWeightWhereTheyShould() {
        val rumble = Eq.filters(SoundSettings(eqOn = true, bassStyle = "Sub rumble", bassAmount = 6.0), usb = true)
        assertTrue(Eq.responseDb(rumble, 32.0) > 5.5)
        assertTrue(Eq.responseDb(rumble, 150.0) < 1.0)
        val punchy = Eq.filters(SoundSettings(eqOn = true, bassStyle = "Punchy", bassAmount = 6.0), usb = true)
        assertTrue(Eq.responseDb(punchy, 90.0) > Eq.responseDb(punchy, 35.0) + 2)
        assertTrue(!Eq.anyEffect(SoundSettings(eqOn = true, bassStyle = "Off"), usb = true))
    }

    @Test fun lyricTitlesMatchAcrossVersions() {
        assertTrue(LyricsRepo.titleFits("Man Ko Ati Bhavey London Dreams", "Man Ko Ati Bhavey - EnVy"))
        assertTrue(LyricsRepo.titleFits("Dil Jigar Nazar Kya Hai", "Dil Jigar Nazar Kya Hai (Jhankar) - EnVy"))
        assertTrue(LyricsRepo.titleFits("Ibn - E - Batuta", "Ibn-E-Batuta"))
        assertTrue(LyricsRepo.titleFits("Boom Shaka [Explicit]", "Boom Shaka"))
        assertTrue(!LyricsRepo.titleFits("Tumhe Jo Maine Dekha", "Main Hoon Na"))
    }

    @Test fun albumNameSimilarity() {
        assertEquals(1.0, ArtRepo.similarity("Kind of Blue", "Kind Of Blue"), 1e-9)
        assertTrue(ArtRepo.similarity("Jannat 2", "Jannat 2") > ArtRepo.similarity("Jannat 2", "Jannat"))
        assertTrue(ArtRepo.similarity("Shape of You", "Divide") < 0.4)
        assertTrue(ArtRepo.similarity("Music", "Music, Music, Music") < 0.5)
        assertTrue(ArtRepo.similarity("Taal Se Taal Mila", "Taal se taal mila") > 0.99)
    }

    @Test fun groupsUntaggedFilesByFolder() {
        assertEquals("folder:/sdcard/Music/x", albumKeyOf("", "", "/sdcard/Music/x"))
        assertEquals("kk|jannat 2", albumKeyOf("KK", "Jannat 2", "/a"))
        assertNotNull(albumKeyOf("a", "<unknown>", "/f"))
    }
}
