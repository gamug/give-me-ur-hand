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
            // Fail toward "attempts exhausted", not "keep re-asking forever". If reads still work
            // but this write is failing (e.g. a Mongo primary stepdown or write-concern failure —
            // a real partial-outage mode, not just a hypothetical), getState will keep reporting
            // pendingConsentRequest=true while this call never durably increments the counter. A
            // low/zero fallback here would make the ambiguous branch's `attempts < consentMaxAttempts`
            // check pass forever, so the person is asked the same consent question indefinitely and
            // never sees the fallback phone. Returning Int.MAX_VALUE guarantees that check fails
            // regardless of the configured consentMaxAttempts, so the caller resolves to the decline
            // path — which still hands the person fallbackHelpPhone, so no one is left stranded.
            logger.error("incrementConsentAttempts failed for session $sessionId, treating attempts as exhausted", e)
            Int.MAX_VALUE
        }
    }

    override suspend fun incrementRedirectAttempts(sessionId: String): Int {
        return try {
            val current = sessionMemories.get(sessionId)
            val updated = current.copy(redirectAttempts = current.redirectAttempts + 1)
            sessionMemories.save(updated)
            updated.redirectAttempts
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail toward "attempts exhausted", not "keep redirecting forever" — same direction as
            // incrementConsentAttempts and for the same reason (see MemoryService KDoc). Unlike the
            // consent path, there is no fallbackHelpPhone shown directly on the redirect path, but
            // escalating routes into startConsentFlow, which always resolves to either a real
            // professional or the fallback phone. Returning a low/zero value here instead would let
            // `attempts <= incoherenceMaxAttempts` pass forever, trapping someone in an infinite
            // loop of clarifying questions with no path to a human at all. Returning Int.MAX_VALUE
            // guarantees that check fails regardless of the configured incoherenceMaxAttempts, so
            // the caller escalates instead.
            logger.error("incrementRedirectAttempts failed for session $sessionId, treating attempts as exhausted", e)
            Int.MAX_VALUE
        }
    }

    override suspend fun resetRedirectAttempts(sessionId: String) {
        try {
            val current = sessionMemories.get(sessionId)
            sessionMemories.save(current.copy(redirectAttempts = 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("resetRedirectAttempts failed for session $sessionId, continuing without recording", e)
        }
    }
}
