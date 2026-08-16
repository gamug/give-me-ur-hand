// backend/src/test/kotlin/com/givemeurhand/backend/agent/AlarmClassifyStepTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.alarm.TriageColor
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmClassifyStepTest {
    private val criteria = AlarmCriteria(
        version = 1,
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        classificationPromptText = "criterios de prueba",
        controlStrategiesText = "estrategias de prueba"
    )

    @Test
    fun `parses ROJO color`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val result = AlarmClassifyStep.run("ya no quiero vivir", criteria, fake)
        assertEquals(TriageColor.ROJO, result.color)
    }

    @Test
    fun `parses AMARILLO color`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"AMARILLO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val result = AlarmClassifyStep.run("me siento mal", criteria, fake)
        assertEquals(TriageColor.AMARILLO, result.color)
    }

    @Test
    fun `parses VERDE color`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val result = AlarmClassifyStep.run("como manejo la ansiedad", criteria, fake)
        assertEquals(TriageColor.VERDE, result.color)
    }

    @Test
    fun `malformed JSON fails safe to ROJO NORMAL coherent wantsToBeHeard false`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("esto no es json en absoluto"))
        val result = AlarmClassifyStep.run("algo raro", criteria, fake)
        assertEquals(
            TriageResult(TriageColor.ROJO, ChatIntent.NORMAL, coherent = true, wantsToBeHeard = false),
            result
        )
    }

    @Test
    fun `unrecognized color string fails safe to ROJO NORMAL coherent wantsToBeHeard false`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"NARANJA","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val result = AlarmClassifyStep.run("algo raro", criteria, fake)
        assertEquals(
            TriageResult(TriageColor.ROJO, ChatIntent.NORMAL, coherent = true, wantsToBeHeard = false),
            result
        )
    }

    @Test
    fun `parses coherent and wantsToBeHeard booleans from JSON`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"VERDE","intent":"NORMAL","coherente":false,"quiere_ser_escuchado":true}"""
        ))
        val result = AlarmClassifyStep.run("mensaje raro", criteria, fake)
        assertEquals(false, result.coherent)
        assertEquals(true, result.wantsToBeHeard)
    }

    @Test
    fun `parses GREETING intent`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"VERDE","intent":"SALUDO","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val result = AlarmClassifyStep.run("hola", criteria, fake)
        assertEquals(ChatIntent.GREETING, result.intent)
    }

    @Test
    fun `parses HUMAN_HELP_EXPLICIT intent`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"color":"VERDE","intent":"AYUDA_HUMANA","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val result = AlarmClassifyStep.run("quiero hablar con alguien", criteria, fake)
        assertEquals(ChatIntent.HUMAN_HELP_EXPLICIT, result.intent)
    }
}
