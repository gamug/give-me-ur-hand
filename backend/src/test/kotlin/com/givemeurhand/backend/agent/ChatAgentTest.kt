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
        chunkRepository: FakeChunkRepository = FakeChunkRepository(emptyMap())
    ) = ChatAgent(fake, chunkRepository, assignmentService, memoryService, alarmCriteria, fallbackPhone, consentMaxAttempts)

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
    fun `normal question with no matching chunks returns the out-of-scope message`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "cual es la capital de francia",
            """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}""",
            expandJson
        ))
        val agent = agent(fake)

        val result = agent.handle("session-1", "cual es la kapital de francia")

        assertEquals("out_of_scope", result.kind)
        assertEquals("Tu pregunta no está relacionada con el propósito de esta aplicación.", result.reply)
    }
}
