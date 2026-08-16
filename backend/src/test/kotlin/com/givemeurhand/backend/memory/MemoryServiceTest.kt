package com.givemeurhand.backend.memory

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryServiceTest {
    @Test
    fun `recordTurn appends the user message then the assistant message in order`() = runTest {
        val chatMessages = FakeChatMessageRepository()
        val service = MemoryService(chatMessages, FakeSessionMemoryRepository(), monitorIntervalMessages = 2)

        service.recordTurn("session-1", "hola", "¡Hola!")

        val messages = chatMessages.lastN("session-1", 10)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("hola", messages[0].text)
        assertEquals("assistant", messages[1].role)
        assertEquals("¡Hola!", messages[1].text)
    }

    @Test
    fun `recordTurn returns false before the threshold and true exactly when it is reached`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val service = MemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2)

        val firstTurn = service.recordTurn("session-1", "uno", "resp uno")
        assertFalse(firstTurn)
        assertEquals(1, sessionMemories.get("session-1").messagesSinceCompaction)

        val secondTurn = service.recordTurn("session-1", "dos", "resp dos")
        assertTrue(secondTurn)
        assertEquals(2, sessionMemories.get("session-1").messagesSinceCompaction)
    }
}
