// backend/src/main/kotlin/com/givemeurhand/backend/agent/ChatAgent.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.alarm.TriageColor
import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.memory.MemoryService
import com.givemeurhand.backend.rag.ChunkRepository
import com.givemeurhand.backend.rag.RagSearchStep

data class AgentResult(val reply: String, val kind: String)

private const val GREETING_MESSAGE =
    "¡Hola! Estoy aquí para acompañarte y ayudarte a manejar cómo te sientes después del terremoto. " +
        "Puedes contarme qué te preocupa o preguntarme sobre cómo sobrellevar la situación."

private const val CONSENT_REQUEST_MESSAGE =
    "He notado que esto es difícil y me gustaría conectarte con una persona real que pueda ayudarte. " +
        "¿Me das tu permiso para compartir tus datos con un profesional? Si es así, ¿a qué número de " +
        "teléfono te pueden contactar?"

private const val CONSENT_GRANTED_MESSAGE =
    "Gracias, un profesional se pondrá en contacto contigo pronto al número que compartiste."

class ChatAgent(
    private val deepSeekClient: DeepSeekClient,
    private val chunkRepository: ChunkRepository,
    private val assignmentService: AssignmentService,
    private val memoryService: MemoryService,
    private val alarmCriteria: AlarmCriteria,
    private val fallbackHelpPhone: String,
    private val consentMaxAttempts: Int,
    private val incoherenceMaxAttempts: Int
) {
    suspend fun handle(sessionId: String, rawMessage: String): AgentResult {
        val result = handleInner(sessionId, rawMessage)
        memoryService.recordTurn(sessionId, rawMessage, result.reply)
        return result
    }

    private suspend fun handleInner(sessionId: String, rawMessage: String): AgentResult {
        val memory = memoryService.getState(sessionId)
        if (memory.pendingConsentRequest) {
            return handlePendingConsent(sessionId, rawMessage, memory.pendingAssignmentId)
        }

        val clean = StandardizeStep.run(rawMessage, deepSeekClient)
        val triage = AlarmClassifyStep.run(clean, alarmCriteria, deepSeekClient)

        if (triage.intent == ChatIntent.HUMAN_HELP_EXPLICIT || triage.color == TriageColor.ROJO) {
            return startConsentFlow(sessionId, clean, triggerSource = "immediate_triage")
        }

        if (triage.intent == ChatIntent.GREETING) {
            return AgentResult(GREETING_MESSAGE, "greeting")
        }

        if (!triage.coherent) {
            val attempts = memoryService.incrementRedirectAttempts(sessionId)
            if (attempts <= incoherenceMaxAttempts) {
                return AgentResult(RedirectStep.run(clean, deepSeekClient), "redirect")
            }
            return startConsentFlow(sessionId, clean, triggerSource = "immediate_triage")
        }
        memoryService.resetRedirectAttempts(sessionId)

        val reformulations = ExpandStep.run(clean, deepSeekClient)
        val chunks = RagSearchStep.search((listOf(clean) + reformulations).distinct(), chunkRepository)

        if (chunks.isEmpty()) {
            return AgentResult(
                "No tengo información suficiente para responder eso con seguridad, pero cuéntame más sobre cómo te sientes y trato de ayudarte.",
                "out_of_scope"
            )
        }

        val answer = AnswerStep.run(clean, chunks, deepSeekClient)
        return AgentResult(answer, "answer")
    }

    private suspend fun handlePendingConsent(
        sessionId: String,
        rawMessage: String,
        pendingAssignmentId: String?
    ): AgentResult {
        val parsed = ConsentParseStep.run(rawMessage, deepSeekClient)

        if (parsed.consents == true && parsed.phone != null) {
            assignmentService.recordConsent(
                pendingAssignmentId!!,
                granted = true,
                phone = parsed.phone,
                evidenceText = rawMessage
            )
            memoryService.clearPendingConsent(sessionId)
            return AgentResult(CONSENT_GRANTED_MESSAGE, "consent_granted")
        }

        if (parsed.consents == false) {
            assignmentService.recordConsent(
                pendingAssignmentId!!,
                granted = false,
                phone = null,
                evidenceText = rawMessage
            )
            memoryService.clearPendingConsent(sessionId)
            return AgentResult(consentDeclinedMessage(), "consent_declined")
        }

        // Ambiguous: consents == null, or consents == true with a missing phone number.
        val attempts = memoryService.incrementConsentAttempts(sessionId)
        if (attempts < consentMaxAttempts) {
            return AgentResult(CONSENT_REQUEST_MESSAGE, "consent_clarify")
        }

        assignmentService.recordConsent(
            pendingAssignmentId!!,
            granted = false,
            phone = null,
            evidenceText = rawMessage
        )
        memoryService.clearPendingConsent(sessionId)
        return AgentResult(consentDeclinedMessage(), "consent_declined")
    }

    private suspend fun startConsentFlow(sessionId: String, reason: String, triggerSource: String): AgentResult {
        val result = assignmentService.assignHelper(sessionId, reason, triggerSource)
        if (result.assignmentId != null) {
            memoryService.setPendingConsent(sessionId, result.assignmentId)
            return AgentResult(CONSENT_REQUEST_MESSAGE, "human_help_pending_consent")
        }
        // No professional available at all — nothing to attach consent to, fall back exactly like today.
        return AgentResult(humanHelpFallbackMessage(result.phone), "human_help")
    }

    private fun humanHelpFallbackMessage(phone: String) =
        "Entiendo que esto es difícil y quiero que hables con una persona real que pueda acompañarte ahora mismo. Por favor comunícate con este número: $phone"

    private fun consentDeclinedMessage() =
        "Entiendo, respeto tu decisión. Si en algún momento cambias de opinión o necesitas hablar con una persona real, " +
            "puedes comunicarte con este número: $fallbackHelpPhone"
}
