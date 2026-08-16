// backend/src/main/kotlin/com/givemeurhand/backend/agent/CompactionStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.memory.ChatMessage

object CompactionStep {
    private const val SYSTEM_PROMPT = """Eres un asistente que mantiene un resumen breve y actualizado del estado de una persona afectada por un terremoto, a partir de una conversación de apoyo psicológico de primeros auxilios.

Se te dará el resumen anterior (puede estar vacío) y los turnos más recientes de la conversación. Combina ambos en un resumen actualizado y conciso, en español, enfocado en:
- estado de ánimo y síntomas mencionados
- indicadores de riesgo
- preferencias expresadas (por ejemplo, si la persona quiere ser escuchada y no recibir consejos)

Omite el saludo social y las trivialidades. Sé conciso. Responde ÚNICAMENTE con el resumen actualizado, sin texto adicional."""

    suspend fun run(priorSummary: String, recentTurns: List<ChatMessage>, client: DeepSeekClient): String {
        val turnsText = recentTurns.joinToString(separator = "\n") { "${it.role}: ${it.text}" }
        val userPrompt = "Resumen anterior:\n$priorSummary\n\nTurnos recientes:\n$turnsText"
        return client.complete(SYSTEM_PROMPT, userPrompt).trim()
    }
}
