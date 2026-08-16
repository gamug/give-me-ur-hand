package com.givemeurhand.backend.alarm

class FakeAlarmCriteriaRepository(private val current: AlarmCriteria?) : AlarmCriteriaRepository {
    override suspend fun getCurrent(): AlarmCriteria? = current
}
