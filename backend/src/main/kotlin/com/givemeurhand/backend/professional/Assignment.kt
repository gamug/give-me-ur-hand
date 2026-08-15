package com.givemeurhand.backend.professional

import java.time.Instant

data class Assignment(
    val id: String,
    val professionalId: String,
    val sessionId: String,
    val reasonSnippet: String,
    val status: String,
    val assignedAt: Instant,
    val closedAt: Instant?
)

interface AssignmentRepository {
    suspend fun countActiveSince(professionalId: String, since: Instant): Int
    suspend fun lastAssignedAt(professionalId: String): Instant?
    suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String): Assignment
    suspend fun findByProfessional(professionalId: String): List<Assignment>
    suspend fun close(assignmentId: String, professionalId: String): Boolean
}
