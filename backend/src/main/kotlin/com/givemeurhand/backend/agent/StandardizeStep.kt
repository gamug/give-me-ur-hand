// backend/src/main/kotlin/com/givemeurhand/backend/agent/StandardizeStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

object StandardizeStep {
    private const val SYSTEM_PROMPT = """Eres un corrector ortográfico y gramatical. Recibes un mensaje escrito por una persona en una zona de emergencia (terremoto) desde su celular, probablemente con errores de tipeo o acentuación. Devuelve ÚNICAMENTE el mismo mensaje corregido en español, sin agregar explicaciones, sin responder la pregunta, sin agregar información nueva. Si ya está bien escrito, devuélvelo igual."""

    suspend fun run(rawInput: String, client: DeepSeekClient): String {
        return client.complete(SYSTEM_PROMPT, rawInput).trim()
    }
}
