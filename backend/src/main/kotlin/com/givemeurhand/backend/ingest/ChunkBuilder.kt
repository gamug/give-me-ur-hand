package com.givemeurhand.backend.ingest

data class ChunkRecord(
    val text: String,
    val sourceDocument: String,
    val page: Int,
    val chunkIndex: Int,
    val language: String
)

object ChunkBuilder {
    fun build(sourceDocument: String, pages: List<String>, language: String = "en"): List<ChunkRecord> {
        return pages.flatMapIndexed { pageIndex, pageText ->
            TextChunker.chunk(pageText).mapIndexed { chunkIndex, chunkText ->
                ChunkRecord(
                    text = chunkText,
                    sourceDocument = sourceDocument,
                    page = pageIndex + 1,
                    chunkIndex = chunkIndex,
                    language = language
                )
            }
        }
    }
}
