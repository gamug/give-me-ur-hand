package com.givemeurhand.backend.memory

class FakeSessionMemoryRepository(
    private val memories: MutableMap<String, SessionMemory> = mutableMapOf()
) : SessionMemoryRepository {
    override suspend fun get(sessionId: String): SessionMemory =
        memories[sessionId] ?: SessionMemory(sessionId)

    override suspend fun save(memory: SessionMemory) {
        memories[memory.sessionId] = memory
    }
}
