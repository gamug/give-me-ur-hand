// backend/src/test/kotlin/com/givemeurhand/backend/monitor/BackgroundMonitorAgentTest.kt
package com.givemeurhand.backend.monitor

import com.givemeurhand.backend.agent.FakeAssignmentService
import com.givemeurhand.backend.agent.FakeDeepSeekClient
import com.givemeurhand.backend.agent.FakeMemoryService
import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.assignment.AssignResult
import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.memory.MemoryService
import com.givemeurhand.backend.memory.SessionMemory
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundMonitorAgentTest {
    private val alarmCriteria = AlarmCriteria(
        version = 1,
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        classificationPromptText = "criterios de prueba",
        controlStrategiesText = "estrategias de prueba"
    )

    private val rojoClassification = """{"color":"ROJO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
    private val amarilloClassification = """{"color":"AMARILLO","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""
    private val verdeClassification = """{"color":"VERDE","intent":"NORMAL","coherente":true,"quiere_ser_escuchado":false}"""

    @Test
    fun `ROJO triggers assignHelper then setPendingConsent exactly once`() = runTest {
        val memoryService = FakeMemoryService()
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val monitor = BackgroundMonitorAgent(
            memoryService, alarmCriteria, FakeDeepSeekClient(mutableListOf(rojoClassification)), assignmentService
        )

        monitor.evaluate("session-1")

        assertEquals(1, assignmentService.assignHelperCalls.size)
        assertEquals(
            FakeAssignmentService.AssignCall("session-1", "", "background_monitor"),
            assignmentService.assignHelperCalls.single()
        )
        val state = memoryService.getState("session-1")
        assertTrue(state.pendingConsentRequest)
        assertEquals("assignment-1", state.pendingAssignmentId)
    }

    @Test
    fun `AMARILLO triggers neither assignHelper nor setPendingConsent`() = runTest {
        val memoryService = FakeMemoryService()
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val monitor = BackgroundMonitorAgent(
            memoryService, alarmCriteria, FakeDeepSeekClient(mutableListOf(amarilloClassification)), assignmentService
        )

        monitor.evaluate("session-1")

        assertEquals(emptyList<FakeAssignmentService.AssignCall>(), assignmentService.assignHelperCalls)
        assertEquals(false, memoryService.getState("session-1").pendingConsentRequest)
    }

    @Test
    fun `VERDE triggers neither assignHelper nor setPendingConsent`() = runTest {
        val memoryService = FakeMemoryService()
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-1")
        val monitor = BackgroundMonitorAgent(
            memoryService, alarmCriteria, FakeDeepSeekClient(mutableListOf(verdeClassification)), assignmentService
        )

        monitor.evaluate("session-1")

        assertEquals(emptyList<FakeAssignmentService.AssignCall>(), assignmentService.assignHelperCalls)
        assertEquals(false, memoryService.getState("session-1").pendingConsentRequest)
    }

    @Test
    fun `ROJO with no professional available sets no pending consent and does not crash`() = runTest {
        val memoryService = FakeMemoryService()
        val assignmentService = FakeAssignmentService(assignmentId = null)
        val monitor = BackgroundMonitorAgent(
            memoryService, alarmCriteria, FakeDeepSeekClient(mutableListOf(rojoClassification)), assignmentService
        )

        monitor.evaluate("session-1")

        assertEquals(1, assignmentService.assignHelperCalls.size)
        assertEquals(false, memoryService.getState("session-1").pendingConsentRequest)
    }

    @Test
    fun `a session already awaiting consent is skipped - never double-fires`() = runTest {
        val memoryService = FakeMemoryService()
        memoryService.setPendingConsent("session-1", "existing-assignment")
        val assignmentService = FakeAssignmentService(assignmentId = "assignment-2")
        val monitor = BackgroundMonitorAgent(
            memoryService, alarmCriteria, FakeDeepSeekClient(mutableListOf(rojoClassification)), assignmentService
        )

        monitor.evaluate("session-1")

        assertEquals(emptyList<FakeAssignmentService.AssignCall>(), assignmentService.assignHelperCalls)
        assertEquals("existing-assignment", memoryService.getState("session-1").pendingAssignmentId)
    }

    @Test
    fun `any dependency throwing is swallowed and never propagates out of evaluate`() = runTest {
        val throwingMemoryService = object : MemoryService {
            override suspend fun recordTurn(sessionId: String, userText: String, replyText: String) = false
            override suspend fun getState(sessionId: String) = SessionMemory(sessionId)
            override suspend fun setPendingConsent(sessionId: String, assignmentId: String) {}
            override suspend fun clearPendingConsent(sessionId: String) {}
            override suspend fun incrementConsentAttempts(sessionId: String) = 0
            override suspend fun incrementRedirectAttempts(sessionId: String) = 0
            override suspend fun resetRedirectAttempts(sessionId: String) {}
            override suspend fun compactIfDue(sessionId: String): SessionMemory = throw RuntimeException("boom")
        }
        val monitor = BackgroundMonitorAgent(
            throwingMemoryService, alarmCriteria, FakeDeepSeekClient(), FakeAssignmentService()
        )

        // Must not throw.
        monitor.evaluate("session-1")
    }

    @Test
    fun `a throwing DeepSeek client during triage classification is swallowed`() = runTest {
        val throwingDeepSeek = object : DeepSeekClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
                throw RuntimeException("deepseek unreachable")
            }
        }
        val monitor = BackgroundMonitorAgent(
            FakeMemoryService(), alarmCriteria, throwingDeepSeek, FakeAssignmentService()
        )

        // Must not throw.
        monitor.evaluate("session-1")
    }

    @Test
    fun `a throwing AssignmentService is swallowed`() = runTest {
        val throwingAssignmentService = object : AssignmentService {
            override suspend fun assignHelper(sessionId: String, reason: String, triggerSource: String): AssignResult {
                throw RuntimeException("assignment service unreachable")
            }
            override suspend fun recordConsent(assignmentId: String, granted: Boolean, phone: String?, evidenceText: String) {}
        }
        val monitor = BackgroundMonitorAgent(
            FakeMemoryService(), alarmCriteria, FakeDeepSeekClient(mutableListOf(rojoClassification)), throwingAssignmentService
        )

        // Must not throw.
        monitor.evaluate("session-1")
    }
}
