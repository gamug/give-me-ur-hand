package com.givemeurhand.backend.ingest

object TextChunker {
    fun chunk(text: String, chunkSize: Int = 600, overlap: Int = 100): List<String> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= chunkSize) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            val hardEnd = minOf(start + chunkSize, normalized.length)
            val end = if (hardEnd == normalized.length) {
                hardEnd
            } else {
                val sentenceBreak = normalized.lastIndexOfAny(charArrayOf('.', '!', '?'), hardEnd - 1)
                val spaceBreak = normalized.lastIndexOf(' ', hardEnd - 1)
                when {
                    sentenceBreak > start -> sentenceBreak + 1
                    spaceBreak > start -> spaceBreak
                    else -> hardEnd
                }
            }
            val piece = normalized.substring(start, end).trim()
            if (piece.isNotBlank()) chunks.add(piece)
            if (end >= normalized.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return chunks
    }
}
