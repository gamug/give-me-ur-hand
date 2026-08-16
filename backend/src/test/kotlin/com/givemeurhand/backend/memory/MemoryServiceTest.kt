package com.givemeurhand.backend.memory

import com.givemeurhand.backend.agent.FakeDeepSeekClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryServiceTest {
    private val defaultMaxSummaryChars = 2000
    @Test
    fun `recordTurn appends the user message then the assistant message in order`() = runTest {
        val chatMessages = FakeChatMessageRepository()
        val service = DefaultMemoryService(chatMessages, FakeSessionMemoryRepository(), monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

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
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val firstTurn = service.recordTurn("session-1", "uno", "resp uno")
        assertFalse(firstTurn)
        assertEquals(1, sessionMemories.get("session-1").messagesSinceCompaction)

        val secondTurn = service.recordTurn("session-1", "dos", "resp dos")
        assertTrue(secondTurn)
        assertEquals(2, sessionMemories.get("session-1").messagesSinceCompaction)
    }

    @Test
    fun `recordTurn keeps returning true on every turn at or above the threshold, so a launch keeps being retried until compaction succeeds`() = runTest {
        // Deliberately "at or above", not exact-crossing: compactIfDue's own failure path does NOT
        // reset messagesSinceCompaction on a transient error, so a later call can retry compaction.
        // If recordTurn only signaled "threshold crossed" on the exact turn that reaches it, a
        // single failed compaction would permanently silence the monitor for that session (the
        // counter keeps climbing past the threshold and would never exactly equal it again). The
        // repeated launches this enables are safe: BackgroundMonitorAgent's in-flight guard
        // ensures at most one evaluate() actually runs per session at a time, so this can't
        // reintroduce the double-fire bug fixed alongside the guard.
        val sessionMemories = FakeSessionMemoryRepository()
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val turn1 = service.recordTurn("session-1", "uno", "resp uno")
        val turn2 = service.recordTurn("session-1", "dos", "resp dos")
        val turn3 = service.recordTurn("session-1", "tres", "resp tres")
        val turn4 = service.recordTurn("session-1", "cuatro", "resp cuatro")

        assertFalse(turn1)
        assertTrue(turn2)
        assertTrue(turn3)
        assertTrue(turn4)
    }

    @Test
    fun `a compactIfDue failure does not permanently disable future launches - a later successful call still compacts`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val chatMessages = FakeChatMessageRepository()
        val throwingDeepSeek = object : com.givemeurhand.backend.deepseek.DeepSeekClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
                throw RuntimeException("deepseek unreachable")
            }
        }
        val failingService = DefaultMemoryService(
            chatMessages, sessionMemories, monitorIntervalMessages = 2,
            deepSeekClient = throwingDeepSeek, maxSummaryChars = defaultMaxSummaryChars
        )

        // Turn crosses the threshold; recordTurn signals true and a compaction attempt is made
        // (simulating ChatAgent's launch), but the DeepSeek call fails.
        val turn1 = failingService.recordTurn("session-1", "uno", "resp uno")
        val turn2 = failingService.recordTurn("session-1", "dos", "resp dos")
        assertFalse(turn1)
        assertTrue(turn2)

        val failedAttempt = failingService.compactIfDue("session-1")
        // Fails toward returning the current, unsummarized state — counter is NOT reset.
        assertEquals(2, failedAttempt.messagesSinceCompaction)
        assertEquals("", failedAttempt.summary)

        // A later turn (still at/above threshold, since the counter was never reset) must still
        // signal true — proving the failure did not permanently silence future launch attempts.
        val turn3 = failingService.recordTurn("session-1", "tres", "resp tres")
        assertTrue(turn3)
        assertEquals(3, sessionMemories.get("session-1").messagesSinceCompaction)

        // And once DeepSeek is healthy again, a retry succeeds and resets state as normal.
        val workingDeepSeek = FakeDeepSeekClient(mutableListOf("resumen recuperado"))
        val recoveredService = DefaultMemoryService(
            chatMessages, sessionMemories, monitorIntervalMessages = 2,
            deepSeekClient = workingDeepSeek, maxSummaryChars = defaultMaxSummaryChars
        )
        val recovered = recoveredService.compactIfDue("session-1")
        assertEquals("resumen recuperado", recovered.summary)
        assertEquals(0, recovered.messagesSinceCompaction)
    }

    @Test
    fun `recordTurn swallows repository failures and returns false instead of propagating`() = runTest {
        val throwingChatMessages = object : ChatMessageRepository {
            override suspend fun append(sessionId: String, role: String, text: String) {
                throw RuntimeException("mongo unreachable")
            }

            override suspend fun lastN(sessionId: String, n: Int): List<ChatMessage> = emptyList()
        }
        val service = DefaultMemoryService(throwingChatMessages, FakeSessionMemoryRepository(), monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val result = service.recordTurn("session-1", "hola", "hola de vuelta")

        assertFalse(result)
    }

    @Test
    fun `setPendingConsent then getState reflects pending consent with a reset attempt count`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        service.setPendingConsent("session-1", "assignment-1")
        val state = service.getState("session-1")

        assertTrue(state.pendingConsentRequest)
        assertEquals("assignment-1", state.pendingAssignmentId)
        assertEquals(0, state.consentAttempts)
    }

    @Test
    fun `clearPendingConsent resets pending state and attempt count`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)
        service.setPendingConsent("session-1", "assignment-1")
        service.incrementConsentAttempts("session-1")

        service.clearPendingConsent("session-1")
        val state = service.getState("session-1")

        assertFalse(state.pendingConsentRequest)
        assertEquals(null, state.pendingAssignmentId)
        assertEquals(0, state.consentAttempts)
    }

    @Test
    fun `incrementConsentAttempts increments and returns the new count`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        assertEquals(1, service.incrementConsentAttempts("session-1"))
        assertEquals(2, service.incrementConsentAttempts("session-1"))
    }

    @Test
    fun `getState swallows repository failures and returns default state instead of propagating`() = runTest {
        val throwingSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = throw RuntimeException("mongo unreachable")
            override suspend fun save(memory: SessionMemory) {}
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), throwingSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val state = service.getState("session-1")

        assertEquals(SessionMemory("session-1"), state)
    }

    @Test
    fun `setPendingConsent and clearPendingConsent swallow repository failures instead of propagating`() = runTest {
        val throwingSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = throw RuntimeException("mongo unreachable")
            override suspend fun save(memory: SessionMemory) {}
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), throwingSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        // Must not throw.
        service.setPendingConsent("session-1", "assignment-1")
        service.clearPendingConsent("session-1")
    }

    @Test
    fun `incrementConsentAttempts fails toward attempts-exhausted, not toward retrying forever, when the read itself fails`() = runTest {
        val throwingSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = throw RuntimeException("mongo unreachable")
            override suspend fun save(memory: SessionMemory) {}
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), throwingSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val attempts = service.incrementConsentAttempts("session-1")

        // Must be a value that fails `attempts < consentMaxAttempts` for any configured max, so a
        // caller resolves to "attempts exhausted" (which still hands over the fallback phone)
        // instead of looping the same consent question forever.
        assertEquals(Int.MAX_VALUE, attempts)
    }

    @Test
    fun `incrementConsentAttempts fails toward attempts-exhausted when reads succeed but the write fails`() = runTest {
        // The real-world failure mode this guards against: a Mongo primary stepdown, write-concern
        // failure, or disk-full condition where reads keep working but writes silently fail. In
        // that scenario getState correctly keeps reporting pendingConsentRequest=true, so this is
        // the only signal available to stop an infinite re-ask loop.
        val readableButUnwritableSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = SessionMemory(sessionId, pendingConsentRequest = true, consentAttempts = 1)
            override suspend fun save(memory: SessionMemory) {
                throw RuntimeException("write concern failure")
            }
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), readableButUnwritableSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val attempts = service.incrementConsentAttempts("session-1")

        assertEquals(Int.MAX_VALUE, attempts)
    }

    @Test
    fun `incrementRedirectAttempts increments and returns the new count`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        assertEquals(1, service.incrementRedirectAttempts("session-1"))
        assertEquals(2, service.incrementRedirectAttempts("session-1"))
    }

    @Test
    fun `resetRedirectAttempts sets the count back to zero`() = runTest {
        val sessionMemories = FakeSessionMemoryRepository()
        val service = DefaultMemoryService(FakeChatMessageRepository(), sessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)
        service.incrementRedirectAttempts("session-1")
        service.incrementRedirectAttempts("session-1")

        service.resetRedirectAttempts("session-1")

        assertEquals(0, service.getState("session-1").redirectAttempts)
    }

    @Test
    fun `incrementRedirectAttempts fails toward attempts-exhausted, not toward redirecting forever, when the read itself fails`() = runTest {
        val throwingSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = throw RuntimeException("mongo unreachable")
            override suspend fun save(memory: SessionMemory) {}
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), throwingSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val attempts = service.incrementRedirectAttempts("session-1")

        // Must be a value that fails `attempts <= incoherenceMaxAttempts` for any configured max,
        // so a caller escalates to the consent flow (which always ends in a professional or the
        // fallback phone) instead of looping redirect questions forever.
        assertEquals(Int.MAX_VALUE, attempts)
    }

    @Test
    fun `incrementRedirectAttempts fails toward attempts-exhausted when reads succeed but the write fails`() = runTest {
        val readableButUnwritableSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = SessionMemory(sessionId, redirectAttempts = 1)
            override suspend fun save(memory: SessionMemory) {
                throw RuntimeException("write concern failure")
            }
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), readableButUnwritableSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        val attempts = service.incrementRedirectAttempts("session-1")

        assertEquals(Int.MAX_VALUE, attempts)
    }

    @Test
    fun `resetRedirectAttempts swallows repository failures instead of propagating`() = runTest {
        val throwingSessionMemories = object : SessionMemoryRepository {
            override suspend fun get(sessionId: String): SessionMemory = throw RuntimeException("mongo unreachable")
            override suspend fun save(memory: SessionMemory) {}
        }
        val service = DefaultMemoryService(FakeChatMessageRepository(), throwingSessionMemories, monitorIntervalMessages = 2, deepSeekClient = FakeDeepSeekClient(), maxSummaryChars = defaultMaxSummaryChars)

        // Must not throw.
        service.resetRedirectAttempts("session-1")
    }

    @Test
    fun `compactIfDue is a no-op under the threshold - no DeepSeek call, state unchanged`() = runTest {
        val chatMessages = FakeChatMessageRepository()
        val sessionMemories = FakeSessionMemoryRepository(
            mutableMapOf("session-1" to SessionMemory("session-1", summary = "resumen previo", messagesSinceCompaction = 1))
        )
        val fakeDeepSeek = FakeDeepSeekClient(mutableListOf("no debería usarse"))
        val service = DefaultMemoryService(
            chatMessages, sessionMemories, monitorIntervalMessages = 2,
            deepSeekClient = fakeDeepSeek, maxSummaryChars = defaultMaxSummaryChars
        )

        val result = service.compactIfDue("session-1")

        assertEquals("resumen previo", result.summary)
        assertEquals(1, result.messagesSinceCompaction)
        assertEquals(null, fakeDeepSeek.lastSystemPrompt)
    }

    @Test
    fun `compactIfDue compacts at threshold and hard-truncates the result to maxSummaryChars`() = runTest {
        val chatMessages = FakeChatMessageRepository()
        chatMessages.append("session-1", "user", "me siento muy ansioso")
        chatMessages.append("session-1", "assistant", "cuéntame más")
        val sessionMemories = FakeSessionMemoryRepository(
            mutableMapOf("session-1" to SessionMemory("session-1", summary = "resumen previo", messagesSinceCompaction = 2))
        )
        val oversizedSummary = "x".repeat(50)
        val fakeDeepSeek = FakeDeepSeekClient(mutableListOf(oversizedSummary))
        val service = DefaultMemoryService(
            chatMessages, sessionMemories, monitorIntervalMessages = 2,
            deepSeekClient = fakeDeepSeek, maxSummaryChars = 10
        )

        val result = service.compactIfDue("session-1")

        assertEquals("x".repeat(10), result.summary)
        assertEquals(0, result.messagesSinceCompaction)
        assertEquals(result, sessionMemories.get("session-1"))
    }

    @Test
    fun `compactIfDue folds in every turn since the last compaction, not just monitorIntervalMessages worth of messages`() = runTest {
        // Regression: recordTurn appends 2 messages (user + assistant) per turn, but
        // messagesSinceCompaction counts turns. compactIfDue must fetch
        // messagesSinceCompaction * 2 messages, not monitorIntervalMessages messages — the latter
        // silently drops the earliest turns of every window from the summary (e.g. with
        // monitorIntervalMessages = 2 turns = 4 messages, fetching only 2 messages would drop the
        // entire first turn).
        val chatMessages = FakeChatMessageRepository()
        val sessionMemories = FakeSessionMemoryRepository()
        val fakeDeepSeek = FakeDeepSeekClient(mutableListOf("resumen actualizado"))
        val service = DefaultMemoryService(
            chatMessages, sessionMemories, monitorIntervalMessages = 2,
            deepSeekClient = fakeDeepSeek, maxSummaryChars = defaultMaxSummaryChars
        )

        service.recordTurn("session-1", "primer turno del usuario", "primera respuesta")
        service.recordTurn("session-1", "segundo turno del usuario", "segunda respuesta")

        service.compactIfDue("session-1")

        val prompt = fakeDeepSeek.lastUserPrompt!!
        assertTrue(
            prompt.contains("primer turno del usuario"),
            "should include the earliest turn since the last compaction, not just the most recent monitorIntervalMessages worth of messages"
        )
        assertTrue(prompt.contains("segundo turno del usuario"))
    }
}
