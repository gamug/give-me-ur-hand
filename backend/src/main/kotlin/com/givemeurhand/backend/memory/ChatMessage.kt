// backend/src/main/kotlin/com/givemeurhand/backend/memory/ChatMessage.kt
package com.givemeurhand.backend.memory

import java.time.Instant

const val CHAT_MESSAGES_COLLECTION = "chat_messages"
const val SESSION_MEMORY_COLLECTION = "session_memory"

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String, // "user" | "assistant"
    val text: String,
    val createdAt: Instant
)

data class SessionMemory(
    val sessionId: String,
    val summary: String = "",
    val messagesSinceCompaction: Int = 0,
    val pendingConsentRequest: Boolean = false,
    val pendingAssignmentId: String? = null,
    val consentAttempts: Int = 0,
    val redirectAttempts: Int = 0
)
