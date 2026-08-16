// backend/src/test/kotlin/com/givemeurhand/backend/agent/SupportStepTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.rag.Chunk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportStepTest {
    @Test
    fun `passes chunk text and control strategies as context and returns the trimmed answer`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  Prueba respirar profundo tres veces.  "))
        val chunks = listOf(
            Chunk(id = "1", text = "Grounding techniques help regulate breathing.", sourceDocument = "pfa.pdf", page = 3, chunkIndex = 0)
        )

        val result = SupportStep.run("me siento ansioso", chunks, "estrategias de control de prueba", fake)

        assertEquals("Prueba respirar profundo tres veces.", result)
        assertTrue(fake.lastSystemPrompt!!.contains("Grounding techniques help regulate breathing."))
        assertTrue(fake.lastSystemPrompt!!.contains("estrategias de control de prueba"))
    }

    @Test
    fun `system prompt instructs an active, not annoying role that offers concrete coping strategies`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("Respira profundo."))
        val chunks = listOf(
            Chunk(id = "1", text = "Grounding techniques help regulate breathing.", sourceDocument = "pfa.pdf", page = 3, chunkIndex = 0)
        )

        SupportStep.run("me siento ansioso", chunks, "estrategias de control de prueba", fake)

        val prompt = fake.lastSystemPrompt!!
        assertTrue(prompt.contains("activo"))
        assertTrue(prompt.contains("estrategia"))
    }
}
