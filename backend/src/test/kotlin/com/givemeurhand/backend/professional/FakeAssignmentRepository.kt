package com.givemeurhand.backend.professional

import java.time.Instant

class FakeAssignmentRepository : AssignmentRepository {
    private val assignments = mutableListOf<Assignment>()
    val createdCalls = mutableListOf<Triple<String, String, String>>()

    fun seed(professionalId: String, assignedAt: Instant, status: String = "active") {
        assignments.add(
            Assignment(
                id = "seed-${assignments.size}",
                professionalId = professionalId,
                sessionId = "seed-session",
                reasonSnippet = "seed",
                status = status,
                assignedAt = assignedAt,
                closedAt = null
            )
        )
    }

    override suspend fun countActiveSince(professionalId: String, since: Instant): Int =
        assignments.count { it.professionalId == professionalId && it.status == "active" && it.assignedAt.isAfter(since) }

    override suspend fun lastAssignedAt(professionalId: String): Instant? =
        assignments.filter { it.professionalId == professionalId }.maxOfOrNull { it.assignedAt }

    override suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String): Assignment {
        createdCalls.add(Triple(professionalId, sessionId, reasonSnippet))
        val assignment = Assignment(
            id = "gen-${assignments.size}",
            professionalId = professionalId,
            sessionId = sessionId,
            reasonSnippet = reasonSnippet,
            status = "active",
            assignedAt = Instant.now(),
            closedAt = null
        )
        assignments.add(assignment)
        return assignment
    }

    override suspend fun findByProfessional(professionalId: String): List<Assignment> =
        assignments.filter { it.professionalId == professionalId }

    override suspend fun close(assignmentId: String, professionalId: String): Boolean {
        val index = assignments.indexOfFirst { it.id == assignmentId && it.professionalId == professionalId }
        if (index == -1) return false
        assignments[index] = assignments[index].copy(status = "closed", closedAt = Instant.now())
        return true
    }
}
