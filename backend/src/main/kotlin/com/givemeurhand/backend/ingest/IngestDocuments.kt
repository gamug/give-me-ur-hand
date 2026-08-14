package com.givemeurhand.backend.ingest

import com.givemeurhand.backend.config.AppConfig
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.runBlocking
import org.bson.Document
import java.io.File
import java.util.Date

fun main(args: Array<String>) {
    val sourceDir = args.getOrNull(0)
        ?: System.getenv("PFA_SOURCE_DIR")
        ?: "../psicological-first-aid"

    val pdfFiles = File(sourceDir)
        .listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?: emptyList()

    if (pdfFiles.isEmpty()) {
        println("No se encontraron PDFs en $sourceDir")
        return
    }

    val config = AppConfig.fromEnv()
    val extractor = PdfBoxTextExtractor()

    runBlocking {
        val mongoClient = MongoClient.create(config.mongoUri)
        try {
            val collection = mongoClient.getDatabase(config.mongoDatabase).getCollection<Document>("knowledge_chunks")
            var totalInserted = 0

            pdfFiles.forEach { pdfFile ->
                println("Procesando ${pdfFile.name}...")
                val pages = extractor.extractPages(pdfFile.toPath())
                val records = ChunkBuilder.build(pdfFile.name, pages)
                records.forEach { record ->
                    val doc = Document()
                        .append("text", record.text)
                        .append("sourceDocument", record.sourceDocument)
                        .append("page", record.page)
                        .append("chunkIndex", record.chunkIndex)
                        .append("language", record.language)
                        .append("createdAt", Date())
                    collection.insertOne(doc)
                    totalInserted++
                }
                println("  -> ${records.size} chunks")
            }

            println("Listo. $totalInserted chunks insertados en knowledge_chunks.")
        } finally {
            mongoClient.close()
        }
    }
}
