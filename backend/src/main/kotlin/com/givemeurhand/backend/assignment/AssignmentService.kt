// backend/src/main/kotlin/com/givemeurhand/backend/assignment/AssignmentService.kt
package com.givemeurhand.backend.assignment

data class AssignResult(val assignmentId: String?, val phone: String)

/**
 * Assigns a human helper (professional/volunteer) to a session, or falls back
 * to the configured fallback help phone number.
 *
 * Contract: implementations MUST NOT throw. This is called from a crisis/human-help
 * path in [com.givemeurhand.backend.agent.ChatAgent] with no surrounding try/catch —
 * someone in crisis must never see a generic technical error here. Any internal
 * failure (e.g. a database being unreachable) MUST be caught internally and MUST
 * resolve to returning the fallback phone number instead of propagating.
 *
 * The same "must never throw" contract applies to [recordConsent] — it is called
 * from the same no-surrounding-try/catch path in ChatAgent, after a reply has
 * already been decided.
 */
interface AssignmentService {
    suspend fun assignHelper(sessionId: String, reason: String, triggerSource: String): AssignResult
    suspend fun recordConsent(assignmentId: String, granted: Boolean, phone: String?, evidenceText: String)
}

class FallbackOnlyAssignmentService(private val fallbackPhone: String) : AssignmentService {
    override suspend fun assignHelper(sessionId: String, reason: String, triggerSource: String): AssignResult =
        AssignResult(assignmentId = null, phone = fallbackPhone)

    override suspend fun recordConsent(assignmentId: String, granted: Boolean, phone: String?, evidenceText: String) {
        // No-op: there is never an assignment to attach consent to in fallback-only mode.
    }
}
