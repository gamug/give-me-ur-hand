package com.givemeurhand.backend.memory

import java.time.Instant

class FakeChatMessageRepository(
    private val messages: MutableList<ChatMessage> = mutableListOf()
) : ChatMessageRepository {
    override suspend fun append(sessionId: String, role: String, text: String) {
        messages.add(
            ChatMessage(
                id = "msg-${messages.size}",
                sessionId = sessionId,
                role = role,
                text = text,
                createdAt = Instant.now()
            )
        )
    }

    override suspend fun lastN(sessionId: String, n: Int): List<ChatMessage> =
        messages.filter { it.sessionId == sessionId }.takeLast(n)
}
