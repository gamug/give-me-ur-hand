// backend/src/test/kotlin/com/givemeurhand/backend/ingest/ChunkBuilderTest.kt
package com.givemeurhand.backend.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkBuilderTest {
    @Test
    fun `builds one record per page with correct page and index metadata`() {
        val pages = listOf("Texto corto de la pagina uno.", "Texto corto de la pagina dos.")
        val records = ChunkBuilder.build("doc.pdf", pages)

        assertEquals(2, records.size)
        assertEquals(1, records[0].page)
        assertEquals(0, records[0].chunkIndex)
        assertEquals("doc.pdf", records[0].sourceDocument)
        assertEquals("en", records[0].language)
        assertEquals(2, records[1].page)
    }

    @Test
    fun `numbers multiple chunks within the same page independently`() {
        val longPage = "Palabra ".repeat(200)
        val records = ChunkBuilder.build("doc.pdf", listOf(longPage), language = "es")

        assertTrue(records.size > 1)
        assertTrue(records.all { it.page == 1 })
        assertEquals((0 until records.size).toList(), records.map { it.chunkIndex })
        assertTrue(records.all { it.language == "es" })
    }
}
