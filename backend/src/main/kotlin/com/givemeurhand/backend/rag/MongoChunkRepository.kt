// backend/src/main/kotlin/com/givemeurhand/backend/rag/MongoChunkRepository.kt
package com.givemeurhand.backend.rag

import com.mongodb.client.model.Aggregates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document

class MongoChunkRepository(
    private val collection: MongoCollection<Document>,
    private val searchIndexName: String = "default"
) : ChunkRepository {
    override suspend fun search(query: String, limit: Int): List<Chunk> {
        val pipeline = listOf(
            Document(
                "\$search",
                Document("index", searchIndexName)
                    .append("text", Document("query", query).append("path", "text"))
            ),
            Aggregates.limit(limit).toBsonDocument().let { Document(it) },
            Document("\$addFields", Document("score", Document("\$meta", "searchScore")))
        )
        return collection.aggregate(pipeline).map { doc -> doc.toChunk() }.toList()
    }

    private fun Document.toChunk() = Chunk(
        id = get("_id").toString(),
        text = getString("text") ?: "",
        sourceDocument = getString("sourceDocument") ?: "",
        page = getInteger("page", 0),
        chunkIndex = getInteger("chunkIndex", 0),
        score = getDouble("score") ?: 0.0
    )
}
