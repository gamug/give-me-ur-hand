// backend/src/test/kotlin/com/givemeurhand/backend/agent/ConsentParseStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsentParseStepTest {
    @Test
    fun `parses explicit consent with a phone number`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"consiente":true,"telefono":"3001234567"}"""
        ))
        val result = ConsentParseStep.run("si, mi numero es 3001234567", fake)
        assertEquals(ConsentParseResult(consents = true, phone = "3001234567"), result)
    }

    @Test
    fun `parses explicit decline`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"consiente":false,"telefono":null}"""
        ))
        val result = ConsentParseStep.run("no, gracias", fake)
        assertEquals(ConsentParseResult(consents = false, phone = null), result)
    }

    @Test
    fun `consent without a phone number is parsed as consents true with a null phone`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"consiente":true,"telefono":null}"""
        ))
        val result = ConsentParseStep.run("si, esta bien", fake)
        assertEquals(ConsentParseResult(consents = true, phone = null), result)
    }

    @Test
    fun `malformed JSON is ambiguous, not an auto-decline`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("esto no es json en absoluto"))
        val result = ConsentParseStep.run("no se que decir", fake)
        assertEquals(ConsentParseResult(consents = null, phone = null), result)
    }

    @Test
    fun `explicit null consent is parsed as ambiguous`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"consiente":null,"telefono":null}"""
        ))
        val result = ConsentParseStep.run("no estoy seguro", fake)
        assertEquals(ConsentParseResult(consents = null, phone = null), result)
    }
}
