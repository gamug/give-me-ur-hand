// backend/src/main/kotlin/com/givemeurhand/backend/agent/ExpandStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

object ExpandStep {
    private const val SYSTEM_PROMPT = """Genera 3 reformulaciones de la siguiente pregunta, cada una con una perspectiva más amplia que la original (sinónimos, contexto relacionado, ángulos distintos del mismo tema), en español. Responde ÚNICAMENTE con un array JSON de 3 strings, sin texto adicional. Formato: ["reformulación 1", "reformulación 2", "reformulación 3"]"""

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(text: String, client: DeepSeekClient): List<String> {
        val raw = client.complete(SYSTEM_PROMPT, text).trim()
        return try {
            val parsed = json.decodeFromString<List<String>>(raw)
            parsed.ifEmpty { listOf(text) }
        } catch (e: Exception) {
            listOf(text)
        }
    }
}
