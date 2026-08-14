package com.givemeurhand.backend.rag

interface ChunkRepository {
    suspend fun search(query: String, limit: Int): List<Chunk>
}
