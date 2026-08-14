// backend/src/test/kotlin/com/givemeurhand/backend/agent/ExpandStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
