package com.givemeurhand.backend.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkerTest {
    @Test
    fun `returns a single chunk when text is shorter than chunkSize`() {
        val result = TextChunker.chunk("Hola mundo.", chunkSize = 600, overlap = 100)
        assertEquals(listOf("Hola mundo."), result)
    }

    @Test
    fun `blank text returns no chunks`() {
        assertEquals(emptyList(), TextChunker.chunk("   "))
    }

    @Test
    fun `splits long text into multiple chunks that respect chunkSize`() {
        val sentence = "Esta es una oracion de prueba para el chunking. "
        val text = sentence.repeat(30) // ~1500 chars
        val result = TextChunker.chunk(text, chunkSize = 300, overlap = 50)

        assertTrue(result.size > 1)
        result.forEach { assertTrue(it.length <= 300) }
    }

    @Test
    fun `consecutive chunks overlap`() {
        val text = "Palabra ".repeat(200) // 1600 chars, no punctuation
        val result = TextChunker.chunk(text, chunkSize = 300, overlap = 50)

        assertTrue(result.size > 1)
        val tailOfFirst = result[0].takeLast(20)
        assertTrue(result[1].contains(tailOfFirst))
    }
}
