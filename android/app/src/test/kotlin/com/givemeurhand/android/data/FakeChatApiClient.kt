package com.givemeurhand.android.data

class FakeChatApiClient(
    private val response: ChatResponse = ChatResponse("ok", "answer"),
    private val error: Exception? = null
) : ChatApiClient {
    var lastSessionId: String? = null
    var lastMessage: String? = null

    override suspend fun sendMessage(sessionId: String, message: String): ChatResponse {
        lastSessionId = sessionId
        lastMessage = message
        error?.let { throw it }
        return response
    }
}
