package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.agent.FakeDeepSeekClient
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.rag.FakeChunkRepository
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `health check returns 200`() = testApplication {
        val agent = ChatAgent(FakeDeepSeekClient(), FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))
        application { module(agent) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
