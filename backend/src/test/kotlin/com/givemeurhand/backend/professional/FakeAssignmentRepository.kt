package com.givemeurhand.backend.professional

import org.bson.types.ObjectId
import java.time.Instant

class FakeAssignmentRepository : AssignmentRepository {
    data class CreateCall(val professionalId: String, val sessionId: String, val reasonSnippet: String, val triggerSource: String)

    private val assignments = mutableListOf<Assignment>()
    val createdCalls = mutableListOf<CreateCall>()

    fun seed(professionalId: String, assignedAt: Instant, status: String = "active") {
        assignments.add(
            Assignment(
                id = ObjectId().toHexString(),
                professionalId = professionalId,
                sessionId = "seed-session",
                reasonSnippet = "seed",
                status = status,
                assignedAt = assignedAt,
                closedAt = null,
                consentStatus = "PENDING",
                contactPhone = null,
                consentEvidenceText = null,
                consentTimestamp = null,
                triggerSource = "immediate_triage"
            )
        )
    }

    override suspend fun countActiveSince(professionalId: String, since: Instant): Int =
        assignments.count { it.professionalId == professionalId && it.status == "active" && it.assignedAt.isAfter(since) }

    override suspend fun lastAssignedAt(professionalId: String): Instant? =
        assignments.filter { it.professionalId == professionalId }.maxOfOrNull { it.assignedAt }

    override suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String, triggerSource: String): Assignment {
        createdCalls.add(CreateCall(professionalId, sessionId, reasonSnippet, triggerSource))
        val assignment = Assignment(
            id = ObjectId().toHexString(),
            professionalId = professionalId,
            sessionId = sessionId,
            reasonSnippet = reasonSnippet,
            status = "active",
            assignedAt = Instant.now(),
            closedAt = null,
            consentStatus = "PENDING",
            contactPhone = null,
            consentEvidenceText = null,
            consentTimestamp = null,
            triggerSource = triggerSource
        )
        assignments.add(assignment)
        return assignment
    }

    override suspend fun findByProfessional(professionalId: String): List<Assignment> =
        assignments.filter { it.professionalId == professionalId }

    override suspend fun close(assignmentId: String, professionalId: String): Boolean {
        // Mirror MongoAssignmentRepository's contract: a malformed (non-ObjectId-shaped) id
        // is rejected the same way it would be against a real Mongo collection.
        if (!ObjectId.isValid(assignmentId)) return false
        val index = assignments.indexOfFirst { it.id == assignmentId && it.professionalId == professionalId }
        if (index == -1) return false
        assignments[index] = assignments[index].copy(status = "closed", closedAt = Instant.now())
        return true
    }

    override suspend fun updateConsent(
        assignmentId: String,
        status: String,
        contactPhone: String?,
        evidenceText: String,
        timestamp: Instant
    ): Boolean {
        if (!ObjectId.isValid(assignmentId)) return false
        val index = assignments.indexOfFirst { it.id == assignmentId }
        if (index == -1) return false
        assignments[index] = assignments[index].copy(
            consentStatus = status,
            contactPhone = contactPhone,
            consentEvidenceText = evidenceText,
            consentTimestamp = timestamp
        )
        return true
    }
}
