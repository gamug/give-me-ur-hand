// backend/src/test/kotlin/com/givemeurhand/backend/agent/ClassifyStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyStepTest {
    @Test
    fun `maps AYUDA_HUMANA to HUMAN_HELP_EXPLICIT`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("AYUDA_HUMANA"))
        assertEquals(Intent.HUMAN_HELP_EXPLICIT, ClassifyStep.run("quiero hablar con alguien", fake))
    }

    @Test
    fun `maps RIESGO_CRISIS to CRISIS_RISK`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("RIESGO_CRISIS"))
        assertEquals(Intent.CRISIS_RISK, ClassifyStep.run("ya no quiero vivir", fake))
    }

    @Test
    fun `maps PREGUNTA_NORMAL to NORMAL_QUESTION`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("PREGUNTA_NORMAL"))
        assertEquals(Intent.NORMAL_QUESTION, ClassifyStep.run("como manejo la ansiedad", fake))
    }

    @Test
    fun `unrecognized response fails safe to CRISIS_RISK`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("no estoy seguro"))
        assertEquals(Intent.CRISIS_RISK, ClassifyStep.run("algo raro", fake))
    }
}
