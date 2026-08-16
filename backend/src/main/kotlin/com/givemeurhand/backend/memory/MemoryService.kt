// backend/src/main/kotlin/com/givemeurhand/backend/memory/MemoryService.kt
package com.givemeurhand.backend.memory

/**
 * Records chat turns and tracks per-session turn counts.
 *
 * Contract: implementations MUST NOT throw. This is called from
 * [com.givemeurhand.backend.agent.ChatAgent] after a response has already been produced, with no
 * surrounding try/catch — a transient recording failure (e.g. Mongo being unreachable) MUST NOT
 * turn an already-correct answer into an error. Any internal failure MUST be caught and logged
 * internally instead of propagating (see [com.givemeurhand.backend.assignment.AssignmentService]
 * for the same contract applied to a different collaborator).
 */
interface MemoryService {
    suspend fun recordTurn(sessionId: String, userText: String, replyText: String): Boolean
}
