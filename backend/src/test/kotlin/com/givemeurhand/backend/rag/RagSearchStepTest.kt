package com.givemeurhand.backend.rag

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RagSearchStepTest {
    private fun chunk(id: String, score: Double) =
        Chunk(id = id, text = "texto $id", sourceDocument = "doc.pdf", page = 1, chunkIndex = 0, score = score)

    @Test
    fun `dedupes by id keeping the highest score and sorts descending`() = runTest {
        val repo = FakeChunkRepository(
            mapOf(
                "q1" to listOf(chunk("a", 0.5), chunk("b", 0.9)),
                "q2" to listOf(chunk("a", 0.8), chunk("c", 0.3))
            )
        )
        val result = RagSearchStep.search(listOf("q1", "q2"), repo, finalLimit = 6)
        assertEquals(listOf("b", "a", "c"), result.map { it.id })
        assertEquals(0.8, result.first { it.id == "a" }.score)
    }

    @Test
    fun `respects finalLimit`() = runTest {
        val repo = FakeChunkRepository(
            mapOf("q1" to listOf(chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.7)))
        )
        val result = RagSearchStep.search(listOf("q1"), repo, finalLimit = 2)
        assertEquals(2, result.size)
    }

    @Test
    fun `returns empty list when nothing matches`() = runTest {
        val repo = FakeChunkRepository(emptyMap())
        val result = RagSearchStep.search(listOf("sin resultados"), repo)
        assertEquals(emptyList(), result)
    }
}
