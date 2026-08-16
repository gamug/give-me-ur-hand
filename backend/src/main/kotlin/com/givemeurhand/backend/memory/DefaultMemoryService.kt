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

    override suspend fun getState(sessionId: String): SessionMemory {
        // Contract (see MemoryService KDoc): must never throw — ChatAgent reads this at the top
        // of every turn with no surrounding try/catch, to decide whether a consent reply is
        // pending. A transient read failure must not prevent a reply; fail safe to "no pending
        // consent" defaults so the turn proceeds through the normal Standardize/Classify flow.
        return try {
            sessionMemories.get(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("getState failed for session $sessionId, returning default state", e)
            SessionMemory(sessionId)
        }
    }

    override suspend fun setPendingConsent(sessionId: String, assignmentId: String) {
        try {
            val current = sessionMemories.get(sessionId)
            sessionMemories.save(
                current.copy(pendingConsentRequest = true, pendingAssignmentId = assignmentId, consentAttempts = 0)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("setPendingConsent failed for session $sessionId, continuing without recording", e)
        }
    }

    override suspend fun clearPendingConsent(sessionId: String) {
        try {
            val current = sessionMemories.get(sessionId)
            sessionMemories.save(
                current.copy(pendingConsentRequest = false, pendingAssignmentId = null, consentAttempts = 0)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("clearPendingConsent failed for session $sessionId, continuing without recording", e)
        }
    }

    override suspend fun incrementConsentAttempts(sessionId: String): Int {
        return try {
            val current = sessionMemories.get(sessionId)
            val updated = current.copy(consentAttempts = current.consentAttempts + 1)
            sessionMemories.save(updated)
            updated.consentAttempts
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("incrementConsentAttempts failed for session $sessionId, returning 0", e)
            0
        }
    }
}
