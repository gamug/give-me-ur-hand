package com.givemeurhand.android.data

class FakeProfessionalApiClient(
    private val loginResult: Boolean = true,
    private val cases: List<CaseResponse> = emptyList(),
    private val closeResult: Boolean = true,
    private val loginException: Exception? = null,
    private val listCasesException: Exception? = null,
    private val closeCaseException: Exception? = null
) : ProfessionalApiClient {
    val closedCaseIds = mutableListOf<String>()

    override suspend fun login(username: String, password: String): Boolean {
        loginException?.let { throw it }
        return loginResult
    }

    override suspend fun listCases(): List<CaseResponse> {
        listCasesException?.let { throw it }
        return cases
    }

    override suspend fun closeCase(caseId: String): Boolean {
        closeCaseException?.let { throw it }
        if (closeResult) closedCaseIds.add(caseId)
        return closeResult
    }
}
