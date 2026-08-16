// backend/src/main/kotlin/com/givemeurhand/backend/monitor/BackgroundMonitorAgent.kt
package com.givemeurhand.backend.monitor

import com.givemeurhand.backend.agent.AlarmClassifyStep
import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.alarm.TriageColor
import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.memory.MemoryService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Periodically re-evaluates a session's accumulated summary for escalation, independent of the
 * user sending another message. Launched fire-and-forget from [com.givemeurhand.backend.agent.ChatAgent.handle]
 * on an app-level [kotlinx.coroutines.CoroutineScope] once the per-session message-count
 * threshold is crossed — it must never block or fail a request, so [evaluate] must never throw.
 */
class BackgroundMonitorAgent(
    private val memoryService: MemoryService,
    private val alarmCriteria: AlarmCriteria,
    private val deepSeekClient: DeepSeekClient,
    private val assignmentService: AssignmentService
) {
    private val logger = LoggerFactory.getLogger(BackgroundMonitorAgent::class.java)

    suspend fun evaluate(sessionId: String) {
        try {
            val memory = memoryService.compactIfDue(sessionId)
            if (memory.pendingConsentRequest) return // already awaiting consent from a prior trigger — never double-fire

            val triage = AlarmClassifyStep.run(memory.summary, alarmCriteria, deepSeekClient)
            if (triage.color == TriageColor.ROJO) {
                val result = assignmentService.assignHelper(sessionId, memory.summary, triggerSource = "background_monitor")
                if (result.assignmentId != null) {
                    memoryService.setPendingConsent(sessionId, result.assignmentId)
                }
                // assignmentId == null (no professional available): do nothing further — the
                // monitor must never interrupt the user's turn; a persisting condition will be
                // re-detected by immediate triage on the user's next message.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Background monitor failed for session {}", sessionId, e)
        }
    }
}
