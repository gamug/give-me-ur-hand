// backend/src/main/kotlin/com/givemeurhand/backend/memory/MongoChatMessageRepository.kt
package com.givemeurhand.backend.memory

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.util.Date

class MongoChatMessageRepository(
    private val collection: MongoCollection<Document>
) : ChatMessageRepository {
    override suspend fun append(sessionId: String, role: String, text: String) {
        val doc = Document()
            .append("sessionId", sessionId)
            .append("role", role)
            .append("text", text)
            .append("createdAt", Date.from(java.time.Instant.now()))
        collection.insertOne(doc)
    }

    override suspend fun lastN(sessionId: String, n: Int): List<ChatMessage> =
        collection.find(Filters.eq("sessionId", sessionId))
            .sort(Sorts.descending("createdAt"))
            .limit(n)
            .map { it.toChatMessage() }
            .toList()
            .reversed()

    private fun Document.toChatMessage() = ChatMessage(
        id = get("_id").toString(),
        sessionId = getString("sessionId"),
        role = getString("role"),
        text = getString("text"),
        createdAt = getDate("createdAt").toInstant()
    )
}
