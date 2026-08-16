package com.givemeurhand.backend.alarm

interface AlarmCriteriaRepository {
    suspend fun getCurrent(): AlarmCriteria?
}
