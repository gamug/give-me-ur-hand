// backend/src/main/kotlin/com/givemeurhand/backend/agent/ClassifyStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

enum class Intent { HUMAN_HELP_EXPLICIT, CRISIS_RISK, NORMAL_QUESTION }

object ClassifyStep {
    private const val SYSTEM_PROMPT = """Clasifica el siguiente mensaje de una persona afectada por un terremoto en EXACTAMENTE una de estas tres etiquetas. Responde solo con la etiqueta, nada más:
AYUDA_HUMANA - la persona pide explícitamente hablar con una persona, un profesional o ayuda humana directa.
RIESGO_CRISIS - hay señales de posible daño a sí mismo, a otros, o peligro de vida inmediato.
PREGUNTA_NORMAL - cualquier otro caso."""

    suspend fun run(text: String, client: DeepSeekClient): Intent {
        val raw = client.complete(SYSTEM_PROMPT, text).trim().uppercase()
        return when {
            raw.contains("AYUDA_HUMANA") -> Intent.HUMAN_HELP_EXPLICIT
            raw.contains("RIESGO_CRISIS") -> Intent.CRISIS_RISK
            raw.contains("PREGUNTA_NORMAL") -> Intent.NORMAL_QUESTION
            // Respuesta ambigua o no reconocida: por seguridad se trata como
            // posible crisis, para no dejar sin atención humana un caso real.
            else -> Intent.CRISIS_RISK
        }
    }
}
