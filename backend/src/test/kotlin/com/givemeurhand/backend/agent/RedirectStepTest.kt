// backend/src/test/kotlin/com/givemeurhand/backend/agent/RedirectStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedirectStepTest {
    @Test
    fun `passes the person's text as the user prompt and returns the trimmed reply`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  ¿Puedes contarme un poco más sobre lo que está pasando?  "))

        val result = RedirectStep.run("palabras sueltas sin sentido aparente", fake)

        assertEquals("¿Puedes contarme un poco más sobre lo que está pasando?", result)
        assertEquals("palabras sueltas sin sentido aparente", fake.lastUserPrompt)
    }

    @Test
    fun `system prompt asks for a single gentle grounding question in Spanish`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("¿Qué está pasando ahora mismo?"))

        RedirectStep.run("mensaje confuso", fake)

        val prompt = fake.lastSystemPrompt!!
        assertTrue(prompt.contains("español", ignoreCase = true))
        assertTrue(prompt.contains("pregunta"))
    }
}
