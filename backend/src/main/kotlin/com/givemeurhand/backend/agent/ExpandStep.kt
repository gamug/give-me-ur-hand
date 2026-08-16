// backend/src/main/kotlin/com/givemeurhand/backend/agent/ExpandStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.slf4j.LoggerFactory

object ExpandStep {
    // The user writes in Spanish, but the knowledge base indexed in Mongo (knowledge_chunks) is
    // English-only text run through an Atlas Search lexical index — it matches literal words, not
    // meaning. A Spanish-only reformulation almost never shares vocabulary with the English chunk
    // text, so RagSearchStep would come back empty for nearly every real question. Reformulating
    // (and translating) into English here is what lets the search queries actually hit the index.
    private const val SYSTEM_PROMPT = """Genera 3 reformulaciones EN INGLÉS de la siguiente pregunta (que puede estar en español), ya que la base de conocimiento contra la que se buscará solo tiene contenido en inglés. Cada reformulación debe tener una perspectiva más amplia que la original (sinónimos, contexto relacionado, ángulos distintos del mismo tema), traducida y expresada en inglés. Responde ÚNICAMENTE con un array JSON de 3 strings en inglés, sin texto adicional. Formato: ["reformulation 1", "reformulation 2", "reformulation 3"]"""

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(ExpandStep::class.java)

    suspend fun run(text: String, client: DeepSeekClient): List<String> {
        val raw = client.complete(SYSTEM_PROMPT, text).trim()
        return try {
            val parsed = json.decodeFromString<List<String>>(raw)
            parsed.ifEmpty { listOf(text) }
        } catch (e: Exception) {
            logger.warn("Failed to parse ExpandStep JSON response, falling back to original query", e)
            listOf(text)
        }
    }
}
