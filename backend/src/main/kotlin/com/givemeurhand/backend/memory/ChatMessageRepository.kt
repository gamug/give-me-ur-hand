// backend/src/main/kotlin/com/givemeurhand/backend/memory/ChatMessageRepository.kt
package com.givemeurhand.backend.memory

interface ChatMessageRepository {
    suspend fun append(sessionId: String, role: String, text: String)
    suspend fun lastN(sessionId: String, n: Int): List<ChatMessage> // chronological, oldest first
}
