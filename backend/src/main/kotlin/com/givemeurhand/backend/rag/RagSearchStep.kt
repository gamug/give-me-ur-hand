package com.givemeurhand.backend.rag

object RagSearchStep {
    suspend fun search(
        queries: List<String>,
        repository: ChunkRepository,
        perQueryLimit: Int = 6,
        finalLimit: Int = 6
    ): List<Chunk> {
        val all = queries.flatMap { repository.search(it, perQueryLimit) }
        return all.groupBy { it.id }
            .map { (_, dupes) -> dupes.maxBy { it.score } }
            .sortedByDescending { it.score }
            .take(finalLimit)
    }
}
