package com.givemeurhand.backend.rag

data class Chunk(
    val id: String,
    val text: String,
    val sourceDocument: String,
    val page: Int,
    val chunkIndex: Int,
    val score: Double = 0.0
)
