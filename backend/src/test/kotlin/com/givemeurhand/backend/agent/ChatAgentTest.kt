// backend/src/test/kotlin/com/givemeurhand/backend/agent/ChatAgentTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.rag.Chunk
import com.givemeurhand.backend.rag.FakeChunkRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAgentTest {
    private val expandJson = """["reform 1", "reform 2", "reform 3"]"""

    @Test
    fun `explicit human help request returns the fallback phone`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("Quiero hablar con alguien", "AYUDA_HUMANA"))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "kiero ablar con alguien")

        assertEquals("human_help", result.kind)
        assertEquals(true, result.reply.contains("+57 3219699131"))
    }

    @Test
    fun `crisis risk also escalates to human help`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("ya no quiero vivir", "RIESGO_CRISIS"))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "ya no kiero vivir")

        assertEquals("human_help", result.kind)
    }

    @Test
    fun `greeting returns a cordial reply without querying the knowledge base`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("Hola", "SALUDO_CORTESIA"))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "hola")

        assertEquals("greeting", result.kind)
        assertEquals(true, result.reply.isNotBlank())
        assertEquals(false, result.reply.contains("no está relacionada"))
        // Only standardize + classify should have run; expand/answer would consume a 3rd/4th
        // fake response and change lastSystemPrompt, so this proves RAG was never reached.
        assertEquals(true, fake.lastSystemPrompt?.contains("Clasifica") == true)
    }

    @Test
    fun `normal question with matching chunks returns a grounded answer`() = runTest {
        val chunk = Chunk(id = "1", text = "Breathing exercises help.", sourceDocument = "pfa.pdf", page = 1, chunkIndex = 0, score = 0.9)
        val repo = FakeChunkRepository(
            mapOf(
                "como manejo la ansiedad" to listOf(chunk),
                "reform 1" to listOf(chunk),
                "reform 2" to listOf(chunk),
                "reform 3" to listOf(chunk)
            )
        )
        val fake = FakeDeepSeekClient(mutableListOf(
            "como manejo la ansiedad", // standardize
            "PREGUNTA_NORMAL",          // classify
            expandJson,                 // expand
            "Respira profundo."         // answer
        ))
        val agent = ChatAgent(fake, repo, FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "komo manejo la anciedad")

        assertEquals("answer", result.kind)
        assertEquals("Respira profundo.", result.reply)
    }

    @Test
    fun `normal question with no matching chunks returns the out-of-scope message`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "cual es la capital de francia",
            "PREGUNTA_NORMAL",
            expandJson
        ))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "cual es la kapital de francia")

        assertEquals("out_of_scope", result.kind)
        assertEquals("Tu pregunta no está relacionada con el propósito de esta aplicación.", result.reply)
    }
}
