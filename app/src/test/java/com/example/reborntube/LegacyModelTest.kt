package com.example.reborntube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyModelTest {
    @Test
    fun normalizesSupportedRegionCodes() {
        assertEquals("MX", YouTubeRepository.normalizeRegionCode("mx"))
        assertEquals("US", YouTubeRepository.normalizeRegionCode("us"))
        assertNull(YouTubeRepository.normalizeRegionCode("M1"))
    }

    @Test
    fun normalizesSupportedLanguageCodes() {
        assertEquals("es", YouTubeRepository.normalizeLanguageCode("ES"))
        assertEquals("system", YouTubeRepository.normalizeLanguageCode("SYSTEM"))
        assertNull(YouTubeRepository.normalizeLanguageCode("spanish"))
    }

    @Test
    fun regionalFeedSeedsChangeWithSelectedCountry() {
        val mexico = YouTubeRepository.regionalFeedSeeds("MX", "es")
        val spain = YouTubeRepository.regionalFeedSeeds("ES", "es")
        val aruba = YouTubeRepository.regionalFeedSeeds("AW", "en")

        assertEquals(3, mexico.size)
        assertTrue(mexico.all { it.contains("México") })
        assertTrue(spain.all { it.contains("España") })
        assertTrue(aruba.any { it.contains("Aruba") })
        assertNotEquals(mexico, spain)
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
