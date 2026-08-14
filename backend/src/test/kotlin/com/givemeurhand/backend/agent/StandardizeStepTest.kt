// backend/src/test/kotlin/com/givemeurhand/backend/agent/StandardizeStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StandardizeStepTest {
    @Test
    fun `returns the cleaned text from DeepSeek, trimmed`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  Hola, ¿cómo estás?  "))
        val result = StandardizeStep.run("ola komo estas", fake)
        assertEquals("Hola, ¿cómo estás?", result)
        assertEquals("ola komo estas", fake.lastUserPrompt)
    }
}
