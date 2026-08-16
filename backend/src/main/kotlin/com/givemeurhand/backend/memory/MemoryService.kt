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

    /**
     * Increments consentAttempts and returns the new count. On internal failure, implementations
     * MUST fail toward "attempts exhausted" (a value that reliably fails any `attempts <
     * consentMaxAttempts` check), NOT toward "keep re-asking" — a caller stuck retrying forever
     * because a write is silently failing would never surface the fallback phone to someone who
     * may be in genuine crisis.
     */
    suspend fun incrementConsentAttempts(sessionId: String): Int

    /**
     * Increments redirectAttempts and returns the new count. On internal failure, implementations
     * MUST fail toward "attempts exhausted" (a value that reliably fails any `attempts <=
     * incoherenceMaxAttempts` check), the same direction as [incrementConsentAttempts] and for the
     * same reason: a caller stuck retrying forever because a write is silently failing would loop
     * gentle redirect questions indefinitely with no way out. Unlike the redirect reply itself,
     * escalating to the consent flow (via ChatAgent.startConsentFlow) always terminates in either
     * a real professional or the fallback phone number, so failing toward escalation is the safe
     * direction here too — failing toward "keep redirecting" has no such safety net.
     */
    suspend fun incrementRedirectAttempts(sessionId: String): Int

    /** Resets redirectAttempts to 0. Called on any coherent message. */
    suspend fun resetRedirectAttempts(sessionId: String)
}
