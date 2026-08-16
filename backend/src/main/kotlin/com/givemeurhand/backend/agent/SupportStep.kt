// backend/src/main/kotlin/com/givemeurhand/backend/agent/SupportStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.rag.Chunk

object SupportStep {
    private const val SYSTEM_PROMPT_TEMPLATE = """Eres un asistente de primeros auxilios psicológicos para personas afectadas por un terremoto. Responde ÚNICAMENTE en español, con un tono calmado, cercano y psicoeducativo. No diagnostiques. Adopta un rol activo, pero no invasivo: en lugar de solo informar, ofrece proactivamente 1 o 2 estrategias concretas de afrontamiento o control que la persona pueda aplicar ahora mismo, sin abrumarla ni sonar repetitivo. Basa tu respuesta SOLO en las siguientes estrategias de control y el contenido de referencia (puede estar en inglés: tradúcelo y sintetízalo al responder).

Estrategias de control:
%s

Contenido de referencia:
%s"""

    suspend fun run(question: String, chunks: List<Chunk>, controlStrategiesText: String, client: DeepSeekClient): String {
        val context = chunks.joinToString(separator = "\n---\n") { it.text }
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.format(controlStrategiesText, context)
        return client.complete(systemPrompt, question).trim()
    }
}
