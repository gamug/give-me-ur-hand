// backend/src/test/kotlin/com/givemeurhand/backend/agent/CompactionStepTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.memory.ChatMessage
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionStepTest {
    private fun message(role: String, text: String) =
        ChatMessage(id = "msg-1", sessionId = "session-1", role = role, text = text, createdAt = Instant.now())

    @Test
    fun `passes prior summary and recent turns as context and returns the trimmed summary`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  Persona ansiosa, quiere ser escuchada.  "))
        val recentTurns = listOf(
            message("user", "me siento muy ansioso"),
            message("assistant", "cuéntame más sobre eso")
        )

        val result = CompactionStep.run("Resumen previo vacío", recentTurns, fake)

        assertEquals("Persona ansiosa, quiere ser escuchada.", result)
        assertTrue(fake.lastSystemPrompt!!.contains("resumen"))
        assertTrue(fake.lastUserPrompt!!.contains("Resumen previo vacío"))
        assertTrue(fake.lastUserPrompt!!.contains("me siento muy ansioso"))
    }

    @Test
    fun `free-text completion is returned as-is (trimmed) with no JSON parsing`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("no es JSON, solo texto libre."))

        val result = CompactionStep.run("", emptyList(), fake)

        assertEquals("no es JSON, solo texto libre.", result)
    }
}
