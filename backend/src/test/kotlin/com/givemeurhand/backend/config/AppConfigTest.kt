package com.givemeurhand.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    private val requiredOnly = mapOf(
        "DEEPSEEK_API_KEY" to "key-123",
        "MONGODB_URI" to "mongodb+srv://example",
        "JWT_SECRET" to "secret-123"
    )

    @Test
    fun `applies defaults when optional vars are missing`() {
        val config = AppConfig.fromEnv(requiredOnly)
        assertEquals("https://api.deepseek.com", config.deepSeekBaseUrl)
        assertEquals("deepseek-chat", config.deepSeekModel)
        assertEquals("give_me_ur_hand", config.mongoDatabase)
        assertEquals("+57 3219699131", config.fallbackHelpPhone)
        assertEquals(4L, config.assignmentMaxAgeHours)
        assertEquals(6, config.monitorIntervalMessages)
        assertEquals(2, config.consentMaxAttempts)
        assertEquals(3, config.incoherenceMaxAttempts)
        assertEquals(2000, config.memorySummaryMaxChars)
    }

    @Test
    fun `throws when a required var is missing`() {
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnv(mapOf("MONGODB_URI" to "x", "JWT_SECRET" to "y"))
        }
    }

    @Test
    fun `reads overrides when present`() {
        val config = AppConfig.fromEnv(requiredOnly + mapOf("ASSIGNMENT_MAX_AGE_HOURS" to "6"))
        assertEquals(6L, config.assignmentMaxAgeHours)
    }

    @Test
    fun `reads MONITOR_INTERVAL_MESSAGES override when present`() {
        val config = AppConfig.fromEnv(requiredOnly + mapOf("MONITOR_INTERVAL_MESSAGES" to "10"))
        assertEquals(10, config.monitorIntervalMessages)
    }

    @Test
    fun `reads CONSENT_MAX_ATTEMPTS override when present`() {
        val config = AppConfig.fromEnv(requiredOnly + mapOf("CONSENT_MAX_ATTEMPTS" to "3"))
        assertEquals(3, config.consentMaxAttempts)
    }

    @Test
    fun `reads INCOHERENCE_MAX_ATTEMPTS override when present`() {
        val config = AppConfig.fromEnv(requiredOnly + mapOf("INCOHERENCE_MAX_ATTEMPTS" to "5"))
        assertEquals(5, config.incoherenceMaxAttempts)
    }

    @Test
    fun `reads MEMORY_SUMMARY_MAX_CHARS override when present`() {
        val config = AppConfig.fromEnv(requiredOnly + mapOf("MEMORY_SUMMARY_MAX_CHARS" to "500"))
        assertEquals(500, config.memorySummaryMaxChars)
    }
}
