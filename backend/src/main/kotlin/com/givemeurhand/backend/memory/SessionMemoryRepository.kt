// backend/src/main/kotlin/com/givemeurhand/backend/memory/SessionMemoryRepository.kt
package com.givemeurhand.backend.memory

interface SessionMemoryRepository {
    suspend fun get(sessionId: String): SessionMemory // never null — returns SessionMemory(sessionId) defaults if no doc exists
    suspend fun save(memory: SessionMemory) // full-document upsert keyed by sessionId
}
