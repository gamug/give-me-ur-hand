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
import java.util.concurrent.ConcurrentHashMap

/**
 * Periodically re-evaluates a session's accumulated summary for escalation, independent of the
 * user sending another message. Launched fire-and-forget from [com.givemeurhand.backend.agent.ChatAgent.handle]
 * on an app-level [kotlinx.coroutines.CoroutineScope] once the per-session message-count
 * threshold is crossed — it must never block or fail a request, so [evaluate] must never throw.
 *
 * [evaluate] is launched fire-and-forget per turn that crosses the threshold; ChatAgent's launch
 * gate (an exact threshold-crossing check) already prevents most re-launches, but a second,
 * independent request for the same session can still race a still-running evaluation (each run
 * does a DeepSeek round-trip plus Mongo I/O, realistically 5-15 seconds). The [inProgress] guard
 * below is defense in depth for that narrower true-concurrency case: without it, two overlapping
 * runs could both read `pendingConsentRequest == false`, both pass, and both call [assignHelper],
 * creating two assignments for one alarm with only the last one ever getting a pendingAssignmentId
 * attached — the other silently orphaned as a permanently-open, no-consent case.
 */
class BackgroundMonitorAgent(
    private val memoryService: MemoryService,
    private val alarmCriteria: AlarmCriteria,
    private val deepSeekClient: DeepSeekClient,
    private val assignmentService: AssignmentService
) {
    private val logger = LoggerFactory.getLogger(BackgroundMonitorAgent::class.java)

    /** Session ids with an [evaluate] call currently in flight, guarding against concurrent runs. */
    private val inProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun evaluate(sessionId: String) {
        if (!inProgress.add(sessionId)) {
            // A run for this session is already in flight — this is a legitimate "skip", not an
            // error: never run two evaluations for the same session concurrently.
            logger.info("Background monitor already in progress for session {}, skipping", sessionId)
            return
        }
        try {
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
        } finally {
            inProgress.remove(sessionId)
        }
    }
}
