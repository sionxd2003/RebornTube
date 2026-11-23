package com.example.reborntube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyModelTest {
    @Test
    fun normalizesSupportedRegionCodes() {
        assertEquals("MX", YouTubeRepository.normalizeRegionCode("mx"))
        assertEquals("US", YouTubeRepository.normalizeRegionCode("us"))
        assertNull(YouTubeRepository.normalizeRegionCode("M1"))
    }

    @Test
    fun channelKeyIgnoresTrailingSlash() {
        assertEquals("@creator", channelKey("https://www.youtube.com/@creator/"))
    }

    @Test
    fun parsesExactUploadDatesWithoutInventingDates() {
        assertEquals(1_795_176_000_000L, parseIsoMillis("2026-11-20T12:00:00Z"))
        assertNull(parseIsoMillis("hace 3 semanas"))
        assertNull(parseIsoMillis("not-a-dateT"))
    }
}
