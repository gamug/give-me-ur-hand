package com.givemeurhand.backend.rag

class FakeChunkRepository(private val byQuery: Map<String, List<Chunk>>) : ChunkRepository {
    override suspend fun search(query: String, limit: Int): List<Chunk> =
        (byQuery[query] ?: emptyList()).take(limit)
}
