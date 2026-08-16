// backend/src/main/kotlin/com/givemeurhand/backend/memory/MemoryService.kt
package com.givemeurhand.backend.memory

/**
 * Records chat turns and tracks per-session turn counts and pending-consent state.
 *
 * Contract: implementations MUST NOT throw. All of these are called from ChatAgent with no
 * surrounding try/catch — some (like [recordTurn]) after a response has already been produced,
 * others (the pending-consent accessors/mutators) as part of deciding what that response even
 * is. A transient failure here MUST NOT turn an already-correct answer into an error, and MUST
 * NOT prevent ChatAgent from producing a reply. Any internal failure MUST be caught and logged
 * internally instead of propagating (see AssignmentService for the same contract applied to a
 * different collaborator).
 */
interface MemoryService {
    suspend fun recordTurn(sessionId: String, userText: String, replyText: String): Boolean

    suspend fun getState(sessionId: String): SessionMemory

    /** Sets pendingConsentRequest=true, pendingAssignmentId=assignmentId, consentAttempts=0. */
    suspend fun setPendingConsent(sessionId: String, assignmentId: String)

    /** Sets pendingConsentRequest=false, pendingAssignmentId=null, consentAttempts=0. */
    suspend fun clearPendingConsent(sessionId: String)

    /** Increments consentAttempts and returns the new count. */
    suspend fun incrementConsentAttempts(sessionId: String): Int
}
