package com.givemeurhand.android.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

interface ChatApiClient {
    suspend fun sendMessage(sessionId: String, message: String): ChatResponse
}

class HttpChatApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ChatApiClient {
    override suspend fun sendMessage(sessionId: String, message: String): ChatResponse =
        httpClient.post("$baseUrl/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(sessionId, message))
        }.body()
}
