// backend/src/main/kotlin/com/givemeurhand/backend/deepseek/DeepSeekClient.kt
package com.givemeurhand.backend.deepseek

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

interface DeepSeekClient {
    suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double = 0.3): String
}

class DeepSeekException(message: String) : Exception(message)

class HttpDeepSeekClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) : DeepSeekClient {
    override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
        val response: DeepSeekChatResponse = httpClient.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                DeepSeekChatRequest(
                    model = model,
                    messages = listOf(
                        DeepSeekMessage("system", systemPrompt),
                        DeepSeekMessage("user", userPrompt)
                    ),
                    temperature = temperature
                )
            )
        }.body()

        return response.choices.firstOrNull()?.message?.content
            ?: throw DeepSeekException("Respuesta vacía de DeepSeek")
    }
}
