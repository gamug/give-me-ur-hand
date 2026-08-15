package com.givemeurhand.android.ui.professional

import com.givemeurhand.android.MainDispatcherRule
import com.givemeurhand.android.data.CaseResponse
import com.givemeurhand.android.data.FakeProfessionalApiClient
import com.givemeurhand.android.data.ProfessionalApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfessionalDashboardViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun case(id: String, status: String = "active") =
        CaseResponse(id, "necesito ayuda", status, "2026-08-14T10:00:00Z", null)

    @Test
    fun `loads cases on init`() = runTest {
        val viewModel = ProfessionalDashboardViewModel(FakeProfessionalApiClient(cases = listOf(case("1"))))

        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.cases.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `closing a case refreshes the list`() = runTest {
        val api = FakeProfessionalApiClient(cases = listOf(case("1")))
        val viewModel = ProfessionalDashboardViewModel(api)
        advanceUntilIdle()

        viewModel.closeCase("1")
        advanceUntilIdle()

        assertEquals(listOf("1"), api.closedCaseIds)
    }

    @Test
    fun `a refresh failure stops loading, surfaces an error, and keeps previously loaded cases`() = runTest {
        val api = SwitchableFakeProfessionalApiClient(initialCases = listOf(case("1")))
        val viewModel = ProfessionalDashboardViewModel(api)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.cases.size)

        api.failNextListCases = true
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.cases.size)
        assertEquals("No pudimos actualizar los casos. Intenta de nuevo.", state.errorMessage)
    }

    @Test
    fun `a CancellationException during refresh propagates and leaves loading state untouched`() = runTest {
        val viewModel = ProfessionalDashboardViewModel(
            FakeProfessionalApiClient(listCasesException = CancellationException("cancelled"))
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.cases.size)
        // The catch block never ran (the CancellationException propagated instead of being
        // swallowed), so isLoading was never reset back to false after init's refresh() call.
        assertTrue(state.isLoading)
    }

    @Test
    fun `a closeCase failure leaves the case list unchanged`() = runTest {
        val api = FakeProfessionalApiClient(
            cases = listOf(case("1")),
            closeCaseException = RuntimeException("server error")
        )
        val viewModel = ProfessionalDashboardViewModel(api)
        advanceUntilIdle()

        viewModel.closeCase("1")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.cases.size)
        assertEquals(emptyList<String>(), api.closedCaseIds)
    }
}

/**
 * A fake whose listCases() failure can be toggled after construction, needed to exercise
 * "a later refresh() call fails after cases were already loaded" without wiping the visible list.
 */
private class SwitchableFakeProfessionalApiClient(
    initialCases: List<CaseResponse>
) : ProfessionalApiClient {
    private val cases = initialCases
    var failNextListCases = false

    override suspend fun login(username: String, password: String): Boolean = true

    override suspend fun listCases(): List<CaseResponse> {
        if (failNextListCases) {
            failNextListCases = false
            throw RuntimeException("server error")
        }
        return cases
    }

    override suspend fun closeCase(caseId: String): Boolean = true
}
