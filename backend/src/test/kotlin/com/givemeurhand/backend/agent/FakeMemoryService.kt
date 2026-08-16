// backend/src/test/kotlin/com/givemeurhand/backend/agent/FakeMemoryService.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.memory.MemoryService
import com.givemeurhand.backend.memory.SessionMemory

class FakeMemoryService : MemoryService {
    val recordedCalls = mutableListOf<Triple<String, String, String>>()
    private val states = mutableMapOf<String, SessionMemory>()

    override suspend fun recordTurn(sessionId: String, userText: String, replyText: String): Boolean {
        recordedCalls.add(Triple(sessionId, userText, replyText))
        return false
    }

    override suspend fun getState(sessionId: String): SessionMemory =
        states[sessionId] ?: SessionMemory(sessionId)

    override suspend fun setPendingConsent(sessionId: String, assignmentId: String) {
        val current = states[sessionId] ?: SessionMemory(sessionId)
        states[sessionId] = current.copy(pendingConsentRequest = true, pendingAssignmentId = assignmentId, consentAttempts = 0)
    }

    override suspend fun clearPendingConsent(sessionId: String) {
        val current = states[sessionId] ?: SessionMemory(sessionId)
        states[sessionId] = current.copy(pendingConsentRequest = false, pendingAssignmentId = null, consentAttempts = 0)
    }

    override suspend fun incrementConsentAttempts(sessionId: String): Int {
        val current = states[sessionId] ?: SessionMemory(sessionId)
        val updated = current.copy(consentAttempts = current.consentAttempts + 1)
        states[sessionId] = updated
        return updated.consentAttempts
    }
}
