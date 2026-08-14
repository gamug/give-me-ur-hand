// backend/src/main/kotlin/com/givemeurhand/backend/agent/ChatAgent.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.rag.ChunkRepository
import com.givemeurhand.backend.rag.RagSearchStep

data class AgentResult(val reply: String, val kind: String)

class ChatAgent(
    private val deepSeekClient: DeepSeekClient,
    private val chunkRepository: ChunkRepository,
    private val assignmentService: AssignmentService
) {
    suspend fun handle(sessionId: String, rawMessage: String): AgentResult {
        val clean = StandardizeStep.run(rawMessage, deepSeekClient)
        val intent = ClassifyStep.run(clean, deepSeekClient)

        if (intent == Intent.HUMAN_HELP_EXPLICIT || intent == Intent.CRISIS_RISK) {
            val phone = assignmentService.assignHelper(sessionId, clean)
            return AgentResult(humanHelpMessage(phone), "human_help")
        }

        val reformulations = ExpandStep.run(clean, deepSeekClient)
        val chunks = RagSearchStep.search(listOf(clean) + reformulations, chunkRepository)

        if (chunks.isEmpty()) {
            return AgentResult(
                "Tu pregunta no está relacionada con el propósito de esta aplicación.",
                "out_of_scope"
            )
        }

        val answer = AnswerStep.run(clean, chunks, deepSeekClient)
        return AgentResult(answer, "answer")
    }

    private fun humanHelpMessage(phone: String) =
        "Entiendo que esto es difícil y quiero que hables con una persona real que pueda acompañarte ahora mismo. Por favor comunícate con este número: $phone"
}
