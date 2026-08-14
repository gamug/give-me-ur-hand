// backend/src/main/kotlin/com/givemeurhand/backend/assignment/AssignmentService.kt
package com.givemeurhand.backend.assignment

/**
 * Assigns a human helper (professional/volunteer) to a session, or falls back
 * to the configured fallback help phone number.
 *
 * Contract: implementations MUST NOT throw. This is called from a crisis/human-help
 * path in [com.givemeurhand.backend.agent.ChatAgent] with no surrounding try/catch —
 * someone in crisis must never see a generic technical error here. Any internal
 * failure (e.g. a database being unreachable) MUST be caught internally and MUST
 * resolve to returning the fallback phone number instead of propagating.
 */
interface AssignmentService {
    suspend fun assignHelper(sessionId: String, reason: String): String
}

class FallbackOnlyAssignmentService(private val fallbackPhone: String) : AssignmentService {
    override suspend fun assignHelper(sessionId: String, reason: String): String = fallbackPhone
}
