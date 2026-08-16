// backend/src/main/kotlin/com/givemeurhand/backend/memory/MongoSessionMemoryRepository.kt
package com.givemeurhand.backend.memory

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document

class MongoSessionMemoryRepository(
    private val collection: MongoCollection<Document>
) : SessionMemoryRepository {
    override suspend fun get(sessionId: String): SessionMemory {
        val doc = collection.find(Filters.eq("sessionId", sessionId)).firstOrNull()
            ?: return SessionMemory(sessionId)
        return doc.toSessionMemory()
    }

    override suspend fun save(memory: SessionMemory) {
        val doc = Document()
            .append("sessionId", memory.sessionId)
            .append("summary", memory.summary)
            .append("messagesSinceCompaction", memory.messagesSinceCompaction)
            .append("pendingConsentRequest", memory.pendingConsentRequest)
            .append("pendingAssignmentId", memory.pendingAssignmentId)
            .append("consentAttempts", memory.consentAttempts)
            .append("redirectAttempts", memory.redirectAttempts)
        collection.replaceOne(Filters.eq("sessionId", memory.sessionId), doc, ReplaceOptions().upsert(true))
    }

    private fun Document.toSessionMemory() = SessionMemory(
        sessionId = getString("sessionId"),
        summary = getString("summary") ?: "",
        messagesSinceCompaction = getIntFlexible("messagesSinceCompaction", 0),
        pendingConsentRequest = getBoolean("pendingConsentRequest", false),
        pendingAssignmentId = getString("pendingAssignmentId"),
        consentAttempts = getIntFlexible("consentAttempts", 0),
        redirectAttempts = getIntFlexible("redirectAttempts", 0)
    )

    private fun Document.getIntFlexible(key: String, default: Int): Int =
        (get(key) as? Number)?.toInt() ?: default
}
