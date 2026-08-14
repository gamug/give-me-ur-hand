// backend/src/main/kotlin/com/givemeurhand/backend/deepseek/DeepSeekModels.kt
package com.givemeurhand.backend.deepseek

import kotlinx.serialization.Serializable

@Serializable
data class DeepSeekMessage(val role: String, val content: String)

@Serializable
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val temperature: Double
)

@Serializable
data class DeepSeekChoice(val message: DeepSeekMessage)

@Serializable
data class DeepSeekChatResponse(val choices: List<DeepSeekChoice>)
