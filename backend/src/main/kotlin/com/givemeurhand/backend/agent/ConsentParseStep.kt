// backend/src/main/kotlin/com/givemeurhand/backend/agent/ConsentParseStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

data class ConsentParseResult(val consents: Boolean?, val phone: String?)

@Serializable
private data class RawConsentResponse(val consiente: Boolean? = null, val telefono: String? = null)

object ConsentParseStep {
    private const val SYSTEM_PROMPT = """Analiza la siguiente respuesta de una persona a quien se le preguntó si da su consentimiento para compartir sus datos con un profesional y, de ser así, cuál es su número de teléfono de contacto.

Responde ÚNICAMENTE con un objeto JSON, sin texto adicional, con este formato exacto:
{"consiente":true|false|null,"telefono":"..."|null}

- consiente: true si la persona da su consentimiento explícitamente, false si lo niega explícitamente, null si la respuesta no permite determinarlo con claridad.
- telefono: el número de teléfono que la persona compartió, o null si no compartió ninguno."""

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(ConsentParseStep::class.java)

    private val AMBIGUOUS = ConsentParseResult(consents = null, phone = null)

    suspend fun run(replyText: String, client: DeepSeekClient): ConsentParseResult {
        val raw = client.complete(SYSTEM_PROMPT, replyText).trim()
        return try {
            val parsed = json.decodeFromString<RawConsentResponse>(raw)
            ConsentParseResult(consents = parsed.consiente, phone = parsed.telefono)
        } catch (e: Exception) {
            logger.warn("Failed to parse ConsentParseStep JSON response, treating as ambiguous", e)
            AMBIGUOUS
        }
    }
}
