// backend/src/main/kotlin/com/givemeurhand/backend/agent/AnswerStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.rag.Chunk

object AnswerStep {
    private const val SYSTEM_PROMPT_TEMPLATE = """Eres un asistente de primeros auxilios psicológicos para personas afectadas por un terremoto. Responde ÚNICAMENTE en español, con un tono calmado, cercano y psicoeducativo. No diagnostiques. Basa tu respuesta SOLO en el siguiente contenido de referencia (puede estar en inglés: tradúcelo y sintetízalo al responder).

Contenido de referencia:
%s"""

    suspend fun run(question: String, chunks: List<Chunk>, client: DeepSeekClient): String {
        val context = chunks.joinToString(separator = "\n---\n") { it.text }
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.format(context)
        return client.complete(systemPrompt, question).trim()
    }
}
