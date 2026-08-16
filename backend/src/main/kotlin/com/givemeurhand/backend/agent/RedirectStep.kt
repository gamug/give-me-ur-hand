// backend/src/main/kotlin/com/givemeurhand/backend/agent/RedirectStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

object RedirectStep {
    private const val SYSTEM_PROMPT = """Eres un asistente de primeros auxilios psicológicos para personas afectadas por un terremoto. Responde ÚNICAMENTE en español, con un tono calmado, cercano y psicoeducativo. El mensaje de la persona es difícil de entender o parece incoherente. No lo juzgues ni le digas que no le entendiste. En su lugar, haz UNA sola pregunta breve, amable y que ayude a centrarla (por ejemplo, sobre cómo se siente en este momento o qué está pasando), para intentar comprender genuinamente lo que le ocurre."""

    suspend fun run(text: String, client: DeepSeekClient): String {
        return client.complete(SYSTEM_PROMPT, text).trim()
    }
}
