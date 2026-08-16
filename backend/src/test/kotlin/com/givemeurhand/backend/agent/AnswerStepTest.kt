// backend/src/test/kotlin/com/givemeurhand/backend/agent/AnswerStepTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.rag.Chunk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerStepTest {
    @Test
    fun `passes chunk text as context and returns the trimmed answer`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  Respira profundo y busca un lugar seguro.  "))
        val chunks = listOf(
            Chunk(id = "1", text = "Grounding techniques help regulate breathing.", sourceDocument = "pfa.pdf", page = 3, chunkIndex = 0)
        )

        val result = AnswerStep.run("¿cómo calmo la ansiedad?", chunks, fake)

        assertEquals("Respira profundo y busca un lugar seguro.", result)
        assertTrue(fake.lastSystemPrompt!!.contains("Grounding techniques help regulate breathing."))
    }

    @Test
    fun `system prompt does not instruct the model to suggest professional help`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("Respira profundo."))
        val chunks = listOf(
            Chunk(id = "1", text = "Grounding techniques help regulate breathing.", sourceDocument = "pfa.pdf", page = 3, chunkIndex = 0)
        )

        AnswerStep.run("¿cómo calmo la ansiedad?", chunks, fake)

        assertEquals(false, fake.lastSystemPrompt!!.contains("ayuda profesional"))
    }
}
