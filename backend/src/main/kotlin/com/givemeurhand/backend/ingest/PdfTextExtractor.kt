package com.givemeurhand.backend.ingest

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Path

interface PdfTextExtractor {
    fun extractPages(pdfPath: Path): List<String>
}

class PdfBoxTextExtractor : PdfTextExtractor {
    override fun extractPages(pdfPath: Path): List<String> {
        val document = Loader.loadPDF(pdfPath.toFile())
        return document.use {
            val stripper = PDFTextStripper()
            (1..document.numberOfPages).map { pageNum ->
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                stripper.getText(document)
            }
        }
    }
}
