// backend/src/main/kotlin/com/givemeurhand/backend/agent/ChatAgent.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.alarm.TriageColor
import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.memory.MemoryService
import com.givemeurhand.backend.monitor.BackgroundMonitorAgent
import com.givemeurhand.backend.rag.ChunkRepository
import com.givemeurhand.backend.rag.RagSearchStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class AgentResult(val reply: String, val kind: String)

private const val GREETING_MESSAGE =
    "¡Hola! Estoy aquí para acompañarte y ayudarte a manejar cómo te sientes después del terremoto. " +
        "Puedes contarme qué te preocupa o preguntarme sobre cómo sobrellevar la situación."

private const val CONSENT_REQUEST_MESSAGE_BASE =
    "He notado que esto es difícil y me gustaría conectarte con una persona real que pueda ayudarte. " +
        "¿Me das tu permiso para compartir tus datos con un profesional? Si es así, ¿a qué número de " +
        "teléfono te pueden contactar?"

class ChatAgent(
    private val deepSeekClient: DeepSeekClient,
    private val chunkRepository: ChunkRepository,
    private val assignmentService: AssignmentService,
    private val memoryService: MemoryService,
    private val alarmCriteria: AlarmCriteria,
    private val fallbackHelpPhone: String,
    private val consentMaxAttempts: Int,
    private val incoherenceMaxAttempts: Int,
    private val backgroundScope: CoroutineScope,
    private val monitorAgent: BackgroundMonitorAgent
) {
    suspend fun handle(sessionId: String, rawMessage: String): AgentResult {
        val result = handleInner(sessionId, rawMessage)
        val thresholdCrossed = memoryService.recordTurn(sessionId, rawMessage, result.reply)
        if (thresholdCrossed) {
            // Launched on the app-level backgroundScope (not this request's own coroutine), so
            // this job runs independently and does not delay the HTTP response.
            backgroundScope.launch { monitorAgent.evaluate(sessionId) }
        }
        return result
    }

    private suspend fun handleInner(sessionId: String, rawMessage: String): AgentResult {
        val memory = memoryService.getState(sessionId)
        if (memory.pendingConsentRequest) {
            return handlePendingConsent(sessionId, rawMessage, memory.pendingAssignmentId)
        }

        val clean = StandardizeStep.run(rawMessage, deepSeekClient)
        val triage = AlarmClassifyStep.run(clean, alarmCriteria, deepSeekClient)

        // Only a ROJO triage grading ever offers to connect a professional — an explicit request
        // to talk to someone (ChatIntent.HUMAN_HELP_EXPLICIT) is a strong signal but not itself
        // sufficient: a coherent, non-critical person who just says "I need someone to talk to"
        // must not be met with a request to share their data with a professional. That request
        // still gets a caring, human-toned reply — it flows into the AMARILLO/VERDE branches below
        // (wantsToBeHeard drives whether it's an active-support or a plain listening response).
        if (triage.color == TriageColor.ROJO) {
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

        val reply = if (triage.color == TriageColor.AMARILLO && !triage.wantsToBeHeard) {
            SupportStep.run(clean, chunks, alarmCriteria.controlStrategiesText, deepSeekClient)
        } else {
            AnswerStep.run(clean, chunks, deepSeekClient)
        }
        return AgentResult(reply, "answer")
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
            return AgentResult(consentGrantedMessage(parsed.phone), "consent_granted")
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
            return AgentResult(consentClarifyMessage(), "consent_clarify")
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
            return AgentResult(consentRequestMessage(), "human_help_pending_consent")
        }
        // No professional available at all — nothing to attach consent to, fall back exactly like today.
        return AgentResult(humanHelpFallbackMessage(result.phone), "human_help")
    }

    private fun humanHelpFallbackMessage(phone: String) =
        "Entiendo que esto es difícil y quiero que hables con una persona real que pueda acompañarte ahora mismo. Por favor comunícate con este número: $phone"

    private fun consentDeclinedMessage() =
        "Entiendo, respeto tu decisión. Si en algún momento cambias de opinión o necesitas hablar con una persona real, " +
            "puedes comunicarte con este número: $fallbackHelpPhone"

    // A ROJO-graded user who closes the app without answering the consent question must not be
    // left with nothing: the initial ask always carries the fallback phone as an immediate
    // callback option, independent of whether they ever grant consent to share their data.
    private fun consentRequestMessage() =
        "$CONSENT_REQUEST_MESSAGE_BASE Y si necesitas hablar con alguien ahora mismo, puedes llamar a este número: $fallbackHelpPhone."

    // Used only for the ambiguous-retry branch of handlePendingConsent, so a person who answered
    // unclearly gets a distinct clarifying follow-up instead of the identical first ask (which
    // reads as a stuck bot). Also carries the fallback phone since this may be the person's last
    // chance to hear it before max attempts resolves to a decline.
    private fun consentClarifyMessage() =
        "No estoy seguro de haber entendido. ¿Me confirmas si quieres que comparta tus datos con un profesional, y a qué número te pueden llamar? " +
            "Si necesitas hablar con alguien ahora mismo, puedes llamar a este número: $fallbackHelpPhone."

    private fun consentGrantedMessage(phone: String) =
        "Gracias, un profesional se pondrá en contacto contigo pronto al número que compartiste: $phone."
}
