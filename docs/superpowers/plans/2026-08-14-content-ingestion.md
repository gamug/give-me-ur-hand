# Content Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A one-off script (`./gradlew ingestDocuments`) that reads the PDFs in `psicological-first-aid/`, splits them into chunks with metadata, and loads them into the `knowledge_chunks` Mongo collection that `ChunkRepository` (backend core plan) searches against.

**Architecture:** Three pure/testable pieces — `TextChunker` (splits raw text into overlapping chunks), `PdfBoxTextExtractor` (PDF → per-page text via Apache PDFBox), `ChunkBuilder` (combines the two into metadata-tagged `ChunkRecord`s) — wired together by a thin `main()` that inserts into Mongo. Depends on `AppConfig` from the backend core plan.

**Tech Stack:** Kotlin, Apache PDFBox, MongoDB Kotlin coroutine driver, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-14-give-me-ur-hand-mvp-design.md`

## Global Constraints

- Chunk size ~600 characters with ~100 character overlap, preferring to break at sentence boundaries.
- Every chunk stores metadata: `sourceDocument`, `page`, `chunkIndex`, `language`, `createdAt`.
- Source PDFs are in English (`language = "en"`); this plan does not translate them — translation happens at answer-generation time (backend core plan's `AnswerStep`).
- `PFA_SOURCE_DIR` env var (default `../psicological-first-aid`, i.e. relative to `backend/`) controls where PDFs are read from.
- This plan assumes Task 1 of the backend core plan already exists (`backend/build.gradle.kts`, `AppConfig`) — it modifies that build file rather than recreating it.

---

### Task 1: TextChunker (pure chunking logic)

**Files:**
- Modify: `backend/build.gradle.kts` (add PDFBox dependency, used starting Task 2)
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/ingest/TextChunker.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/ingest/TextChunkerTest.kt`

**Interfaces:**
- Produces: `object TextChunker { fun chunk(text: String, chunkSize: Int = 600, overlap: Int = 100): List<String> }`.

- [ ] **Step 1: Add the PDFBox dependency**

In `backend/build.gradle.kts`, inside the existing `dependencies { ... }` block, add:

```kotlin
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
```

- [ ] **Step 2: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/ingest/TextChunkerTest.kt
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ingest.TextChunkerTest"`
Expected: FAIL — `TextChunker` does not exist.

- [ ] **Step 4: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/ingest/TextChunker.kt
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ingest.TextChunkerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/kotlin/com/givemeurhand/backend/ingest/TextChunker.kt backend/src/test/kotlin/com/givemeurhand/backend/ingest/TextChunkerTest.kt
git commit -m "feat(backend): add text chunker for RAG ingestion"
```

---

### Task 2: PdfTextExtractor (Apache PDFBox)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/ingest/PdfTextExtractor.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/ingest/PdfBoxTextExtractorTest.kt`

**Interfaces:**
- Produces: `interface PdfTextExtractor { fun extractPages(pdfPath: Path): List<String> }` and `class PdfBoxTextExtractor : PdfTextExtractor` (one string per page, in order).

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/ingest/PdfBoxTextExtractorTest.kt
package com.givemeurhand.backend.ingest

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
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
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            document.save(path.toFile())
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ingest.PdfBoxTextExtractorTest"`
Expected: FAIL — `PdfTextExtractor`/`PdfBoxTextExtractor` don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/ingest/PdfTextExtractor.kt
package com.givemeurhand.backend.ingest

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Path

interface PdfTextExtractor {
    fun extractPages(pdfPath: Path): List<String>
}

class PdfBoxTextExtractor : PdfTextExtractor {
    override fun extractPages(pdfPath: Path): List<String> {
        PDDocument.load(pdfPath.toFile()).use { document ->
            val stripper = PDFTextStripper()
            return (1..document.numberOfPages).map { pageNum ->
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                stripper.getText(document)
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ingest.PdfBoxTextExtractorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/ingest/PdfTextExtractor.kt backend/src/test/kotlin/com/givemeurhand/backend/ingest/PdfBoxTextExtractorTest.kt
git commit -m "feat(backend): add PDFBox-based per-page text extraction"
```

---

### Task 3: ChunkBuilder (attaches metadata to chunks)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/ingest/ChunkBuilder.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/ingest/ChunkBuilderTest.kt`

**Interfaces:**
- Consumes: `TextChunker` (Task 1).
- Produces: `data class ChunkRecord(val text: String, val sourceDocument: String, val page: Int, val chunkIndex: Int, val language: String)`, `object ChunkBuilder { fun build(sourceDocument: String, pages: List<String>, language: String = "en"): List<ChunkRecord> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ingest.ChunkBuilderTest"`
Expected: FAIL — `ChunkBuilder`/`ChunkRecord` don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/ingest/ChunkBuilder.kt
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ingest.ChunkBuilderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/ingest/ChunkBuilder.kt backend/src/test/kotlin/com/givemeurhand/backend/ingest/ChunkBuilderTest.kt
git commit -m "feat(backend): add chunk metadata builder"
```

---

### Task 4: IngestDocuments script + Gradle task + Atlas Search index + manual verification

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/ingest/IngestDocuments.kt`
- Modify: `backend/build.gradle.kts` (register the `ingestDocuments` Gradle task)

**Interfaces:**
- Consumes: `AppConfig` (backend core plan, Task 2), `PdfBoxTextExtractor`, `ChunkBuilder`, `ChunkRecord` (Tasks 2–3).
- Produces: `fun main(args: Array<String>)` in `com.givemeurhand.backend.ingest.IngestDocumentsKt`, runnable via `./gradlew ingestDocuments`.

This task has no automated test — it is a one-off I/O script wiring already-tested pieces to a real MongoDB cluster. Verification is manual (Step 4).

- [ ] **Step 1: Register the Gradle task**

In `backend/build.gradle.kts`, after the existing `application { ... }` block, add:

```kotlin
tasks.register<JavaExec>("ingestDocuments") {
    group = "application"
    description = "Extrae los PDFs de psicological-first-aid y los carga como chunks en MongoDB"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.givemeurhand.backend.ingest.IngestDocumentsKt")
    if (project.hasProperty("sourceDir")) {
        args = listOf(project.property("sourceDir") as String)
    }
}
```

- [ ] **Step 2: Write the script**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/ingest/IngestDocuments.kt
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
```

- [ ] **Step 3: Create the Atlas Search index (manual, one-time, in the Atlas UI)**

1. Open the Atlas cluster → **Search** tab → **Create Search Index**.
2. Choose **JSON Editor**, database `give_me_ur_hand` (or the value of `MONGODB_DATABASE`), collection `knowledge_chunks`.
3. Name the index exactly `default`.
4. Use this definition:
   ```json
   {
     "mappings": {
       "dynamic": false,
       "fields": {
         "text": { "type": "string" }
       }
     }
   }
   ```
5. Save and wait until the index status shows **Active** (usually under a minute on the free tier).

- [ ] **Step 4: Manual end-to-end run**

```bash
cd backend
export $(cat ../.env | xargs)   # or set the vars manually on Windows
./gradlew ingestDocuments
```

Expected: console output listing each of the 6 PDFs with a chunk count, ending in `Listo. N chunks insertados...`. Verify in the Atlas UI (Collections → `knowledge_chunks`) that documents exist with `text`, `sourceDocument`, `page`, `chunkIndex`, `language`, `createdAt` fields populated.

- [ ] **Step 5: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/kotlin/com/givemeurhand/backend/ingest/IngestDocuments.kt
git commit -m "feat(backend): add ingestion script for psicological-first-aid PDFs"
```
