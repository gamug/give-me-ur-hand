// backend/src/test/kotlin/com/givemeurhand/backend/ingest/PdfBoxTextExtractorTest.kt
package com.givemeurhand.backend.ingest

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfBoxTextExtractorTest {
    @Test
    fun `extracts one text block per page`(@TempDir tempDir: Path) {
        val pdfPath = tempDir.resolve("sample.pdf")
        createSamplePdf(pdfPath, listOf("Hola mundo de prueba.", "Segunda pagina de prueba."))

        val pages = PdfBoxTextExtractor().extractPages(pdfPath)

        assertEquals(2, pages.size)
        assertTrue(pages[0].contains("Hola mundo de prueba."))
        assertTrue(pages[1].contains("Segunda pagina de prueba."))
    }

    private fun createSamplePdf(path: Path, pagesText: List<String>) {
        PDDocument().use { document ->
            pagesText.forEach { text ->
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            document.save(path.toFile())
        }
    }
}
