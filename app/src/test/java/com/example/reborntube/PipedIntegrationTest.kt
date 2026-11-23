package com.example.reborntube

import org.junit.Assert.assertEquals
import org.junit.Test

class PipedIntegrationTest {
    @Test
    fun migratesUnusableLegacyDefaultWhenNoSessionExists() {
        assertEquals(
            PipedRepository.DEFAULT_API_URL,
            PipedRepository.migrateLegacyDefault("https://pipedapi.kavin.rocks/", hasSavedSession = false)
        )
    }

    @Test
    fun preservesLegacyInstanceWhenSessionExists() {
        assertEquals(
            "https://pipedapi.kavin.rocks",
            PipedRepository.migrateLegacyDefault("https://pipedapi.kavin.rocks", hasSavedSession = true)
        )
    }

    @Test
    fun doesNotRewriteCustomInstances() {
        assertEquals(
            "https://pipedapi.ducks.party",
            PipedRepository.migrateLegacyDefault("https://pipedapi.ducks.party/", hasSavedSession = false)
        )
    }
}
