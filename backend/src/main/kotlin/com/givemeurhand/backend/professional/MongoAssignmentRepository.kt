// backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoAssignmentRepository.kt
package com.givemeurhand.backend.professional

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date

const val ASSIGNMENTS_COLLECTION = "assignments"

class MongoAssignmentRepository(
    private val collection: MongoCollection<Document>
) : AssignmentRepository {
    override suspend fun countActiveSince(professionalId: String, since: Instant): Int {
        val filter = Filters.and(
            Filters.eq("professionalId", professionalId),
            Filters.eq("status", "active"),
            Filters.gt("assignedAt", Date.from(since))
        )
        return collection.countDocuments(filter).toInt()
    }

    override suspend fun lastAssignedAt(professionalId: String): Instant? =
        collection.find(Filters.eq("professionalId", professionalId))
            .sort(Sorts.descending("assignedAt"))
            .limit(1)
            .firstOrNull()
            ?.getDate("assignedAt")
            ?.toInstant()

    override suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String, triggerSource: String): Assignment {
        val now = Date()
        val doc = Document()
            .append("professionalId", professionalId)
            .append("sessionId", sessionId)
            .append("reasonSnippet", reasonSnippet)
            .append("status", "active")
            .append("assignedAt", now)
            .append("closedAt", null)
            .append("consentStatus", "PENDING")
            .append("contactPhone", null)
            .append("consentEvidenceText", null)
            .append("consentTimestamp", null)
            .append("triggerSource", triggerSource)
        collection.insertOne(doc)
        return Assignment(
            id = doc.getObjectId("_id").toHexString(),
            professionalId = professionalId,
            sessionId = sessionId,
            reasonSnippet = reasonSnippet,
            status = "active",
            assignedAt = now.toInstant(),
            closedAt = null,
            consentStatus = "PENDING",
            contactPhone = null,
            consentEvidenceText = null,
            consentTimestamp = null,
            triggerSource = triggerSource
        )
    }

    override suspend fun findByProfessional(professionalId: String): List<Assignment> =
        collection.find(Filters.eq("professionalId", professionalId)).map { it.toAssignment() }.toList()

    override suspend fun close(assignmentId: String, professionalId: String): Boolean {
        val objectId = runCatching { ObjectId(assignmentId) }.getOrNull() ?: return false
        val filter = Filters.and(
            Filters.eq("_id", objectId),
            Filters.eq("professionalId", professionalId),
            Filters.eq("status", "active")
        )
        val update = Updates.combine(Updates.set("status", "closed"), Updates.set("closedAt", Date()))
        return collection.updateOne(filter, update).modifiedCount > 0
    }

    override suspend fun updateConsent(
        assignmentId: String,
        status: String,
        contactPhone: String?,
        evidenceText: String,
        timestamp: Instant
    ): Boolean {
        val objectId = runCatching { ObjectId(assignmentId) }.getOrNull() ?: return false
        val update = Updates.combine(
            Updates.set("consentStatus", status),
            Updates.set("contactPhone", contactPhone),
            Updates.set("consentEvidenceText", evidenceText),
            Updates.set("consentTimestamp", Date.from(timestamp))
        )
        return collection.updateOne(Filters.eq("_id", objectId), update).modifiedCount > 0
    }

    private fun Document.toAssignment() = Assignment(
        id = getObjectId("_id").toHexString(),
        professionalId = getString("professionalId"),
        sessionId = getString("sessionId"),
        reasonSnippet = getString("reasonSnippet"),
        status = getString("status"),
        assignedAt = getDate("assignedAt").toInstant(),
        closedAt = getDate("closedAt")?.toInstant(),
        consentStatus = getString("consentStatus") ?: "PENDING",
        contactPhone = getString("contactPhone"),
        consentEvidenceText = getString("consentEvidenceText"),
        consentTimestamp = getDate("consentTimestamp")?.toInstant(),
        triggerSource = getString("triggerSource") ?: "immediate_triage"
    )
}
