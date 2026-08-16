// backend/src/test/kotlin/com/givemeurhand/backend/agent/FakeMemoryService.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.memory.MemoryService

class FakeMemoryService : MemoryService {
    val recordedCalls = mutableListOf<Triple<String, String, String>>()

    override suspend fun recordTurn(sessionId: String, userText: String, replyText: String): Boolean {
        recordedCalls.add(Triple(sessionId, userText, replyText))
        return false
    }
}
