package com.givemeurhand.backend.alarm

import java.time.Instant

const val ALARM_CRITERIA_COLLECTION = "alarm_criteria"

data class AlarmCriteria(
    val version: Int,
    val generatedAt: Instant,
    val classificationPromptText: String, // injected into AlarmClassifyStep's system prompt (Task 3)
    val controlStrategiesText: String      // injected into SupportStep's system prompt (Task 6)
)
