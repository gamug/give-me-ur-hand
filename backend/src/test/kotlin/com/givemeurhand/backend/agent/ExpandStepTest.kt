// backend/src/test/kotlin/com/givemeurhand/backend/agent/ExpandStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpandStepTest {
    @Test
    fun `parses a JSON array of 3 reformulations`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """["¿qué es la ansiedad?", "cómo calmar el miedo", "síntomas de estrés postraumático"]"""
        ))
        val result = ExpandStep.run("tengo ansiedad", fake)
        assertEquals(3, result.size)
        assertEquals("¿qué es la ansiedad?", result[0])
    }

    @Test
    fun `falls back to the original text when the response is not valid JSON`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("esto no es json"))
        val result = ExpandStep.run("tengo ansiedad", fake)
        assertEquals(listOf("tengo ansiedad"), result)
    }

    @Test
    fun `asks DeepSeek to reformulate in English since the knowledge base is English-only`() = runTest {
        // The knowledge chunks in Mongo are stored in English (WHO PFA source PDFs), while users
        // write in Spanish. RagSearchStep runs these reformulations as literal Atlas Search text
        // queries against that English index, so Spanish-only reformulations never lexically match
        // the English chunk text and RAG silently returns nothing for almost every real question.
        val fake = FakeDeepSeekClient(mutableListOf(
            """["what is anxiety?", "how to calm fear", "post-traumatic stress symptoms"]"""
        ))
        ExpandStep.run("tengo ansiedad", fake)
        assertTrue(fake.lastSystemPrompt!!.contains("inglés", ignoreCase = true))
    }
}
