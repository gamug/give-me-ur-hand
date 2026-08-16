// backend/src/main/kotlin/com/givemeurhand/backend/agent/AlarmClassifyStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.alarm.TriageColor
import com.givemeurhand.backend.deepseek.DeepSeekClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

enum class ChatIntent { GREETING, HUMAN_HELP_EXPLICIT, NORMAL }

data class TriageResult(
    val color: TriageColor,
    val intent: ChatIntent,
    val coherent: Boolean,
    val wantsToBeHeard: Boolean
)

@Serializable
private data class RawTriageResponse(
    val color: String,
    val intent: String,
    val coherente: Boolean,
    val quiere_ser_escuchado: Boolean
)

object AlarmClassifyStep {
    private const val SYSTEM_PROMPT_TEMPLATE = """Eres un sistema de triage psicológico para personas afectadas por un terremoto. Clasifica el siguiente mensaje según los criterios de alarma proporcionados a continuación.

Criterios de clasificación:
%s

Responde ÚNICAMENTE con un objeto JSON, sin texto adicional, con este formato exacto:
{"color":"ROJO|AMARILLO|VERDE","intent":"SALUDO|AYUDA_HUMANA|NORMAL","coherente":true|false,"quiere_ser_escuchado":true|false}

- color: nivel de alarma según los criterios de clasificación (ROJO = riesgo alto, AMARILLO = riesgo moderado, VERDE = sin riesgo aparente).
- intent: SALUDO si el mensaje es solo un saludo, agradecimiento u otra fórmula de cortesía social sin una necesidad concreta; AYUDA_HUMANA si la persona pide explícitamente hablar con una persona o un profesional; NORMAL en cualquier otro caso.
- coherente: true si el mensaje tiene sentido y es comprensible, false si es incoherente o incomprensible.
- quiere_ser_escuchado: true si la persona parece buscar principalmente ser escuchada/acompañada más que recibir una respuesta informativa."""

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(AlarmClassifyStep::class.java)

    private val FAIL_SAFE = TriageResult(
        color = TriageColor.ROJO,
        intent = ChatIntent.NORMAL,
        coherent = true,
        wantsToBeHeard = false
    )

    suspend fun run(text: String, criteria: AlarmCriteria, client: DeepSeekClient): TriageResult {
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.format(criteria.classificationPromptText)
        val raw = client.complete(systemPrompt, text).trim()
        return try {
            val parsed = json.decodeFromString<RawTriageResponse>(raw)
            val color = parseColor(parsed.color)
            if (color == null) {
                logger.warn("AlarmClassifyStep received an unrecognized color '{}', failing safe to ROJO", parsed.color)
                FAIL_SAFE
            } else {
                TriageResult(
                    color = color,
                    intent = parseIntent(parsed.intent),
                    coherent = parsed.coherente,
                    wantsToBeHeard = parsed.quiere_ser_escuchado
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse AlarmClassifyStep JSON response, failing safe to ROJO", e)
            FAIL_SAFE
        }
    }

    private fun parseColor(raw: String): TriageColor? {
        val upper = raw.uppercase()
        return when {
            upper.contains("ROJO") -> TriageColor.ROJO
            upper.contains("AMARILLO") -> TriageColor.AMARILLO
            upper.contains("VERDE") -> TriageColor.VERDE
            else -> null
        }
    }

    private fun parseIntent(raw: String): ChatIntent {
        val upper = raw.uppercase()
        return when {
            upper.contains("SALUDO") -> ChatIntent.GREETING
            upper.contains("AYUDA_HUMANA") -> ChatIntent.HUMAN_HELP_EXPLICIT
            else -> ChatIntent.NORMAL
        }
    }
}
