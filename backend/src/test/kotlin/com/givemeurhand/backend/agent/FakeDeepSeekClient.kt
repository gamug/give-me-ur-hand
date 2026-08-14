// backend/src/test/kotlin/com/givemeurhand/backend/agent/FakeDeepSeekClient.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

class FakeDeepSeekClient(
    private val responses: MutableList<String> = mutableListOf(),
    var lastSystemPrompt: String? = null,
    var lastUserPrompt: String? = null
) : DeepSeekClient {
    var throwOnCall: Exception? = null

    override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
        throwOnCall?.let { throw it }
        lastSystemPrompt = systemPrompt
        lastUserPrompt = userPrompt
        return if (responses.isNotEmpty()) responses.removeAt(0) else ""
    }
}
