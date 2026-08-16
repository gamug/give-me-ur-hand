// backend/src/test/kotlin/com/givemeurhand/backend/agent/ChatAgentTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.rag.Chunk
import com.givemeurhand.backend.rag.FakeChunkRepository
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatAgentTest {
    private val expandJson = """["reform 1", "reform 2", "reform 3"]"""
    private val fallbackPhone = "+57 3219699131"

    private val alarmCriteria = AlarmCriteria(
        version = 1,
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        classificationPromptText = "criterios de prueba",
        controlStrategiesText = "estrategias de prueba"
    )

    private fun agent(
        fake: FakeDeepSeekClient,
        assignmentService: com.givemeurhand.backend.assignment.AssignmentService = FallbackOnlyAssignmentService(fallbackPhone),
        memoryService: FakeMemoryService = FakeMemoryService(),
        consentMaxAttempts: Int = 2,
        chunkRepository: FakeChunkRepository = FakeChunkRepository(emptyMap()),
        incoherenceMaxAttempts: Int = 2
    ) = ChatAgent(fake, chunkRepository, assignmentService, memoryService, alarmCriteria, fallbackPhone, consentMaxAttempts, incoherenceMaxAttempts)

    @Test
    fun `explicit human help request starts the consent flow instead of a one-way phone hand-off`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "Quiero hablar con alguien",
            """{"color":"VERDE","intent":"AYUDA_HUMANA","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val memoryService = FakeMemoryService()
        val agent = agent(fake, assignmentService, memoryService)

        val result = agent.handle("session-1", "kiero ablar con alguien")

        assertEquals("human_help_pending_consent", result.kind)
        assertTrue(result.reply.contains("permiso"))
        assertEquals(
            listOf(FakeAssignmentService.AssignCall("session-1", "Quiero hablar con alguien", "immediate_triage")),
            assignmentService.assignHelperCalls
        )
        val state = memoryService.getState("session-1")
        assertTrue(state.pendingConsentRequest)
        assertEquals("assignment-1", state.pendingAssignmentId)
    }

    @Test
    fun `crisis risk also escalates to consent flow, falling back to the phone when no professional is available`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "ya no quiero vivir",
            """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val agent = agent(fake, FallbackOnlyAssignmentService(fallbackPhone))

        val result = agent.handle("session-1", "ya no kiero vivir")

        assertEquals("human_help", result.kind)
        assertTrue(result.reply.contains(fallbackPhone))
    }

    @Test
    fun `ROJO-graded message sets pendingConsentRequest, then a granted reply with a phone records consent and clears pending`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "ya no quiero vivir", // standardize
            """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""", // classify
            """{"consiente":true,"telefono":"3001234567"}""" // consent parse (next turn)
        ))
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val memoryService = FakeMemoryService()
        val agent = agent(fake, assignmentService, memoryService)

        val first = agent.handle("session-1", "ya no kiero vivir")
        assertEquals("human_help_pending_consent", first.kind)
        assertTrue(memoryService.getState("session-1").pendingConsentRequest)

        val second = agent.handle("session-1", "sí, mi número es 3001234567")

        assertEquals("consent_granted", second.kind)
        assertTrue(second.reply.contains("Gracias"))
        assertEquals(
            listOf(FakeAssignmentService.ConsentCall("assignment-1", true, "3001234567", "sí, mi número es 3001234567")),
            assignmentService.recordConsentCalls
        )
        val stateAfter = memoryService.getState("session-1")
        assertEquals(false, stateAfter.pendingConsentRequest)
        assertEquals(null, stateAfter.pendingAssignmentId)
    }

    @Test
    fun `an explicit decline records DECLINED consent, clears pending and shares the fallback phone`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "ya no quiero vivir",
            """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""",
            """{"consiente":false,"telefono":null}"""
        ))
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val memoryService = FakeMemoryService()
        val agent = agent(fake, assignmentService, memoryService)

        agent.handle("session-1", "ya no kiero vivir")
        val second = agent.handle("session-1", "no")

        assertEquals("consent_declined", second.kind)
        assertTrue(second.reply.contains(fallbackPhone))
        assertEquals(
            listOf(FakeAssignmentService.ConsentCall("assignment-1", false, null, "no")),
            assignmentService.recordConsentCalls
        )
        assertEquals(false, memoryService.getState("session-1").pendingConsentRequest)
    }

    @Test
    fun `an ambiguous reply is retried up to consentMaxAttempts then resolves to DECLINED`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "ya no quiero vivir", // standardize
            """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""", // classify
            "no entiendo lo que dice esto", // ambiguous consent parse, attempt 1
            "tal vez, no se" // ambiguous consent parse, attempt 2 (== consentMaxAttempts)
        ))
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val memoryService = FakeMemoryService()
        val agent = agent(fake, assignmentService, memoryService, consentMaxAttempts = 2)

        agent.handle("session-1", "ya no kiero vivir")

        val retry = agent.handle("session-1", "mmm no se")
        assertEquals("consent_clarify", retry.kind)
        assertTrue(memoryService.getState("session-1").pendingConsentRequest)
        assertEquals(1, memoryService.getState("session-1").consentAttempts)

        val resolved = agent.handle("session-1", "sigo sin saber")
        assertEquals("consent_declined", resolved.kind)
        assertTrue(resolved.reply.contains(fallbackPhone))
        assertEquals(false, memoryService.getState("session-1").pendingConsentRequest)
        assertEquals(
            listOf(FakeAssignmentService.ConsentCall("assignment-1", false, null, "sigo sin saber")),
            assignmentService.recordConsentCalls
        )
    }

    @Test
    fun `consent granted without a phone number is ambiguous, not an auto-grant`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "ya no quiero vivir",
            """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""",
            """{"consiente":true,"telefono":null}"""
        ))
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val memoryService = FakeMemoryService()
        val agent = agent(fake, assignmentService, memoryService, consentMaxAttempts = 2)

        agent.handle("session-1", "ya no kiero vivir")
        val second = agent.handle("session-1", "sí, está bien")

        assertEquals("consent_clarify", second.kind)
        assertTrue(memoryService.getState("session-1").pendingConsentRequest)
        assertEquals(emptyList<FakeAssignmentService.ConsentCall>(), assignmentService.recordConsentCalls)
    }

    @Test
    fun `a pending consent reply short-circuits before Standardize or Classify run`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """{"consiente":false,"telefono":null}"""
        ))
        val memoryService = FakeMemoryService()
        memoryService.setPendingConsent("session-1", "assignment-1")
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val agent = agent(fake, assignmentService, memoryService)

        val result = agent.handle("session-1", "no")

        assertEquals("consent_declined", result.kind)
        // Only one DeepSeek call (ConsentParseStep) should have run; Standardize/Classify would
        // have consumed additional queued responses and changed lastSystemPrompt away from the
        // consent-parsing prompt.
        assertEquals(false, fake.lastSystemPrompt?.contains("Clasifica") == true)
    }

    @Test
    fun `greeting returns a cordial reply without querying the knowledge base`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "Hola",
            """{"color":"VERDE","intent":"SALUDO","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val agent = agent(fake)

        val result = agent.handle("session-1", "hola")

        assertEquals("greeting", result.kind)
        assertEquals(true, result.reply.isNotBlank())
        assertEquals(false, result.reply.contains("no está relacionada"))
        // Only standardize + classify should have run; expand/answer would consume a 3rd/4th
        // fake response and change lastSystemPrompt, so this proves RAG was never reached.
        assertEquals(true, fake.lastSystemPrompt?.contains("Clasifica") == true)
    }

    @Test
    fun `greeting reply no longer proactively offers to talk to a real person`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "Hola",
            """{"color":"VERDE","intent":"SALUDO","coherente":true,"quiere_ser_escuchado":false}"""
        ))
        val agent = agent(fake)

        val result = agent.handle("session-1", "hola")

        assertEquals(false, result.reply.contains("hablar con una persona real"))
    }

    @Test
    fun `normal question with matching chunks returns a grounded answer`() = runTest {
        val chunk = Chunk(id = "1", text = "Breathing exercises help.", sourceDocument = "pfa.pdf", page = 1, chunkIndex = 0, score = 0.9)
        val repo = FakeChunkRepository(
            mapOf(
                "como manejo la ansiedad" to listOf(chunk),
                "reform 1" to listOf(chunk),
                "reform 2" to listOf(chunk),
                "reform 3" to listOf(chunk)
            )
        )
        val fake = FakeDeepSeekClient(mutableListOf(
            "como manejo la ansiedad", // standardize
            """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""", // classify
            expandJson,                 // expand
            "Respira profundo."         // answer
        ))
        val agent = agent(fake, chunkRepository = repo)

        val result = agent.handle("session-1", "komo manejo la anciedad")

        assertEquals("answer", result.kind)
        assertEquals("Respira profundo.", result.reply)
    }

    @Test
    fun `recordTurn is invoked with the session id, raw message and final reply`() = runTest {
        val chunk = Chunk(id = "1", text = "Breathing exercises help.", sourceDocument = "pfa.pdf", page = 1, chunkIndex = 0, score = 0.9)
        val repo = FakeChunkRepository(
            mapOf(
                "como manejo la ansiedad" to listOf(chunk),
                "reform 1" to listOf(chunk),
                "reform 2" to listOf(chunk),
                "reform 3" to listOf(chunk)
            )
        )
        val fake = FakeDeepSeekClient(mutableListOf(
            "como manejo la ansiedad", // standardize
            """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""", // classify
            expandJson,                 // expand
            "Respira profundo."         // answer
        ))
        val memoryService = FakeMemoryService()
        val agent = agent(fake, memoryService = memoryService, chunkRepository = repo)

        val result = agent.handle("session-1", "komo manejo la anciedad")

        assertEquals(listOf(Triple("session-1", "komo manejo la anciedad", result.reply)), memoryService.recordedCalls)
    }

    @Test
    fun `normal question with no matching chunks returns the softened out-of-scope message`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "cual es la capital de francia",
            """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""",
            expandJson
        ))
        val agent = agent(fake)

        val result = agent.handle("session-1", "cual es la kapital de francia")

        assertEquals("out_of_scope", result.kind)
        assertEquals(
            "No tengo información suficiente para responder eso con seguridad, pero cuéntame más sobre cómo te sientes y trato de ayudarte.",
            result.reply
        )
    }

    @Test
    fun `an incoherent message returns a redirect reply and increments the attempts counter`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "palabras sueltas raras", // standardize
            """{"color":"VERDE","intent":"NORMAL","coherente":false,"quiere_ser_escuchado":false}""", // classify
            "¿Puedes contarme más sobre cómo te sientes ahora mismo?" // redirect
        ))
        val memoryService = FakeMemoryService()
        val agent = agent(fake, memoryService = memoryService, incoherenceMaxAttempts = 2)

        val result = agent.handle("session-1", "asdkjh qweoiu asdlkj zxcv")

        assertEquals("redirect", result.kind)
        assertEquals("¿Puedes contarme más sobre cómo te sientes ahora mismo?", result.reply)
        assertEquals(1, memoryService.getState("session-1").redirectAttempts)
    }

    @Test
    fun `incoherenceMaxAttempts + 1 consecutive incoherent messages escalate to the consent flow instead of another redirect`() = runTest {
        val incoherentClassify = """{"color":"VERDE","intent":"NORMAL","coherente":false,"quiere_ser_escuchado":false}"""
        val fake = FakeDeepSeekClient(mutableListOf(
            "raw 1", incoherentClassify, "redirect reply 1",
            "raw 2", incoherentClassify, "redirect reply 2",
            "raw 3", incoherentClassify
        ))
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val memoryService = FakeMemoryService()
        val agent = agent(fake, assignmentService, memoryService, incoherenceMaxAttempts = 2)

        val first = agent.handle("session-1", "mensaje raro 1")
        assertEquals("redirect", first.kind)
        assertEquals(1, memoryService.getState("session-1").redirectAttempts)

        val second = agent.handle("session-1", "mensaje raro 2")
        assertEquals("redirect", second.kind)
        assertEquals(2, memoryService.getState("session-1").redirectAttempts)

        val third = agent.handle("session-1", "mensaje raro 3")
        assertEquals("human_help_pending_consent", third.kind)
        assertTrue(memoryService.getState("session-1").pendingConsentRequest)
        assertEquals(
            listOf(FakeAssignmentService.AssignCall("session-1", "raw 3", "immediate_triage")),
            assignmentService.assignHelperCalls
        )
    }

    @Test
    fun `a coherent message in between resets the redirect counter so a later incoherent message gets a fresh redirect`() = runTest {
        val incoherentClassify = """{"color":"VERDE","intent":"NORMAL","coherente":false,"quiere_ser_escuchado":false}"""
        val coherentClassify = """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
        val fake = FakeDeepSeekClient(mutableListOf(
            "raw 1", incoherentClassify, "redirect reply 1",
            "raw 2", coherentClassify, expandJson,
            "raw 3", incoherentClassify, "redirect reply 2"
        ))
        val memoryService = FakeMemoryService()
        val agent = agent(fake, memoryService = memoryService, incoherenceMaxAttempts = 2)

        val first = agent.handle("session-1", "mensaje raro 1")
        assertEquals("redirect", first.kind)
        assertEquals(1, memoryService.getState("session-1").redirectAttempts)

        val second = agent.handle("session-1", "una pregunta normal")
        assertEquals("out_of_scope", second.kind)
        assertEquals(0, memoryService.getState("session-1").redirectAttempts)

        val third = agent.handle("session-1", "mensaje raro 2")
        assertEquals("redirect", third.kind)
        assertEquals("redirect reply 2", third.reply)
        assertEquals(1, memoryService.getState("session-1").redirectAttempts)
    }
}
