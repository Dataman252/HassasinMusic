package com.hassasin.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TagWriterTest {
    private val v = TagValues("New Title", "New Artist", "Album", "", "2024", "3/10")

    private fun le32(x: Int) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())

    @Test fun flacKeepsAudioAndOtherComments() {
        val audio = "AUDIOFRAMES".toByteArray()
        val vc = ByteArrayOutputStream().apply {
            val vendor = "ref".toByteArray(); write(le32(vendor.size)); write(vendor)
            val cs = listOf("TITLE=Old", "REPLAYGAIN_TRACK_GAIN=-6.5 dB", "LYRICS=la la")
            write(le32(cs.size)); cs.forEach { c -> val b = c.toByteArray(); write(le32(b.size)); write(b) }
        }.toByteArray()
        val f = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            write(byteArrayOf(0, 0, 0, 34)); write(ByteArray(34) { it.toByte() })
            write(byteArrayOf((0x80 or 4).toByte(), 0, 0, vc.size.toByte())); write(vc)
            write(audio)
        }.toByteArray()
        val out = TagWriter.rebuild(f, "x.flac", v)
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("fLaC"))
        assertTrue("TITLE=New Title" in text); assertTrue("TITLE=Old" !in text)
        assertTrue("REPLAYGAIN_TRACK_GAIN=-6.5 dB" in text); assertTrue("LYRICS=la la" in text)
        assertArrayEquals(ByteArray(34) { it.toByte() }, out.copyOfRange(8, 42)) // STREAMINFO unchanged, first
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
    }

    @Test fun mp3KeepsAudioAndPictures() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3, 4)
        fun frame(id: String, body: ByteArray) = id.toByteArray() + byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(), 0, 0) + body
        val frames = frame("TIT2", byteArrayOf(0) + "Old".toByteArray()) + frame("APIC", "PICTUREDATA".toByteArray())
        val size = frames.size
        val f = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, (size shr 7).toByte(), (size and 0x7F).toByte()) + frames + audio
        val out = TagWriter.rebuild(f, "x.mp3", v)
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("ID3"))
        assertTrue("PICTUREDATA" in text)
        val be = "New Title".toByteArray(Charsets.UTF_16BE)
        assertTrue((0..out.size - be.size).any { i -> be.indices.all { out[i + it] == be[it] } })
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
        assertEquals(1, Regex("TIT2").findAll(text).count())
    }
}
