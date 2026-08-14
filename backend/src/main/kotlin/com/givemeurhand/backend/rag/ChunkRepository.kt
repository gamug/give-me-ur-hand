package com.givemeurhand.backend.rag

const val KNOWLEDGE_CHUNKS_COLLECTION = "knowledge_chunks"

interface ChunkRepository {
    suspend fun search(query: String, limit: Int): List<Chunk>
}
