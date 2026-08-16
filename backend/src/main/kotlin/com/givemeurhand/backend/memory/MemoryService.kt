// backend/src/main/kotlin/com/givemeurhand/backend/memory/MemoryService.kt
package com.givemeurhand.backend.memory

// Open (and recordTurn is open) so tests can substitute a FakeMemoryService that records
// calls without exercising the real repository coordination logic below.
open class MemoryService(
    private val chatMessages: ChatMessageRepository,
    private val sessionMemories: SessionMemoryRepository,
    private val monitorIntervalMessages: Int
) {
    open suspend fun recordTurn(sessionId: String, userText: String, replyText: String): Boolean {
        chatMessages.append(sessionId, "user", userText)
        chatMessages.append(sessionId, "assistant", replyText)

        val memory = sessionMemories.get(sessionId)
        val updated = memory.copy(messagesSinceCompaction = memory.messagesSinceCompaction + 1)
        sessionMemories.save(updated)

        return updated.messagesSinceCompaction >= monitorIntervalMessages
    }
}
