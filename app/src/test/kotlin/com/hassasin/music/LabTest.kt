package com.hassasin.music

import org.junit.Assert.assertEquals
import org.junit.Test

class LabTest {
    // The Audiocular D07 lists these (from the phone).
    private val d07 = listOf(44_100, 48_000, 96_000, 192_000, 384_000)

    @Test fun ratesItHasStayUntouched() {
        assertEquals(44_100, DeviceLab.usbTargetFor(44_100, d07, "auto"))
        assertEquals(96_000, DeviceLab.usbTargetFor(96_000, d07, "auto"))
    }

    @Test fun ratesItLacksGoToTheNearestHigherOne() {
        assertEquals(96_000, DeviceLab.usbTargetFor(88_200, d07, "auto"))
        assertEquals(192_000, DeviceLab.usbTargetFor(176_400, d07, "auto"))
        assertEquals(384_000, DeviceLab.usbTargetFor(352_800, d07, "auto"))
        assertEquals(384_000, DeviceLab.usbTargetFor(705_600, d07, "auto"))
    }

    @Test fun modes() {
        assertEquals(384_000, DeviceLab.usbTargetFor(44_100, d07, "max"))
        assertEquals(0, DeviceLab.usbTargetFor(176_400, d07, "off"))
        assertEquals(176_400, DeviceLab.usbTargetFor(176_400, emptyList(), "auto"))
    }

    @Test fun labels() {
        assertEquals("FLAC 16/44.1", LibraryQuality.labelOf(QEntry("FLAC", 44_100, 16, 900, true, false)))
        assertEquals("MP3 320k", LibraryQuality.labelOf(QEntry("MP3", 44_100, 0, 320, false, false)))
        assertEquals("DSD256", LibraryQuality.labelOf(QEntry("DSD256", 11_289_600, 1, 22_000, true, true)))
    }
}
