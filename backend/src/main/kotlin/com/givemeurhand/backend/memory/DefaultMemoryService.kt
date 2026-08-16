// backend/src/main/kotlin/com/givemeurhand/backend/memory/DefaultMemoryService.kt
package com.givemeurhand.backend.memory

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class DefaultMemoryService(
    private val chatMessages: ChatMessageRepository,
    private val sessionMemories: SessionMemoryRepository,
    private val monitorIntervalMessages: Int
) : MemoryService {

    private val logger = LoggerFactory.getLogger(DefaultMemoryService::class.java)

    override suspend fun recordTurn(sessionId: String, userText: String, replyText: String): Boolean {
        // Contract (see MemoryService KDoc): this must never throw. ChatAgent.handle calls this
        // after a response has already been produced, with no surrounding try/catch — a transient
        // recording failure must not turn an already-correct answer into an error.
        return try {
            chatMessages.append(sessionId, "user", userText)
            chatMessages.append(sessionId, "assistant", replyText)

            val memory = sessionMemories.get(sessionId)
            val updated = memory.copy(messagesSinceCompaction = memory.messagesSinceCompaction + 1)
            sessionMemories.save(updated)

            updated.messagesSinceCompaction >= monitorIntervalMessages
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("recordTurn failed for session $sessionId, continuing without recording", e)
            false
        }
    }
}
